package com.mcan.nexusforge.mcp;

import com.google.gson.*;
import com.mcan.nexusforge.tool.ToolRegistry;
import com.mcan.nexusforge.tool.project.ProjectTools;
import com.mcan.nexusforge.workflow.ScopedReadTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Read-only MCP server. stdout is reserved exclusively for the SDK's JSON-RPC transport. */
public final class WorkspaceMcpServer {
    private WorkspaceMcpServer() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].length() > 48_000) throw new IllegalArgumentException("One bounded workspace configuration is required.");
        JsonObject config = JsonParser.parseString(new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        Path root = Path.of(config.get("root").getAsString());
        ToolRegistry tools;
        if (config.has("allowed_files")) {
            Set<String> allowed = new LinkedHashSet<>();
            for (JsonElement file : config.getAsJsonArray("allowed_files")) allowed.add(file.getAsString());
            tools = ScopedReadTools.create(root, Set.copyOf(allowed));
        } else tools = new ProjectTools(root).registry();
        var mapper = McpJsonDefaults.getMapper();
        CountDownLatch eof = new CountDownLatch(1);
        var input = new FilterInputStream(System.in) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                if (count < 0) eof.countDown();
                return count;
            }
            @Override public int read() throws IOException {
                int value = super.read(); if (value < 0) eof.countDown(); return value;
            }
        };
        var specification = McpServer.sync(new StdioServerTransportProvider(mapper, input, System.out))
                .serverInfo("nexus-forge-workspace", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for (JsonElement definition : tools.definitions()) {
            JsonObject function = definition.getAsJsonObject().getAsJsonObject("function");
            String name = function.get("name").getAsString();
            var tool = McpSchema.Tool.builder().name(name).description(function.get("description").getAsString())
                    .inputSchema(mapper, function.get("parameters").toString()).build();
            specification.toolCall(tool, (exchange, request) -> {
                JsonObject arguments = request.arguments() == null ? null : new Gson().toJsonTree(request.arguments()).getAsJsonObject();
                String result = tools.execute(name, arguments);
                return McpSchema.CallToolResult.builder().addTextContent(result).isError(result.startsWith("ERROR:")).build();
            });
        }
        var server = specification.build();
        try (tools) { eof.await(); }
        finally { server.close(); }
    }
}
