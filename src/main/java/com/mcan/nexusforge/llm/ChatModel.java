package com.mcan.nexusforge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Model boundary: the runner supplies conversation history and available tools. */
@FunctionalInterface
public interface ChatModel {
    JsonObject chat(JsonArray messages, JsonArray tools) throws Exception;

    default JsonObject chat(JsonArray messages, JsonArray tools, JsonObject responseSchema) throws Exception {
        return chat(messages, tools);
    }
}
