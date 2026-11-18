package com.mcan.nexusforge;

import com.mcan.nexusforge.agent.AgentRunner;
import com.mcan.nexusforge.config.DevelopmentTask;
import com.mcan.nexusforge.llm.ollama.OllamaClient;
import com.mcan.nexusforge.mcp.McpTools;
import com.mcan.nexusforge.verification.VerificationService;
import com.mcan.nexusforge.workspace.Workspace;
import com.mcan.nexusforge.workflow.GradleChecks;
import com.mcan.nexusforge.workflow.FrontendWorkflow;
import com.mcan.nexusforge.workflow.DirectivePlanner;
import com.mcan.nexusforge.workflow.WorkflowResult;
import com.mcan.nexusforge.workflow.WorkflowRunner;

import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public final class AgentApplication {
    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try {
            Path root = Path.of(setting("agent.workspace", "C:/Users/pc/Desktop/workspace/pim", 4096));
            String taskFile = setting("agent.taskFile", null, 4096);
            String mode = System.getProperty("agent.mode", taskFile == null ? "inspect" : "develop");
            if (!List.of("inspect", "develop", "interactive").contains(mode)) throw new IllegalArgumentException("agent.mode must be inspect, develop or interactive.");
            if (mode.equals("interactive") && taskFile != null) throw new IllegalArgumentException("Interactive mode does not accept a task file.");
            String model = System.getProperty("agent.model", "qwen3:8b");
            URI endpoint = URI.create(System.getProperty("agent.endpoint", "http://localhost:11434/api/chat"));
            boolean develop = !mode.equals("inspect");
            int maxSteps = Integer.getInteger("agent.maxSteps", develop ? 16 : 8);
            int maxToolCalls = Integer.getInteger("agent.maxToolCalls", develop ? 10 : 6);
            int context = Integer.getInteger("agent.contextTokens", develop ? 6144 : 4096);
            int predict = Integer.getInteger("agent.predictionTokens", mode.equals("interactive") ? 3072 : develop ? 2048 : 1024);

            try (OllamaClient client = new OllamaClient(model, endpoint, context, predict)) {
                System.out.println("[Runtime] Model: LangChain4j Ollama; tools: local read-only MCP over stdio.");
                if (develop) {
                    if (taskFile == null && !mode.equals("interactive")) throw new IllegalArgumentException("Develop mode requires agent.taskFile or run.ps1 -TaskFile.");
                    Path source = repositoryRoot(root);
                    Path runs = Path.of(System.getProperty("agent.runsDirectory", ".agent-runs")).toAbsolutePath().normalize();
                    DevelopmentTask task;
                    if (mode.equals("interactive")) {
                        String directive = setting("agent.directive", null, 3000);
                        if (directive == null) {
                            System.out.println("[Analyst] What should we develop? Enter one directive (maximum 3000 characters):");
                            var reader = new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8);
                            StringBuilder input = new StringBuilder();
                            int value;
                            while ((value = reader.read()) != -1 && value != '\n') {
                                if (input.length() >= 3000) throw new IllegalArgumentException("Directive exceeds the input limit.");
                                input.append((char) value);
                            }
                            directive = input.toString().strip();
                        }
                        if (runs.startsWith(source) || source.startsWith(runs)) throw new IllegalArgumentException("Run artifacts must be outside the source repository.");
                        Path ancestor = runs;
                        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
                        if (ancestor.toRealPath().startsWith(source)) throw new IllegalArgumentException("Run artifacts resolve inside the source repository.");
                        Files.createDirectories(runs);
                        Path planning = Files.createTempDirectory(runs, "directive-");
                        Files.writeString(planning.resolve("directive.txt"), directive, StandardCharsets.UTF_8);
                        task = DirectivePlanner.plan(client, source, directive, planning);
                        System.out.println("[Analyst] Handing the validated task to Developer and Tester. Plan: " + planning);
                    } else task = DevelopmentTask.load(Path.of(taskFile));
                    if (task.frontend()) {
                        WorkflowResult result = FrontendWorkflow.run(client, source, runs, task, maxSteps, maxToolCalls);
                        if (!result.success()) System.exit(1);
                        return;
                    }
                    Path gradle = GradleChecks.discover();
                    int seconds = Integer.getInteger("agent.checkTimeoutSeconds", 180);
                    if (seconds < 5 || seconds > 600) throw new IllegalArgumentException("Check timeout must be 5..600 seconds.");
                    System.out.println("[Workflow] Task: " + task.name() + "; source: " + source + "; model: " + model);
                    WorkflowRunner workflow = new WorkflowRunner(client, source, runs,
                            (copy, request, logs) -> new VerificationService(copy.root(), List.of(GradleChecks.forTask(gradle, request)),
                                    logs, Duration.ofSeconds(seconds), 4000).run("targeted-tests"), maxSteps, maxToolCalls);
                    WorkflowResult result = workflow.run(task);
                    if (!result.success()) System.exit(1);
                } else {
                    Workspace workspace = new Workspace(root);
                    System.out.println("[Analyst] Workspace: " + workspace.root() + "; model: " + model + "; endpoint: " + endpoint);
                    try (var tools = McpTools.connect(workspace.root())) {
                        new AgentRunner(client, tools, maxSteps, maxToolCalls,
                                setting("agent.task", null, 3000)).run();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Analyst] ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String setting(String name, String fallback, int limit) throws Exception {
        String encoded = System.getProperty(name + "Encoded");
        String value = System.getProperty(name, fallback);
        if (encoded != null) {
            if (encoded.length() > limit * 8) throw new IllegalArgumentException("Setting is too large: " + name);
            byte[] bytes = Base64.getDecoder().decode(encoded);
            value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
        if (value != null && (value.isBlank() || value.length() > limit)) throw new IllegalArgumentException("Invalid setting: " + name);
        return value;
    }

    private static Path repositoryRoot(Path configured) throws Exception {
        Path root = configured.toRealPath();
        if (Files.isRegularFile(root.resolve("build.gradle.kts")) || Files.isRegularFile(root.resolve("build.gradle"))) return root;
        try (var children = Files.list(root)) {
            List<Path> projects = children.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("build.gradle.kts")) || Files.isRegularFile(path.resolve("build.gradle"))).toList();
            if (projects.size() == 1) return projects.getFirst().toRealPath();
        }
        throw new IllegalArgumentException("Set agent.workspace to one Gradle repository root.");
    }
}
