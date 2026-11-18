package com.mcan.nexusforge.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

/** Response formats restrict model proposals; Java separately validates permissions and results. */
final class WorkflowSchemas {
    private WorkflowSchemas() {}

    static JsonObject plan() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                 "summary":{"type":"string","maxLength":600},
                 "evidence_ids":{"type":"array","minItems":1,"maxItems":6,"items":{"type":"string"}},
                 "steps":{"type":"array","minItems":1,"maxItems":5,"items":{"type":"string","maxLength":300}},
                 "risks":{"type":"array","maxItems":4,"items":{"type":"string","maxLength":300}}
                },"required":["summary","evidence_ids","steps","risks"],"additionalProperties":false}
                """).getAsJsonObject();
    }

    static JsonObject patch(Set<String> paths) {
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","properties":{
                 "summary":{"type":"string","maxLength":600},
                 "edits":{"type":"array","minItems":1,"maxItems":4,"items":{"type":"object","properties":{
                   "path":{"type":"string"},"content":{"type":"string","minLength":1,"maxLength":8000}
                 },"required":["path","content"],"additionalProperties":false}}
                },"required":["summary","edits"],"additionalProperties":false}
                """).getAsJsonObject();
        JsonArray allowed = new JsonArray();
        paths.stream().sorted().forEach(allowed::add);
        schema.getAsJsonObject("properties").getAsJsonObject("edits").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("path").add("enum", allowed);
        return schema;
    }

    static JsonObject review() {
        return JsonParser.parseString("""
                {"type":"object","properties":{"summary":{"type":"string","maxLength":800},
                 "concerns":{"type":"array","maxItems":5,"items":{"type":"string","maxLength":300}}},
                 "required":["summary","concerns"],"additionalProperties":false}
                """).getAsJsonObject();
    }
}
