package com.mcan.nexusforge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleFileCodeModelTest {
    private static final String PATH = "module/src/main/kotlin/demo/Rule.kt";
    @TempDir Path temporary;

    @Test void rawCodePreservesLiteralsAndUsesExactlyOneExplicitNullSchemaCall() throws Exception {
        String code = "\npackage demo\r\nclass Rule { val regex = Regex(\"^[a-z0-9_\\\\-]{2,64}$\") }\r\n";
        RecordingModel delegate = new RecordingModel(code);
        JsonArray messages = messages();
        JsonObject schema = schema(PATH);
        JsonObject result = new SingleFileCodeModel(delegate, temporary.resolve("audit")).chat(messages, new JsonArray(), schema);
        assertEquals(code, proposedContent(result));
        assertEquals(PATH, proposal(result).getAsJsonArray("edits").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(1, delegate.structuredCalls);
        assertEquals(0, delegate.ordinaryCalls);
        assertNull(delegate.schema);
        assertTrue(delegate.tools.isEmpty());
        assertNotSame(messages, delegate.messages);
        assertEquals(1, messages.size());
        assertTrue(delegate.messages.toString().contains("RAW_CODE"));
        assertTrue(delegate.messages.toString().contains(PATH));
        assertTrue(proposal(result).get("summary").getAsString().startsWith("Complete source proposed"));
        assertEquals(code, Files.readString(temporary.resolve("audit/001.txt")));
        JsonObject archived = JsonParser.parseString(Files.readString(temporary.resolve("audit/001-request.json"))).getAsJsonObject();
        assertEquals(delegate.messages, archived.getAsJsonArray("messages"));
        assertTrue(archived.get("response_schema").isJsonNull());
        assertEquals("RAW_CODE", archived.get("protocol").getAsString());
    }

    @Test void optionalFileHeaderAndOneFencePreserveExactSourceAndFinalNewline() throws Exception {
        String code = "package demo\r\nclass Rule { val text = \"\\\\n\" }\r\n";
        List<String> variants = List.of("FILE: " + PATH + "\r\n" + code,
                "```kotlin\r\n" + code + "```\r\n",
                "FILE: " + PATH + "\n```\n" + code + "```",
                "```java\nFILE: " + PATH + "\n" + code + "```\n");
        int index = 0;
        for (String raw : variants) {
            RecordingModel delegate = new RecordingModel(raw);
            JsonObject result = new SingleFileCodeModel(delegate, temporary.resolve("case" + index++)).chat(messages(), new JsonArray(), schema(PATH));
            assertEquals(code, proposedContent(result));
            assertEquals(1, delegate.structuredCalls);
        }
    }

    @Test void unfencedSourceDoesNotGainAFinalNewline() throws Exception {
        String code = "package demo\nclass Rule {}";
        JsonObject result = new SingleFileCodeModel(new RecordingModel(code), temporary).chat(messages(), new JsonArray(), schema(PATH));
        assertEquals(code, proposedContent(result));
    }

    @Test void acceptsFrontendFenceWithoutChangingJsxOrTemplateLiterals() throws Exception {
        String path = "frontend/src/Page.tsx";
        String code = "export const Page = () => <h1>{`Category ${1}`}</h1>;\n";
        for (String language : List.of("tsx", "typescript", "ts")) {
            JsonObject result = new SingleFileCodeModel(new RecordingModel("```" + language + "\n" + code + "```"),
                    temporary.resolve(language)).chat(messages(), new JsonArray(), schema(path));
            assertEquals(code, proposedContent(result));
        }
    }

    @Test void wrongPathAndJsonResponsesAreRejectedAndOriginalRawReplyIsArchived() throws Exception {
        int index = 0;
        for (String raw : List.of("FILE: elsewhere/Rule.kt\npackage demo\nclass Rule {}\n", "  {\"edits\":[]}")) {
            Path archive = temporary.resolve("case" + index++);
            RecordingModel delegate = new RecordingModel(raw);
            assertThrows(IllegalArgumentException.class,
                    () -> new SingleFileCodeModel(delegate, archive).chat(messages(), new JsonArray(), schema(PATH)));
            assertEquals(1, delegate.structuredCalls);
            assertEquals(raw, Files.readString(archive.resolve("001.txt")));
        }
    }

    @Test void danglingMultipleAndUnsupportedFencesAreRejectedWithoutRetries() {
        int index = 0;
        for (String raw : List.of("```kotlin\nclass Rule {}", "class Rule {}\n```",
                "```kotlin\nclass Rule {}\n```\n```java\nclass Other {}\n```",
                "```json\n{}\n```", "```kotlin\nclass Rule {}\n```\nExplanation")) {
            RecordingModel delegate = new RecordingModel(raw);
            Path archive = temporary.resolve("case" + index++);
            assertThrows(IllegalArgumentException.class,
                    () -> new SingleFileCodeModel(delegate, archive).chat(messages(), new JsonArray(), schema(PATH)));
            assertEquals(1, delegate.structuredCalls);
        }
    }

    @Test void emptyAndOversizedSourceAreRejected() {
        int index = 0;
        for (String raw : List.of(" \n\t", "x".repeat(8_001))) {
            RecordingModel delegate = new RecordingModel(raw);
            Path archive = temporary.resolve("case" + index++);
            assertThrows(IllegalArgumentException.class,
                    () -> new SingleFileCodeModel(delegate, archive).chat(messages(), new JsonArray(), schema(PATH)));
            assertEquals(1, delegate.structuredCalls);
        }
    }

    @Test void ordinaryCallsAndOtherSchemasAreForwardedUnchanged() throws Exception {
        RecordingModel delegate = new RecordingModel("ordinary");
        SingleFileCodeModel wrapper = new SingleFileCodeModel(delegate, temporary.resolve("unused"));
        JsonArray messages = messages();
        JsonArray tools = new JsonArray();
        assertSame(delegate.reply, wrapper.chat(messages, tools));
        assertSame(messages, delegate.messages);
        assertSame(tools, delegate.tools);
        for (JsonObject schema : List.of(JsonParser.parseString("{\"properties\":{\"summary\":{\"type\":\"string\"}}}").getAsJsonObject(),
                schema(PATH, "module/src/main/kotlin/demo/Other.kt"))) {
            assertSame(delegate.reply, wrapper.chat(messages, tools, schema));
            assertSame(messages, delegate.messages);
            assertSame(tools, delegate.tools);
            assertSame(schema, delegate.schema);
        }
        assertEquals(1, delegate.ordinaryCalls);
        assertEquals(2, delegate.structuredCalls);
        assertFalse(Files.exists(temporary.resolve("unused")));
    }

    @Test void nonemptyToolsKeepTheOriginalSchemaAndDoNotSwitchProtocol() throws Exception {
        RecordingModel delegate = new RecordingModel("ordinary");
        JsonArray tools = new JsonArray(); tools.add(new JsonObject());
        JsonObject schema = schema(PATH);
        JsonArray messages = messages();
        assertSame(delegate.reply, new SingleFileCodeModel(delegate, temporary).chat(messages, tools, schema));
        assertSame(schema, delegate.schema);
        assertSame(messages, delegate.messages);
        assertSame(tools, delegate.tools);
    }

    @Test void repeatedCallsRetainEveryRawRequestAndReply() throws Exception {
        RecordingModel delegate = new RecordingModel("class Rule {}\n");
        SingleFileCodeModel wrapper = new SingleFileCodeModel(delegate, temporary);
        wrapper.chat(messages(), new JsonArray(), schema(PATH));
        wrapper.chat(messages(), new JsonArray(), schema(PATH));
        assertEquals(2, delegate.structuredCalls);
        assertEquals("class Rule {}\n", Files.readString(temporary.resolve("001.txt")));
        assertEquals("class Rule {}\n", Files.readString(temporary.resolve("002.txt")));
        assertTrue(Files.exists(temporary.resolve("002-request.json")));
    }

    private static JsonObject schema(String... paths) {
        JsonObject schema = JsonParser.parseString("{\"properties\":{\"edits\":{\"items\":{\"properties\":{\"path\":{}}}}}}").getAsJsonObject();
        JsonArray values = new JsonArray();
        for (String path : paths) values.add(path);
        schema.getAsJsonObject("properties").getAsJsonObject("edits").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("path").add("enum", values);
        return schema;
    }

    private static JsonArray messages() {
        JsonObject message = new JsonObject(); message.addProperty("role", "system"); message.addProperty("content", "Generate the approved source.");
        JsonArray messages = new JsonArray(); messages.add(message); return messages;
    }

    private static JsonObject proposal(JsonObject response) {
        return JsonParser.parseString(response.get("content").getAsString()).getAsJsonObject();
    }

    private static String proposedContent(JsonObject response) {
        return proposal(response).getAsJsonArray("edits").get(0).getAsJsonObject().get("content").getAsString();
    }

    private static final class RecordingModel implements ChatModel {
        private final JsonObject reply = new JsonObject();
        private JsonArray messages;
        private JsonArray tools;
        private JsonObject schema;
        private int ordinaryCalls;
        private int structuredCalls;
        private RecordingModel(String content) { reply.addProperty("role", "assistant"); reply.addProperty("content", content); }
        @Override public JsonObject chat(JsonArray messages, JsonArray tools) {
            ordinaryCalls++; this.messages = messages; this.tools = tools; return reply;
        }
        @Override public JsonObject chat(JsonArray messages, JsonArray tools, JsonObject schema) {
            structuredCalls++; this.messages = messages; this.tools = tools; this.schema = schema; return reply;
        }
    }
}
