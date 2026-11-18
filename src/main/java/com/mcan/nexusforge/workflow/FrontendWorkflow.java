package com.mcan.nexusforge.workflow;

import com.google.gson.*;
import com.mcan.nexusforge.config.DevelopmentTask;
import com.mcan.nexusforge.llm.ChatModel;
import com.mcan.nexusforge.workspace.run.IsolatedWorkspace;
import java.nio.file.*;
import java.util.*;

/** Trusted bootstrap plus the same Analyst/Developer/Tester and RED/GREEN checks used for backend work. */
public final class FrontendWorkflow {
    private static final List<String> FILES = List.of("package.json", "index.html", "tsconfig.json", "vite.config.ts", "README.md", "API-CONTRACT.md",
            "src/main.tsx", "src/App.tsx", "src/styles.css", "src/features/categories/api.ts", "src/features/categories/api.test.ts", "src/features/categories/CategoryPage.tsx",
            "src/features/categories/CategoryPage.test.tsx", "src/test/setup.ts", "src/test/render.tsx", "tools/verify.mjs");
    private FrontendWorkflow() {}
    public static WorkflowResult run(ChatModel model, Path source, Path runs, DevelopmentTask task, int steps, int calls) throws Exception {
        if (!task.frontend()) throw new IllegalArgumentException("A frontend task is required.");
        if (Files.isDirectory(source.resolve("frontend")))
            return new WorkflowRunner(model, source, runs, FrontendChecks::run, steps, calls).run(task);
        var seed = IsolatedWorkspace.create(source, runs);
        Path frontend = seed.root().resolve("frontend");
        for (String relative : FILES) {
            Path destination = frontend.resolve(relative);
            Files.createDirectories(destination.getParent());
            try (var input = FrontendWorkflow.class.getResourceAsStream("/frontend-template/" + relative)) {
                if (input == null) throw new IllegalStateException("Missing trusted frontend template: " + relative);
                Files.copy(input, destination);
            }
        }
        Files.writeString(frontend.resolve(".gitignore"), "node_modules/\ndist/\nbuild/\n.vitest/\n");
        System.out.println("[Frontend] Trusted React/Vite skeleton: " + frontend);
        FrontendChecks.install(seed.root(), seed.runDirectory().resolve("dependencies"), false);
        WorkflowResult result = new WorkflowRunner(model, seed.root(), seed.runDirectory().resolve("agent-workflow"),
                FrontendChecks::run, steps, calls).run(task);
        // Retain a runnable, dependency-installed application in the outer workspace and a full patch against PIM.
        if (result.success()) for (String relative : task.writeFiles())
            Files.copy(result.runDirectory().resolve("workspace").resolve(relative), seed.root().resolve(relative), StandardCopyOption.REPLACE_EXISTING);
        seed.writeDiff(seed.runDirectory().resolve("changes.diff"));
        boolean preserved = seed.originalUnchanged();
        JsonObject report = new JsonObject();
        report.addProperty("status", result.success() && preserved ? "SUCCESS" : "FAILED");
        report.addProperty("source", source.toString()); report.addProperty("workspace", seed.root().toString());
        report.addProperty("original_unchanged", preserved); report.addProperty("workflow_report", result.report().toString());
        report.addProperty("limitations", "Only category lookup/create/update UI is connected. Other services remain planned. API calls were mocked in UI tests; live backend/database and authentication were not verified.");
        Files.writeString(seed.runDirectory().resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        Path summary = seed.runDirectory().resolve("report.md");
        Files.writeString(summary, "# Frontend run: " + report.get("status").getAsString() + "\n\nOriginal PIM unchanged: " + preserved
                + "\n\nRunnable app: " + frontend + "\n\nDetailed workflow: " + result.report() + "\n\n" + report.get("limitations").getAsString());
        System.out.println("[Frontend] " + report.get("status").getAsString() + "; app: " + frontend + "; report: " + summary);
        return new WorkflowResult(result.success() && preserved, seed.runDirectory(), summary, seed.runDirectory().resolve("changes.diff"), result.message());
    }
}
