package com.mcan.nexusforge.mcp;

import com.google.gson.*;
import com.mcan.nexusforge.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class McpToolsTest {
    @TempDir Path temporary;
    private static JsonObject path(String path) { JsonObject args = new JsonObject(); args.addProperty("path", path); return args; }

    @Test void discoversAndCallsRealStdioServerWithUtf8AndWorkspaceGuards() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repository with spaces"));
        Files.writeString(root.resolve("README.md"), "MCP integration\nTurkish text: \u0131\u015f\u011f\u00fc\u015e\n");
        Files.writeString(root.resolve(".env"), "PRIVATE_VALUE");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/output.txt"), "BUILD_VALUE");
        Files.write(root.resolve("data.bin"), new byte[]{0, 1, 2});
        Files.writeString(temporary.resolve("outside.txt"), "OUTSIDE_VALUE");
        ToolRegistry tools = McpTools.connect(root);
        try (tools) {
            assertEquals(2, tools.definitions().size());
            String listing = tools.execute("list_files", path("."));
            assertTrue(listing.contains("README.md"), listing);
            assertFalse(listing.contains(".env")); assertFalse(listing.contains("build")); assertFalse(listing.contains("data.bin"));
            String read = tools.execute("read_file", path("README.md"));
            assertTrue(read.contains("MCP integration"), read);
            assertTrue(read.contains("\u0131\u015f\u011f\u00fc\u015e"), read);
            for (String denied : new String[]{"../outside.txt", ".env", "build/output.txt", "data.bin", "missing.md"}) {
                String result = tools.execute("read_file", path(denied));
                assertTrue(result.startsWith("ERROR:"), denied + ": " + result);
                assertFalse(result.contains("PRIVATE_VALUE")); assertFalse(result.contains("OUTSIDE_VALUE"));
            }
            assertTrue(tools.execute("write_file", path("README.md")).startsWith("ERROR:"));
            JsonObject invalid = path("README.md"); invalid.addProperty("extra", "bad");
            assertTrue(tools.execute("read_file", invalid).contains("Exactly one string"));
        }
        assertTrue(tools.execute("read_file", path("README.md")).contains("closed"));
    }

    @Test void keepsServerSideRoleScopesAndConnectionsIndependent() throws Exception {
        Files.createDirectories(temporary.resolve("src"));
        Files.writeString(temporary.resolve("src/Allowed.java"), "class Allowed {}\n");
        Files.writeString(temporary.resolve("src/Hidden.java"), "class Hidden {}\n");
        try (var restricted = McpTools.connect(temporary, Set.of("src/Allowed.java"));
             var separate = McpTools.connect(temporary, Set.of("src/Hidden.java"))) {
            assertTrue(restricted.execute("read_file", path("src/Allowed.java")).contains("class Allowed"));
            assertTrue(restricted.execute("read_file", path("src/Hidden.java")).startsWith("ERROR:"));
            assertTrue(separate.execute("read_file", path("src/Hidden.java")).contains("class Hidden"));
            assertTrue(separate.execute("read_file", path("src/Allowed.java")).startsWith("ERROR:"));
            assertTrue(restricted.execute("read_file", path("Allowed.java")).contains("Use exact approved path: src/Allowed.java"));
            assertTrue(restricted.execute("list_files", path(".")).contains("src"));
        }
    }

    @Test void boundsContentAcrossProtocolAndPreservesCompleteJsonRows() throws Exception {
        Files.writeString(temporary.resolve("large.txt"), ("source line " + "x".repeat(100) + "\n").repeat(200));
        try (var tools = McpTools.connect(temporary)) {
            String result = tools.execute("read_file", path("large.txt"), 700);
            assertTrue(result.length() <= 700, result);
            JsonObject read = JsonParser.parseString(result).getAsJsonObject();
            assertTrue(read.get("truncated").getAsBoolean());
            assertFalse(read.getAsJsonArray("lines").isEmpty());
        }
    }
}
