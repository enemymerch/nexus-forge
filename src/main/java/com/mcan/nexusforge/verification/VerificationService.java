package com.mcan.nexusforge.verification;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only an application-owned catalog and checks fresh on-disk test evidence. */
public final class VerificationService {
    private static final long MAX_REPORT_BYTES = 2_000_000;
    private static final int MAX_REPORTS = 200;
    private static final int MAX_LOG_CHARS = 1_000_000;
    private final Path workspace;
    private final Map<String, CheckDefinition> checks;
    private final Path artifactDirectory;
    private final Duration timeout;
    private final int maxOutputChars;
    private final AtomicInteger sequence = new AtomicInteger();

    public VerificationService(Path workspace, List<CheckDefinition> definitions, Path artifactDirectory,
                               Duration timeout, int maxOutputChars) throws IOException {
        this.workspace = workspace.toRealPath();
        if (!Files.isDirectory(this.workspace)) throw new IllegalArgumentException("Check workspace must be a directory.");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Check timeout must be between zero and one hour.");
        if (maxOutputChars < 128 || maxOutputChars > 64_000)
            throw new IllegalArgumentException("Check output limit must be between 128 and 64000 characters.");
        this.timeout = timeout;
        this.maxOutputChars = maxOutputChars;
        Files.createDirectories(artifactDirectory);
        this.artifactDirectory = artifactDirectory.toRealPath();
        var catalog = new LinkedHashMap<String, CheckDefinition>();
        for (var definition : definitions) {
            if (catalog.putIfAbsent(definition.id(), definition) != null)
                throw new IllegalArgumentException("Duplicate check id: " + definition.id());
            resolveInside(definition.workingDirectory(), true);
            for (String directory : definition.reportDirectories()) resolveInside(directory, false);
            if (Path.of(definition.command().getFirst()).toRealPath().startsWith(this.workspace))
                throw new IllegalArgumentException("Checks must use an installed executable outside the editable workspace.");
            launchCommand(definition.command());
        }
        this.checks = Collections.unmodifiableMap(catalog);
    }

    public List<CheckDefinition> definitions() { return List.copyOf(checks.values()); }

    public synchronized CheckResult run(String checkId) {
        CheckDefinition definition = checks.get(checkId);
        if (definition == null)
            return new CheckResult(checkId, -1, false, "Unknown check id. Available checks: " + checks.keySet(), null, List.of(), "Check not allowed.");
        Path log = artifactDirectory.resolve(String.format(Locale.ROOT, "%03d-%s-%s.log", sequence.incrementAndGet(), checkId, UUID.randomUUID().toString().substring(0, 8)));
        Process process = null;
        ExecutorService executor = null;
        boolean timedOut = false;
        try {
            Path directory = resolveInside(definition.workingDirectory(), true);
            if (Path.of(definition.command().getFirst()).toRealPath().startsWith(workspace))
                throw new IllegalArgumentException("Check executable resolves inside the editable workspace.");
            clearReports(definition);
            ProcessBuilder builder = new ProcessBuilder(launchCommand(definition.command()))
                    .directory(directory.toFile()).redirectErrorStream(true);
            // Gradle's Windows launcher echoes its startup script when DEBUG is inherited.
            builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("DEBUG"));
            process = builder.start();
            Process running = process;
            var capture = new OutputCapture(maxOutputChars);
            executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "check-output"); t.setDaemon(true); return t; });
            Future<?> drain = executor.submit(() -> {
                try (Reader reader = new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8);
                     Writer writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    char[] buffer = new char[4096];
                    int written = 0;
                    for (int n; (n = reader.read(buffer)) >= 0;) {
                        capture.append(buffer, n);
                        int allowed = Math.min(n, MAX_LOG_CHARS - written);
                        if (allowed > 0) {
                            writer.write(buffer, 0, allowed);
                            written += allowed;
                            writer.flush();
                        }
                    }
                    if (capture.count > MAX_LOG_CHARS) writer.write("\n[Log truncated at " + MAX_LOG_CHARS + " characters.]\n");
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { timedOut = true; stop(process); }
            // A child process must not hold the output pipe forever after its parent exits.
            try { drain.get(5, TimeUnit.SECONDS); }
            catch (TimeoutException e) { stop(process); process.getInputStream().close(); drain.cancel(true); }
            int exit = process.isAlive() ? -1 : process.exitValue();
            List<TestSuite> suites = new ArrayList<>();
            String evidenceError = null;
            if (definition.requireTests()) {
                try { suites = readReports(definition); }
                catch (Exception e) { evidenceError = "Test evidence rejected: " + concise(e); }
            }
            if (timedOut) evidenceError = "Check exceeded its timeout; test results cannot establish success.";
            return new CheckResult(checkId, exit, timedOut, capture.text(), log, suites, evidenceError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(checkId, -1, timedOut, "Check interrupted.", log, List.of(), "Check interrupted.");
        } catch (Exception e) {
            return new CheckResult(checkId, -1, timedOut, concise(e), log, List.of(), "Check could not complete: " + concise(e));
        } finally {
            if (process != null && process.isAlive()) stop(process);
            if (executor != null) executor.shutdownNow();
        }
    }

    private Path resolveInside(String relative, boolean mustExist) throws IOException {
        if (relative == null || relative.isBlank() || Path.of(relative).isAbsolute())
            throw new IllegalArgumentException("Check paths must be relative to the isolated workspace.");
        Path path = workspace.resolve(relative).normalize();
        if (!path.startsWith(workspace)) throw new IllegalArgumentException("Check path escapes the isolated workspace.");
        Path current = workspace;
        for (Path part : workspace.relativize(path)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!current.toRealPath().startsWith(workspace))
                    throw new IllegalArgumentException("Check path resolves outside the isolated workspace.");
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Check paths must not traverse symbolic links.");
            }
        }
        if (mustExist && !Files.isDirectory(path)) throw new IllegalArgumentException("Check directory does not exist: " + relative);
        return path;
    }

    private void clearReports(CheckDefinition definition) throws IOException {
        for (String relative : definition.reportDirectories()) {
            Path directory = resolveInside(relative, false);
            if (!Files.exists(directory)) continue;
            if (!Files.isDirectory(directory)) throw new IOException("JUnit report path is not a directory.");
            try (var files = Files.newDirectoryStream(directory, "TEST-*.xml")) {
                for (Path file : files) {
                    validateReportPath(file);
                    // Only individual JUnit XML files in the exact configured directory are removed.
                    Files.delete(file);
                }
            }
        }
    }

    private List<TestSuite> readReports(CheckDefinition definition) throws Exception {
        var suites = new ArrayList<TestSuite>();
        boolean expectedFound = definition.expectedTestClass() == null;
        int executed = 0;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        int reportCount = 0;
        for (String relative : definition.reportDirectories()) {
            Path directory = resolveInside(relative, false);
            if (!Files.exists(directory)) continue;
            try (var files = Files.newDirectoryStream(directory, "TEST-*.xml")) {
                for (Path file : files) {
                    if (++reportCount > MAX_REPORTS) throw new IOException("Too many JUnit reports.");
                    validateReportPath(file);
                    if (Files.size(file) > MAX_REPORT_BYTES) throw new IOException("JUnit report exceeds its byte limit.");
                    Element suite;
                    try (var stream = Files.newInputStream(file)) {
                        byte[] xml = stream.readNBytes((int) MAX_REPORT_BYTES + 1);
                        if (xml.length > MAX_REPORT_BYTES) throw new IOException("JUnit report exceeds its byte limit.");
                        var parser = factory.newDocumentBuilder();
                        // Report parser errors through the tool result, without writing to the console.
                        parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                            @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                        });
                        suite = parser.parse(new ByteArrayInputStream(xml)).getDocumentElement();
                    }
                    if (!suite.getTagName().equals("testsuite")) throw new IOException("Expected a JUnit testsuite root element.");
                    int tests = integer(suite, "tests"), failures = integer(suite, "failures"), errors = integer(suite, "errors");
                    List<Element> cases = directChildren(suite, "testcase");
                    if (tests != cases.size())
                        throw new IOException("JUnit suite counts do not match its test cases.");
                    int actualFailures = 0, actualErrors = 0, actualSkipped = 0;
                    for (Element test : cases) {
                        boolean failed = !directChildren(test, "failure").isEmpty();
                        boolean errored = !directChildren(test, "error").isEmpty();
                        boolean skippedCase = !directChildren(test, "skipped").isEmpty();
                        if ((failed ? 1 : 0) + (errored ? 1 : 0) + (skippedCase ? 1 : 0) > 1)
                            throw new IOException("JUnit test case has conflicting result states.");
                        if (failed) actualFailures++;
                        if (errored) actualErrors++;
                        if (skippedCase) actualSkipped++;
                        boolean didRun = !skippedCase;
                        if (didRun) executed++;
                        String className = test.hasAttribute("classname") ? test.getAttribute("classname") : suite.getAttribute("name");
                        if (didRun && definition.expectedTestClass() != null && definition.expectedTestClass().equals(className))
                            expectedFound = true;
                    }
                    int skipped = suite.hasAttribute("skipped") ? integer(suite, "skipped") : actualSkipped;
                    if (failures != actualFailures || errors != actualErrors || skipped != actualSkipped)
                        throw new IOException("JUnit suite status counts do not match its test cases.");
                    suites.add(new TestSuite(workspace.relativize(file).toString().replace('\\', '/'),
                            suite.getAttribute("name"), tests, failures, errors, skipped));
                }
            }
        }
        if (suites.isEmpty() || executed == 0) throw new IOException("No fresh, executed JUnit tests were found (cached or skipped checks do not count).");
        if (!expectedFound) throw new IOException("No executed test from expected class " + definition.expectedTestClass() + " was found.");
        return List.copyOf(suites);
    }

    private void validateReportPath(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                !file.toRealPath().startsWith(workspace)) throw new IOException("Unsafe JUnit report path.");
    }

    private static List<Element> directChildren(Element parent, String tag) {
        var elements = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && element.getTagName().equals(tag)) elements.add(element);
        }
        return elements;
    }

    private static int integer(Element element, String name) throws IOException {
        try {
            int n = Integer.parseInt(element.getAttribute(name));
            if (n < 0 || n > 1_000_000) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) { throw new IOException("Invalid JUnit " + name + " count."); }
    }

    private static List<String> launchCommand(List<String> tokens) throws IOException {
        Path executable = Path.of(tokens.getFirst());
        if (!executable.isAbsolute() || !Files.isRegularFile(executable))
            throw new IllegalArgumentException("Checks require an absolute installed executable path.");
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Set.of("cmd.exe", "powershell.exe", "pwsh.exe", "sh", "bash", "bash.exe", "wscript.exe", "cscript.exe").contains(name))
            throw new IllegalArgumentException("General shell executables cannot be checks.");
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
                throw new IllegalArgumentException("Windows batch checks require Windows.");
            for (String token : tokens) {
                if (token.chars().anyMatch(c -> c < 32 || "\"%&|<>^!".indexOf(c) >= 0))
                    throw new IllegalArgumentException("Unsafe character in fixed Windows batch command.");
            }
            Path cmd = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe");
            String line = "\"" + String.join(" ", tokens.stream().map(t -> "\"" + t + "\"").toList()) + "\"";
            return List.of(cmd.toString(), "/d", "/s", "/c", line);
        }
        for (String token : tokens) if (token.indexOf('\0') >= 0 || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid fixed command argument.");
        return List.copyOf(tokens);
    }

    private static void stop(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String concise(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static final class OutputCapture {
        private final int headLimit, tailLimit;
        private final StringBuilder head = new StringBuilder(), tail = new StringBuilder();
        private long count;
        OutputCapture(int limit) { headLimit = limit / 2; tailLimit = limit - headLimit; }
        synchronized void append(char[] chars, int length) {
            int headLength = Math.min(length, headLimit - head.length());
            if (headLength > 0) head.append(chars, 0, headLength);
            if (length > headLength) {
                tail.append(chars, headLength, length - headLength);
                if (tail.length() > tailLimit) tail.delete(0, tail.length() - tailLimit);
            }
            count += length;
        }
        synchronized String text() {
            return head + (count > headLimit + tailLimit ? "\n[Output truncated; bounded log available.]\n" : "") + tail;
        }
    }
}
