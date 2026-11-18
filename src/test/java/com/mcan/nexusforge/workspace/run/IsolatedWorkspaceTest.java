package com.mcan.nexusforge.workspace.run;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IsolatedWorkspaceTest {
    @TempDir Path directory;

    @Test void copiesCurrentDirtyAndUntrackedFilesWithoutTouchingOriginalGit() throws Exception {
        Path source = source();
        git(source, "init", "--quiet");
        git(source, "add", "--all");
        git(source, "-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgsign=false",
                "commit", "--quiet", "-m", "Initial state");
        Files.writeString(source.resolve("src/main/java/App.java"), "class App { int dirty = 1; }\n");
        Files.writeString(source.resolve("src/main/java/New.java"), "class New {}\n");
        String before = git(source, "status", "--porcelain");
        IsolatedWorkspace workspace = IsolatedWorkspace.create(source, directory.resolve("runs"));
        assertEquals("class App { int dirty = 1; }\n", Files.readString(workspace.root().resolve("src/main/java/App.java")));
        assertEquals("class New {}\n", Files.readString(workspace.root().resolve("src/main/java/New.java")));
        assertTrue(workspace.originalUnchanged());
        assertTrue(workspace.changedFiles().isEmpty());
        assertEquals("", workspace.diff());
        assertEquals(before, git(source, "status", "--porcelain"));
        assertNotEquals(source, workspace.root());
        assertEquals(source.toRealPath(), workspace.source());
    }

    @Test void excludesCredentialsBuildOutputsMalformedUtf8AndBinaryContent() throws Exception {
        Path source = source();
        Files.createDirectories(source.resolve("build"));
        Files.writeString(source.resolve("build/Generated.java"), "class Generated {}");
        Files.writeString(source.resolve(".env.local"), "KEY=secret");
        Files.writeString(source.resolve("service-credentials.json"), "{}");
        Files.writeString(source.resolve("wrapper.jar"), "not really a jar");
        Files.write(source.resolve("raw.dat"), new byte[]{0, 1, 2});
        Files.write(source.resolve("malformed.txt"), new byte[]{(byte) 0xC3, (byte) 0x28});
        Files.write(source.resolve("large.txt"), new byte[(int) RunFilePolicy.MAX_FILE_BYTES + 1]);
        IsolatedWorkspace workspace = IsolatedWorkspace.create(source, directory.resolve("runs"));
        for (String excluded : List.of("build", ".env.local", "service-credentials.json", "wrapper.jar", "raw.dat", "malformed.txt", "large.txt"))
            assertFalse(Files.exists(workspace.root().resolve(excluded)), excluded);
        assertTrue(Files.exists(workspace.root().resolve("README.md")));
    }

    @Test void refusesOverlappingRootsAndDetectsOriginalAdditionsChangesAndRemovals() throws Exception {
        Path source = source();
        assertThrows(IOException.class, () -> IsolatedWorkspace.create(source, source.resolve("runs")));
        assertThrows(IOException.class, () -> IsolatedWorkspace.create(source, directory));
        IsolatedWorkspace workspace = IsolatedWorkspace.create(source, directory.resolve("runs"));
        Files.writeString(source.resolve("extra.md"), "new file");
        assertFalse(workspace.originalUnchanged());
        Files.delete(source.resolve("extra.md"));
        assertTrue(workspace.originalUnchanged());
        Files.writeString(source.resolve("README.md"), "modified");
        assertFalse(workspace.originalUnchanged());
        Files.delete(source.resolve("README.md"));
        assertFalse(workspace.originalUnchanged());
    }

    @Test void skipsSymlinksAndRefusesLinkedSourceAndRunsRootsWhenSupported() throws Exception {
        Path source = source();
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("Outside.java"), "class Outside {}");
        try { Files.createSymbolicLink(source.resolve("linked"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic link creation is unavailable: " + unavailable.getMessage());
        }
        IsolatedWorkspace workspace = IsolatedWorkspace.create(source, directory.resolve("runs"));
        assertFalse(Files.exists(workspace.root().resolve("linked")));
        assertThrows(IOException.class, () -> IsolatedWorkspace.create(source.resolve("linked"), directory.resolve("runs")));
        Files.createSymbolicLink(directory.resolve("runs-link"), directory.resolve("runs"));
        assertThrows(IOException.class, () -> IsolatedWorkspace.create(source, directory.resolve("runs-link")));
    }

    @Test void diffIncludesNewFilesAndArtifactsCannotOverwriteOriginalOrWorkspace() throws Exception {
        Path source = source();
        IsolatedWorkspace workspace = IsolatedWorkspace.create(source, directory.resolve("runs"));
        PatchApplier applier = new PatchApplier(workspace, Set.of("src/test/java/NewTest.java"), 1, 1000);
        applier.apply(edits("src/test/java/NewTest.java", "", "class NewTest {}\n"));
        assertEquals(List.of("src/test/java/NewTest.java"), workspace.changedFiles());
        String diff = workspace.diff();
        assertTrue(diff.contains("new file mode"));
        assertTrue(diff.contains("+class NewTest {}"));
        Path artifact = workspace.runDirectory().resolve("changes.patch");
        workspace.writeDiff(artifact);
        assertEquals(diff, Files.readString(artifact));
        assertTrue(workspace.originalUnchanged());
        assertThrows(IOException.class, () -> workspace.writeDiff(source.resolve("changes.patch")));
        assertThrows(IOException.class, () -> workspace.writeDiff(workspace.root().resolve("changes.patch")));
    }

    private Path source() throws IOException {
        Path source = Files.createDirectories(directory.resolve("source/src/main/java")).getParent().getParent().getParent();
        Files.writeString(source.resolve("README.md"), "Read me\n");
        Files.writeString(source.resolve("src/main/java/App.java"), "class App { int value = 1; }\n");
        return source;
    }

    static JsonArray edits(String path, String oldText, String newText) {
        JsonObject edit = new JsonObject();
        edit.addProperty("path", path);
        edit.addProperty("old_text", oldText);
        edit.addProperty("new_text", newText);
        JsonArray edits = new JsonArray();
        edits.add(edit);
        return edits;
    }

    private static String git(Path root, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_"));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
