package com.mcan.nexusforge.agent;

import com.google.gson.*;
import com.mcan.nexusforge.tool.project.ProjectTools;
import com.mcan.nexusforge.llm.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairContextTest {
    @TempDir Path root;
    @Test void repairsUseFreshSourceAndKeepAuditAndCumulativeBudgets() throws Exception {
        Files.writeString(root.resolve("Source.kt"), "val value = 1\n");
        List<JsonArray> requests = new ArrayList<>();
        ChatModel model = (messages, tools) -> {
            requests.add(messages.deepCopy());
            JsonObject answer = new JsonObject();
            answer.addProperty("role", "assistant"); answer.addProperty("content", "{\"summary\":\"proposal\"}");
            return answer;
        };
        try (var session = new RoleSession("Developer", "Write approved code.", model,
                new ProjectTools(root).registry(), 4, 2, 8000, true)) {
            JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}}}").getAsJsonObject();
            session.request("INITIAL_TASK_MARKER", schema, Set.of("Source.kt"));
            Files.writeString(root.resolve("Source.kt"), "val value = 2\n");
            session.request("Repair the actual compiler error.", schema, Set.of("Source.kt"));
            String repair = requests.get(1).toString();
            assertFalse(repair.contains("INITIAL_TASK_MARKER"));
            assertFalse(repair.contains("val value = 1"));
            assertTrue(repair.contains("val value = 2"));
            assertTrue(repair.contains("Write approved code."));
            assertTrue(session.historySnapshot().toString().contains("INITIAL_TASK_MARKER"));
            assertEquals(2, session.steps()); assertEquals(2, session.toolCalls());
            assertThrows(IllegalStateException.class, () -> session.request("Another repair", schema, Set.of("Source.kt")));
        }
    }
}
