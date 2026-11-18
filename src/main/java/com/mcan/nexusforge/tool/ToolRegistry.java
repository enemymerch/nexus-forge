package com.mcan.nexusforge.tool;


import com.google.gson.*;
import java.util.*;
public final class ToolRegistry implements AutoCloseable {
    public static final int OUTPUT_LIMIT = 6500;
    private final Map<String,Tool> tools = new LinkedHashMap<>();
    private AutoCloseable connection;
    private boolean closed;
    public ToolRegistry(AutoCloseable connection, Tool... entries) {
        this(entries);
        this.connection = Objects.requireNonNull(connection);
    }
    public ToolRegistry(Tool... entries) {
        for (Tool tool : entries) if (tools.putIfAbsent(tool.name(), tool) != null) throw new IllegalArgumentException("Duplicate tool name.");
    }
    public JsonArray definitions() {
        JsonArray result = new JsonArray();
        tools.values().forEach(t -> {
            JsonObject f = new JsonObject(); f.addProperty("name",t.name()); f.addProperty("description",t.description()); f.add("parameters",t.parameters());
            JsonObject d = new JsonObject(); d.addProperty("type","function"); d.add("function",f); result.add(d);
        });
        return result;
    }
    public String execute(String name, JsonObject args) {
        return execute(name, args, OUTPUT_LIMIT);
    }
    public String execute(String name, JsonObject args, int outputBudget) {
        int budget = Math.min(OUTPUT_LIMIT, outputBudget);
        if (budget <= 0) return "";
        try {
            if (closed) throw new IllegalStateException("Tool connection is closed.");
            Tool tool = tools.get(name);
            if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
            if (args == null || args.size() != 1 || !args.has("path") || !args.get("path").isJsonPrimitive() || !args.getAsJsonPrimitive("path").isString()) throw new IllegalArgumentException("Exactly one string parameter named path is required.");
            String result = tool.execute(args);
            return ToolOutput.bound(result, budget);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return ToolOutput.bound("ERROR: " + e.getClass().getSimpleName() + ": " + message.substring(0, Math.min(300, message.length())), budget);
        }
    }
    @Override public void close() throws Exception {
        if (!closed) {
            closed = true;
            if (connection != null) connection.close();
        }
    }
}
