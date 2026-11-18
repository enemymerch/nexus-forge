package com.mcan.nexusforge.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckDiagnosticsTest {
    @TempDir Path temporary;

    @Test void actualLogFindsTheFailedTestCompilationOmittedFromTheCapturedExcerpt() throws Exception {
        Path log = temporary.resolve("check.log");
        String full = "Gradle output before diagnostics.\n".repeat(160)
                + "> Task :services:catalog:compileTestKotlin FAILED\n"
                + "e: VariantTest.kt: Missing assertEquals import.\nBUILD FAILED\n";
        Files.writeString(log, full);
        CheckResult result = result("Gradle output before diagnostics.\n[TRUNCATED]\nBUILD FAILED", log, List.of());

        assertFalse(result.output().contains("compileTestKotlin FAILED"));
        assertEquals(full, CheckDiagnostics.actualOutput(result));
        assertTrue(CheckDiagnostics.isTestSourceFailure(result));
    }

    @Test void executionFailureNamesAlsoIdentifyTestCompilerFailures() {
        for (String language : List.of("Kotlin", "Java")) {
            CheckResult result = result("Execution failed for task ':services:catalog:compileTest" + language + "'.\n", null, List.of());
            assertTrue(CheckDiagnostics.isTestSourceFailure(result));
        }
    }

    @Test void completedTestCompilationDoesNotMisrouteRuntimeOrProductionFailures() {
        String compiled = "> Task :services:catalog:compileTestKotlin\n> Task :services:catalog:compileTestJava NO-SOURCE\n";
        CheckResult runtimeWithoutReports = result(compiled + "> Task :services:catalog:test FAILED\nNoClassDefFoundError\n", null, List.of());
        CheckResult runtimeWithReports = result(compiled + "> Task :services:catalog:test FAILED\n", null,
                List.of(new TestSuite("TEST-VariantTest.xml", "VariantTest", 1, 1, 0, 0)));
        CheckResult production = result("> Task :services:catalog:compileKotlin FAILED\n"
                + "Execution failed for task ':services:catalog:compileKotlin'.\n", null, List.of());

        assertFalse(CheckDiagnostics.isTestSourceFailure(runtimeWithoutReports));
        assertFalse(CheckDiagnostics.isTestSourceFailure(runtimeWithReports));
        assertFalse(CheckDiagnostics.isTestSourceFailure(production));
    }

    @Test void executedTestsTakePrecedenceOverAnyCompilationWordsInTheOutput() {
        CheckResult result = result("> Task :another:compileTestKotlin FAILED\n", null,
                List.of(new TestSuite("TEST-VariantTest.xml", "VariantTest", 2, 1, 0, 0)));
        assertFalse(CheckDiagnostics.isTestSourceFailure(result));
    }

    @Test void missingRequestedTestClassIsATestSourceFailure() {
        CheckResult result = result("No tests found for given includes: [com.nexus.catalog.domain.model.VariantTest]\n", null, List.of());
        assertTrue(CheckDiagnostics.isTestSourceFailure(result));
    }

    @Test void unreadableLogFallsBackToCapturedActualOutput() {
        String captured = "> Task :services:catalog:compileTestJava FAILED\n";
        CheckResult result = result(captured, temporary.resolve("does-not-exist.log"), List.of());
        assertEquals(captured, CheckDiagnostics.actualOutput(result));
        assertTrue(CheckDiagnostics.isTestSourceFailure(result));
    }

    @Test void actualLogReadingRemainsBounded() throws Exception {
        Path log = temporary.resolve("large-check.log");
        Files.writeString(log, "x".repeat(64_000) + "\n> Task :services:catalog:compileTestKotlin FAILED\n");
        String output = CheckDiagnostics.actualOutput(result("captured", log, List.of()));
        assertEquals(64_000, output.length());
        assertFalse(output.contains("compileTestKotlin"));
    }

    private static CheckResult result(String output, Path log, List<TestSuite> suites) {
        return new CheckResult("targeted-tests", 1, false, output, log, suites, null);
    }
}
