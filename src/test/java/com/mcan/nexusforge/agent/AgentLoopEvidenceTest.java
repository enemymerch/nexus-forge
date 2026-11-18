package com.mcan.nexusforge.agent;

import com.mcan.nexusforge.tool.project.ProjectTools;
import com.mcan.nexusforge.llm.ChatModel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopEvidenceTest {
    @TempDir Path root;

    private static JsonObject call(String name, String path) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", path);
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.add("arguments", arguments);
        JsonObject toolCall = new JsonObject();
        toolCall.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(toolCall);
        JsonObject response = new JsonObject();
        response.addProperty("content", "");
        response.add("tool_calls", calls);
        return response;
    }

    private static JsonObject finding(String claim, String... ids) {
        JsonArray evidence = new JsonArray();
        for (String id : ids) evidence.add(id);
        JsonObject finding = new JsonObject();
        finding.addProperty("claim", claim);
        finding.add("evidence_ids", evidence);
        return finding;
    }

    private static JsonObject response(JsonObject... findings) {
        JsonArray items = new JsonArray();
        for (JsonObject finding : findings) items.add(finding);
        JsonObject report = new JsonObject();
        report.add("findings", items);
        JsonObject response = new JsonObject();
        response.addProperty("content", report.toString());
        return response;
    }

    private static List<JsonObject> toolResults(JsonArray messages) {
        List<JsonObject> results = new ArrayList<>();
        for (JsonElement item : messages) {
            JsonObject message = item.getAsJsonObject();
            if ("tool".equals(message.get("role").getAsString())) results.add(message);
        }
        return results;
    }

    private static String deliveredId(JsonArray messages, String path, int line) {
        for (JsonObject message : toolResults(messages)) {
            if (!"read_file".equals(message.get("tool_name").getAsString())) continue;
            JsonObject data = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
            if (!path.equals(data.get("path").getAsString())) continue;
            for (JsonElement element : data.getAsJsonArray("lines")) {
                JsonObject row = element.getAsJsonObject();
                if (row.get("line").getAsInt() == line) {
                    assertTrue(row.has("id"), "A visible nonblank line must have a Java-issued evidence ID.");
                    return row.get("id").getAsString();
                }
            }
        }
        throw new AssertionError("The model was not given the requested evidence: " + path + ":" + line);
    }

    private void writeSmallProject() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Nexus PIM\nKatalog yönetimi hedeflenir.\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
    }

    @Test void actualBoundedReadAcceptsVisibleIdAndRejectsForgedAndUndeliveredEvidence() throws Exception {
        writeSmallProject();
        List<String> buildLines = new ArrayList<>();
        buildLines.add("plugins { id 'java' }");
        for (int i = 0; i < 100; i++) buildLines.add("// padding " + i + ": " + "x".repeat(80));
        String hiddenLine = "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }";
        buildLines.add(hiddenLine);
        Files.writeString(root.resolve("build.gradle"), String.join("\n", buildLines));
        AtomicInteger steps = new AtomicInteger();
        ChatModel model = (messages, tools) -> switch (steps.incrementAndGet()) {
            case 1 -> {
                assertFalse(tools.isEmpty());
                yield call("list_files", ".");
            }
            case 2 -> {
                List<JsonObject> results = toolResults(messages);
                assertEquals(1, results.size());
                assertEquals("list_files", results.getFirst().get("tool_name").getAsString());
                JsonObject listing = JsonParser.parseString(results.getFirst().get("content").getAsString()).getAsJsonObject();
                assertTrue(listing.getAsJsonArray("entries").toString().contains("README.md"));
                yield call("read_file", "README.md");
            }
            case 3 -> call("read_file", "build.gradle");
            case 4 -> {
                assertTrue(tools.isEmpty(), "The synthesis step must not offer tools.");
                List<JsonObject> results = toolResults(messages);
                assertEquals(3, results.size());
                String delivered = results.getLast().get("content").getAsString();
                assertTrue(delivered.length() <= 2400);
                JsonObject boundedBuild = JsonParser.parseString(delivered).getAsJsonObject();
                assertEquals("build.gradle", boundedBuild.get("path").getAsString());
                assertTrue(boundedBuild.get("truncated").getAsBoolean());
                assertEquals(buildLines.size(), boundedBuild.get("totalLines").getAsInt());
                assertTrue(boundedBuild.getAsJsonArray("lines").size() < buildLines.size());
                assertFalse(delivered.contains(hiddenLine));
                yield response(
                        finding("GÖRÜNÜR_BULGU: Java eklentisi belirtiliyor.", deliveredId(messages, "build.gradle", 1)),
                        finding("GÖRÜNMEYEN_BULGU", "UNDELIVERED_LINE_ID"),
                        finding("SAHTE_KANIT_BULGUSU", "FORGED_EVIDENCE_ID"));
            }
            default -> throw new AssertionError("Unexpected model call.");
        };

        String result = new AgentRunner(model, new ProjectTools(root).registry(), 4, 3).run();

        assertEquals(4, steps.get());
        assertTrue(result.contains("GÖRÜNÜR_BULGU"));
        assertTrue(result.contains("build.gradle:1"));
        assertTrue(result.contains("Finding 2"));
        assertTrue(result.contains("Finding 3"));
        assertFalse(result.contains("GÖRÜNMEYEN_BULGU"));
        assertFalse(result.contains("SAHTE_KANIT_BULGUSU"));
        assertTrue(result.contains("list_files \".\""));
        assertTrue(result.contains("read_file \"README.md\""));
        assertTrue(result.contains("read_file \"build.gradle\""));
        assertTrue(result.contains("[TRUNCATED]"));
    }

    @Test void prematureModelReportIsDiscardedBeforeInspectionAndNeverBecomesEvidence() throws Exception {
        writeSmallProject();
        String invented = "PREMATURE_INVENTED_SOURCE_ID";
        AtomicInteger steps = new AtomicInteger();
        ChatModel model = (messages, tools) -> switch (steps.incrementAndGet()) {
            case 1 -> response(finding("Erken ve kanıtsız rapor.", invented));
            case 2 -> {
                assertFalse(messages.toString().contains(invented), "Premature model text must be removed from history.");
                assertFalse(tools.isEmpty());
                assertTrue(toolResults(messages).isEmpty());
                yield call("list_files", ".");
            }
            case 3 -> call("read_file", "README.md");
            case 4 -> call("read_file", "build.gradle");
            case 5 -> {
                assertTrue(tools.isEmpty());
                assertEquals(3, toolResults(messages).size());
                assertFalse(messages.toString().contains(invented));
                yield response(
                        finding("BELGEDE_GÖRÜLEN_HEDEF", deliveredId(messages, "README.md", 2)),
                        finding("ERKEN_METİNDEN_UYDURULAN_BULGU", invented));
            }
            default -> throw new AssertionError("Unexpected model call.");
        };

        String result = new AgentRunner(model, new ProjectTools(root).registry(), 5, 3).run();

        assertEquals(5, steps.get());
        assertTrue(result.contains("BELGEDE_GÖRÜLEN_HEDEF"));
        assertTrue(result.contains("README.md:2"));
        assertTrue(result.contains("Finding 2"));
        assertFalse(result.contains("ERKEN_METİNDEN_UYDURULAN_BULGU"));
    }

    @Test void modelSuccessWithoutAnyToolEvidenceFailsTheRun() throws Exception {
        writeSmallProject();
        AtomicInteger steps = new AtomicInteger();
        ChatModel model = (messages, tools) -> {
            int step = steps.incrementAndGet();
            assertTrue(toolResults(messages).isEmpty());
            if (step == 3) assertTrue(tools.isEmpty());
            else assertFalse(tools.isEmpty());
            return response(finding("Her şey incelendi ve başarılı.", "MODEL_INVENTED_ID"));
        };

        assertThrows(IllegalStateException.class,
                () -> new AgentRunner(model, new ProjectTools(root).registry(), 3, 3).run());
        assertEquals(3, steps.get());
    }

    @Test void evidenceValidationGetsOnlyOneRepairAttemptWithinStepBudget() throws Exception {
        writeSmallProject();
        AtomicInteger steps = new AtomicInteger();
        ChatModel model = (messages, tools) -> switch (steps.incrementAndGet()) {
            case 1 -> call("list_files", ".");
            case 2 -> call("read_file", "README.md");
            case 3 -> call("read_file", "build.gradle");
            case 4, 5 -> {
                assertTrue(tools.isEmpty());
                assertEquals(3, toolResults(messages).size());
                if (steps.get() == 5) assertTrue(messages.toString().contains("One repair attempt"));
                yield response(
                        finding("Belgenin başlığı Nexus PIM.", deliveredId(messages, "README.md", 1)),
                        finding("Kanıtsız iddia", "UNKNOWN_REPAIR_ID"));
            }
            default -> throw new AssertionError("Repair must not loop indefinitely.");
        };
        String result = new AgentRunner(model, new ProjectTools(root).registry(), 8, 3).run();
        assertEquals(5, steps.get());
        assertTrue(result.contains("README.md:1"));
        assertTrue(result.contains("Finding 2"));
    }
}
