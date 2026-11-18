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
import static com.mcan.nexusforge.workspace.run.IsolatedWorkspaceTest.edits;

class PatchApplierTest {
    @TempDir Path directory;
    private static final String FILE = "src/main/java/App.java";

    @Test void exactReplacementPreservesUtf8LineEndingsAndOriginal() throws Exception {
        IsolatedWorkspace workspace = workspace("class App { String value = \"çalışma\"; }\r\n");
        PatchApplier applier = new PatchApplier(workspace, Set.of(FILE), 1, 1000);
        assertEquals(List.of(FILE), applier.apply(edits(FILE, "çalışma", "öğrenme")));
        assertEquals("class App { String value = \"öğrenme\"; }\r\n", Files.readString(workspace.root().resolve(FILE)));
        assertEquals("class App { String value = \"çalışma\"; }\r\n", Files.readString(workspace.source().resolve(FILE)));
        assertTrue(workspace.originalUnchanged());
        assertTrue(workspace.diff().contains("öğrenme"));
    }

    @Test void frontendSourceEditsRemainScopedAndCannotModifyTooling() throws Exception {
        IsolatedWorkspace copy = workspace("class App {}\n");
        String page = "frontend/src/features/categories/Page.tsx";
        new PatchApplier(copy, Set.of(page), 1, 1000).apply(edits(page, "", "export const Page = () => <h1>Categories</h1>;\n"));
        assertTrue(Files.readString(copy.root().resolve(page)).contains("Categories"));
        assertTrue(copy.originalUnchanged());
        for (String denied : List.of("frontend/package.json", "frontend/tools/verify.mjs", "frontend/vite.config.ts", "frontend/src/secrets.ts", "elsewhere/src/Page.tsx"))
            assertThrows(IllegalArgumentException.class, () -> new PatchApplier(copy, Set.of(denied), 1, 1000), denied);
    }

    @Test void rejectsOutOfScopeTraversalBuildConfigAndDuplicatePaths() throws Exception {
        IsolatedWorkspace workspace = workspace("class App {}\n");
        PatchApplier applier = new PatchApplier(workspace, Set.of(FILE), 2, 1000);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits("src/main/java/Other.java", "", "class Other {}")));
        for (String path : List.of("../src/App.java", "src/../App.java", "C:/src/App.java", "src//App.java", "src/build/App.java",
                "src/main/resources/application.yml", "pom.xml", "README.md", "src/main/java/Password.java")) {
            assertThrows(IllegalArgumentException.class, () -> new PatchApplier(workspace, Set.of(path), 1, 1000), path);
        }
        JsonArray duplicate = edits(FILE, "App", "Changed");
        duplicate.add(edits(FILE, "App", "ChangedAgain").get(0));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(duplicate));
        assertTrue(workspace.changedFiles().isEmpty());
    }

    @Test void adaptsModelLfAnchorsAndReplacementsToConsistentCrLfSource() throws Exception {
        IsolatedWorkspace workspace = workspace("class App {\r\n    int value = 1;\r\n}\r\n");
        PatchApplier applier = new PatchApplier(workspace, Set.of(FILE), 1, 1000);
        applier.apply(edits(FILE, "class App {\n    int value = 1;\n}", "class App {\n    int value = 2;\n}"));
        assertEquals("class App {\r\n    int value = 2;\r\n}\r\n", Files.readString(workspace.root().resolve(FILE)));
        assertTrue(workspace.originalUnchanged());
    }

    @Test void rejectsStaleAmbiguousEmptyAndMalformedEdits() throws Exception {
        IsolatedWorkspace workspace = workspace("class App { int x = 1; int y = 1; }\n");
        PatchApplier applier = new PatchApplier(workspace, Set.of(FILE), 2, 1000);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits(FILE, "int z = 1", "int z = 2")));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits(FILE, "= 1", "= 2")));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits(FILE, "", "overwrite")));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits(FILE, "App", "App")));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(new JsonArray()));
        JsonArray malformed = edits(FILE, "App", "Changed");
        malformed.get(0).getAsJsonObject().addProperty("extra", "unexpected");
        assertThrows(IllegalArgumentException.class, () -> applier.apply(malformed));
        JsonArray nonString = edits(FILE, "App", "Changed");
        nonString.get(0).getAsJsonObject().addProperty("old_text", 42);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(nonString));
        assertTrue(workspace.changedFiles().isEmpty());
    }

    @Test void validatesTheWholeBatchBeforeApplyingAnyChange() throws Exception {
        IsolatedWorkspace workspace = workspace("class App {}\n");
        String second = "src/main/java/Second.java";
        PatchApplier applier = new PatchApplier(workspace, Set.of(FILE, second), 2, 1000);
        JsonArray batch = edits(FILE, "App", "Changed");
        batch.add(edits(second, "non-empty-old-text", "class Second {}").get(0));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(batch));
        assertEquals("class App {}\n", Files.readString(workspace.root().resolve(FILE)));
        assertFalse(Files.exists(workspace.root().resolve(second)));
        assertTrue(workspace.changedFiles().isEmpty());
    }

    @Test void enforcesFileAndChangedCharacterLimits() throws Exception {
        IsolatedWorkspace workspace = workspace("class App {}\n");
        PatchApplier applier = new PatchApplier(workspace, Set.of(FILE), 1, 5);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits(FILE, "App", "Changed")));
        JsonArray tooMany = edits(FILE, "App", "B");
        tooMany.add(new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> applier.apply(tooMany));
        assertTrue(workspace.changedFiles().isEmpty());
    }

    @Test void newFilesRequireAbsentTargetEmptyOldTextAndNonBlankContent() throws Exception {
        IsolatedWorkspace workspace = workspace("class App {}\n");
        String path = "nested/src/test/kotlin/ExampleTest.kt";
        PatchApplier applier = new PatchApplier(workspace, Set.of(path), 1, 1000);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits(path, "previous", "class ExampleTest {}")));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(edits(path, "", "   ")));
        applier.apply(edits(path, "", "class ExampleTest {}\n"));
        assertEquals("class ExampleTest {}\n", Files.readString(workspace.root().resolve(path)));
        assertTrue(workspace.diff().contains("+class ExampleTest {}"));
        assertTrue(workspace.originalUnchanged());
    }

    @Test void rejectsLinkedPatchTargetsWhenSupported() throws Exception {
        IsolatedWorkspace workspace = workspace("class App {}\n");
        Path outside = directory.resolve("Outside.java");
        Files.writeString(outside, "class Outside {}\n");
        Path target = workspace.root().resolve(FILE);
        Files.delete(target);
        try { Files.createSymbolicLink(target, outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic link creation is unavailable: " + unavailable.getMessage());
        }
        PatchApplier applier = new PatchApplier(workspace, Set.of(FILE), 1, 1000);
        assertThrows(IOException.class, () -> applier.apply(edits(FILE, "Outside", "Changed")));
        assertEquals("class Outside {}\n", Files.readString(outside));
    }

    private IsolatedWorkspace workspace(String contents) throws IOException {
        Path source = directory.resolve("source");
        Files.createDirectories(source.resolve("src/main/java"));
        Files.writeString(source.resolve(FILE), contents);
        return IsolatedWorkspace.create(source, directory.resolve("runs"));
    }
}
