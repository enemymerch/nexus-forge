package com.mcan.nexusforge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcan.nexusforge.llm.ChatModel;
import com.mcan.nexusforge.tool.Tool;
import com.mcan.nexusforge.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RoleSessionTest {
    private static final JsonObject SCHEMA = JsonParser.parseString("""
            {"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"],"additionalProperties":false}
            """).getAsJsonObject();

    @Test void sessionsHaveSeparatePersistentHistoriesAndDefensiveSnapshots() throws Exception {
        List<JsonArray> sent = new ArrayList<>();
        ChatModel model = (messages, tools) -> {
            sent.add(messages.deepCopy());
            return tools.isEmpty() ? content("{\"summary\":\"complete\"}") : content("unverified prose");
        };
        RoleSession analyst = session("Analyst", model, new ToolRegistry(), 8, 4);
        RoleSession developer = session("Developer", model, new ToolRegistry(), 8, 4);
        analyst.request("Analyze task A", SCHEMA);
        developer.request("Develop task B", SCHEMA);
        assertTrue(analyst.historySnapshot().toString().contains("Analyze task A"));
        assertFalse(analyst.historySnapshot().toString().contains("Develop task B"));
        assertFalse(developer.historySnapshot().toString().contains("Analyze task A"));
        JsonArray snapshot = analyst.historySnapshot();
        snapshot.remove(0);
        assertEquals("system", analyst.historySnapshot().get(0).getAsJsonObject().get("role").getAsString());
        assertEquals(2, analyst.steps());
        assertEquals(2, developer.steps());
    }

    @Test void untrustedProseTriggersSeparateSchemaConstrainedFinalCall() throws Exception {
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger structured = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public JsonObject chat(JsonArray messages, JsonArray tools) {
                ordinary.incrementAndGet();
                return content("I already ran tests successfully. Untrusted prose.");
            }
            @Override public JsonObject chat(JsonArray messages, JsonArray tools, JsonObject schema) {
                structured.incrementAndGet();
                assertTrue(tools.isEmpty());
                assertEquals(SCHEMA, schema);
                assertNotSame(SCHEMA, schema);
                assertFalse(messages.toString().contains("I already ran tests successfully"));
                return content("{\"summary\":\"not verified\"}");
            }
        };
        RoleSession role = session("Analyst", model, new ToolRegistry(), 6, 3);
        assertEquals("not verified", role.request("Inspect files", SCHEMA).get("summary").getAsString());
        assertEquals(1, ordinary.get());
        assertEquals(1, structured.get());
        assertEquals(2, role.steps());
    }

    @Test void repairRequestsShareStepAndActualToolCallBudgets() throws Exception {
        Queue<JsonObject> responses = new ArrayDeque<>(List.of(
                readCall("A.kt"), content("prepare final"), content("{\"summary\":\"first\"}"),
                readCall("B.kt"), content("{\"summary\":\"second\"}"), content("{\"summary\":\"third\"}")));
        AtomicInteger executed = new AtomicInteger();
        ChatModel model = (messages, tools) -> responses.remove();
        RoleSession role = session("Developer", model, new ToolRegistry(readTool(executed)), 6, 2);
        role.request("Initial task", SCHEMA);
        role.request("Repair the draft", SCHEMA);
        role.request("Final repair", SCHEMA);
        assertEquals(6, role.steps());
        assertEquals(2, role.toolCalls());
        assertEquals(2, executed.get());
        assertThrows(IllegalStateException.class, () -> role.request("Do more", SCHEMA));
        assertEquals(6, role.steps());
    }

    @Test void malformedAndInvalidArgumentCallsConsumeDispatchBudget() throws Exception {
        JsonObject malformed = new JsonObject();
        malformed.add("tool_calls", JsonParser.parseString("[{\"function\":{}},{\"function\":{\"name\":\"read_file\",\"arguments\":{}}}]").getAsJsonArray());
        Queue<JsonObject> responses = new ArrayDeque<>(List.of(malformed, content("{\"summary\":\"bad calls\"}")));
        AtomicInteger executed = new AtomicInteger();
        RoleSession role = session("Analyst", (messages, tools) -> responses.remove(),
                new ToolRegistry(readTool(executed)), 4, 2);
        role.request("Inspect files", SCHEMA);
        assertEquals(2, role.toolCalls());
        assertEquals(0, executed.get());
        assertTrue(role.historySnapshot().toString().contains("Invalid tool call"));
        assertTrue(role.historySnapshot().toString().contains("Exactly one string parameter"));
    }

    @Test void outputIsBoundedAfterEvidenceIdsAreAddedAndTotalBudgetIsEnforced() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        ChatModel model = (messages, tools) -> tools.isEmpty()
                ? content("{\"summary\":\"partial inspection\"}") : readCall("Source" + sequence.incrementAndGet() + ".kt");
        RoleSession role = session("Analyst", model, new ToolRegistry(readTool(executed)), 12, 10);
        role.request("Read source files", SCHEMA);
        int total = 0;
        int sourceIds = 0;
        for (JsonElement element : role.historySnapshot()) {
            JsonObject message = element.getAsJsonObject();
            if (!message.get("role").getAsString().equals("tool")) continue;
            String output = message.get("content").getAsString();
            assertTrue(output.length() <= 2_400);
            total += output.length();
            if (output.startsWith("ERROR:")) continue;
            for (JsonElement row : JsonParser.parseString(output).getAsJsonObject().getAsJsonArray("lines")) {
                JsonObject line = row.getAsJsonObject();
                if (line.has("id")) {
                    assertTrue(role.evidence().resolve(line.get("id").getAsString()).isPresent());
                    sourceIds++;
                }
            }
        }
        assertTrue(total <= 8_000);
        assertTrue(total > 2_400);
        assertTrue(sourceIds > 0);
        assertEquals(executed.get(), role.toolCalls());
        assertTrue(role.historySnapshot().toString().length() <= 24_000);
    }

    @Test void closedToolsCannotExecuteEvenIfModelRequestsThem() {
        AtomicInteger executed = new AtomicInteger();
        RoleSession role = session("Tester", (messages, tools) -> readCall("A.kt"),
                new ToolRegistry(readTool(executed)), 2, 3);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> role.request("Inspect files", SCHEMA));
        assertTrue(failure.getMessage().contains("final JSON"));
        assertEquals(1, executed.get());
        assertEquals(1, role.toolCalls());
        assertEquals(2, role.steps());
    }

    @Test void transportFailuresStillConsumeModelSteps() {
        RoleSession role = session("Tester", (messages, tools) -> { throw new IOException("offline"); },
                new ToolRegistry(), 2, 2);
        assertThrows(IOException.class, () -> role.request("Initial task", SCHEMA));
        assertThrows(IOException.class, () -> role.request("Retry task", SCHEMA));
        assertEquals(2, role.steps());
        assertThrows(IllegalStateException.class, () -> role.request("Unlimited retry", SCHEMA));
    }

    @Test void javaRequiredReadsDeliverActualEvidenceBeforeTheFirstModelRequest() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        ChatModel model = (messages, tools) -> {
            assertTrue(tools.isEmpty());
            assertEquals(2, executed.get());
            List<String> paths = new ArrayList<>();
            for (JsonElement element : messages) {
                JsonObject message = element.getAsJsonObject();
                assertFalse(message.has("tool_calls"), "Java must not fabricate assistant tool calls.");
                if (!message.get("role").getAsString().equals("tool")) continue;
                assertEquals("read_file", message.get("tool_name").getAsString());
                JsonObject actual = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
                assertFalse(actual.getAsJsonArray("lines").isEmpty());
                assertTrue(actual.getAsJsonArray("lines").get(0).getAsJsonObject().has("id"));
                paths.add(actual.get("path").getAsString());
            }
            assertEquals(List.of("A.kt", "B.kt"), paths);
            assertTrue(messages.toString().contains("Java-controlled required inspection follows"));
            return content("{\"summary\":\"inspected\"}");
        };
        RoleSession role = session("Analyst", model, new ToolRegistry(readTool(executed)), 10, 8);
        role.request("Read both files", SCHEMA, java.util.Set.of("A.kt", "B.kt"));
        assertEquals(2, executed.get());
        assertEquals(2, role.toolCalls());
        assertEquals(1, role.steps());
        assertFalse(role.evidence().evidenceIds().isEmpty());
    }

    @Test void failedRequiredReadCannotReachTheModelOrEstablishSourceEvidence() {
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        Tool denied = new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "Denied source read"; }
            public JsonObject parameters() { return new JsonObject(); }
            public String execute(JsonObject arguments) throws IOException {
                executed.incrementAndGet();
                throw new IOException("Source is unavailable.");
            }
        };
        RoleSession role = session("Analyst", (messages, tools) -> {
            modelCalls.incrementAndGet();
            return content("{\"summary\":\"claimed inspection\"}");
        }, new ToolRegistry(denied), 4, 2);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> role.request("Read required file", SCHEMA, java.util.Set.of("Missing.kt")));

        assertTrue(failure.getMessage().contains("did not deliver rows"));
        assertEquals(1, executed.get());
        assertEquals(1, role.toolCalls());
        assertEquals(0, role.steps());
        assertEquals(0, modelCalls.get());
        assertTrue(role.evidence().evidenceIds().isEmpty());
        assertTrue(role.historySnapshot().toString().contains("Source is unavailable."));
    }

    @Test void requiredReadsValidateRemainingToolBudgetBeforeExecutingAnyMoreReads() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        RoleSession role = session("Developer", (messages, tools) -> {
            modelCalls.incrementAndGet();
            return content("{\"summary\":\"inspected\"}");
        }, new ToolRegistry(readTool(executed)), 4, 2);
        role.request("Initial inspection", SCHEMA, java.util.Set.of("A.kt"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> role.request("Repair inspection", SCHEMA, java.util.Set.of("B.kt", "C.kt")));

        assertTrue(failure.getMessage().contains("insufficient remaining tool call budget"));
        assertEquals(1, executed.get());
        assertEquals(1, role.toolCalls());
        assertEquals(1, role.steps());
        assertEquals(1, modelCalls.get());
    }

    @Test void finalEvidenceSchemaAllowsOnlyDeliveredIdsWithoutMutatingTheCallerSchema() throws Exception {
        JsonObject schema = evidenceSchema();
        JsonObject original = schema.deepCopy();
        AtomicInteger executed = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public JsonObject chat(JsonArray messages, JsonArray tools) {
                fail("Required inspection should proceed directly to the structured response.");
                return null;
            }
            @Override public JsonObject chat(JsonArray messages, JsonArray tools, JsonObject actualSchema) {
                assertTrue(tools.isEmpty());
                List<String> delivered = new ArrayList<>();
                for (JsonElement value : messages) {
                    JsonObject message = value.getAsJsonObject();
                    if (!message.get("role").getAsString().equals("tool")) continue;
                    JsonObject actual = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
                    for (JsonElement line : actual.getAsJsonArray("lines")) {
                        JsonObject row = line.getAsJsonObject();
                        if (row.has("id")) delivered.add(row.get("id").getAsString());
                    }
                }
                List<String> allowed = new ArrayList<>();
                for (JsonElement id : actualSchema.getAsJsonObject("properties").getAsJsonObject("evidence_ids")
                        .getAsJsonObject("items").getAsJsonArray("enum")) allowed.add(id.getAsString());
                assertFalse(delivered.isEmpty());
                assertEquals(delivered, allowed);
                assertFalse(allowed.contains("E9999"));
                assertEquals(original, schema);
                return content("{\"summary\":\"inspected\",\"evidence_ids\":[\"" + delivered.getFirst() + "\"]}");
            }
        };
        RoleSession role = session("Analyst", model, new ToolRegistry(readTool(executed)), 4, 2);
        role.request("Inspect required source", schema, java.util.Set.of("A.kt"));
        assertEquals(1, executed.get());
        assertEquals(1, role.steps());
        assertEquals(original, schema);
    }

    @Test void evidenceSchemaCannotReachTheModelWithoutDeliveredSourceIds() {
        AtomicInteger modelCalls = new AtomicInteger();
        RoleSession role = session("Analyst", (messages, tools) -> {
            modelCalls.incrementAndGet();
            return content("{\"evidence_ids\":[\"E9999\"]}");
        }, new ToolRegistry(), 4, 2);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> role.requestFinal("Report source evidence", evidenceSchema()));
        assertTrue(failure.getMessage().contains("no delivered source evidence IDs"));
        assertEquals(0, modelCalls.get());
        assertEquals(0, role.steps());
    }

    @Test void compactCodingReadsDeliverBoundedCodeBeforeTheModelWithoutIssuingEvidenceIds() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        ChatModel model = (messages, tools) -> {
            assertTrue(tools.isEmpty());
            JsonObject observation = messages.asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(message -> message.has("tool_name")).findFirst().orElseThrow();
            String source = observation.get("content").getAsString();
            assertTrue(source.startsWith("FILE: Source.kt\n"));
            assertTrue(source.contains("val sourceLine1 = \""));
            assertTrue(source.contains("truncated: true"));
            assertTrue(source.length() <= 2_400);
            return content("{\"summary\":\"source read\"}");
        };
        RoleSession role = new RoleSession("Developer", "Read source", model, new ToolRegistry(readTool(executed)), 4, 2, 24_000, true);
        role.request("Inspect source", SCHEMA, java.util.Set.of("Source.kt"));
        assertEquals(1, executed.get());
        assertEquals(1, role.steps());
        assertEquals(1, role.toolCalls());
        assertTrue(role.evidence().evidenceIds().isEmpty());
    }

    @Test void finalReviewUsesNoToolsAndConsumesOneBoundedModelStep() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        ChatModel model = (messages, tools) -> {
            assertTrue(tools.isEmpty());
            return content("{\"summary\":\"reviewed supplied evidence\"}");
        };
        RoleSession role = session("Tester", model, new ToolRegistry(readTool(executed)), 4, 2);
        role.requestFinal("Review the actual command result", SCHEMA);
        assertEquals(0, executed.get());
        assertEquals(1, role.steps());
    }

    @Test void contextOverflowPreservesHistoryAndNeverExecutesPendingTools() {
        AtomicInteger executed = new AtomicInteger();
        JsonObject oversized = readCall("A.kt");
        oversized.addProperty("content", "x".repeat(4_000));
        RoleSession role = new RoleSession("Analyst", "Inspect files", (messages, tools) -> oversized,
                new ToolRegistry(readTool(executed)), 4, 3, 4_000);
        assertThrows(IllegalStateException.class, () -> role.request("Read one file", SCHEMA));
        assertEquals(0, executed.get());
        assertEquals(0, role.toolCalls());
        assertTrue(role.historySnapshot().toString().length() < 4_000);
        assertEquals(2, role.historySnapshot().size());
    }

    @Test void invalidFinalContentFailsExplicitly() {
        for (String invalid : List.of("", "{}", "[]", "not JSON", "{\"a\":1} {\"b\":2}", "// comment\n{\"a\":1}")) {
            Queue<JsonObject> responses = new ArrayDeque<>(List.of(content("prepare final"), content(invalid)));
            RoleSession role = session("Tester", (messages, tools) -> responses.remove(), new ToolRegistry(), 4, 2);
            assertThrows(IllegalStateException.class, () -> role.request("Review", SCHEMA), invalid);
        }
    }

    @Test void requestAndConstructorLimitsAreJavaGuards() {
        ChatModel model = (messages, tools) -> content("unused");
        assertThrows(IllegalArgumentException.class,
                () -> new RoleSession("Analyst", "Inspect", model, new ToolRegistry(), 25, 1, 24_000));
        assertThrows(IllegalArgumentException.class,
                () -> new RoleSession("Analyst", "Inspect", model, new ToolRegistry(), 2, 21, 24_000));
        assertThrows(IllegalArgumentException.class,
                () -> new RoleSession("Analyst", "Inspect", model, new ToolRegistry(), 2, 1, 24_001));
        RoleSession role = session("Analyst", model, new ToolRegistry(), 4, 2);
        assertThrows(IllegalArgumentException.class, () -> role.request("x".repeat(6_001), SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> role.request("Inspect", new JsonObject()));
        assertEquals(0, role.steps());
    }

    private RoleSession session(String name, ChatModel model, ToolRegistry tools, int steps, int calls) {
        return new RoleSession(name, "Complete the assigned task using actual evidence.", model, tools, steps, calls, 24_000);
    }

    private static JsonObject evidenceSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{"summary":{"type":"string"},
                  "evidence_ids":{"type":"array","items":{"type":"string"}}},
                 "required":["summary","evidence_ids"],"additionalProperties":false}
                """).getAsJsonObject();
    }

    private static JsonObject content(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject readCall(String path) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", path);
        JsonObject function = new JsonObject();
        function.addProperty("name", "read_file");
        function.add("arguments", arguments);
        JsonObject call = new JsonObject();
        call.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(call);
        JsonObject response = content("");
        response.add("tool_calls", calls);
        return response;
    }

    private static Tool readTool(AtomicInteger executed) {
        return new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "Read source lines"; }
            public JsonObject parameters() { return JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}").getAsJsonObject(); }
            public String execute(JsonObject arguments) {
                executed.incrementAndGet();
                JsonObject output = new JsonObject();
                output.addProperty("path", arguments.get("path").getAsString());
                output.addProperty("totalLines", 80);
                output.addProperty("truncated", false);
                JsonArray lines = new JsonArray();
                for (int i = 1; i <= 80; i++) {
                    JsonObject line = new JsonObject();
                    line.addProperty("line", i);
                    line.addProperty("text", "val sourceLine" + i + " = \"" + "x".repeat(90) + "\"");
                    lines.add(line);
                }
                output.add("lines", lines);
                return output.toString();
            }
        };
    }
}
