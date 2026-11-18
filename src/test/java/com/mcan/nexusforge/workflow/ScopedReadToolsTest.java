package com.mcan.nexusforge.workflow;

import com.google.gson.JsonObject;
import com.mcan.nexusforge.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScopedReadToolsTest {
    private static final String APPROVED = "services/catalog/src/main/kotlin/com/nexus/catalog/domain/model/Variant.kt";
    private static final String SOURCE = "class Variant // actual-source-marker\n";
    @TempDir Path root;

    @Test void malformedPathIsDeniedWithExactUniqueSuggestionAndNeverAutoRead() throws Exception {
        write(APPROVED, SOURCE);
        ToolRegistry tools = ScopedReadTools.create(root, Set.of(APPROVED));

        String denied = read(tools, "services/catalog/src/main/kotlin/com.nexus/catalog/domain/model/Variant.kt");

        assertTrue(denied.startsWith("ERROR:"));
        assertTrue(denied.contains("Use exact approved path: " + APPROVED + "."));
        assertFalse(denied.contains("actual-source-marker"));
        assertFalse(denied.contains("\"lines\""));
        String accepted = read(tools, APPROVED);
        assertFalse(accepted.startsWith("ERROR:"));
        assertTrue(accepted.contains("actual-source-marker"));
    }

    @Test void unapprovedFilenameAndAmbiguousApprovedFilenamesDoNotProduceSuggestions() throws Exception {
        write(APPROVED, SOURCE);
        String second = "another/module/Variant.kt";
        write(second, "class Other // second-source-marker\n");
        ToolRegistry tools = ScopedReadTools.create(root, Set.of(APPROVED, second));

        for (String path : Set.of("wrong/Other.kt", "wrong/Variant.kt")) {
            String denied = read(tools, path);
            assertTrue(denied.startsWith("ERROR:"));
            assertTrue(denied.contains("outside this task's read scope"));
            assertFalse(denied.contains("Use exact approved path"));
            assertFalse(denied.contains("source-marker"));
        }
    }

    @Test void longRejectedInputCannotCrowdTheUsefulSuggestionOutOfTheErrorBudget() throws Exception {
        write(APPROVED, SOURCE);
        ToolRegistry tools = ScopedReadTools.create(root, Set.of(APPROVED));

        String denied = read(tools, "wrong/".repeat(100) + "Variant.kt");

        assertTrue(denied.startsWith("ERROR:"));
        assertTrue(denied.contains("Use exact approved path: " + APPROVED + "."));
        assertTrue(denied.contains("Requested path is outside this task scope."));
        assertFalse(denied.contains("actual-source-marker"));
    }

    private void write(String path, String content) throws Exception {
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), content);
    }

    private static String read(ToolRegistry tools, String path) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", path);
        return tools.execute("read_file", arguments);
    }
}
