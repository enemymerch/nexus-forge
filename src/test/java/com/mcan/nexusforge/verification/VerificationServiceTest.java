package com.mcan.nexusforge.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VerificationServiceTest {
    @TempDir Path temp;

    private Path workspace() throws Exception { return Files.createDirectories(temp.resolve("workspace")); }
    private List<String> command(String... arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path classes = Path.of(VerificationFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var result = new ArrayList<>(List.of(java.toString(), "-cp", classes.toString(), VerificationFixture.class.getName()));
        result.addAll(List.of(arguments));
        return result;
    }
    private VerificationService service(CheckDefinition definition) throws Exception {
        return new VerificationService(workspace(), List.of(definition), temp.resolve("logs"), Duration.ofSeconds(10), 512);
    }
    private CheckDefinition tests(String mode, String expected) throws Exception {
        return CheckDefinition.tests("test", command("report", mode), ".", List.of("reports"), expected);
    }

    @Test void requiresFreshExecutedJUnitEvidenceAndExpectedClass() throws Exception {
        CheckResult result = service(tests("pass", "example.FeatureTest")).run("test");
        assertTrue(result.passed(), result.output() + " " + result.evidenceError());
        assertEquals(1, result.tests());
        assertEquals(0, result.failures());
        assertTrue(Files.isRegularFile(result.logFile()));
        assertEquals(1, result.suites().size());
    }

    @Test void successfulProcessWithoutFreshReportsIsNotTestSuccess() throws Exception {
        Path reports = Files.createDirectories(workspace().resolve("reports"));
        Files.writeString(reports.resolve("TEST-stale.xml"), "<testsuite tests=\"1\" failures=\"0\" errors=\"0\"><testcase name=\"old\"/></testsuite>");
        Files.writeString(reports.resolve("keep.txt"), "unrelated");
        var definition = CheckDefinition.tests("test", command("quiet"), ".", List.of("reports"), null);
        CheckResult result = service(definition).run("test");
        assertEquals(0, result.exitCode());
        assertFalse(result.passed());
        assertTrue(result.evidenceError().contains("No fresh"));
        assertFalse(Files.exists(reports.resolve("TEST-stale.xml")));
        assertTrue(Files.exists(reports.resolve("keep.txt")));
    }

    @Test void compileCheckCanPassWithoutClaimingAnyTests() throws Exception {
        CheckResult result = service(CheckDefinition.compile("compile", command("quiet"), ".")).run("compile");
        assertTrue(result.passed());
        assertEquals(0, result.tests());
    }

    @Test void failureExitIsRealAndNeverPasses() throws Exception {
        CheckResult result = service(CheckDefinition.compile("compile", command("failure"), ".")).run("compile");
        assertEquals(7, result.exitCode());
        assertFalse(result.passed());
        assertTrue(result.output().contains("nonzero"));
    }

    @Test void rejectsFailingSkippedMismatchedAndMalformedJUnitReports() throws Exception {
        for (String mode : List.of("skipped", "fake-count", "hidden-failure", "fake-failure", "hidden-skipped", "xxe")) {
            CheckResult result = service(tests(mode, "example.FeatureTest")).run("test");
            assertEquals(0, result.exitCode(), mode);
            assertFalse(result.passed(), mode);
            assertNotNull(result.evidenceError(), mode);
        }
        CheckResult otherClass = service(tests("pass", "example.DifferentTest")).run("test");
        assertFalse(otherClass.passed());
        assertTrue(otherClass.evidenceError().contains("expected class"));
    }

    @Test void preservesFailureCountersEvenWhenProcessExitsZero() throws Exception {
        CheckResult result = service(tests("fail", null)).run("test");
        assertFalse(result.passed());
        assertEquals(1, result.failures());
        assertNull(result.evidenceError(), "Valid failed tests are evidence, not an evidence validation error.");
    }

    @Test void failedProcessWithFreshFailedTestsProducesValidRedEvidence() throws Exception {
        var definition = CheckDefinition.tests("red", command("report", "fail", "exit-failure"), ".",
                List.of("reports"), "example.FeatureTest");
        CheckResult result = service(definition).run("red");
        assertEquals(1, result.exitCode());
        assertEquals(1, result.tests());
        assertEquals(1, result.failures());
        assertNull(result.evidenceError());
        assertFalse(result.timedOut());
        assertFalse(result.passed());
    }

    @Test void explicitDifferentClassCannotBorrowExpectedSuiteName() throws Exception {
        CheckResult result = service(tests("wrong-class", "example.FeatureTest")).run("test");
        assertFalse(result.passed());
        assertNotNull(result.evidenceError());
        assertTrue(result.evidenceError().contains("expected class"));
        assertTrue(service(tests("no-class", "example.FeatureTest")).run("test").passed());
    }

    @Test void acceptsConsistentGradleStylePassedFailedSkippedAndErrorCases() throws Exception {
        CheckResult mixed = service(tests("mixed", "example.FeatureTest")).run("test");
        assertNull(mixed.evidenceError());
        assertEquals(3, mixed.tests());
        assertEquals(1, mixed.failures());
        assertEquals(1, mixed.skipped());
        assertFalse(mixed.passed());
        CheckResult error = service(tests("error", "example.FeatureTest")).run("test");
        assertNull(error.evidenceError());
        assertEquals(1, error.errors());
        assertFalse(error.passed());
    }

    @Test void drainsHugeOutputWithBoundedExcerptAndLog() throws Exception {
        CheckResult result = service(CheckDefinition.compile("compile", command("output"), ".")).run("compile");
        assertTrue(result.passed(), result.evidenceError());
        assertTrue(result.output().startsWith("START"));
        assertTrue(result.output().endsWith("END"));
        assertTrue(result.output().length() < 640);
        assertTrue(Files.size(result.logFile()) < 1_001_000);
        assertTrue(Files.readString(result.logFile()).contains("Log truncated"));
    }

    @Test void timeoutTerminatesProcessAndDescendants() throws Exception {
        var definition = CheckDefinition.compile("timeout", command("child"), ".");
        var service = new VerificationService(workspace(), List.of(definition), temp.resolve("logs"), Duration.ofSeconds(2), 512);
        long start = System.nanoTime();
        CheckResult result = service.run("timeout");
        assertTrue(result.timedOut());
        assertFalse(result.passed());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(10)) < 0);
        long child = Long.parseLong(Files.readString(workspace().resolve("child.pid")));
        for (int i = 0; i < 20 && ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(50);
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void unknownCheckNeverStartsAnything() throws Exception {
        var service = service(CheckDefinition.compile("compile", command("quiet"), "."));
        assertFalse(service.run("arbitrary shell text").passed());
        assertNull(service.run("anything").logFile());
    }

    @Test void rejectsEscapesGenericShellAndWorkspaceExecutables() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service(CheckDefinition.compile("bad", command("quiet"), "../")));
        var escapedReport = CheckDefinition.tests("bad", command("quiet"), ".", List.of("../outside"), null);
        assertThrows(IllegalArgumentException.class, () -> service(escapedReport));
        Path local = Files.writeString(workspace().resolve("fake.exe"), "x");
        assertThrows(IllegalArgumentException.class, () -> service(CheckDefinition.compile("bad", List.of(local.toString()), ".")));
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path shell = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
            assertThrows(IllegalArgumentException.class, () -> service(CheckDefinition.compile("bad", List.of(shell.toString(), "/c", "anything"), ".")));
        }
    }

    @Test void launchesFixedWindowsBatchArgumentsWithSpacesAndRejectsMetacharacters() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) return;
        Path tooling = Files.createDirectories(temp.resolve("trusted tooling with spaces"));
        Path batch = Files.writeString(tooling.resolve("fixture.cmd"), "@echo off\r\necho ARG=%~1\r\nexit /b 0\r\n");
        var definition = CheckDefinition.compile("batch", List.of(batch.toString(), "argument with spaces"), ".");
        CheckResult result = service(definition).run("batch");
        assertTrue(result.passed(), result.output() + " " + result.evidenceError());
        assertTrue(result.output().contains("ARG=argument with spaces"), result.output());
        for (String unsafe : List.of("x & whoami", "%PATH%", "!PATH!", "x|y", "x\"y")) {
            var bad = CheckDefinition.compile("bad", List.of(batch.toString(), unsafe), ".");
            assertThrows(IllegalArgumentException.class, () -> service(bad));
        }
    }
}
