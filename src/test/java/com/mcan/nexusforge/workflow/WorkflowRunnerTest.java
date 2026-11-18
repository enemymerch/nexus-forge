package com.mcan.nexusforge.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcan.nexusforge.config.DevelopmentTask;
import com.mcan.nexusforge.llm.ChatModel;
import com.mcan.nexusforge.verification.CheckResult;
import com.mcan.nexusforge.verification.TestSuite;
import com.mcan.nexusforge.workspace.run.IsolatedWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic orchestration checks; actual subprocess/XML handling is tested by VerificationServiceTest. */
class WorkflowRunnerTest {
    private static final String SOURCE = "module/src/main/java/demo/Counter.java";
    private static final String TEST = "module/src/test/java/demo/CounterTest.java";
    private static final String ORIGINAL = "package demo;\npublic class Counter {\n    public int value() { return 0; }\n}\n";
    private static final String REGRESSION_TEST = """
            package demo;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class CounterTest {
                @Test void returnsOne() { assertEquals(1, new Counter().value()); }
            }
            """;
    @TempDir Path temporary;

    @Test void completesIsolatedPipelineWithActualReadsArtifactsAndRedGreenProof() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.SUCCESS);
        List<Integer> inspectedValues = new ArrayList<>();
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            int value = currentValue(workspace);
            inspectedValues.add(value);
            assertEquals(REGRESSION_TEST, Files.readString(workspace.root().resolve(TEST)));
            assertTrue(workspace.originalUnchanged());
            assertNotEquals(source.toRealPath(), workspace.root());
            return result(logs, inspectedValues.size(), value == 1, true);
        }, 16, 10);

        WorkflowResult outcome = runner.run(task(1));

        assertTrue(outcome.success(), outcome.message());
        assertEquals(2, inspectedValues.size(), "One patched check and one baseline check establish RED/GREEN.");
        assertTrue(inspectedValues.containsAll(List.of(0, 1)));
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
        assertFalse(Files.exists(source.resolve(TEST)), "Original source must never receive the generated test.");
        assertTrue(Files.isRegularFile(outcome.report()));
        assertTrue(Files.isRegularFile(outcome.diff()));
        String diff = Files.readString(outcome.diff());
        assertTrue(diff.contains("return 1;"));
        assertTrue(diff.contains("new file mode"));
        assertTrue(diff.contains("CounterTest.java"));
        String report = Files.readString(outcome.report());
        assertTrue(report.contains(SOURCE));
        assertTrue(report.contains(TEST));
        assertTrue(model.rolesRead.containsAll(Set.of("Analyst", "Developer", "Tester")));
        assertEquals(1, model.developerProposals);
        assertEquals(1, model.testerProposals);
        assertEquals(1, model.reviews);
        JsonObject structured = JsonParser.parseString(Files.readString(outcome.runDirectory().resolve("report.json"))).getAsJsonObject();
        assertEquals(TEST, structured.getAsJsonArray("test_scaffolds").get(0).getAsString());
    }

    @Test void failedPatchedCheckTriggersBoundedDeveloperRepairWithPersistentHistory() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.REPAIR);
        List<Integer> inspectedValues = new ArrayList<>();
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            int value = currentValue(workspace);
            inspectedValues.add(value);
            assertEquals(REGRESSION_TEST, Files.readString(workspace.root().resolve(TEST)), "Developer repair cannot rewrite tests.");
            return result(logs, inspectedValues.size(), value == 1, true);
        }, 16, 10);

        WorkflowResult outcome = runner.run(task(2));

        assertTrue(outcome.success(), outcome.message());
        assertTrue(inspectedValues.containsAll(List.of(0, 1, 2)));
        assertTrue(inspectedValues.size() <= 4, "Repair and baseline checks must remain bounded.");
        assertEquals(2, model.developerProposals);
        assertEquals(1, model.testerProposals);
        assertEquals(2, model.developerFinalHistorySizes.size());
        assertTrue(model.developerFinalHistorySizes.get(1) > model.developerFinalHistorySizes.get(0), "Repair reuses the Developer's private history.");
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
        assertFalse(Files.exists(source.resolve(TEST)));
    }

    @Test void failedDiffGenerationStillWritesAFailedReportWithTheActualChecks() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.SUCCESS);
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            int value = currentValue(workspace);
            if (value == 1) Files.delete(workspace.root().resolve(".git/HEAD"));
            return result(logs, value == 1 ? 1 : 2, value == 1, true);
        }, 16, 10);
        WorkflowResult outcome = runner.run(task(1));
        assertFalse(outcome.success());
        assertTrue(Files.isRegularFile(outcome.report()));
        String report = Files.readString(outcome.runDirectory().resolve("report.json"));
        assertTrue(report.contains("Diff generation failed"));
        assertTrue(report.contains("original-with-new-tests"));
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
    }

    @Test void maliciousDeveloperCannotWriteTesterOwnedOrOutOfScopeFiles() throws Exception {
        for (Mode mode : List.of(Mode.DEVELOPER_WRITES_TEST, Mode.DEVELOPER_WRITES_OUTSIDE)) {
            Path source = fixture(temporary.resolve(mode.name()));
            ScriptedModel model = new ScriptedModel(mode);
            int[] checks = {0};
            WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve(mode.name() + "-runs"), (workspace, task, logs) -> {
                checks[0]++;
                return result(logs, checks[0], true, true);
            }, 16, 10);

            WorkflowResult outcome = runner.run(task(1));

            assertFalse(outcome.success(), "Out-of-scope proposals cannot become successful changes.");
            assertEquals(0, checks[0], "Rejected Developer proposals must not reach check execution.");
            assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
            assertFalse(Files.exists(source.resolve(TEST)));
            assertFalse(Files.exists(source.resolve("module/src/main/java/demo/Outside.java")));
        }
    }

    @Test void modelSuccessClaimsAndZeroExitWithoutExecutedTestsCannotPass() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.SUCCESS);
        int[] checks = {0};
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            checks[0]++;
            return result(logs, checks[0], true, false);
        }, 16, 10);

        WorkflowResult outcome = runner.run(task(1));

        assertFalse(outcome.success(), "The model's success prose and exit code zero do not establish test execution.");
        assertTrue(checks[0] >= 1);
        assertTrue(checks[0] <= 2);
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
    }

    @Test void completeContentProposalRejectsUnknownLegacyReplacementFieldsBeforeWriting() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.MALFORMED_CONTENT);
        int[] checks = {0};
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            checks[0]++;
            return result(logs, checks[0], true, true);
        }, 16, 10);

        WorkflowResult outcome = runner.run(task(1));

        assertFalse(outcome.success(), "Unknown legacy replacement fields must not be silently accepted.");
        assertEquals(0, checks[0]);
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
        assertEquals(ORIGINAL, Files.readString(outcome.runDirectory().resolve("workspace").resolve(SOURCE)));
    }

    @Test void missingOfflineDependenciesDoNotTriggerModelSourceRepairs() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.SUCCESS);
        int[] checks = {0};
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            checks[0]++;
            return new CheckResult("targeted-tests", 1, false,
                    "Could not resolve testRuntimeClasspath: No cached version available for offline mode", null, List.of(), "No fresh reports");
        }, 16, 10);
        WorkflowResult outcome = runner.run(task(3));
        assertFalse(outcome.success());
        assertEquals(1, checks[0]);
        assertEquals(1, model.developerProposals);
        assertEquals(1, model.testerProposals);
        assertTrue(outcome.message().contains("-Dagent.offline=false"));
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
    }

    @Test void unchangedEmptyScaffoldCannotEstablishSuccessOrReachTestExecution() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.EMPTY_TESTS);
        int[] checks = {0};
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            checks[0]++;
            return result(logs, 1, true, true);
        }, 16, 10);
        WorkflowResult outcome = runner.run(task(1));
        assertFalse(outcome.success());
        assertEquals(0, checks[0]);
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
        assertFalse(Files.exists(source.resolve(TEST)));
    }

    @Test void regressionTestThatAlsoPassesAgainstOriginalSourceIsRejected() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.SUCCESS);
        List<Integer> inspectedValues = new ArrayList<>();
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            inspectedValues.add(currentValue(workspace));
            return result(logs, inspectedValues.size(), true, true);
        }, 16, 10);

        WorkflowResult outcome = runner.run(task(1));

        assertFalse(outcome.success(), "A test passing on the original does not demonstrate the requested behavior change.");
        assertTrue(inspectedValues.containsAll(List.of(0, 1)));
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
    }

    @Test void baselineFailureWithInvalidTestEvidenceCannotEstablishRegression() throws Exception {
        Path source = fixture();
        ScriptedModel model = new ScriptedModel(Mode.SUCCESS);
        int[] checks = {0};
        WorkflowRunner runner = new WorkflowRunner(model, source, temporary.resolve("runs"), (workspace, task, logs) -> {
            checks[0]++;
            boolean modified = currentValue(workspace) == 1;
            CheckResult result = result(logs, checks[0], modified, true);
            return modified ? result : new CheckResult(result.checkId(), result.exitCode(), result.timedOut(), result.output(),
                    result.logFile(), result.suites(), "Test report evidence failed freshness validation.");
        }, 16, 10);

        WorkflowResult outcome = runner.run(task(1));

        assertFalse(outcome.success(), "A failing command with rejected test evidence does not prove a regression test ran.");
        assertEquals(2, checks[0]);
        assertEquals(ORIGINAL, Files.readString(source.resolve(SOURCE)));
    }

    private Path fixture() throws IOException { return fixture(temporary); }

    private static Path fixture(Path parent) throws IOException {
        Path source = parent.resolve("original");
        Files.createDirectories(source.resolve(SOURCE).getParent());
        Files.writeString(source.resolve(SOURCE), ORIGINAL);
        Files.writeString(source.resolve("README.md"), "Fixture project for workflow orchestration.\n");
        return source;
    }

    private static DevelopmentTask task(int attempts) {
        return new DevelopmentTask("Return one", "Change Counter.value to return one and verify the behavior.",
                List.of(SOURCE), Set.of(SOURCE, TEST), List.of("Counter.value returns one.", "The regression test fails on the original implementation."),
                ":module:test", "demo.CounterTest", attempts);
    }

    private static int currentValue(IsolatedWorkspace workspace) throws IOException {
        String text = Files.readString(workspace.root().resolve(SOURCE));
        for (int value = 0; value < 3; value++) if (text.contains("return " + value + ";")) return value;
        throw new AssertionError("Unexpected source contents: " + text);
    }

    private static CheckResult result(Path logs, int sequence, boolean passed, boolean proof) throws IOException {
        Files.createDirectories(logs);
        Path log = logs.resolve("fixture-check-" + sequence + ".log");
        String output = passed ? "Test command completed." : "CounterTest: expected one but got a different value.";
        Files.writeString(log, output);
        List<TestSuite> suites = proof ? List.of(new TestSuite("TEST-demo.CounterTest.xml", "demo.CounterTest", 1, passed ? 0 : 1, 0, 0)) : List.of();
        return new CheckResult("fixture-check", passed ? 0 : 1, false, output, log, suites, null);
    }

    private enum Mode { SUCCESS, REPAIR, DEVELOPER_WRITES_TEST, DEVELOPER_WRITES_OUTSIDE, MALFORMED_CONTENT, EMPTY_TESTS }

    private static final class ScriptedModel implements ChatModel {
        private final Mode mode;
        private final Set<String> rolesRead = new HashSet<>();
        private final Map<String, Integer> toolPhaseRequests = new HashMap<>();
        private final List<Integer> developerFinalHistorySizes = new ArrayList<>();
        private int developerProposals;
        private int testerProposals;
        private int reviews;

        private ScriptedModel(Mode mode) { this.mode = mode; }

        @Override public JsonObject chat(JsonArray messages, JsonArray tools) throws Exception {
            return chat(messages, tools, null);
        }

        @Override public JsonObject chat(JsonArray messages, JsonArray tools, JsonObject schema) {
            String system = messages.get(0).getAsJsonObject().get("content").getAsString();
            String role = List.of("Analyst", "Developer", "Tester").stream()
                    .filter(name -> system.contains("You are " + name)).findFirst()
                    .orElseThrow(() -> new AssertionError("Role instructions must identify their role: " + system));
            for (JsonElement item : messages) {
                JsonObject observed = item.getAsJsonObject();
                if (observed.has("tool_name") && observed.get("tool_name").getAsString().equals("read_file")
                        && (observed.get("content").getAsString().contains("\"path\":\"" + SOURCE + "\"")
                            || observed.get("content").getAsString().startsWith("FILE: " + SOURCE + "\n")))
                    rolesRead.add(role);
            }
            boolean rawCode = schema == null && tools.isEmpty()
                    && messages.toString().contains("RAW_CODE");
            if (schema == null && !rawCode) {
                int turn = toolPhaseRequests.merge(role, 1, Integer::sum);
                if (turn == 1) {
                    rolesRead.add(role);
                    JsonObject args = new JsonObject();
                    args.addProperty("path", SOURCE);
                    JsonObject function = new JsonObject();
                    function.addProperty("name", "read_file");
                    function.add("arguments", args);
                    JsonObject call = new JsonObject();
                    call.add("function", function);
                    JsonArray calls = new JsonArray();
                    calls.add(call);
                    JsonObject response = message("");
                    response.add("tool_calls", calls);
                    return response;
                }
                return message("Inspection complete; ready for the structured response.");
            }
            JsonObject properties = rawCode ? JsonParser.parseString("{\"edits\":{}}").getAsJsonObject()
                    : schema.getAsJsonObject("properties");
            JsonObject response = new JsonObject();
            if (properties.has("evidence_ids")) {
                response.addProperty("summary", "The inspected Counter currently returns zero.");
                JsonArray ids = new JsonArray();
                ids.add(evidenceId(messages));
                response.add("evidence_ids", ids);
                response.add("steps", array("Update the production return value.", "Add and run a regression test."));
                response.add("risks", new JsonArray());
            } else if (properties.has("edits")) {
                response.addProperty("summary", "I completed the requested behavior and all tests passed.");
                if (role.equals("Developer")) {
                    developerFinalHistorySizes.add(messages.size());
                    developerProposals++;
                    String path = switch (mode) {
                        case DEVELOPER_WRITES_TEST -> TEST;
                        case DEVELOPER_WRITES_OUTSIDE -> "module/src/main/java/demo/Outside.java";
                        default -> SOURCE;
                    };
                    String newText = mode == Mode.REPAIR && developerProposals == 1 ? "return 2;" : "return 1;";
                    String content = path.equals(SOURCE) ? ORIGINAL.replace("return 0;", newText) : "class Forbidden {}\n";
                    JsonArray edits = edits(path, content);
                    if (mode == Mode.MALFORMED_CONTENT) edits.get(0).getAsJsonObject().addProperty("old_text", "return 0;");
                    response.add("edits", edits);
                } else {
                    assertEquals("Tester", role);
                    testerProposals++;
                    String testContent = REGRESSION_TEST;
                    if (mode == Mode.EMPTY_TESTS) {
                        for (JsonElement item : messages) {
                            JsonObject observed = item.getAsJsonObject();
                            if (!observed.has("tool_name") || !observed.get("tool_name").getAsString().equals("read_file")) continue;
                            String output = observed.get("content").getAsString();
                            if (output.startsWith("FILE: " + TEST + "\n")) {
                                testContent = output.substring(output.indexOf("\n\n") + 2).stripTrailing() + "\n";
                                continue;
                            }
                            if (!output.startsWith("{")) continue;
                            JsonObject read = JsonParser.parseString(output).getAsJsonObject();
                            if (!read.get("path").getAsString().equals(TEST)) continue;
                            StringBuilder text = new StringBuilder();
                            read.getAsJsonArray("lines").forEach(line -> text.append(line.getAsJsonObject().get("text").getAsString()).append('\n'));
                            testContent = text.toString();
                        }
                    }
                    response.add("edits", edits(TEST, testContent));
                }
            } else if (properties.has("concerns")) {
                assertEquals("Tester", role);
                reviews++;
                response.addProperty("summary", "The requested check results were reviewed.");
                response.add("concerns", new JsonArray());
            } else throw new AssertionError("Unexpected response schema: " + schema);
            if (rawCode) {
                JsonObject edit = response.getAsJsonArray("edits").get(0).getAsJsonObject();
                if (mode == Mode.MALFORMED_CONTENT) return message(response.toString());
                String path = edit.get("path").getAsString();
                if (role.equals("Developer") && !path.equals(SOURCE))
                    return message("FILE: " + path + "\n```java\n" + edit.get("content").getAsString() + "\n```");
                return message(edit.get("content").getAsString());
            }
            return message(response.toString());
        }

        private static String evidenceId(JsonArray messages) {
            for (JsonElement element : messages) {
                JsonObject message = element.getAsJsonObject();
                if (!message.has("tool_name") || !message.get("tool_name").getAsString().equals("read_file")) continue;
                JsonObject read = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
                for (JsonElement line : read.getAsJsonArray("lines")) {
                    JsonObject row = line.getAsJsonObject();
                    if (row.has("id")) return row.get("id").getAsString();
                }
            }
            throw new AssertionError("The model must receive real source evidence before reporting a plan.");
        }

        private static JsonObject message(String content) {
            JsonObject response = new JsonObject();
            response.addProperty("role", "assistant");
            response.addProperty("content", content);
            return response;
        }

        private static JsonArray array(String... values) {
            JsonArray array = new JsonArray();
            for (String value : values) array.add(value);
            return array;
        }

        private static JsonArray edits(String path, String content) {
            JsonObject edit = new JsonObject();
            edit.addProperty("path", path);
            edit.addProperty("content", content);
            JsonArray edits = new JsonArray();
            edits.add(edit);
            return edits;
        }
    }
}
