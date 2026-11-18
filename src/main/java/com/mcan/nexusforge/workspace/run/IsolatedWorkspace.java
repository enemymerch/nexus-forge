package com.mcan.nexusforge.workspace.run;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** A bounded copy of the current working tree, including safe uncommitted and untracked files. */
public final class IsolatedWorkspace {
    private static final int MAX_FILES = 10_000;
    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final int MAX_GIT_OUTPUT_BYTES = 4 * 1024 * 1024;
    private final Path source;
    private final Path runDirectory;
    private final Path root;
    private final String git;
    private final Map<String, String> baseline;

    private IsolatedWorkspace(Path source, Path runDirectory, Map<String, String> baseline, String git) {
        this.source = source;
        this.runDirectory = runDirectory;
        this.root = runDirectory.resolve("workspace");
        this.baseline = Map.copyOf(baseline);
        this.git = git;
    }

    public static IsolatedWorkspace create(Path source, Path runsDirectory) throws IOException {
        if (source == null || runsDirectory == null) throw new IllegalArgumentException("Source and runs directory are required.");
        Path sourceAbsolute = source.toAbsolutePath().normalize();
        Path runsAbsolute = runsDirectory.toAbsolutePath().normalize();
        RunFilePolicy.requireNoLinks(sourceAbsolute);
        RunFilePolicy.requireNoLinks(runsAbsolute);
        Path sourceReal = sourceAbsolute.toRealPath();
        if (!Files.isDirectory(sourceReal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source must be a directory.");
        if (runsAbsolute.startsWith(sourceReal) || sourceReal.startsWith(runsAbsolute))
            throw new IOException("Runs directory must be outside the source and cannot be its ancestor.");
        String git = findGit();
        Files.createDirectories(runsAbsolute);
        RunFilePolicy.requireNoLinks(runsAbsolute);
        Path runsReal = runsAbsolute.toRealPath();
        if (runsReal.startsWith(sourceReal) || sourceReal.startsWith(runsReal))
            throw new IOException("Runs directory overlaps the source.");
        Path run = Files.createTempDirectory(runsReal, "run-");
        Path copy = Files.createDirectory(run.resolve("workspace"));
        Map<String, String> hashes = snapshot(sourceReal, copy);
        IsolatedWorkspace workspace = new IsolatedWorkspace(sourceReal, run, hashes, git);
        Files.createDirectories(run.resolve("disabled-hooks"));
        Files.writeString(run.resolve("empty-git-config"), "", StandardOpenOption.CREATE_NEW);
        workspace.git("init", "--quiet");
        workspace.git("add", "--force", "--all", "--", ".");
        workspace.git("commit", "--quiet", "--allow-empty", "--no-verify", "--no-gpg-sign", "-m", "Agent working-copy baseline");
        if (!workspace.originalUnchanged()) throw new IOException("Source changed while the isolated snapshot was being created; retry the task.");
        return workspace;
    }

    public Path root() { return root; }
    public Path runDirectory() { return runDirectory; }
    public Path source() { return source; }

    /** Detects changes to included source files and newly added eligible files; never resets the original. */
    public boolean originalUnchanged() throws IOException {
        RunFilePolicy.requireNoLinks(source);
        return baseline.equals(snapshot(source, null));
    }

    public List<String> changedFiles() throws IOException {
        Map<String, String> current = snapshot(root, null);
        TreeSet<String> candidates = new TreeSet<>(baseline.keySet());
        candidates.addAll(current.keySet());
        return candidates.stream().filter(path -> !java.util.Objects.equals(baseline.get(path), current.get(path))).toList();
    }

    public String diff() throws IOException {
        RunFilePolicy.requireNoLinks(root);
        Map<String, String> current = snapshot(root, null);
        for (String path : current.keySet()) {
            if (!baseline.containsKey(path)) git("add", "--intent-to-add", "--force", "--", path);
        }
        return git("diff", "--no-ext-diff", "--no-textconv", "--no-color", "--binary", "HEAD", "--", ".");
    }

    public void writeDiff(Path output) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        if (!target.startsWith(runDirectory) || target.startsWith(root))
            throw new IOException("Diff artifacts must be stored in the run directory outside its workspace.");
        RunFilePolicy.requireNoLinks(target);
        Files.createDirectories(target.getParent());
        RunFilePolicy.requireNoLinks(target);
        Files.writeString(target, diff(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Map<String, String> snapshot(Path root, Path destination) throws IOException {
        RunFilePolicy.requireNoLinks(root);
        Map<String, String> result = new LinkedHashMap<>();
        long[] total = {0};
        int[] visited = {0};
        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 64,
                new SimpleFileVisitor<>() {
                    private void visit() throws IOException {
                        if (++visited[0] > MAX_ENTRIES) throw new IOException("Workspace exceeds the 50000-entry traversal limit.");
                    }

                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                        visit();
                        if (!directory.equals(root) && (!RunFilePolicy.allowed(root.relativize(directory)) || RunFilePolicy.linked(directory)))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        visit();
                        Path relative = root.relativize(file);
                        if (!RunFilePolicy.allowed(relative) || !attributes.isRegularFile() || attributes.isSymbolicLink()
                                || attributes.size() > RunFilePolicy.MAX_FILE_BYTES || RunFilePolicy.linked(file)) return FileVisitResult.CONTINUE;
                        byte[] bytes;
                        try (InputStream input = Files.newInputStream(file)) { bytes = input.readNBytes((int) RunFilePolicy.MAX_FILE_BYTES + 1); }
                        if (bytes.length > RunFilePolicy.MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                        try { RunFilePolicy.text(bytes); } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException ignored) { return FileVisitResult.CONTINUE; }
                        if (result.size() >= MAX_FILES || (total[0] += bytes.length) > MAX_TOTAL_BYTES)
                            throw new IOException("Workspace snapshot exceeds the 10000-file / 32 MiB copy limit.");
                        String name = RunFilePolicy.relative(root, file);
                        result.put(name, hash(bytes));
                        if (destination != null) {
                            Path target = destination.resolve(relative);
                            Files.createDirectories(target.getParent());
                            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return result;
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String git(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(git, "--no-pager", "-c", "core.autocrlf=false", "-c", "core.safecrlf=false",
                "-c", "core.fsmonitor=false", "-c", "core.hooksPath=" + runDirectory.resolve("disabled-hooks"),
                "-c", "user.name=Local Coding Agent", "-c", "user.email=agent@localhost", "-c", "commit.gpgsign=false",
                "-c", "safe.directory=" + root));
        command.addAll(List.of(arguments));
        Path capture = Files.createTempFile(runDirectory, "git-output-", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile());
        builder.environment().keySet().removeIf(key -> key.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", runDirectory.resolve("empty-git-config").toString());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        try {
            if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("Local Git operation exceeded its 30-second limit.");
            }
            if (Files.size(capture) > MAX_GIT_OUTPUT_BYTES) throw new IOException("Git output exceeds the 4 MiB limit.");
            String output = Files.readString(capture);
            if (process.exitValue() != 0) throw new IOException("Local Git operation failed: " + output.substring(0, Math.min(output.length(), 2000)).strip());
            return output;
        } catch (InterruptedException interrupted) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Local Git operation interrupted.", interrupted);
        } finally { Files.deleteIfExists(capture); }
    }

    private static String findGit() throws IOException {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "git.exe" : "git";
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (directory.isBlank()) continue;
                Path candidate = Path.of(directory.replace("\"", "")).resolve(executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString();
            }
        }
        throw new IOException("Git is required for isolated baseline and diff creation. Add Git to PATH.");
    }
}
