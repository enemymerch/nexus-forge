package com.mcan.nexusforge.verification;

import java.nio.file.Path;
import java.util.List;

public record CheckResult(String checkId, int exitCode, boolean timedOut, String output,
                          Path logFile, List<TestSuite> suites, String evidenceError) {
    public CheckResult { suites = List.copyOf(suites); }
    public boolean passed() {
        return exitCode == 0 && !timedOut && evidenceError == null &&
                suites.stream().allMatch(suite -> suite.failures() == 0 && suite.errors() == 0);
    }
    public int tests() { return suites.stream().mapToInt(TestSuite::tests).sum(); }
    public int failures() { return suites.stream().mapToInt(TestSuite::failures).sum(); }
    public int errors() { return suites.stream().mapToInt(TestSuite::errors).sum(); }
    public int skipped() { return suites.stream().mapToInt(TestSuite::skipped).sum(); }
}
