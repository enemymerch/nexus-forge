package com.mcan.nexusforge.workflow;

import com.mcan.nexusforge.config.DevelopmentTask;
import com.mcan.nexusforge.verification.CheckDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The model cannot supply command text or additional Gradle flags. */
public final class GradleChecks {
    private GradleChecks() {}

    public static Path discover() throws IOException {
        String configured = System.getProperty("agent.gradleExecutable");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toRealPath();
        Path distributions = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists", "gradle-8.8-bin");
        if (Files.isDirectory(distributions)) {
            try (var files = Files.walk(distributions, 4)) {
                return files.filter(path -> path.getFileName().toString().equals(isWindows() ? "gradle.bat" : "gradle"))
                        .sorted(Comparator.comparing(Path::toString)).findFirst()
                        .orElseThrow(() -> new IOException("Installed Gradle 8.8 was not found; set agent.gradleExecutable."));
            }
        }
        throw new IOException("Set agent.gradleExecutable to an installed Gradle executable outside the workspace.");
    }

    public static CheckDefinition forTask(Path executable, DevelopmentTask task) {
        List<String> command = new ArrayList<>(List.of(executable.toAbsolutePath().toString(), task.gradleTask(),
                "--tests", task.testFilter(), "--no-daemon", "--console=plain", "--max-workers=2",
                "--rerun-tasks", "-Pkotlin.compiler.execution.strategy=in-process",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Duser.language=en", "-Duser.country=US"));
        if (Boolean.parseBoolean(System.getProperty("agent.offline", "true"))) command.add("--offline");
        String module = task.gradleTask().substring(1, task.gradleTask().lastIndexOf(':')).replace(':', '/');
        return CheckDefinition.tests("targeted-tests", command, ".", List.of(module + "/build/test-results/test"), task.testFilter());
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"); }
}
