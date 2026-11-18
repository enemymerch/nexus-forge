package com.mcan.nexusforge.verification;

import java.util.List;
import java.util.Objects;

/** A command selected by application configuration, never by model supplied shell text. */
public record CheckDefinition(String id, List<String> command, String workingDirectory,
                              List<String> reportDirectories, boolean requireTests,
                              String expectedTestClass) {
    public CheckDefinition {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid check id.");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty() || command.stream().anyMatch(s -> s == null || s.isBlank()))
            throw new IllegalArgumentException("A check must have fixed, nonblank command arguments.");
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        reportDirectories = List.copyOf(Objects.requireNonNull(reportDirectories, "reportDirectories"));
        if (requireTests && reportDirectories.isEmpty())
            throw new IllegalArgumentException("Test checks must declare their JUnit report directories.");
        if (expectedTestClass != null && !expectedTestClass.matches("[a-zA-Z_$][\\w.$]*"))
            throw new IllegalArgumentException("Expected test class must be a concrete Java class name.");
    }

    public static CheckDefinition compile(String id, List<String> command, String workingDirectory) {
        return new CheckDefinition(id, command, workingDirectory, List.of(), false, null);
    }

    public static CheckDefinition tests(String id, List<String> command, String workingDirectory,
                                        List<String> reportDirectories, String expectedTestClass) {
        return new CheckDefinition(id, command, workingDirectory, reportDirectories, true, expectedTestClass);
    }
}
