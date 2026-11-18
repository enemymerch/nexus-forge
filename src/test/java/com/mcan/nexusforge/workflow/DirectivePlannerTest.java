package com.mcan.nexusforge.workflow;

import com.google.gson.*;
import com.mcan.nexusforge.agent.RoleSession;
import com.mcan.nexusforge.tool.ToolRegistry;
import com.mcan.nexusforge.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectivePlannerTest {
    @TempDir Path root;
    private Workspace fixture() throws Exception {
        Files.createDirectories(root.resolve("repo/module/src/main/java/demo"));
        Files.writeString(root.resolve("repo/module/src/main/java/demo/Counter.java"), "package demo; class Counter {}\n");
        Files.writeString(root.resolve("repo/module/build.gradle.kts"), "plugins { java }\n");
        return new Workspace(root.resolve("repo"));
    }
    private RoleSession analyst(Workspace workspace) throws Exception {
        RoleSession role = new RoleSession("Analyst", "Plan work.", (messages, tools) -> new JsonObject(), new ToolRegistry(), 4, 4, 8000);
        role.evidence().deliver("read_file", JsonParser.parseString("{\"path\":\"module/src/main/java/demo/Counter.java\"}").getAsJsonObject(),
                workspace.read("module/src/main/java/demo/Counter.java"), 2000);
        return role;
    }
    private JsonObject proposal() {
        return JsonParser.parseString("""
                {"name":"Counter","goal":"Fix the counter","project_type":"jvm",
                "read_files":["module/src/main/java/demo/Counter.java"],
                "write_files":["module/src/main/java/demo/Counter.java","module/src/test/java/demo/CounterTest.java"],
                "acceptance_criteria":["Regression test detects original behavior"],"gradle_task":":module:test",
                "test_filter":"demo.CounterTest","evidence_ids":["E1"]}
                """).getAsJsonObject();
    }
    @Test void validatesObservedPlanThroughStrictTaskParser() throws Exception {
        Workspace workspace = fixture();
        try (RoleSession analyst = analyst(workspace)) {
            var task = DirectivePlanner.validate(proposal(), analyst, workspace, root.resolve("task.json"));
            assertEquals(3, task.maxAttempts());
            assertEquals(2, task.writeFiles().size());
            assertTrue(Files.isRegularFile(root.resolve("task.json")));
        }
    }

    @Test void directiveFlowsThroughAnalystMcpInspectionToValidatedHandoff() throws Exception {
        Workspace workspace = fixture();
        com.mcan.nexusforge.llm.ChatModel model = (messages, tools) -> {
            if (!tools.isEmpty() && messages.toString().contains("\"role\":\"tool\""))
                return JsonParser.parseString("{\"role\":\"assistant\",\"content\":\"Ready to plan.\"}").getAsJsonObject();
            if (!tools.isEmpty()) return JsonParser.parseString("""
                    {"role":"assistant","content":"","tool_calls":[{"function":{
                    "name":"read_file","arguments":{"path":"module/src/main/java/demo/Counter.java"}}}]}
                    """).getAsJsonObject();
            assertTrue(messages.toString().contains("class Counter"));
            assertTrue(messages.toString().contains("User's exact directive"));
            JsonObject reply = new JsonObject(); reply.addProperty("role", "assistant");
            reply.addProperty("content", proposal().toString()); return reply;
        };
        Path artifacts = root.resolve("planning");
        var task = DirectivePlanner.plan(model, workspace.root(), "User's exact directive", artifacts);
        assertEquals("User's exact directive", task.goal());
        assertEquals(":module:test", task.gradleTask());
        assertTrue(Files.readString(artifacts.resolve("analyst-history.json")).contains("class Counter"));
        assertTrue(Files.readString(artifacts.resolve("evidence.txt")).contains("Counter.java"));
    }
    @Test void rejectsUnobservedEvidenceAndInjectedCommands() throws Exception {
        Workspace workspace = fixture();
        try (RoleSession analyst = analyst(workspace)) {
            JsonObject unknown = proposal(); unknown.add("evidence_ids", JsonParser.parseString("[\"E999\"]"));
            assertThrows(IllegalArgumentException.class, () -> DirectivePlanner.validate(unknown, analyst, workspace, root.resolve("bad.json")));
            JsonObject command = proposal(); command.addProperty("gradle_task", ":module:test && whoami");
            assertThrows(IllegalArgumentException.class, () -> DirectivePlanner.validate(command, analyst, workspace, root.resolve("bad.json")));
            JsonObject module = proposal(); module.addProperty("gradle_task", ":missing:test");
            assertThrows(Exception.class, () -> DirectivePlanner.validate(module, analyst, workspace, root.resolve("bad.json")));
            JsonObject secret = proposal();
            secret.add("write_files", JsonParser.parseString("[\"module/src/main/java/demo/Secret.java\",\"module/src/test/java/demo/CounterTest.java\"]"));
            assertThrows(IllegalArgumentException.class, () -> DirectivePlanner.validate(secret, analyst, workspace, root.resolve("bad.json")));
        }
    }
    @Test void indexExcludesCredentialsBuildsAndOutsideLinks() throws Exception {
        Workspace workspace = fixture();
        Files.createDirectories(workspace.root().resolve("build"));
        Files.writeString(workspace.root().resolve("build/Generated.java"), "private");
        Files.writeString(workspace.root().resolve("module/src/main/java/demo/Secret.java"), "private");
        String index = DirectivePlanner.index(workspace);
        assertTrue(index.contains("Counter.java"));
        assertFalse(index.contains("Generated.java"));
        assertFalse(index.contains("Secret.java"));
        assertTrue(index.length() < 2600);
    }
}
