package com.mcan.nexusforge.workspace;

import com.mcan.nexusforge.tool.ToolRegistry;
import com.mcan.nexusforge.tool.ToolOutput;
import com.mcan.nexusforge.tool.project.ProjectTools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceEvidenceTest {
    @TempDir Path root;

    @Test void readHasCanonicalPathAndExactNumberedWholeLines() throws Exception {
        Files.createDirectory(root.resolve("docs"));
        Files.writeString(root.resolve("docs/README.md"), "one\r\n\t\"<exact>\"\n\nlast\r");
        String output = new Workspace(root).read("docs/../docs/README.md");
        JsonObject evidence = JsonParser.parseString(output).getAsJsonObject();
        assertEquals("docs/README.md", evidence.get("path").getAsString());
        assertEquals(4, evidence.get("totalLines").getAsInt());
        assertFalse(evidence.get("truncated").getAsBoolean());
        JsonArray lines = evidence.getAsJsonArray("lines");
        assertEquals(4, lines.size());
        assertEquals("\t\"<exact>\"", lines.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("", lines.get(2).getAsJsonObject().get("text").getAsString());
        for (int i = 0; i < lines.size(); i++) assertEquals(i + 1, lines.get(i).getAsJsonObject().get("line").getAsInt());
    }

    @Test void readAndRegistryBudgetOmitWholeLinesAndKeepValidJson() throws Exception {
        String source = IntStream.range(0, 80).mapToObj(i -> i + ":" + "x".repeat(120)).collect(Collectors.joining("\n"));
        Files.writeString(root.resolve("README.md"), source);
        String[] original = source.split("\n");
        ToolRegistry registry = new ProjectTools(root).registry();
        JsonObject args = new JsonObject();
        args.addProperty("path", "README.md");
        for (int budget : new int[]{6500, 650, 180, 100}) {
            String output = registry.execute("read_file", args, budget);
            assertTrue(output.length() <= budget);
            JsonObject evidence = JsonParser.parseString(output).getAsJsonObject();
            assertEquals(80, evidence.get("totalLines").getAsInt());
            assertTrue(evidence.get("truncated").getAsBoolean());
            JsonArray lines = evidence.getAsJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                assertEquals(original[i], lines.get(i).getAsJsonObject().get("text").getAsString());
            }
        }
        assertTrue(registry.execute("read_file", args, 30).startsWith("ERROR:"));
        assertEquals("", registry.execute("read_file", args, 0));
        assertEquals("", registry.execute("read_file", args, -1));
    }

    @Test void oversizedSingleLineIsNeverIncludedPartially() throws Exception {
        Files.writeString(root.resolve("README.md"), "x".repeat(7000) + "\nsecond");
        JsonObject evidence = JsonParser.parseString(new Workspace(root).read("README.md")).getAsJsonObject();
        assertTrue(evidence.get("truncated").getAsBoolean());
        assertEquals(2, evidence.get("totalLines").getAsInt());
        assertEquals(0, evidence.getAsJsonArray("lines").size());
    }

    @Test void listHasCanonicalPathsExcludesRestrictedEntriesAndRespectsBudget() throws Exception {
        Files.createDirectory(root.resolve("docs"));
        Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve(".env"), "secret");
        Files.writeString(root.resolve("app.jar"), "binary");
        for (int i = 0; i < 20; i++) Files.writeString(root.resolve(String.format("file-%02d.md", i)), "text");
        ToolRegistry registry = new ProjectTools(root).registry();
        JsonObject args = new JsonObject();
        args.addProperty("path", ".");
        String full = registry.execute("list_files", args);
        JsonObject evidence = JsonParser.parseString(full).getAsJsonObject();
        assertEquals(".", evidence.get("path").getAsString());
        assertEquals(21, evidence.getAsJsonArray("entries").size());
        assertFalse(evidence.get("truncated").getAsBoolean());
        assertFalse(full.contains(".env"));
        assertFalse(full.contains("target"));
        assertFalse(full.contains("app.jar"));
        JsonObject directory = evidence.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("docs", directory.get("path").getAsString());
        assertEquals("directory", directory.get("type").getAsString());
        String bounded = registry.execute("list_files", args, 200);
        assertTrue(bounded.length() <= 200);
        JsonObject trimmed = JsonParser.parseString(bounded).getAsJsonObject();
        assertTrue(trimmed.get("truncated").getAsBoolean());
        assertTrue(trimmed.getAsJsonArray("entries").size() < 21);
    }

    @Test void invalidArgumentsAndErrorsAlsoRespectBudgets() throws Exception {
        ToolRegistry registry = new ProjectTools(root).registry();
        for (int budget : new int[]{0, 3, 6, 12, 100}) {
            String error = registry.execute("read_file", new JsonObject(), budget);
            assertTrue(error.length() <= budget);
            if (!error.isEmpty()) assertTrue(error.startsWith("ERROR:"));
        }
    }
}
