package com.mcan.nexusforge.workspace.run;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Applies exact, explicitly scoped source edits in the isolated copy only. */
public final class PatchApplier {
    public static String validateApprovedPath(String path) { return RunFilePolicy.sourcePath(path); }
    private final IsolatedWorkspace workspace;
    private final Set<String> allowedPaths;
    private final int maxFiles;
    private final int maxChangedChars;

    public PatchApplier(IsolatedWorkspace workspace, Set<String> allowedPaths, int maxFiles, int maxChangedChars) {
        if (workspace == null || allowedPaths == null || allowedPaths.isEmpty()) throw new IllegalArgumentException("Workspace and approved source paths are required.");
        if (maxFiles < 1 || maxFiles > 32 || maxChangedChars < 1 || maxChangedChars > 200_000)
            throw new IllegalArgumentException("Patch limits must be 1..32 files and 1..200000 changed characters.");
        Set<String> paths = new HashSet<>();
        for (String path : allowedPaths) paths.add(RunFilePolicy.sourcePath(path));
        this.workspace = workspace;
        this.allowedPaths = Set.copyOf(paths);
        this.maxFiles = maxFiles;
        this.maxChangedChars = maxChangedChars;
    }

    public synchronized List<String> apply(JsonArray edits) throws IOException {
        if (edits == null || edits.isEmpty() || edits.size() > maxFiles)
            throw new IllegalArgumentException("Patch must contain 1.." + maxFiles + " source edits.");
        List<Edit> planned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long changedChars = 0;
        for (JsonElement element : edits) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Each edit must be an object.");
            JsonObject edit = element.getAsJsonObject();
            if (!edit.keySet().equals(Set.of("path", "old_text", "new_text")))
                throw new IllegalArgumentException("Each edit requires only path, old_text and new_text string fields.");
            String relative = RunFilePolicy.sourcePath(string(edit, "path"));
            if (!allowedPaths.contains(relative)) throw new IllegalArgumentException("Path is outside the approved change scope: " + relative);
            if (!seen.add(relative.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Duplicate edit path: " + relative);
            String oldText = string(edit, "old_text");
            String newText = string(edit, "new_text");
            if (oldText.equals(newText)) throw new IllegalArgumentException("An edit must change file content.");
            changedChars += (long) oldText.length() + newText.length();
            if (changedChars > maxChangedChars) throw new IllegalArgumentException("Patch exceeds its " + maxChangedChars + "-character edit budget.");
            RunFilePolicy.text(RunFilePolicy.utf8(oldText));
            RunFilePolicy.text(RunFilePolicy.utf8(newText));
            Path target = RunFilePolicy.safeTarget(workspace.root(), relative);
            byte[] original = null;
            String result;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > RunFilePolicy.MAX_FILE_BYTES)
                    throw new IllegalArgumentException("Target must be a bounded regular source file.");
                original = Files.readAllBytes(target);
                String text = RunFilePolicy.text(original);
                // read_file exposes logical lines. Preserve a consistently CRLF source even
                // when the model reconstructs those lines with LF in its JSON arguments.
                if (text.contains("\r\n") && !text.replace("\r\n", "").contains("\n")) {
                    oldText = crlf(oldText);
                    newText = crlf(newText);
                }
                if (oldText.isEmpty()) throw new IllegalArgumentException("old_text must be non-empty for an existing file.");
                int position = text.indexOf(oldText);
                if (position < 0) throw new IllegalArgumentException("old_text does not match the current file: " + relative);
                if (text.indexOf(oldText, position + 1) >= 0) throw new IllegalArgumentException("old_text is ambiguous; include more surrounding context: " + relative);
                result = text.substring(0, position) + newText + text.substring(position + oldText.length());
            } else {
                if (!oldText.isEmpty() || newText.isBlank()) throw new IllegalArgumentException("A new file requires empty old_text and non-blank new_text.");
                result = newText;
            }
            byte[] updated = RunFilePolicy.utf8(result);
            if (original != null && Arrays.equals(original, updated))
                throw new IllegalArgumentException("An edit must change file content after newline adaptation.");
            if (updated.length > RunFilePolicy.MAX_FILE_BYTES) throw new IllegalArgumentException("Updated source exceeds the 1 MiB file limit.");
            planned.add(new Edit(relative, target, original, updated));
        }

        // All arguments and matches are checked before touching any destination.
        List<Path> staged = new ArrayList<>();
        LinkedHashSet<Path> createdDirectories = new LinkedHashSet<>();
        List<Edit> applied = new ArrayList<>();
        try {
            for (Edit edit : planned) {
                requireCurrent(edit);
                createParents(edit.target().getParent(), createdDirectories);
                Path temporary = Files.createTempFile(edit.target().getParent(), ".agent-edit-", ".tmp");
                staged.add(temporary);
                Files.write(temporary, edit.updated());
            }
            for (int i = 0; i < planned.size(); i++) {
                Edit edit = planned.get(i);
                requireCurrent(edit);
                move(staged.get(i), edit.target());
                applied.add(edit);
            }
        } catch (IOException | RuntimeException failure) {
            for (int i = applied.size() - 1; i >= 0; i--) {
                Edit edit = applied.get(i);
                try {
                    RunFilePolicy.safeTarget(workspace.root(), edit.relative());
                    if (edit.original() == null) Files.deleteIfExists(edit.target());
                    else Files.write(edit.target(), edit.original());
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        } finally {
            for (Path temporary : staged) Files.deleteIfExists(temporary);
            List<Path> reverse = new ArrayList<>(createdDirectories);
            for (int i = reverse.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(reverse.get(i)); } catch (java.nio.file.DirectoryNotEmptyException ignored) { }
            }
        }
        return planned.stream().map(Edit::relative).toList();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(name + " must be a JSON string.");
        return value.getAsString();
    }

    private static String crlf(String text) {
        return text.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private void requireCurrent(Edit edit) throws IOException {
        Path checked = RunFilePolicy.safeTarget(workspace.root(), edit.relative());
        if (!checked.equals(edit.target())) throw new IOException("Target path changed while preparing the patch.");
        if (edit.original() == null) {
            if (Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) throw new IOException("New target appeared while preparing the patch.");
        } else if (!Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS) || Files.size(checked) > RunFilePolicy.MAX_FILE_BYTES
                || !Arrays.equals(edit.original(), Files.readAllBytes(checked))) {
            throw new IOException("Source changed while preparing the patch; request a fresh read: " + edit.relative());
        }
    }

    private void createParents(Path parent, Set<Path> created) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path cursor = parent;
        while (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(cursor);
            cursor = cursor.getParent();
        }
        RunFilePolicy.requireNoLinks(cursor);
        if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Target parent is not a directory.");
        for (int i = missing.size() - 1; i >= 0; i--) {
            Files.createDirectory(missing.get(i));
            created.add(missing.get(i));
        }
        RunFilePolicy.requireNoLinks(parent);
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private record Edit(String relative, Path target, byte[] original, byte[] updated) { }
}
