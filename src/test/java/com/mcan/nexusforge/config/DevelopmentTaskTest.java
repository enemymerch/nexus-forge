package com.mcan.nexusforge.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DevelopmentTaskTest {
    @TempDir Path temporary;
    private static final String JSON = """
            {
              "name": "Validate an option",
              "goal": "Reject blank codes and retain valid input.",
              "read_files": ["services/catalog/src/main/kotlin/VariantOption.kt", "build.gradle.kts"],
              "write_files": ["services/catalog/src/main/kotlin/VariantOption.kt", "services/catalog/src/test/kotlin/VariantOptionTest.kt"],
              "acceptance_criteria": ["Blank input throws IllegalArgumentException", "Valid input is preserved"],
              "gradle_task": ":services:catalog:test",
              "test_filter": "com.nexus.catalog.domain.model.VariantOptionTest",
              "max_attempts": 3
            }
            """;

    @Test void loadsBoundedTaskAndMakesCollectionsImmutable() throws Exception {
        DevelopmentTask task = load(JSON);
        assertEquals(3, task.maxAttempts());
        assertEquals(":services:catalog:test", task.gradleTask());
        assertEquals(2, task.readFiles().size());
        assertThrows(UnsupportedOperationException.class, () -> task.writeFiles().add("Extra.java"));
        assertThrows(UnsupportedOperationException.class, () -> task.readFiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> task.acceptanceCriteria().clear());
    }

    @Test void taskCannotSupplyArbitraryBuildCommandsOrTestArguments() {
        for (String value : List.of("build", ":services:catalog:build", ":services:catalog:test --info",
                ":services:catalog:test;whoami", ":services:catalog:test && curl example.com", "$(whoami)", "test")) {
            JsonObject json = object();
            json.addProperty("gradle_task", value);
            assertThrows(IllegalArgumentException.class, () -> load(json.toString()), value);
        }
        for (String value : List.of("*", "com.nexus.*", "MyTest", "com.nexus.Test --init-script evil.gradle",
                "com.nexus.Test;whoami", "$(whoami)", "com.nexus.Test\n--info")) {
            JsonObject json = object();
            json.addProperty("test_filter", value);
            assertThrows(IllegalArgumentException.class, () -> load(json.toString()), value);
        }
    }

    @Test void rejectsEscapingAbsoluteAndAliasedPaths() {
        for (String value : List.of("../Escape.java", "src/../Escape.java", "src/./Escape.java", "C:/Escape.java",
                "/Escape.java", "\\\\server\\Escape.java", "src\\Escape.java", "src//Escape.java", " src/Escape.java")) {
            assertThrows(IllegalArgumentException.class, () -> task(List.of(value), Set.of("Safe.java")), value);
        }
        assertThrows(IllegalArgumentException.class,
                () -> task(List.of("src/Foo.java", "src/foo.java"), Set.of("Safe.java")));
        assertThrows(IllegalArgumentException.class,
                () -> task(List.of("Safe.java"), new LinkedHashSet<>(List.of("src/Foo.java", "src/foo.java"))));
    }

    @Test void onlyAllowsSmallSourceFileWriteScopes() {
        for (String value : List.of("build.gradle.kts", "gradlew", "README.md", ".env", "src/Main.class")) {
            assertThrows(IllegalArgumentException.class, () -> task(List.of("README.md"), Set.of(value)), value);
        }
        assertThrows(IllegalArgumentException.class,
                () -> task(List.of("README.md"), Set.of("A.java", "B.java", "C.java", "D.java", "E.java")));
        List<String> tooManyReads = new ArrayList<>();
        for (int i = 0; i < 13; i++) tooManyReads.add("File" + i + ".java");
        assertThrows(IllegalArgumentException.class, () -> task(tooManyReads, Set.of("Safe.java")));
    }

    @Test void jsonRejectsDuplicateKeysUnknownKeysAndDuplicateWritePaths() {
        assertThrows(IllegalArgumentException.class, () -> load(JSON.replace("\"name\":", "\"name\": \"duplicate\", \"name\":")));
        assertThrows(IllegalArgumentException.class, () -> load(JSON.replace("\"name\":", "\"command\": \"whoami\", \"name\":")));
        assertThrows(IllegalArgumentException.class, () -> load(JSON.replace("\"services/catalog/src/test/kotlin/VariantOptionTest.kt\"",
                "\"services/catalog/src/main/kotlin/VariantOption.kt\"")));
        JsonObject json = object();
        json.remove("write_files");
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
    }

    @Test void jsonRequiresExactTypesAndSingleStrictObject() {
        assertThrows(IllegalArgumentException.class, () -> load(JSON.replace("\"max_attempts\": 3", "\"max_attempts\": \"3\"")));
        assertThrows(IllegalArgumentException.class, () -> load(JSON.replace("\"max_attempts\": 3", "\"max_attempts\": 3.0")));
        assertThrows(IllegalArgumentException.class, () -> load(JSON.replace("\"goal\": \"Reject blank codes and retain valid input.\"", "\"goal\": 4")));
        assertThrows(IOException.class, () -> load(JSON + " {}"));
        assertThrows(IOException.class, () -> load("// comment\n" + JSON));
    }

    @Test void rejectsOversizedAndMalformedUtf8Files() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> load(" ".repeat(32_769)));
        Path file = temporary.resolve("invalid.json");
        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> DevelopmentTask.load(file));
    }

    @Test void enforcesGoalCriteriaAndRetryBudgets() {
        for (int attempts : List.of(0, 4)) {
            assertThrows(IllegalArgumentException.class, () -> new DevelopmentTask("task", "goal", List.of("A.java"),
                    Set.of("A.java"), List.of("criterion"), ":module:test", "example.Test", attempts));
        }
        assertThrows(IllegalArgumentException.class, () -> new DevelopmentTask("task", "x".repeat(3_001),
                List.of("A.java"), Set.of("A.java"), List.of("criterion"), ":module:test", "example.Test", 1));
        assertThrows(IllegalArgumentException.class, () -> new DevelopmentTask("task", "goal",
                List.of("A.java"), Set.of("A.java"), List.of(" "), ":module:test", "example.Test", 1));
        assertThrows(IllegalArgumentException.class, () -> new DevelopmentTask("task", "goal",
                List.of("A.java"), Set.of("A.java"), java.util.Collections.nCopies(9, "criterion"),
                ":module:test", "example.Test", 1));
    }

    @Test void loadsTheRunnableExample() throws Exception {
        DevelopmentTask task = DevelopmentTask.load(Path.of("tasks/variant-option-validation.json"));
        assertEquals("com.nexus.catalog.domain.model.VariantOptionTest", task.testFilter());
        assertEquals(2, task.writeFiles().size());
    }

    @Test void frontendProfileScopesWritesAndSelectsOneConcreteTestFile() throws Exception {
        DevelopmentTask task = DevelopmentTask.load(Path.of("tasks/frontend-categories.json"));
        assertTrue(task.frontend());
        assertEquals("frontend", task.gradleTask());
        assertTrue(task.writeFiles().contains(task.testFilter()));
        for (String denied : List.of("frontend/package.json", "frontend/vite.config.ts", "frontend/src/../escape.ts", "backend/src/Code.ts")) {
            assertThrows(IllegalArgumentException.class, () -> new DevelopmentTask("ui", "goal", List.of("README.md"),
                    Set.of(denied, "frontend/src/Page.test.tsx"), List.of("criterion"), "frontend", "frontend/src/Page.test.tsx", 1, true));
        }
        assertThrows(IllegalArgumentException.class, () -> new DevelopmentTask("ui", "goal", List.of("README.md"),
                Set.of("frontend/src/Page.tsx"), List.of("criterion"), "frontend", "frontend/src/Missing.test.tsx", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new DevelopmentTask("jvm", "goal", List.of("README.md"),
                Set.of("frontend/src/Page.tsx"), List.of("criterion"), ":module:test", "example.Test", 1));
    }

    private DevelopmentTask task(List<String> reads, Set<String> writes) {
        return new DevelopmentTask("task", "goal", reads, writes, List.of("criterion"), ":module:test", "example.Test", 1);
    }

    private DevelopmentTask load(String json) throws IOException {
        Path file = temporary.resolve("task.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return DevelopmentTask.load(file);
    }

    private JsonObject object() { return JsonParser.parseString(JSON).getAsJsonObject(); }
}
