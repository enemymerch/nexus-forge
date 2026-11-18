package com.mcan.nexusforge.report;



import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcan.nexusforge.tool.ToolOutput;
import com.mcan.nexusforge.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Stores only tool output actually delivered to this agent, after all output limits. */
public final class EvidenceLedger {
    public record Check(boolean valid, String reason) {}
    /** A reference issued by Java for one complete line actually shown to the model. */
    public record SourceEvidence(String id, String path, int line, String text, String kind) {}
    private record Read(Map<Integer, String> lines, int totalLines, boolean truncated) {}
    private final Map<String, Read> reads = new LinkedHashMap<>();
    private final Set<String> listed = new LinkedHashSet<>();
    private final Set<String> directories = new LinkedHashSet<>();
    private final List<String> calls = new ArrayList<>();
    private final Map<String, SourceEvidence> sources = new LinkedHashMap<>();
    private long nextEvidenceId = 1;

    /** Annotates source rows first, then applies the final budget and records only delivered evidence. */
    public String deliver(String toolName, JsonObject arguments, String rawOutput, int budget) {
        int limit = Math.min(Math.max(0, budget), ToolRegistry.OUTPUT_LIMIT);
        JsonObject read = "read_file".equals(toolName) ? readOutput(rawOutput) : null;
        Map<String, SourceEvidence> issued = new LinkedHashMap<>();
        String annotated = rawOutput;
        if (read != null) {
            String path = read.get("path").getAsString().replace('\\', '/');
            read.addProperty("path", path);
            for (JsonElement element : read.getAsJsonArray("lines")) {
                JsonObject row = element.getAsJsonObject();
                // Tool output cannot choose its own identity, including on blank rows.
                row.remove("id");
                String text = row.get("text").getAsString();
                if (!text.isBlank()) {
                    String id = "E" + nextEvidenceId++;
                    row.addProperty("id", id);
                    issued.put(id, new SourceEvidence(id, path, row.get("line").getAsInt(), text, kindFor(path)));
                }
            }
            annotated = read.toString();
        }
        String delivered = ToolOutput.bound(annotated, limit);
        JsonObject deliveredRead = read != null ? readOutput(delivered) : null;
        if (read != null && deliveredRead == null) {
            // A newer snapshot exists, but none of it fits. Previous bytes must not remain citable.
            String path = read.get("path").getAsString();
            reads.remove(path);
            revokeSourceIds(path);
        }
        record(toolName, arguments, delivered);
        if (deliveredRead != null) {
            for (JsonElement element : deliveredRead.getAsJsonArray("lines")) {
                JsonObject row = element.getAsJsonObject();
                if (row.has("id")) {
                    String id = row.get("id").getAsString();
                    SourceEvidence source = issued.get(id);
                    if (source != null) sources.put(id, source);
                }
            }
        }
        return delivered;
    }

    public Optional<SourceEvidence> resolve(String id) {
        return Optional.ofNullable(sources.get(id));
    }

    public List<String> evidenceIds() {
        return List.copyOf(sources.keySet());
    }

    /** Small reminder of current identities; source text stays in the original tool messages. */
    public String referenceSummary() {
        StringBuilder result = new StringBuilder("Available evidence IDs:");
        for (SourceEvidence source : sources.values()) {
            String reference = "\n" + source.id() + " -> " + source.path() + ":" + source.line();
            if (result.length() + reference.length() > 1200) {
                result.append("\n... See the latest read_file outputs for additional IDs.");
                break;
            }
            result.append(reference);
        }
        if (sources.isEmpty()) result.append(" none.");
        return result.toString();
    }

    private void revokeSourceIds(String path) {
        sources.values().removeIf(source -> source.path().equals(path));
    }

    private static JsonObject readOutput(String output) {
        if (output == null || output.isBlank() || output.startsWith("ERROR:")) return null;
        try {
            JsonObject data = JsonParser.parseString(output).getAsJsonObject();
            if (!data.get("path").getAsJsonPrimitive().isString() || data.get("path").getAsString().isBlank()
                    || !data.get("truncated").getAsJsonPrimitive().isBoolean()
                    || !data.get("totalLines").getAsJsonPrimitive().isNumber()) return null;
            int total = data.get("totalLines").getAsBigDecimal().intValueExact();
            if (total < 0) return null;
            Set<Integer> seen = new LinkedHashSet<>();
            for (JsonElement element : data.getAsJsonArray("lines")) {
                JsonObject row = element.getAsJsonObject();
                if (!row.get("line").getAsJsonPrimitive().isNumber()
                        || !row.get("text").getAsJsonPrimitive().isString()) return null;
                int line = row.get("line").getAsBigDecimal().intValueExact();
                if (line < 1 || line > total || !seen.add(line)) return null;
            }
            return data;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void record(String toolName, JsonObject arguments, String deliveredOutput) {
        String requested = arguments != null && arguments.has("path") ? arguments.get("path").toString() : "?";
        String label = toolName + " " + requested;
        if (deliveredOutput.isBlank() || deliveredOutput.startsWith("ERROR:")) {
            calls.add(label + " => " + (deliveredOutput.isBlank() ? "Output budget exhausted; no evidence recorded." : deliveredOutput));
            return;
        }
        try {
            JsonObject data = JsonParser.parseString(deliveredOutput).getAsJsonObject();
            String path = data.get("path").getAsString().replace('\\', '/');
            boolean truncated = data.get("truncated").getAsBoolean();
            if ("read_file".equals(toolName)) {
                Map<Integer, String> lines = new LinkedHashMap<>();
                for (JsonElement element : data.getAsJsonArray("lines")) {
                    JsonObject row = element.getAsJsonObject();
                    lines.put(row.get("line").getAsInt(), row.get("text").getAsString());
                }
                int total = data.get("totalLines").getAsInt();
                Read previous = reads.get(path);
                if (previous != null && !previous.lines().equals(lines)) {
                    // A second read may observe changed bytes. Never combine snapshots into invented evidence.
                    calls.add(label + " => New read replaced the previous line evidence.");
                }
                revokeSourceIds(path);
                reads.put(path, new Read(Map.copyOf(lines), total, truncated));
                calls.add(label + " => OK; complete lines delivered to the model: " + lines.size() + "/" + total
                        + (truncated ? " [TRUNCATED]" : ""));
            } else if ("list_files".equals(toolName)) {
                directories.add(path);
                for (JsonElement element : data.getAsJsonArray("entries")) {
                    listed.add(element.getAsJsonObject().get("path").getAsString());
                }
                calls.add(label + " => OK; listed names and types only" + (truncated ? " [TRUNCATED]" : ""));
            } else {
                calls.add(label + " => Tool does not provide source evidence.");
            }
        } catch (RuntimeException e) {
            calls.add(label + " => Could not extract structured evidence.");
        }
    }

    public Check check(String path, int line, String quote) {
        if (path == null || path.isBlank()) return new Check(false, "File path is missing.");
        String canonical = path.replace('\\', '/');
        Read read = reads.get(canonical);
        if (read == null) return new Check(false, "File has no successful read_file evidence: " + canonical);
        String actual = read.lines().get(line);
        if (actual == null) return new Check(false, "Line was not delivered to the model: " + canonical + ":" + line);
        if (quote == null || quote.isBlank()) return new Check(false, "Quote must not be blank.");
        if ((quote.length() < 8 && !quote.equals(actual)) || !actual.contains(quote)) {
            return new Check(false, "Quote does not match the read line: " + canonical + ":" + line);
        }
        return new Check(true, "Source and quote matched; the claim's meaning was not verified.");
    }

    public static String kindFor(String path) {
        String name = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.startsWith("readme") || name.startsWith("license") || name.matches(".*\\.(md|rst|adoc|txt)$")) return "documentation";
        if (name.equals("pom.xml") || name.contains(".gradle") || name.equals("dockerfile")
                || name.matches(".*\\.(json|ya?ml|xml|properties|toml)$")) return "configuration";
        if (name.matches(".*\\.(java|kt|kts|js|ts|jsx|tsx|py|go|rs|c|cpp|h|cs|sh)$")) return "source";
        return "unknown";
    }

    public boolean hasInspectionEvidence() {
        return directories.contains(".")
                && reads.entrySet().stream().anyMatch(e -> kindFor(e.getKey()).equals("documentation") && !e.getValue().lines().isEmpty())
                && reads.entrySet().stream().anyMatch(e -> kindFor(e.getKey()).equals("configuration") && !e.getValue().lines().isEmpty());
    }

    public String audit() {
        StringBuilder out = new StringBuilder("Actual tool call records:\n");
        if (calls.isEmpty()) out.append("- No tools were executed.\n");
        calls.forEach(call -> out.append("- ").append(call).append('\n'));
        out.append("\nUninspected content and limitations:\n");
        if (!directories.contains(".")) out.append("- Workspace root was not listed.\n");
        if (reads.keySet().stream().noneMatch(p -> kindFor(p).equals("documentation"))) out.append("- README/documentation content was not read.\n");
        if (reads.keySet().stream().noneMatch(p -> kindFor(p).equals("configuration"))) out.append("- Build/configuration content was not read.\n");
        List<String> unread = listed.stream().filter(p -> !reads.containsKey(p) && !directories.contains(p)).toList();
        unread.stream().limit(20).forEach(p -> out.append("- Content not inspected: ").append(p).append('\n'));
        if (unread.size() > 20) out.append("- Content of ").append(unread.size() - 20).append(" additional listed entries was not inspected.\n");
        reads.forEach((p, read) -> {
            if (read.truncated()) out.append("- Partial read: ").append(p).append("; only the ")
                    .append(read.lines().size()).append('/').append(read.totalLines()).append(" complete lines delivered to the model can be used as evidence.\n");
        });
        out.append("- No coverage is claimed for unlisted files or subdirectory contents.\n")
                .append("- README statements are documentation claims; they do not prove working features.\n")
                .append("- No build, test, or command was run in the target project; runtime behavior was not verified.\n");
        return out.toString();
    }
}
