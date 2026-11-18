package com.mcan.nexusforge.tool.project;

import com.mcan.nexusforge.tool.Tool;
import com.mcan.nexusforge.tool.ToolRegistry;
import com.mcan.nexusforge.workspace.Workspace;
import com.google.gson.*;
import java.nio.file.Path;
public final class ProjectTools {
    private final ToolRegistry registry;
    public ProjectTools(Path root) throws Exception { this(new Workspace(root)); }
    public ProjectTools(Workspace workspace) {
        registry = new ToolRegistry(tool("list_files", "List immediate children as JSON entries; use . for root. Names only, no content evidence.", workspace::list), tool("read_file", "Read UTF-8 as JSON source lines. Use Java-issued line IDs as evidence_ids in findings. truncated means remaining lines are unseen.", workspace::read));
    }
    private interface Operation { String run(String path) throws Exception; }
    private static Tool tool(String name, String description, Operation operation) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public JsonObject parameters() { return JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"],\"additionalProperties\":false}").getAsJsonObject(); }
            public String execute(JsonObject args) throws Exception { return operation.run(args.get("path").getAsString()); }
        };
    }
    public ToolRegistry registry() { return registry; }
    public JsonArray definitions() { return registry.definitions(); }
    public String execute(String name, JsonObject args) { return registry.execute(name,args); }
}
