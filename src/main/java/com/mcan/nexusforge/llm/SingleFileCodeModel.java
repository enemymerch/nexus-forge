package com.mcan.nexusforge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Keeps generated source out of a model-authored JSON string; Java owns the one-file envelope. */
public final class SingleFileCodeModel implements ChatModel {
    private static final int MAX_CODE_CHARACTERS = 8_000;
    private static final Pattern OPEN_FENCE = Pattern.compile("\\A[\\t ]*```(?:java|kotlin|typescript|tsx|ts|javascript|jsx|css)?[\\t ]*(?:\\r\\n|\\n|\\r)");
    private static final Pattern CLOSE_FENCE = Pattern.compile("(?m)^[\\t ]*```[\\t ]*(?:\\r\\n|\\n|\\r)?[\\t \\r\\n]*\\z");
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^[\\t ]*```[^\\r\\n]*$");
    private final ChatModel delegate;
    private final Path archiveDirectory;
    private final AtomicInteger sequence = new AtomicInteger();

    public SingleFileCodeModel(ChatModel delegate, Path archiveDirectory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.archiveDirectory = Objects.requireNonNull(archiveDirectory, "archiveDirectory").toAbsolutePath().normalize();
    }

    @Override public JsonObject chat(JsonArray messages, JsonArray tools) throws Exception {
        return delegate.chat(messages, tools);
    }

    @Override public JsonObject chat(JsonArray messages, JsonArray tools, JsonObject responseSchema) throws Exception {
        String approvedPath = tools != null && tools.isEmpty() ? solePath(responseSchema) : null;
        if (approvedPath == null) return delegate.chat(messages, tools, responseSchema);

        JsonArray outbound = messages.deepCopy();
        JsonObject instruction = new JsonObject();
        instruction.addProperty("role", "user");
        instruction.addProperty("content", "RAW_CODE protocol for exactly one approved file: " + approvedPath
                + ". Java will create the summary/path/JSON metadata. Return ONLY the complete raw source file, including package, imports and declarations. "
                + "Do not JSON-wrap or JSON-escape source. Do not report test results. An optional FILE: " + approvedPath
                + " header and a single code fence for the source language are accepted. Preserve source literals and backslashes exactly.");
        outbound.add(instruction);
        JsonArray noTools = new JsonArray();
        Path rawArchive = archiveRequest(outbound, noTools, approvedPath);
        // One call only. The outer role owns all step and repair budgets.
        JsonObject raw = delegate.chat(outbound, noTools, null);
        if (raw == null || !raw.has("content") || !raw.get("content").isJsonPrimitive()
                || !raw.getAsJsonPrimitive("content").isString())
            throw new IllegalStateException("RAW_CODE response must contain a source string in content.");
        String rawContent = raw.get("content").getAsString();
        Files.writeString(rawArchive, rawContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        if (raw.has("tool_calls") && (!raw.get("tool_calls").isJsonArray() || !raw.getAsJsonArray("tool_calls").isEmpty()))
            throw new IllegalStateException("RAW_CODE response cannot request tools.");
        String code = source(rawContent, approvedPath);
        JsonObject edit = new JsonObject();
        edit.addProperty("path", approvedPath);
        edit.addProperty("content", code);
        JsonArray edits = new JsonArray(); edits.add(edit);
        JsonObject proposal = new JsonObject();
        proposal.addProperty("summary", "Complete source proposed for " + approvedPath + ".");
        proposal.add("edits", edits);
        JsonObject normalized = raw.deepCopy();
        normalized.addProperty("content", proposal.toString());
        return normalized;
    }

    private Path archiveRequest(JsonArray messages, JsonArray tools, String path) throws Exception {
        Files.createDirectories(archiveDirectory);
        JsonObject request = new JsonObject();
        request.addProperty("protocol", "RAW_CODE");
        request.addProperty("approved_path", path);
        request.add("messages", messages);
        request.add("tools", tools);
        request.add("response_schema", JsonNull.INSTANCE);
        while (true) {
            String number = String.format(java.util.Locale.ROOT, "%03d", sequence.incrementAndGet());
            Path raw = archiveDirectory.resolve(number + ".txt");
            if (Files.exists(raw)) continue;
            try {
                Files.writeString(archiveDirectory.resolve(number + "-request.json"), request.toString(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return raw;
            } catch (FileAlreadyExistsException collision) { /* Retain earlier audit files. */ }
        }
    }

    private static String solePath(JsonObject schema) {
        try {
            if (schema == null) return null;
            JsonArray paths = schema.getAsJsonObject("properties").getAsJsonObject("edits")
                    .getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("path").getAsJsonArray("enum");
            if (paths.size() != 1) return null;
            JsonElement path = paths.get(0);
            return path.isJsonPrimitive() && path.getAsJsonPrimitive().isString() && !path.getAsString().isBlank()
                    ? path.getAsString() : null;
        } catch (RuntimeException notCodeSchema) { return null; }
    }

    private static String source(String raw, String approvedPath) {
        String code = wrapperPrefix(raw);
        boolean header = code.startsWith("FILE:");
        if (header) code = removeHeader(code, approvedPath);
        code = wrapperPrefix(code);
        var opening = OPEN_FENCE.matcher(code);
        if (opening.find()) {
            String body = code.substring(opening.end());
            var closing = CLOSE_FENCE.matcher(body);
            if (!closing.find()) throw new IllegalArgumentException("RAW_CODE has a dangling or invalid code fence.");
            code = body.substring(0, closing.start());
            if (FENCE_LINE.matcher(code).find()) throw new IllegalArgumentException("RAW_CODE must contain only one code fence.");
            if (code.startsWith("FILE:")) {
                if (header) throw new IllegalArgumentException("RAW_CODE allows only one FILE header.");
                code = removeHeader(code, approvedPath);
            }
        } else if (FENCE_LINE.matcher(code).find()) {
            throw new IllegalArgumentException("RAW_CODE has a dangling, multiple or unsupported code fence.");
        }
        if (code.isBlank() || code.length() > MAX_CODE_CHARACTERS)
            throw new IllegalArgumentException("RAW_CODE source must contain 1..8000 nonblank characters.");
        if (code.stripLeading().startsWith("{"))
            throw new IllegalArgumentException("RAW_CODE requires source, not a JSON object.");
        return code;
    }

    private static String wrapperPrefix(String value) {
        String stripped = value.stripLeading();
        return stripped.startsWith("FILE:") || stripped.startsWith("```") ? stripped : value;
    }

    private static String removeHeader(String value, String approvedPath) {
        int newline = 0;
        while (newline < value.length() && value.charAt(newline) != '\n' && value.charAt(newline) != '\r') newline++;
        String path = value.substring("FILE:".length(), newline).trim();
        if (!path.equals(approvedPath)) throw new IllegalArgumentException("RAW_CODE FILE header is outside the approved path: " + path);
        if (newline == value.length()) return "";
        int contentStart = newline + 1;
        if (value.charAt(newline) == '\r' && contentStart < value.length() && value.charAt(contentStart) == '\n') contentStart++;
        return value.substring(contentStart);
    }
}
