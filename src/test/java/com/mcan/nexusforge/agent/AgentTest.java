package com.mcan.nexusforge.agent;

import com.mcan.nexusforge.workspace.Workspace;
import com.mcan.nexusforge.tool.project.ProjectTools;
import com.mcan.nexusforge.tool.Tool;
import com.mcan.nexusforge.tool.ToolRegistry;
import com.mcan.nexusforge.llm.ollama.OllamaClient;
import com.mcan.nexusforge.llm.ChatModel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
class AgentTest {
    @TempDir Path root;
    JsonObject args(String path) { JsonObject a=new JsonObject(); a.addProperty("path",path); return a; }
    @Test void workspaceBoundariesAndExclusions() throws Exception {
        Workspace w=new Workspace(root);
        Files.writeString(root.resolve("README.md"),"hello");
        assertTrue(w.read("README.md").contains("hello"));
        assertThrows(Exception.class,()->w.resolve("../outside"));
        assertThrows(Exception.class,()->w.resolve(root.toString()));
        for (String name: new String[]{".env","private.key","credentials.json","app.jar"}) {
            Files.writeString(root.resolve(name),"secret");
            assertThrows(Exception.class,()->w.read(name));
            assertFalse(w.list(".").contains(name));
        }
        Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve("target/log.txt"),"x");
        assertThrows(Exception.class,()->w.read("target/log.txt"));
        Files.write(root.resolve("binary.txt"),new byte[]{0,1,2});
        assertThrows(Exception.class,()->w.read("binary.txt"));
        Files.writeString(root.resolve("large.txt"),"a".repeat(64001));
        assertThrows(Exception.class,()->w.read("large.txt"));
        Files.writeString(root.resolve("cut.txt"),"a".repeat(7000));
        assertTrue(JsonParser.parseString(w.read("cut.txt")).getAsJsonObject().get("truncated").getAsBoolean());
        assertTrue(w.read("cut.txt").length()<6500);
    }
    @Test void argumentsAndUnknownTools() throws Exception {
        var t=new ProjectTools(root);
        assertTrue(t.execute("read_file",new JsonObject()).startsWith("ERROR:"));
        JsonObject numeric=new JsonObject(); numeric.addProperty("path",3);
        assertTrue(t.execute("read_file",numeric).startsWith("ERROR:"));
        assertTrue(t.execute("exec",args(".")).startsWith("ERROR:"));
        assertTrue(t.execute("read_file",args("missing")).startsWith("ERROR:"));
    }
    @Test void realPathEscape() throws Exception {
        Path outside=Files.createTempDirectory("analyst-outside");
        try {
            try { Files.createSymbolicLink(root.resolve("link"),outside); }
            catch (java.io.IOException | UnsupportedOperationException e) { Assumptions.abort("Windows symlink izni yok: " + e.getClass().getSimpleName()); }
            assertThrows(Exception.class,()->new Workspace(root).resolve("link"));
        } finally { Files.deleteIfExists(outside); }
    }
    @Test void hardLimitsAndSeparateHistories() throws Exception {
        AtomicInteger executed=new AtomicInteger();
        Tool tool=new Tool() {
            public String name(){return "list_files";} public String description(){return "test";}
            public JsonObject parameters(){return new JsonObject();}
            public String execute(JsonObject a){executed.incrementAndGet(); return "ok";}
        };
        AtomicInteger steps=new AtomicInteger();
        ChatModel model=(messages,tools)-> {
            steps.incrementAndGet();
            JsonObject r=JsonParser.parseString("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"list_files\",\"arguments\":{\"path\":\".\"}}},{\"function\":{\"name\":\"list_files\",\"arguments\":{\"path\":\".\"}}}]}").getAsJsonObject();
            return r;
        };
        assertThrows(IllegalStateException.class,()->new AgentRunner(model,new ToolRegistry(tool),3,1).run());
        assertEquals(1,executed.get()); assertEquals(3,steps.get());
        for (int i=0;i<2;i++) {
            AtomicInteger modelCalls = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> new AgentRunner((h,t)-> {
                if (modelCalls.getAndIncrement()==0) assertEquals(2,h.size());
                JsonObject r=new JsonObject(); r.addProperty("content","{\"findings\":[]}"); return r;
            },new ToolRegistry(tool),2,1).run());
        }
    }
    @Test void rejectRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class,()->new OllamaClient("qwen3:8b",URI.create("https://example.com/api/chat")));
    }
}
