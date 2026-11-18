package com.mcan.nexusforge.llm.ollama;

import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** LangChain4j HTTP SPI: local-only requests, no redirects, bounded bodies and explicit cleanup. */
final class LocalOllamaHttpClient implements HttpClient, HttpClientBuilder, AutoCloseable {
    private final URI endpoint;
    private final java.net.http.HttpClient delegate = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    private Duration readTimeout = Duration.ofMinutes(3);
    LocalOllamaHttpClient(URI endpoint) { this.endpoint = endpoint; }
    static void validate(URI endpoint) {
        if (endpoint == null || !"http".equals(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null || !"/api/chat".equals(endpoint.getPath())
                || !("localhost".equalsIgnoreCase(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost())))
            throw new IllegalArgumentException("Endpoint must be a local HTTP Ollama /api/chat address.");
    }
    @Override public SuccessfulHttpResponse execute(HttpRequest request) {
        if (!endpoint.equals(URI.create(request.url())) || request.method() != HttpMethod.POST)
            throw new IllegalArgumentException("Only the configured local Ollama chat endpoint is allowed.");
        boolean trace = Boolean.getBoolean("agent.trace");
        if (trace) System.out.println("[Ollama][LangChain4j request] " + request.body());
        var builder = java.net.http.HttpRequest.newBuilder(endpoint).timeout(readTimeout)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
        request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        try {
            var response = delegate.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var input = response.body()) {
                bytes = input.readNBytes(131073);
                if (bytes.length > 131072) throw new IllegalStateException("Ollama response exceeded the size limit.");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (trace) System.out.println("[Ollama][response HTTP " + response.statusCode() + "] " + body);
            if (response.statusCode() != 200) throw new IllegalStateException("Ollama error: HTTP " + response.statusCode() + "\n" + body.substring(0, Math.min(500, body.length())));
            // LangChain4j labels responses with tool calls TOOL_EXECUTION even when done_reason is length.
            // Reject the raw truncation signal first; partial tool arguments must never be executed.
            var envelope = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (!envelope.has("message") || !envelope.get("message").isJsonObject())
                throw new IllegalStateException("Ollama response is missing the message field.");
            if (envelope.has("done_reason") && "length".equals(envelope.get("done_reason").getAsString()))
                throw new IllegalStateException("Model response was truncated at the token limit; incomplete response rejected.");
            return SuccessfulHttpResponse.builder().statusCode(200).headers(response.headers().map()).body(body).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Ollama request interrupted.", e);
        } catch (java.io.IOException e) { throw new IllegalStateException("Local Ollama request failed: " + e.getMessage(), e); }
    }
    @Override public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        throw new UnsupportedOperationException("This agent uses bounded non-streaming Ollama responses.");
    }
    @Override public Duration connectTimeout() { return Duration.ofSeconds(10); }
    @Override public HttpClientBuilder connectTimeout(Duration timeout) { return this; }
    @Override public Duration readTimeout() { return readTimeout; }
    @Override public HttpClientBuilder readTimeout(Duration timeout) { readTimeout = timeout; return this; }
    @Override public HttpClient build() { return this; }
    @Override public void close() { delegate.close(); }
}
