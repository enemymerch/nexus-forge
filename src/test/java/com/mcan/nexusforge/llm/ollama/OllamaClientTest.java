package com.mcan.nexusforge.llm.ollama;

import com.mcan.nexusforge.report.AnalystReport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OllamaClientTest {
    private static JsonArray userMessage() {
        return JsonParser.parseString("[{\"role\":\"user\",\"content\":\"Inspect the repository.\"}]").getAsJsonArray();
    }
    private HttpServer server;
    private final AtomicReference<JsonObject> request = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger();

    private URI respond(int status, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requestCount.incrementAndGet();
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
    }

    @AfterEach void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test void sendsLocalModelOptionsAndUsesReportSchemaOnlyWhenToolsAreEmpty() throws Exception {
        URI endpoint = respond(200, """
                {"message":{"role":"assistant","content":"yerel cevap"},"done_reason":"stop"}
                """);
        JsonArray messages = JsonParser.parseString("""
                [{"role":"user","content":"Nexus PIM incele"}]
                """).getAsJsonArray();
        JsonArray tools = JsonParser.parseString("""
                [{"type":"function","function":{"name":"read_file","parameters":{"type":"object","properties":{},"required":[]}}}]
                """).getAsJsonArray();
        try (OllamaClient client = new OllamaClient("qwen3:8b", endpoint)) {
            JsonObject reply = client.chat(messages, tools);
            assertEquals("yerel cevap", reply.get("content").getAsString());
            assertEquals("assistant", reply.get("role").getAsString());
            assertEquals("POST", method.get());
            assertEquals("application/json", contentType.get());
            JsonObject body = request.get();
            assertEquals("qwen3:8b", body.get("model").getAsString());
            assertFalse(body.get("think").getAsBoolean());
            assertFalse(body.get("stream").getAsBoolean());
            assertEquals(4096, body.getAsJsonObject("options").get("num_ctx").getAsInt());
            assertEquals(1024, body.getAsJsonObject("options").get("num_predict").getAsInt());
            assertEquals(messages, body.getAsJsonArray("messages"));
            assertEquals(tools, body.getAsJsonArray("tools"));
            assertFalse(body.has("format"));

            client.chat(messages, new JsonArray());
            assertTrue(!request.get().has("tools") || request.get().getAsJsonArray("tools").isEmpty());
            assertEquals(AnalystReport.schema(), request.get().getAsJsonObject("format"));
        }
    }

    @Test void rejectsResponseStoppedByTokenLimit() throws Exception {
        URI endpoint = respond(200, """
                {"message":{"role":"assistant","content":"partial answer"},"done_reason":"length"}
                """);
        try (OllamaClient client = new OllamaClient("qwen3:8b", endpoint)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> client.chat(userMessage(), new JsonArray()));
            assertTrue(error.getMessage().contains("token"));
            assertTrue(error.getMessage().contains("truncated"));
        }
    }

    @Test void httpFailureIncludesStatusAndOnlyBoundedResponseExcerpt() throws Exception {
        String response = "server failure: " + "x".repeat(2000) + "END_NOT_DELIVERED";
        URI endpoint = respond(500, response);
        try (OllamaClient client = new OllamaClient("qwen3:8b", endpoint)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> client.chat(userMessage(), new JsonArray()));
            assertTrue(error.getMessage().contains("HTTP 500"));
            assertTrue(error.getMessage().endsWith(response.substring(0, 500)));
            assertTrue(error.getMessage().length() < 600);
            assertFalse(error.getMessage().contains("END_NOT_DELIVERED"));
            assertEquals(1, requestCount.get(), "Framework retries must not bypass the Java model-step budget.");
        }
    }

    @Test void sendsCustomSchemaAlongsideToolsAndConfiguredTokenLimits() throws Exception {
        URI endpoint = respond(200, """
                {"message":{"role":"assistant","content":"Custom schema response."},"done_reason":"stop"}
                """);
        JsonArray messages = JsonParser.parseString("""
                [{"role":"system","content":"You are Developer."},
                 {"role":"user","content":"Inspect the selected source file."}]
                """).getAsJsonArray();
        JsonArray tools = JsonParser.parseString("""
                [{"type":"function","function":{"name":"read_file","parameters":{"type":"object","properties":{},"required":[]}}}]
                """).getAsJsonArray();
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","properties":{"edits":{"type":"array","items":{"type":"string"}}},
                 "required":["edits"],"additionalProperties":false}
                """).getAsJsonObject();
        JsonObject originalSchema = schema.deepCopy();
        try (OllamaClient client = new OllamaClient("qwen3:8b", endpoint, 6144, 2048)) {
            JsonObject reply = client.chat(messages, tools, schema);
            assertEquals("Custom schema response.", reply.get("content").getAsString());
            assertEquals(schema, request.get().getAsJsonObject("format"));
            assertNotEquals(AnalystReport.schema(), request.get().getAsJsonObject("format"));
            assertEquals(6144, request.get().getAsJsonObject("options").get("num_ctx").getAsInt());
            assertEquals(2048, request.get().getAsJsonObject("options").get("num_predict").getAsInt());
            assertEquals(messages, request.get().getAsJsonArray("messages"));
            assertEquals(tools, request.get().getAsJsonArray("tools"));
            assertEquals(originalSchema, schema, "Submitting a schema must not modify the role's contract.");
        }
    }

    @Test void explicitNullSchemaDoesNotInjectAnalystFormatWhenToolsAreEmpty() throws Exception {
        URI endpoint = respond(200, """
                {"message":{"role":"assistant","content":"No schema requested."},"done_reason":"stop"}
                """);
        try (OllamaClient client = new OllamaClient("qwen3:8b", endpoint, 2048, 256)) {
            client.chat(userMessage(), new JsonArray(), null);
            assertFalse(request.get().has("format"));
            assertTrue(!request.get().has("tools") || request.get().getAsJsonArray("tools").isEmpty());
            assertEquals(2048, request.get().getAsJsonObject("options").get("num_ctx").getAsInt());
            assertEquals(256, request.get().getAsJsonObject("options").get("num_predict").getAsInt());
        }
    }

    @Test void rejectsTruncatedToolCallsBeforeTheyCanExecute() throws Exception {
        URI endpoint = respond(200, """
                {"message":{"role":"assistant","content":"","tool_calls":[
                  {"function":{"name":"read_file","arguments":{"path":"README.md"}}}]},"done_reason":"length"}
                """);
        try (var client = new OllamaClient("qwen3:8b", endpoint)) {
            var failure = assertThrows(IllegalStateException.class, () -> client.chat(userMessage(), new JsonArray(), null));
            assertTrue(failure.getMessage().contains("truncated"));
        }
    }

    @Test void roundTripsToolCallsAndToolResultsThroughLangChain4jMessages() throws Exception {
        URI endpoint = respond(200, """
                {"message":{"role":"assistant","content":"","tool_calls":[
                  {"function":{"name":"read_file","arguments":{"path":"README.md"}}},
                  {"function":{"name":"list_files","arguments":{"path":"."}}}]},"done_reason":"stop"}
                """);
        try (var client = new OllamaClient("qwen3:8b", endpoint)) {
            JsonArray history = userMessage();
            JsonObject answer = client.chat(history, new JsonArray(), null);
            assertEquals(2, answer.getAsJsonArray("tool_calls").size());
            assertEquals("README.md", answer.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                    .getAsJsonObject("function").getAsJsonObject("arguments").get("path").getAsString());
            history.add(answer);
            for (String name : new String[]{"read_file", "list_files"}) {
                JsonObject result = new JsonObject(); result.addProperty("role", "tool");
                result.addProperty("tool_name", name); result.addProperty("content", "Actual source: \\d+ \"quoted\" \u0131\u015f\u011f");
                history.add(result);
            }
            client.chat(history, new JsonArray(), null);
            JsonArray sent = request.get().getAsJsonArray("messages");
            assertEquals(4, sent.size());
            assertEquals(history.get(0), sent.get(0));
            assertEquals(answer.get("tool_calls"), sent.get(1).getAsJsonObject().get("tool_calls"));
            assertEquals(history.get(2), sent.get(2));
            assertEquals(history.get(3), sent.get(3));
        }
    }

    @Test void rejectsOversizedResponseAndMissingMessage() throws Exception {
        URI endpoint = respond(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"" + "x".repeat(131072) + "\"}}");
        try (var client = new OllamaClient("qwen3:8b", endpoint)) {
            assertTrue(assertThrows(IllegalStateException.class, () -> client.chat(userMessage(), new JsonArray())).getMessage().contains("size limit"));
        }
        server.stop(0);
        endpoint = respond(200, "{\"done_reason\":\"stop\"}");
        try (var client = new OllamaClient("qwen3:8b", endpoint)) {
            assertTrue(assertThrows(IllegalStateException.class, () -> client.chat(userMessage(), new JsonArray())).getMessage().contains("message field"));
        }
    }

    @Test void refusesRedirectsAndRemoteEndpoints() throws Exception {
        URI endpoint = respond(302, "redirect declined");
        try (var client = new OllamaClient("qwen3:8b", endpoint)) {
            assertTrue(assertThrows(IllegalStateException.class, () -> client.chat(userMessage(), new JsonArray())).getMessage().contains("HTTP 302"));
            assertEquals(1, requestCount.get());
        }
        for (String address : new String[]{"https://localhost/api/chat", "http://example.com/api/chat",
                "http://localhost/api/chat?redirect=remote", "http://user@localhost/api/chat", "http://localhost/wrong"})
            assertThrows(IllegalArgumentException.class, () -> new OllamaClient("qwen3:8b", URI.create(address)));
    }

    @Test void rejectsCloudModelNamesAndInvalidBudgetsBeforeAnyHttpRequest() throws Exception {
        URI endpoint = respond(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"unused\"}}");
        for (String model : new String[]{null, "", "  ", "qwen3:8b-cloud", "QWEN3:8B-CLOUD", "qwen3:cloud"}) {
            assertThrows(IllegalArgumentException.class, () -> new OllamaClient(model, endpoint));
        }
        for (int[] limits : new int[][]{{2047, 1024}, {8193, 1024}, {4096, 255}, {4096, 4097}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OllamaClient("qwen3:8b", endpoint, limits[0], limits[1]));
        }
        assertNull(request.get(), "Invalid configuration must never reach Ollama.");
    }
}
