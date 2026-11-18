package com.mcan.nexusforge.workflow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcan.nexusforge.agent.RoleSession;
import com.mcan.nexusforge.agent.AnalystAgent;
import com.mcan.nexusforge.agent.DeveloperAgent;
import com.mcan.nexusforge.agent.TesterAgent;
import com.mcan.nexusforge.tool.ToolRegistry;
import java.util.function.Function;
import com.mcan.nexusforge.config.DevelopmentTask;
import com.mcan.nexusforge.llm.ChatModel;
import com.mcan.nexusforge.mcp.McpTools;
import com.mcan.nexusforge.verification.CheckResult;
import com.mcan.nexusforge.verification.CheckDiagnostics;
import com.mcan.nexusforge.workspace.run.IsolatedWorkspace;
import com.mcan.nexusforge.workspace.run.PatchApplier;
import com.mcan.nexusforge.workspace.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Sequential roles propose changes; Java owns write scope, command execution and success criteria. */
public final class WorkflowRunner {
    @FunctionalInterface public interface CheckRunner {
        CheckResult run(IsolatedWorkspace workspace, DevelopmentTask task, Path logs) throws Exception;
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int PATCH_BUDGET = 24_000;
    private final ChatModel model;
    private final Path source;
    private final Path runsDirectory;
    private final CheckRunner checks;
    private final int maxSteps;
    private final int maxCalls;

    public WorkflowRunner(ChatModel model, Path source, Path runsDirectory, CheckRunner checks, int maxSteps, int maxCalls) {
        this.model = model;
        this.source = source;
        this.runsDirectory = runsDirectory;
        this.checks = checks;
        this.maxSteps = maxSteps;
        this.maxCalls = maxCalls;
    }

    public WorkflowResult run(DevelopmentTask task) throws Exception {
        Set<String> production = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        task.writeFiles().forEach(path -> (path.startsWith("src/test/") || path.contains("/src/test/") || (task.frontend() && path.matches(".*\\.test\\.tsx?")) ? tests : production).add(path));
        if (production.isEmpty() || tests.isEmpty()) throw new IllegalArgumentException("A development task must approve production and test source files separately.");

        IsolatedWorkspace copy = IsolatedWorkspace.create(source, runsDirectory);
        Path artifacts = copy.runDirectory();
        Path diff = artifacts.resolve("changes.diff");
        Path reportFile = artifacts.resolve("report.md");
        JsonObject report = new JsonObject();
        report.add("task", taskJson(task));
        report.addProperty("source", copy.source().toString());
        report.addProperty("workspace", copy.root().toString());
        report.addProperty("status", "FAILED");
        report.addProperty("success_basis", "Actual fresh test reports, baseline regression evidence and unchanged original source. Model statements are not verification evidence.");
        report.add("checks", new JsonArray());
        List<RoleSession> roles = new ArrayList<>();
        String message = "Workflow did not complete.";
        boolean success = false;
        int attempts = 0;
        write(artifacts.resolve("task.json"), taskJson(task));
        System.out.println("[Workflow] Isolated workspace: " + copy.root());

        try {
            Set<String> readable = new LinkedHashSet<>(task.readFiles());
            readable.addAll(task.writeFiles());
            AnalystAgent analyst = openAgent(copy, readable,
                    tools -> new AnalystAgent(model, tools, maxSteps, maxCalls));
            roles.add(analyst);
            JsonObject plan = analyst.request(taskContext(task) + "\nRead each path in read_files directly with read_file. "
                    + "The file paths are already known; directory exploration is unnecessary. Then produce a concise English plan.",
                    WorkflowSchemas.plan(), existing(copy, new LinkedHashSet<>(task.readFiles())));
            validatePlan(plan, analyst);
            write(artifacts.resolve("analysis.json"), plan);
            writeText(artifacts.resolve("analysis-evidence.txt"), analyst.evidence().audit());

            DeveloperAgent developer = openAgent(copy, readable,
                    tools -> new DeveloperAgent(model, tools, copy.runDirectory(), maxSteps, maxCalls, task.frontend()));
            roles.add(developer);
            PatchApplier productionWriter = new PatchApplier(copy, production, production.size(), PATCH_BUDGET);
            String devContext = taskContext(task) + "\nApproved production edits: " + production
                    + "\nAnalyst plan (unverified model proposal): " + plan;
            Set<String> developerReads = new LinkedHashSet<>(production);
            if (task.frontend()) task.readFiles().stream().filter(path -> path.startsWith("frontend/")).forEach(developerReads::add);
            applyProposal(developer, copy, devContext + "\nRead the production file directly and implement the change. Do not explore test directories; Tester owns those.",
                    production, developerReads, productionWriter, artifacts, "developer-initial");

            TesterAgent tester = openAgent(copy, readable,
                    tools -> new TesterAgent(model, tools, copy.runDirectory(), maxSteps, maxCalls, task.frontend()));
            roles.add(tester);
            PatchApplier testWriter = new PatchApplier(copy, tests, tests.size(), PATCH_BUDGET);
            List<String> scaffolds = task.frontend() ? List.of() : seedTestScaffold(copy, tests, task.testFilter(), testWriter, analyst.historySnapshot());
            report.add("test_scaffolds", JSON.toJsonTree(scaffolds));
            Set<String> testReads = new LinkedHashSet<>(task.readFiles());
            if (task.frontend()) {
                testReads.removeIf(path -> !path.startsWith("frontend/"));
                testReads.addAll(production);
            }
            testReads.addAll(tests);
            String testContext = taskContext(task) + "\nApproved test edits: " + tests;
            applyProposal(tester, copy, testContext + "\nRead the changed production source and the listed build files directly, then create independent regression tests. "
                    + "Read the current approved test file too; Java may have supplied an empty JUnit class. Fill that class with concise tests covering every criterion.",
                    tests, testReads, testWriter, artifacts, "tester-tests");

            CheckResult green = null;
            CheckResult red = null;
            for (int attempt = 1; attempt <= task.maxAttempts(); attempt++) {
                attempts = attempt;
                enforceScope(copy, task.writeFiles());
                System.out.println("[Tester] Running actual targeted tests, attempt " + attempt + "/" + task.maxAttempts());
                green = checks.run(copy, task, artifacts.resolve("checks/attempt-" + attempt));
                recordCheck(report, "modified", attempt, green);
                System.out.println("[Tester] exit=" + green.exitCode() + ", tests=" + green.tests() + ", failures=" + green.failures()
                        + ", errors=" + green.errors() + ", evidence=" + (green.evidenceError() == null ? "accepted" : green.evidenceError()));
                rejectEnvironmentFailure(green);

                if (green(green)) {
                    // An independent control: only Tester changes are copied onto the unmodified source snapshot.
                    IsolatedWorkspace baseline = IsolatedWorkspace.create(copy.source(), artifacts.resolve("baseline"));
                    if (!copy.originalUnchanged()) throw new IllegalStateException("Original source changed during execution; review the saved patch manually.");
                    JsonArray baselineTests = copyTests(copy, baseline, tests);
                    new PatchApplier(baseline, tests, tests.size(), PATCH_BUDGET).apply(baselineTests);
                    System.out.println("[Tester] Checking that the same tests fail against the original source.");
                    red = checks.run(baseline, task, baseline.runDirectory().resolve("checks"));
                    recordCheck(report, "original-with-new-tests", attempt, red);
                    rejectEnvironmentFailure(red);
                    if (red.evidenceError() == null && red.tests() > red.skipped() && red.failures() + red.errors() > 0 && !red.timedOut() && red.exitCode() != 0) {
                        report.addProperty("regression_proven", true);
                        break;
                    }
                    if (attempt == task.maxAttempts()) break;
                    String feedback = "The modified source passed, but the same tests did not demonstrate an executed failure on the original source. "
                            + "Strengthen only the tests for the requested missing behavior. Original check: " + checkJson(red);
                    applyProposal(tester, copy, behavior(task) + "\n" + feedback,
                            tests, tests, testWriter, artifacts, "tester-strengthen-" + attempt);
                } else {
                    if (attempt == task.maxAttempts()) break;
                    String feedback = repairFeedback(green);
                    boolean testCompileError = CheckDiagnostics.isTestSourceFailure(green) || (task.frontend() && green.tests() == 0
                            && CheckDiagnostics.actualOutput(green).contains(".test.tsx("));
                    if (testCompileError) {
                        applyProposal(tester, copy, behavior(task) + "\n" + feedback,
                                tests, tests, testWriter, artifacts, "tester-repair-" + attempt);
                    } else {
                        applyProposal(developer, copy, behavior(task) + "\nTests are independent and immutable to Developer.\n" + feedback,
                                production, production, productionWriter, artifacts, "developer-repair-" + attempt);
                    }
                }
            }

            success = green != null && green(green) && report.has("regression_proven") && report.get("regression_proven").getAsBoolean();
            if (!success) throw new IllegalStateException("No verified GREEN modified source / RED original source result was obtained within the attempt budget.");
            JsonObject review = tester.requestFinal("Review the actual execution evidence. Do not run or claim additional checks.\n"
                    + "Modified source: " + checkSummary(green) + "\nOriginal source with the same tests: " + checkSummary(red)
                    + "\nReturn the concise review JSON; list unverified behavior in concerns.", WorkflowSchemas.review());
            if (!review.keySet().equals(Set.of("summary", "concerns"))) throw new IllegalArgumentException("Invalid Tester review shape.");
            text(review, "summary", 800);
            strings(review.getAsJsonArray("concerns"), 0, 5, 300);
            write(artifacts.resolve("tester-review.json"), review);
            report.add("tester_review", review);
            enforceScope(copy, task.writeFiles());
            if (!copy.changedFiles().stream().anyMatch(production::contains) || !copy.changedFiles().stream().anyMatch(tests::contains))
                throw new IllegalStateException("Both an approved production change and independent test change are required.");
            message = "Verified targeted tests passed on the modified copy and failed on the original source with the same tests.";
        } catch (Exception failure) {
            success = false;
            message = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            System.err.println("[Workflow] ERROR: " + message);
        } finally {
            JsonArray finalizationErrors = new JsonArray();
            boolean unchanged = false;
            try { unchanged = copy.originalUnchanged(); }
            catch (Exception e) { finalizationErrors.add("Original source verification failed: " + e.getMessage()); }
            if (!unchanged) { success = false; message += " Original source preservation could not be confirmed; no automatic merge was performed."; }
            try { copy.writeDiff(diff); report.addProperty("diff_available", true); }
            catch (Exception e) { finalizationErrors.add("Diff generation failed: " + e.getMessage()); report.addProperty("diff_available", false); }
            report.addProperty("attempts", attempts);
            report.addProperty("original_unchanged", unchanged);
            try { report.add("changed_files", JSON.toJsonTree(copy.changedFiles())); }
            catch (Exception e) { report.add("changed_files", new JsonArray()); finalizationErrors.add("Changed-file inspection failed: " + e.getMessage()); }
            report.addProperty("diff", diff.toString());
            report.addProperty("limitations", "Only the configured filtered tests were executed. Broader builds, services and deployment were not verified. Agent analysis/review text remains model interpretation.");
            JsonArray statistics = new JsonArray();
            for (int i = 0; i < roles.size(); i++) {
                RoleSession session = roles.get(i);
                String name = List.of("analyst", "developer", "tester").get(i);
                try { write(artifacts.resolve(name + "-history.json"), session.historySnapshot()); }
                catch (Exception e) { finalizationErrors.add("History save failed for " + name + ": " + e.getMessage()); }
                JsonObject stats = new JsonObject(); stats.addProperty("role", name); stats.addProperty("model_steps", session.steps()); stats.addProperty("tool_calls", session.toolCalls());
                statistics.add(stats);
                try { session.close(); }
                catch (Exception e) { finalizationErrors.add("MCP connection close failed for " + name + ": " + e.getMessage()); }
            }
            if (!finalizationErrors.isEmpty()) { success = false; message += " Finalization incomplete: " + finalizationErrors; }
            report.add("finalization_errors", finalizationErrors);
            report.addProperty("status", success ? "SUCCESS" : "FAILED");
            report.addProperty("message", message);
            report.add("agents", statistics);
            write(artifacts.resolve("report.json"), report);
            writeText(reportFile, markdown(report));
            System.out.println("[Workflow] " + report.get("status").getAsString() + "; report: " + reportFile + "; patch: " + diff);
        }
        return new WorkflowResult(success, artifacts, reportFile, diff, message);
    }

    private static List<String> seedTestScaffold(IsolatedWorkspace copy, Set<String> tests, String testFilter,
                                                PatchApplier writer, JsonArray observations) throws Exception {
        int separator = testFilter.lastIndexOf('.');
        String packageName = testFilter.substring(0, separator);
        String className = testFilter.substring(separator + 1);
        List<String> created = new ArrayList<>();
        Set<String> sourceImports = new LinkedHashSet<>();
        for (JsonElement observation : observations) {
            JsonObject message = observation.getAsJsonObject();
            if (!message.has("tool_name") || !message.get("tool_name").getAsString().equals("read_file")) continue;
            String payload = message.get("content").getAsString();
            if (!payload.startsWith("{")) continue;
            JsonObject read = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            String path = read.get("path").getAsString();
            if (!path.contains("/src/main/") || !(path.endsWith(".kt") || path.endsWith(".java"))) continue;
            for (JsonElement row : read.getAsJsonArray("lines")) {
                String line = row.getAsJsonObject().get("text").getAsString().trim();
                if (line.matches("import [A-Za-z_$][A-Za-z0-9_$.]*;?"))
                    sourceImports.add(line.substring(7).replace(";", ""));
            }
        }
        for (String path : tests) {
            String filename = Path.of(path).getFileName().toString();
            if (Files.exists(copy.root().resolve(path))
                    || !(filename.equals(className + ".kt") || filename.equals(className + ".java"))) continue;
            String content = filename.endsWith(".kt")
                    ? "package " + packageName + "\n\nimport org.junit.jupiter.api.Test\n"
                      + "import org.junit.jupiter.api.Assertions.assertEquals\nimport org.junit.jupiter.api.Assertions.assertThrows\n\n"
                      + "class " + className + " {\n    // Add regression tests and any private helper inside this class.\n}\n"
                    : "package " + packageName + ";\n\nimport org.junit.jupiter.api.Test;\n"
                      + "import static org.junit.jupiter.api.Assertions.assertEquals;\nimport static org.junit.jupiter.api.Assertions.assertThrows;\n\n"
                      + "class " + className + " {\n    // Add regression tests inside this class.\n}\n";
            StringBuilder dependencies = new StringBuilder();
            for (String dependency : sourceImports) dependencies.append("import ").append(dependency)
                    .append(filename.endsWith(".kt") ? "\n" : ";\n");
            content = content.replace("\nclass " + className, "\n" + dependencies + "\nclass " + className);
            JsonObject edit = new JsonObject();
            edit.addProperty("path", path); edit.addProperty("old_text", ""); edit.addProperty("new_text", content);
            JsonArray edits = new JsonArray(); edits.add(edit);
            writer.apply(edits);
            created.add(path);
            System.out.println("[Tester] Created empty JUnit scaffold: " + path);
        }
        return created;
    }

    /** Transfer each MCP connection to its role; close it if agent construction fails. */
    private <T extends RoleSession> T openAgent(IsolatedWorkspace copy, Set<String> readable,
                                               Function<ToolRegistry, T> create) throws Exception {
        var tools = McpTools.connect(copy.root(), readable);
        try {
            return create.apply(tools);
        } catch (Exception failure) { tools.close(); throw failure; }
    }

    private void applyProposal(RoleSession session, IsolatedWorkspace copy, String prompt, Set<String> allowed, Set<String> inspect, PatchApplier writer,
                               Path artifacts, String name) throws Exception {
        String request = prompt;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Map<String, String> before = sourceSnapshot(copy, allowed);
            String states = fileStates(copy, allowed);
            JsonObject proposal = session.request(request + "\n" + states,
                    WorkflowSchemas.patch(allowed), existing(copy, inspect));
            write(artifacts.resolve(name + "-proposal-" + attempt + ".json"), proposal);
            try {
                if (!proposal.keySet().equals(Set.of("summary", "edits"))) throw new IllegalArgumentException("Patch proposal requires only summary and edits.");
                text(proposal, "summary", 600);
                if (!before.equals(sourceSnapshot(copy, allowed))) throw new IllegalStateException("Edit targets changed while the model was preparing its proposal.");
                List<String> changed = writer.apply(exactEdits(proposal.getAsJsonArray("edits"), before));
                System.out.println("[Workflow] Applied scoped patch: " + changed);
                return;
            } catch (Exception rejected) {
                if (attempt == 2) throw rejected;
                request = "Java rejected the patch before accepting it: " + excerpt(String.valueOf(rejected.getMessage()), 600)
                        + ". Read the CURRENT file if necessary. Return complete file content and fix the path/JSON issue. Approved paths remain " + allowed;
            }
        }
    }

    private static Map<String, String> sourceSnapshot(IsolatedWorkspace copy, Set<String> paths) throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        Workspace guard = new Workspace(copy.root());
        for (String path : paths) {
            if (!Files.exists(copy.root().resolve(path))) { snapshot.put(path, null); continue; }
            byte[] bytes;
            try (var input = Files.newInputStream(guard.resolve(path))) { bytes = input.readNBytes(32_001); }
            String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (bytes.length > 32_000 || content.length() > 8_000)
                throw new IllegalArgumentException("Complete-file workflow supports source files up to 8000 characters: " + path);
            snapshot.put(path, content);
        }
        return snapshot;
    }

    private static JsonArray exactEdits(JsonArray proposals, Map<String, String> before) {
        if (proposals == null || proposals.isEmpty() || proposals.size() > 4) throw new IllegalArgumentException("Expected 1..4 complete-file edits.");
        JsonArray edits = new JsonArray();
        for (JsonElement value : proposals) {
            JsonObject proposed = value.getAsJsonObject();
            if (!proposed.keySet().equals(Set.of("path", "content"))) throw new IllegalArgumentException("Each edit requires only path and complete content.");
            String path = text(proposed, "path", 500);
            if (!before.containsKey(path)) throw new IllegalArgumentException("File is outside the approved edit scope: " + path);
            JsonObject exact = new JsonObject();
            exact.addProperty("path", path);
            exact.addProperty("old_text", before.get(path) == null ? "" : before.get(path));
            exact.addProperty("new_text", text(proposed, "content", 8_000));
            edits.add(exact);
        }
        return edits;
    }

    private static Set<String> existing(IsolatedWorkspace copy, Set<String> paths) {
        Set<String> present = new LinkedHashSet<>();
        paths.stream().filter(path -> Files.isRegularFile(copy.root().resolve(path))).forEach(present::add);
        return present;
    }

    private static String fileStates(IsolatedWorkspace copy, Set<String> paths) {
        StringBuilder states = new StringBuilder("Java-confirmed current edit targets:\n");
        for (String path : paths) states.append(path).append(Files.exists(copy.root().resolve(path))
                ? " EXISTS. Read it; return the COMPLETE updated file as content, preserving unrelated declarations.\n"
                : " IS ABSENT. Return the COMPLETE new file as content, including its package declaration.\n");
        return states.toString();
    }

    private static void validatePlan(JsonObject plan, RoleSession analyst) {
        if (!plan.keySet().equals(Set.of("summary", "evidence_ids", "steps", "risks"))) throw new IllegalArgumentException("Invalid Analyst plan fields.");
        text(plan, "summary", 600);
        strings(plan.getAsJsonArray("steps"), 1, 5, 300);
        strings(plan.getAsJsonArray("risks"), 0, 4, 300);
        List<String> ids = strings(plan.getAsJsonArray("evidence_ids"), 1, 6, 40);
        if (ids.stream().anyMatch(id -> analyst.evidence().resolve(id).isEmpty())) throw new IllegalArgumentException("Analyst plan contains unknown source evidence IDs.");
    }

    private static JsonArray copyTests(IsolatedWorkspace changed, IsolatedWorkspace baseline, Set<String> tests) throws Exception {
        JsonArray edits = new JsonArray();
        for (String path : tests) {
            Path current = changed.root().resolve(path);
            if (!Files.isRegularFile(current)) continue;
            String content = Files.readString(current, StandardCharsets.UTF_8);
            Path previous = baseline.root().resolve(path);
            String old = Files.exists(previous) ? Files.readString(previous, StandardCharsets.UTF_8) : "";
            if (old.equals(content)) continue;
            JsonObject edit = new JsonObject(); edit.addProperty("path", path); edit.addProperty("old_text", old); edit.addProperty("new_text", content); edits.add(edit);
        }
        if (edits.isEmpty()) throw new IllegalArgumentException("Tester did not add or modify any regression test source.");
        return edits;
    }

    private static void enforceScope(IsolatedWorkspace copy, Set<String> allowed) throws Exception {
        for (String path : copy.changedFiles()) if (!allowed.contains(path)) throw new IllegalStateException("Unexpected source change outside task scope: " + path);
    }

    private static boolean green(CheckResult result) {
        return result.passed() && result.tests() > result.skipped() && result.failures() == 0 && result.errors() == 0;
    }

    private static void rejectEnvironmentFailure(CheckResult result) {
        String output = CheckDiagnostics.actualOutput(result).toLowerCase(java.util.Locale.ROOT);
        if (result.timedOut() || (result.tests() == 0 && (output.contains("no cached version available")
                || output.contains("could not resolve") || output.contains("could not download")
                || output.contains("could not find java") || output.contains("java_home is set to an invalid"))))
            throw new IllegalStateException("Check environment/dependencies are unavailable; source repair was not attempted. "
                    + "Review the actual log. For missing offline dependencies, retry with -Dagent.offline=false.");
    }

    private static String repairFeedback(CheckResult result) {
        String output = CheckDiagnostics.actualOutput(result);
        String diagnostics = output.lines().filter(line -> line.startsWith("e: ") || line.contains(" error:")
                || line.contains(" FAILED") || line.contains("No tests found")
                || line.contains("expected:") || line.contains("but was:"))
                .limit(8).reduce("", (left, right) -> left + right + "\n");
        return "Actual check failed: exit=" + result.exitCode() + ", tests=" + result.tests()
                + ", failures=" + result.failures() + ", errors=" + result.errors()
                + ". Fix the cause within your approved source scope.\n"
                + excerpt(diagnostics.isBlank() ? output : diagnostics, 1_200);
    }

    private static String taskContext(DevelopmentTask task) {
        return "User task: " + task.goal() + "\nAcceptance criteria: " + task.acceptanceCriteria()
                + "\nFiles approved for reading: " + task.readFiles() + "\nFiles approved for modification: " + task.writeFiles()
                + "\nVerification selected by Java: " + (task.frontend() ? "TypeScript + Vite build + Vitest " : task.gradleTask() + " --tests ") + task.testFilter();
    }

    private static String behavior(DevelopmentTask task) {
        return "Required behavior remains: " + task.goal() + "\nAcceptance criteria: " + task.acceptanceCriteria();
    }

    private static JsonObject taskJson(DevelopmentTask task) {
        JsonObject value = new JsonObject(); value.addProperty("name", task.name()); value.addProperty("goal", task.goal());
        value.add("read_files", JSON.toJsonTree(task.readFiles())); value.add("write_files", JSON.toJsonTree(task.writeFiles()));
        value.add("acceptance_criteria", JSON.toJsonTree(task.acceptanceCriteria())); value.addProperty("gradle_task", task.gradleTask());
        value.addProperty("project_type", task.frontend() ? "frontend" : "jvm");
        value.addProperty("test_filter", task.testFilter()); value.addProperty("max_attempts", task.maxAttempts()); return value;
    }

    private static JsonObject checkJson(CheckResult result) {
        JsonObject object = new JsonObject(); object.addProperty("check_id", result.checkId()); object.addProperty("passed", green(result));
        object.addProperty("exit_code", result.exitCode()); object.addProperty("timed_out", result.timedOut());
        object.addProperty("tests", result.tests()); object.addProperty("failures", result.failures()); object.addProperty("errors", result.errors()); object.addProperty("skipped", result.skipped());
        object.addProperty("evidence_error", result.evidenceError()); object.addProperty("log_file", result.logFile() == null ? null : result.logFile().toString());
        object.addProperty("output", excerpt(result.output(), 1800)); object.add("suites", JSON.toJsonTree(result.suites())); return object;
    }

    private static JsonObject checkSummary(CheckResult result) {
        JsonObject summary = checkJson(result);
        summary.remove("output");
        return summary;
    }

    private static void recordCheck(JsonObject report, String target, int attempt, CheckResult result) {
        JsonObject check = checkJson(result); check.addProperty("target", target); check.addProperty("attempt", attempt); report.getAsJsonArray("checks").add(check);
    }

    private static String markdown(JsonObject report) {
        StringBuilder out = new StringBuilder("# Development run: ").append(report.get("status").getAsString()).append("\n\n")
                .append(report.get("message").getAsString()).append("\n\n")
                .append("- Source: ").append(report.get("source").getAsString()).append('\n')
                .append("- Isolated workspace: ").append(report.get("workspace").getAsString()).append('\n')
                .append("- Original unchanged: ").append(report.get("original_unchanged")).append('\n')
                .append("- Attempts: ").append(report.get("attempts")).append('\n')
                .append("- Changed files: ").append(report.get("changed_files")).append('\n')
                .append("- Diff: ").append(report.get("diff").getAsString()).append("\n\n## Actual checks\n\n");
        for (JsonElement element : report.getAsJsonArray("checks")) {
            JsonObject check = element.getAsJsonObject();
            out.append("- ").append(check.get("target").getAsString()).append(": exit=").append(check.get("exit_code"))
                    .append(", tests=").append(check.get("tests")).append(", failures=").append(check.get("failures"))
                    .append(", errors=").append(check.get("errors")).append(", skipped=").append(check.get("skipped"))
                    .append("; log=").append(check.get("log_file")).append('\n');
        }
        out.append("\n## Limits\n\n").append(report.get("limitations").getAsString()).append('\n');
        if (report.has("tester_review")) out.append("\n## Tester review (model interpretation)\n\n").append(report.get("tester_review")).append('\n');
        return out.toString();
    }

    private static String text(JsonObject object, String field, int limit) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string field: " + field);
        String text = value.getAsString();
        if (text.isBlank() || text.length() > limit || text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t' && c != '\r')) throw new IllegalArgumentException("Invalid field: " + field);
        return text;
    }

    private static List<String> strings(JsonArray values, int min, int max, int limit) {
        if (values == null || values.size() < min || values.size() > max) throw new IllegalArgumentException("Invalid array size.");
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) { JsonObject wrapper = new JsonObject(); wrapper.add("value", value); result.add(text(wrapper, "value", limit)); }
        return result;
    }

    private static String excerpt(String text, int limit) { return text == null ? "" : text.length() <= limit ? text : text.substring(0, limit / 2) + "\n[TRUNCATED]\n" + text.substring(text.length() - limit / 2); }
    private static void write(Path file, JsonElement value) throws Exception { writeText(file, JSON.toJson(value)); }
    private static void writeText(Path file, String value) throws Exception { Files.writeString(file, value, StandardCharsets.UTF_8); }
}
