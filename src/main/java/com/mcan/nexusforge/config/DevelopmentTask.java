package com.mcan.nexusforge.config;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A user-provided, bounded change request. Its text never grants additional tool permissions. */
public record DevelopmentTask(String name, String goal, List<String> readFiles, Set<String> writeFiles,
                              List<String> acceptanceCriteria, String gradleTask, String testFilter,
                              int maxAttempts, boolean frontend) {
    public DevelopmentTask(String name, String goal, List<String> readFiles, Set<String> writeFiles,
                           List<String> criteria, String gradleTask, String testFilter, int maxAttempts) {
        this(name, goal, readFiles, writeFiles, criteria, gradleTask, testFilter, maxAttempts, false);
    }
    private static final int MAX_FILE_BYTES = 32_768;
    private static final Set<String> KEYS = Set.of("name", "goal", "read_files", "write_files",
            "acceptance_criteria", "gradle_task", "test_filter", "max_attempts");
    private static final Set<String> ALLOWED_KEYS = new HashSet<>(KEYS);
    static { ALLOWED_KEYS.add("project_type"); }

    public DevelopmentTask {
        checkText(name, "name", 120);
        checkText(goal, "goal", 3_000);
        readFiles = paths(readFiles, "read_files", 12, false);
        if (writeFiles == null) throw invalid("write_files must be present");
        writeFiles = Collections.unmodifiableSet(new LinkedHashSet<>(
                paths(new ArrayList<>(writeFiles), "write_files", 4, true)));
        if (acceptanceCriteria == null || acceptanceCriteria.isEmpty() || acceptanceCriteria.size() > 8) {
            throw invalid("acceptance_criteria must contain between 1 and 8 items");
        }
        acceptanceCriteria.forEach(item -> checkText(item, "acceptance_criteria item", 600));
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        if (!frontend && writeFiles.stream().anyMatch(path -> !(path.endsWith(".java") || path.endsWith(".kt"))))
            throw invalid("JVM tasks may write only Java/Kotlin sources");
        if (!frontend && (gradleTask == null || gradleTask.length() > 180
                || !gradleTask.matches("(?::[A-Za-z][A-Za-z0-9_-]*)+:test"))) {
            throw invalid("gradle_task must be a module test task, for example :services:catalog:test");
        }
        if (frontend) {
            if (!"frontend".equals(gradleTask) || testFilter == null || !writeFiles.contains(testFilter)
                    || !testFilter.matches("frontend/src/[A-Za-z0-9_/-]+\\.test\\.tsx?"))
                throw invalid("Frontend verification requires gradle_task=frontend and one approved .test.ts/.test.tsx path as test_filter");
            if (writeFiles.stream().anyMatch(path -> !path.startsWith("frontend/src/") || !(path.endsWith(".ts") || path.endsWith(".tsx") || path.endsWith(".css"))))
                throw invalid("Frontend writes must be TS/TSX/CSS sources under frontend/src");
        }
        if (!frontend && (testFilter == null || testFilter.length() > 250
                || !testFilter.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
            throw invalid("test_filter must be one fully qualified test class without wildcards or arguments");
        }
        if (maxAttempts < 1 || maxAttempts > 3) throw invalid("max_attempts must be between 1 and 3");
    }

    /** Reads strict UTF-8 JSON, rejecting duplicate/unknown keys and excess input before use. */
    public static DevelopmentTask load(Path file) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) throw invalid("task file exceeds " + MAX_FILE_BYTES + " bytes");
        String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            Set<String> seen = new HashSet<>();
            String name = null, goal = null, gradleTask = null, testFilter = null;
            List<String> readFiles = null, writeFiles = null, criteria = null;
            int attempts = 0;
            boolean frontend = false;
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!ALLOWED_KEYS.contains(key)) throw invalid("unknown task key: " + key);
                if (!seen.add(key)) throw invalid("duplicate task key: " + key);
                switch (key) {
                    case "project_type" -> {
                        String type = string(reader);
                        if (!Set.of("jvm", "frontend").contains(type)) throw invalid("project_type must be jvm or frontend");
                        frontend = type.equals("frontend");
                    }
                    case "name" -> name = string(reader);
                    case "goal" -> goal = string(reader);
                    case "read_files" -> readFiles = strings(reader, 12);
                    case "write_files" -> writeFiles = strings(reader, 4);
                    case "acceptance_criteria" -> criteria = strings(reader, 8);
                    case "gradle_task" -> gradleTask = string(reader);
                    case "test_filter" -> testFilter = string(reader);
                    case "max_attempts" -> {
                        if (reader.peek() != JsonToken.NUMBER) throw invalid("max_attempts must be an integer");
                        String value = reader.nextString();
                        if (!value.matches("[1-3]")) throw invalid("max_attempts must be an integer between 1 and 3");
                        attempts = Integer.parseInt(value);
                    }
                    default -> throw invalid("unsupported task key");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("unexpected content after task object");
            if (!seen.containsAll(KEYS)) {
                Set<String> missing = new LinkedHashSet<>(KEYS);
                missing.removeAll(seen);
                throw invalid("missing required task keys: " + missing);
            }
            // Validate the original list before converting it to a set so duplicates cannot disappear.
            List<String> validatedWrites = paths(writeFiles, "write_files", 4, true);
            return new DevelopmentTask(name, goal, readFiles, new LinkedHashSet<>(validatedWrites), criteria,
                    gradleTask, testFilter, attempts, frontend);
        } catch (IllegalStateException e) {
            throw invalid("malformed task JSON: " + e.getMessage());
        }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid("expected a JSON string");
        return reader.nextString();
    }

    private static List<String> strings(JsonReader reader, int limit) throws IOException {
        reader.beginArray();
        List<String> values = new ArrayList<>();
        while (reader.hasNext()) {
            if (values.size() == limit) throw invalid("too many items in task array (maximum " + limit + ")");
            values.add(string(reader));
        }
        reader.endArray();
        return values;
    }

    private static List<String> paths(List<String> values, String field, int limit, boolean sourceOnly) {
        if (values == null || values.isEmpty() || values.size() > limit) {
            throw invalid(field + " must contain between 1 and " + limit + " paths");
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            checkText(value, field + " path", 512);
            if (!value.equals(value.strip()) || value.startsWith("/") || value.indexOf('\\') >= 0
                    || value.indexOf(':') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
                throw invalid(field + " paths must be relative and use forward slashes");
            }
            for (String part : value.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    throw invalid(field + " paths cannot contain empty, dot, or parent segments");
                }
            }
            if (!seen.add(value.toLowerCase(Locale.ROOT))) throw invalid("duplicate path in " + field + ": " + value);
            if (sourceOnly && !(value.endsWith(".java") || value.endsWith(".kt") || (value.startsWith("frontend/src/") && (value.endsWith(".ts") || value.endsWith(".tsx") || value.endsWith(".css"))))) {
                throw invalid("write_files may contain only JVM source or frontend/src TS/TSX/CSS files");
            }
        }
        return List.copyOf(values);
    }

    private static void checkText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0) {
            throw invalid(field + " must contain between 1 and " + maximum + " nonblank characters");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid development task: " + reason);
    }
}
