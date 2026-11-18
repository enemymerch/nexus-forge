package com.mcan.nexusforge.verification;

import java.nio.file.Files;
import java.nio.file.Path;

/** An actual child JVM, so tests exercise process IO, exit codes and timeouts. */
public final class VerificationFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "quiet" -> System.out.println("Fixture completed without running tests.");
            case "failure" -> { System.err.println("Real nonzero process exit."); System.exit(7); }
            case "output" -> {
                System.out.print("START");
                System.out.print("x".repeat(1_200_000));
                System.out.print("END");
            }
            case "sleep" -> Thread.sleep(60_000);
            case "child" -> {
                Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
                Process child = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), VerificationFixture.class.getName(), "sleep")
                        .inheritIO().start();
                Files.writeString(Path.of("child.pid"), Long.toString(child.pid()));
                child.waitFor();
            }
            case "report" -> {
                Path report = Path.of("reports", "TEST-fixture.xml");
                Files.createDirectories(report.getParent());
                String body = switch (args[1]) {
                    case "pass" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"example.FeatureTest\" name=\"works\"/></testsuite>";
                    case "fail" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"1\" errors=\"0\"><testcase classname=\"example.FeatureTest\" name=\"fails\"><failure message=\"broken\"/></testcase></testsuite>";
                    case "skipped" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\"><testcase classname=\"example.FeatureTest\" name=\"skipped\"><skipped/></testcase></testsuite>";
                    case "fake-count" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"0\"/>";
                    case "hidden-failure" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"0\"><testcase classname=\"example.FeatureTest\" name=\"fails\"><failure/></testcase></testsuite>";
                    case "fake-failure" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"1\" errors=\"0\"><testcase classname=\"example.FeatureTest\" name=\"works\"/></testsuite>";
                    case "wrong-class" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"0\"><testcase classname=\"example.DifferentTest\" name=\"works\"/></testsuite>";
                    case "mixed" -> "<testsuite name=\"example.FeatureTest\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"1\"><testcase classname=\"example.FeatureTest\" name=\"works\"/><testcase classname=\"example.FeatureTest\" name=\"fails\"><failure/></testcase><testcase classname=\"example.FeatureTest\" name=\"skipped\"><skipped/></testcase></testsuite>";
                    case "error" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"1\"><testcase classname=\"example.FeatureTest\" name=\"errors\"><error/></testcase></testsuite>";
                    case "no-class" -> "<testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"0\"><testcase name=\"works\"/></testsuite>";
                    case "hidden-skipped" -> "<testsuite name=\"example.FeatureTest\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"example.FeatureTest\" name=\"works\"/><testcase classname=\"example.FeatureTest\" name=\"skipped\"><skipped/></testcase></testsuite>";
                    case "xxe" -> "<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM \"file:///sensitive.txt\">]><testsuite name=\"example.FeatureTest\" tests=\"1\" failures=\"0\" errors=\"0\"><testcase name=\"a\">&xxe;</testcase></testsuite>";
                    default -> throw new IllegalArgumentException("unknown fixture");
                };
                Files.writeString(report, body);
                System.out.println("JUnit report generated.");
                if (args.length > 2 && args[2].equals("exit-failure")) System.exit(1);
            }
            default -> throw new IllegalArgumentException("Unknown mode.");
        }
    }
}
