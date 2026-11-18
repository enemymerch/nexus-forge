package com.mcan.nexusforge.llm.ollama;

import com.google.gson.*;
import com.mcan.nexusforge.llm.ChatModel;
import com.mcan.nexusforge.report.AnalystReport;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** LangChain4j adapter. Each role continues to own its history and execution budgets. */
public final class OllamaClient implements ChatModel, AutoCloseable {
    private final LocalOllamaHttpClient transport;
    private final OllamaChatModel model;
    public OllamaClient(String model) { this(model, URI.create("http://localhost:11434/api/chat")); }
    public OllamaClient(String model, URI endpoint) { this(model, endpoint, 4096, 1024); }
    public OllamaClient(String name, URI endpoint, int contextTokens, int predictionTokens) {
        LocalOllamaHttpClient.validate(endpoint);
        if (name == null || name.isBlank() || name.toLowerCase(java.util.Locale.ROOT).contains("cloud"))
            throw new IllegalArgumentException("Select a nonblank local Ollama model; cloud models are not allowed.");
        if (contextTokens < 2048 || contextTokens > 8192 || predictionTokens < 256 || predictionTokens > 4096)
            throw new IllegalArgumentException("Context must be 2048..8192 and prediction limit 256..4096 tokens.");
        int timeoutSeconds = Integer.getInteger("agent.modelTimeoutSeconds", 180);
        if (timeoutSeconds < 30 || timeoutSeconds > 900) throw new IllegalArgumentException("Model timeout must be 30..900 seconds.");
        transport = new LocalOllamaHttpClient(endpoint);
        model = OllamaChatModel.builder().baseUrl(endpoint.resolve("/").toString()).modelName(name)
                .httpClientBuilder(transport).timeout(Duration.ofSeconds(timeoutSeconds))
                .defaultRequestParameters(OllamaChatRequestParameters.builder().numBatch(128).build())
                .numCtx(contextTokens).numPredict(predictionTokens).temperature(0.0).think(false)
                .maxRetries(0).build(); // No hidden model calls outside Java's step budget.
    }
    @Override public JsonObject chat(JsonArray messages, JsonArray tools) throws Exception {
        return chat(messages, tools, tools.isEmpty() ? AnalystReport.schema() : null);
    }
    @Override public JsonObject chat(JsonArray messages, JsonArray tools, JsonObject responseSchema) {
        List<ChatMessage> history = new ArrayList<>();
        for (JsonElement element : messages) {
            JsonObject message = element.getAsJsonObject();
            String content = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
            switch (message.get("role").getAsString()) {
                case "system" -> history.add(SystemMessage.from(content));
                case "user" -> history.add(UserMessage.from(content));
                case "assistant" -> {
                    List<ToolExecutionRequest> requests = new ArrayList<>();
                    if (message.has("tool_calls")) for (JsonElement call : message.getAsJsonArray("tool_calls")) {
                        JsonObject function = call.getAsJsonObject().getAsJsonObject("function");
                        requests.add(ToolExecutionRequest.builder().name(function.get("name").getAsString())
                                .arguments(function.get("arguments").toString()).build());
                    }
                    history.add(AiMessage.from(content.isEmpty() && !requests.isEmpty() ? null : content, requests));
                }
                // The provider currently omits tool_name for ToolExecutionResultMessage. Preserve it
                // through LangChain4j's supported custom-message extension, including batched tool replies.
                case "tool" -> history.add(CustomMessage.from(java.util.Map.of("role", "tool",
                        "tool_name", message.get("tool_name").getAsString(), "content", content)));
                default -> throw new IllegalArgumentException("Unsupported chat message role.");
            }
        }
        List<ToolSpecification> specifications = new ArrayList<>();
        for (JsonElement tool : tools) specifications.add(ToolSpecification.fromJson(tool.getAsJsonObject().get("function").toString()));
        var request = ChatRequest.builder().messages(history).toolSpecifications(specifications);
        if (responseSchema != null) request.responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("agent_response").rootElement(JsonRawSchema.from(responseSchema.toString())).build()).build());
        var response = model.chat(request.build());
        if (response.metadata().finishReason() == FinishReason.LENGTH)
            throw new IllegalStateException("Model response was truncated at the token limit; incomplete response rejected.");
        AiMessage answer = response.aiMessage();
        JsonObject result = new JsonObject(); result.addProperty("role", "assistant");
        result.addProperty("content", answer.text() == null ? "" : answer.text());
        if (answer.hasToolExecutionRequests()) {
            JsonArray calls = new JsonArray();
            for (ToolExecutionRequest execution : answer.toolExecutionRequests()) {
                JsonObject function = new JsonObject(); function.addProperty("name", execution.name());
                function.add("arguments", JsonParser.parseString(execution.arguments()));
                JsonObject call = new JsonObject(); call.add("function", function); calls.add(call);
            }
            result.add("tool_calls", calls);
        }
        return result;
    }
    @Override public void close() { transport.close(); }
}
