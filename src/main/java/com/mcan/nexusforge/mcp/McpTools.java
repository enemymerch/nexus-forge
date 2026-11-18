package com.mcan.nexusforge.mcp;

import com.google.gson.*;
import com.mcan.nexusforge.tool.Tool;
import com.mcan.nexusforge.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Real LangChain4j MCP client; each connection owns one fixed workspace/scope and one local JVM. */
public final class McpTools {
    private McpTools() {}
    public static ToolRegistry connect(Path root) throws Exception { return connect(root, null); }
    public static ToolRegistry connect(Path root, Set<String> allowedFiles) throws Exception {
        JsonObject config = new JsonObject(); config.addProperty("root", root.toRealPath().toString());
        if (allowedFiles != null) config.add("allowed_files", new Gson().toJsonTree(allowedFiles));
        String encoded = Base64.getEncoder().encodeToString(config.toString().getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > 24_000) throw new IllegalArgumentException("MCP workspace configuration is too large.");
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        var transport = StdioMcpTransport.builder().command(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", classpath(), WorkspaceMcpServer.class.getName(), encoded))
                .logEvents(Boolean.getBoolean("agent.trace")).build();
        DefaultMcpClient client;
        try {
            client = DefaultMcpClient.builder().key("workspace").transport(transport)
                    .protocolVersion("2025-11-25").initializationTimeout(Duration.ofSeconds(20))
                    .toolExecutionTimeout(Duration.ofSeconds(15)).autoHealthCheck(false).build();
        } catch (Exception failure) { transport.close(); throw failure; }
        try {
            List<Tool> tools = new ArrayList<>();
            for (var specification : client.listTools()) {
                if (!Set.of("list_files", "read_file").contains(specification.name()))
                    throw new IllegalStateException("Unexpected MCP tool: " + specification.name());
                JsonObject parameters = JsonParser.parseString(specification.toJson()).getAsJsonObject().getAsJsonObject("parameters");
                tools.add(new Tool() {
                    public String name() { return specification.name(); }
                    public String description() { return specification.description(); }
                    public JsonObject parameters() { return parameters.deepCopy(); }
                    public String execute(JsonObject args) {
                        var result = client.executeTool(ToolExecutionRequest.builder().name(name()).arguments(args.toString()).build());
                        String text = result.resultText();
                        return result.isError() && !text.startsWith("ERROR:") ? "ERROR: " + text : text;
                    }
                });
            }
            if (tools.size() != 2) throw new IllegalStateException("MCP server must expose exactly the two read-only workspace tools.");
            return new ToolRegistry(client, tools.toArray(Tool[]::new));
        } catch (Exception failure) { client.close(); throw failure; }
    }

    /** Works with Maven exec's URLClassLoader, Surefire and a normal java -cp launch. */
    private static String classpath() throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            if (!entry.isBlank()) entries.add(Path.of(entry).toAbsolutePath().toString());
        for (ClassLoader loader = McpTools.class.getClassLoader(); loader != null; loader = loader.getParent())
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs())
                if ("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
        return String.join(File.pathSeparator, entries);
    }
}
