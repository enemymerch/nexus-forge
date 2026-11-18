package com.mcan.nexusforge.workflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcan.nexusforge.tool.Tool;
import com.mcan.nexusforge.tool.ToolRegistry;
import com.mcan.nexusforge.workspace.Workspace;

import java.nio.file.Path;
import java.util.Set;

/** A role can read only the files named in the user task and list their ancestor directories. */
public final class ScopedReadTools {
    private ScopedReadTools() {}

    public static ToolRegistry create(Path root, Set<String> allowedFiles) throws Exception {
        Workspace workspace = new Workspace(root);
        return new ToolRegistry(tool("read_file", "Read one task-approved UTF-8 source/config file; use its evidence IDs in analysis.", path -> {
            String relative = normalized(path);
            if (!allowedFiles.contains(relative)) {
                String filename = relative.substring(relative.lastIndexOf('/') + 1);
                var candidates = allowedFiles.stream()
                        .filter(approved -> approved.equals(filename) || approved.endsWith("/" + filename)).toList();
                if (candidates.size() == 1) throw new IllegalArgumentException("Use exact approved path: " + candidates.getFirst()
                        + ". Requested path is outside this task scope.");
                throw new IllegalArgumentException("File is outside this task's read scope: " + relative);
            }
            return workspace.read(relative);
        }), tool("list_files", "List an ancestor directory of task-approved files; no source content is returned.", path -> {
            String relative = normalized(path);
            if (!relative.equals(".") && allowedFiles.stream().noneMatch(file -> file.startsWith(relative + "/")))
                throw new IllegalArgumentException("Directory is outside this task's scope.");
            return workspace.list(relative);
        }));
    }

    private static String normalized(String path) {
        Path parsed = Path.of(path.replace('\\', '/'));
        if (parsed.isAbsolute() || path.isBlank()) throw new IllegalArgumentException("Use a task-relative path.");
        for (Path part : parsed) if (part.toString().equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed.");
        String value = parsed.normalize().toString().replace('\\', '/');
        return value.isEmpty() ? "." : value;
    }

    @FunctionalInterface private interface Operation { String run(String path) throws Exception; }
    private static Tool tool(String name, String description, Operation operation) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public JsonObject parameters() {
                return JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"],\"additionalProperties\":false}").getAsJsonObject();
            }
            public String execute(JsonObject arguments) throws Exception { return operation.run(arguments.get("path").getAsString()); }
        };
    }
}
