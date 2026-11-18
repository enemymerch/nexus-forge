package com.mcan.nexusforge.workflow;

import com.mcan.nexusforge.config.DevelopmentTask;
import com.mcan.nexusforge.verification.*;
import com.mcan.nexusforge.workspace.run.IsolatedWorkspace;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Fixed Node/npm commands; neither the model nor task text supplies shell commands. */
public final class FrontendChecks {
    private FrontendChecks() {}
    public static Path node() throws Exception {
        String configured = System.getProperty("agent.nodeExecutable");
        if (configured != null) return Path.of(configured).toRealPath();
        Path tools = Path.of(".local-tools").toAbsolutePath();
        try (var files = Files.walk(tools, 3)) {
            return files.filter(path -> path.getFileName().toString().equals("node.exe"))
                    .sorted(Comparator.reverseOrder()).findFirst().orElseThrow(() -> new IllegalStateException("Set agent.nodeExecutable to Node >=22.12."));
        }
    }
    public static void install(Path root, Path logs, boolean locked) throws Exception {
        Path node = node();
        Path npm = node.getParent().resolve("node_modules/npm/bin/npm-cli.js");
        var definition = CheckDefinition.compile("frontend-dependencies", List.of(node.toString(), npm.toString(),
                locked ? "ci" : "install", "--ignore-scripts", "--no-audit", "--no-fund"), "frontend");
        var result = new VerificationService(root, List.of(definition), logs, Duration.ofMinutes(5), 4000).run(definition.id());
        if (!result.passed()) throw new IllegalStateException("Frontend dependencies unavailable: " + result.output());
    }
    public static CheckResult run(IsolatedWorkspace copy, DevelopmentTask task, Path logs) throws Exception {
        install(copy.root(), logs, true);
        var definition = CheckDefinition.tests("targeted-tests", List.of(node().toString(), "tools/verify.mjs",
                task.testFilter().substring("frontend/".length())), "frontend", List.of("frontend/build/test-results"), null);
        return new VerificationService(copy.root(), List.of(definition), logs, Duration.ofMinutes(4), 5000).run(definition.id());
    }
}
