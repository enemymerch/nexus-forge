package com.mcan.nexusforge.verification;

public record TestSuite(String file, String name, int tests, int failures, int errors, int skipped) {
    public boolean passed() { return tests > skipped && failures == 0 && errors == 0; }
}
