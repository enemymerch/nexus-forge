package com.mcan.nexusforge.verification;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/** Uses bounded actual check logs to route source repairs without mistaking completed tasks for failures. */
public final class CheckDiagnostics {
    private static final int MAX_LOG_BYTES = 64_000;
    private static final Pattern FAILED_TEST_COMPILE = Pattern.compile(
            "(?m)^[\\t ]*> Task[\\t ]+\\S*:(?:compileTestKotlin|compileTestJava)[\\t ]+FAILED(?:[\\t ]|$)"
                    + "|^[\\t ]*Execution failed for task [^\\r\\n]*:(?:compileTestKotlin|compileTestJava)(?:['\".\\s]|$)");

    private CheckDiagnostics() {}

    public static String actualOutput(CheckResult result) {
        if (result.logFile() != null) {
            try (var input = Files.newInputStream(result.logFile())) {
                return new String(input.readNBytes(MAX_LOG_BYTES), StandardCharsets.UTF_8);
            } catch (Exception ignored) { /* The captured process output remains actual evidence. */ }
        }
        return result.output() == null ? "" : result.output();
    }

    public static boolean isTestSourceFailure(CheckResult result) {
        if (result.tests() != 0) return false;
        String output = actualOutput(result);
        return FAILED_TEST_COMPILE.matcher(output).find() || output.contains("No tests found for given includes");
    }
}
