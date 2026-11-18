package com.mcan.nexusforge.report;



import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Resolves Java-issued evidence IDs, never the semantic truth of model-generated claims. */
public final class AnalystReport {
    public record Result(String text, int accepted, int rejected, boolean validFormat) {}
    private AnalystReport() {}

    public static JsonObject schema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{"findings":{"type":"array","maxItems":3,"items":{
                  "type":"object","properties":{
                    "claim":{"type":"string","maxLength":500,"description":"Write one observation in Turkish based on the selected evidence IDs."},
                    "evidence_ids":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","pattern":"^E[1-9][0-9]*$"}}
                  },"required":["claim","evidence_ids"],"additionalProperties":false
                }}},"required":["findings"],"additionalProperties":false}
                """).getAsJsonObject();
    }

    public static Result validate(String json, EvidenceLedger ledger) {
        List<String> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        JsonArray findings;
        try {
            if (json == null || json.length() > 16000) throw new IllegalArgumentException("Invalid report size.");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.size() != 1 || !root.has("findings")) throw new IllegalArgumentException("Only the findings field is expected.");
            findings = root.getAsJsonArray("findings");
            if (findings.size() > 3) throw new IllegalArgumentException("At most 3 findings are accepted.");
        } catch (RuntimeException ex) {
            return new Result("Model report is not valid findings JSON; free text was excluded from the evidence-backed report.\n"
                    + "The semantic accuracy of model claims is not automatically verified.\n", 0, 0, false);
        }
        for (int index = 0; index < findings.size(); index++) {
            try {
                JsonObject finding = findings.get(index).getAsJsonObject();
                if (finding.size() != 2 || !finding.has("claim") || !finding.has("evidence_ids")) {
                    throw new IllegalArgumentException("Only claim and evidence_ids fields are accepted; source details are supplied by Java.");
                }
                String claim = string(finding, "claim", 500);
                JsonArray citations = finding.getAsJsonArray("evidence_ids");
                if (citations == null || citations.isEmpty() || citations.size() > 2) throw new IllegalArgumentException("1 or 2 evidence references are required.");
                List<EvidenceLedger.SourceEvidence> checked = new ArrayList<>();
                for (JsonElement element : citations) {
                    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("Each evidence ID must be a string.");
                    }
                    String id = element.getAsString();
                    EvidenceLedger.SourceEvidence source = ledger.resolve(id)
                            .orElseThrow(() -> new IllegalArgumentException("Unknown or no longer available evidence ID: " + id));
                    checked.add(source);
                }
                String kind = checked.getFirst().kind();
                if (checked.stream().anyMatch(source -> !source.kind().equals(kind))) {
                    throw new IllegalArgumentException("A finding mixes source kinds; split it into separate findings.");
                }
                String category = switch (kind) {
                    case "documentation" -> "DOCUMENTATION CLAIM - implementation not verified";
                    case "configuration" -> "CONFIGURATION - not executed";
                    case "source" -> "SOURCE INTERPRETATION - behavior not tested";
                    default -> throw new IllegalArgumentException("Unknown finding kind.");
                };
                StringBuilder entry = new StringBuilder("- [").append(category).append("] Model claim: ").append(claim).append('\n');
                checked.forEach(source -> entry.append("  Evidence: [").append(source.id()).append("] ")
                        .append(source.path()).append(':').append(source.line()).append(" | ").append(source.text()).append('\n'));
                accepted.add(entry.toString());
            } catch (RuntimeException ex) {
                rejected.add("- Finding " + (index + 1) + " rejected: " + safeReason(ex));
            }
        }
        StringBuilder text = new StringBuilder("Analyst inspection report\n")
                .append("Evidence IDs were resolved to recorded source lines by Java. The semantic accuracy of model claims is not automatically verified.\n\n");
        if (accepted.isEmpty()) text.append("No findings could be linked to source evidence; the inspection is not considered complete.\n");
        accepted.forEach(text::append);
        if (!rejected.isEmpty()) {
            text.append("\nFindings rejected during evidence validation:\n");
            rejected.forEach(r -> text.append(r).append('\n'));
        }
        return new Result(text.toString(), accepted.size(), rejected.size(), true);
    }

    private static String string(JsonObject object, String name, int limit) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name + " must be a string.");
        String text = value.getAsString();
        if (text.isBlank() || text.length() > limit || text.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException(name + " is blank, too long, or contains control characters.");
        return text;
    }

    private static String safeReason(RuntimeException exception) {
        String reason = exception.getMessage();
        if (reason == null) return "Invalid finding structure.";
        return reason.substring(0, Math.min(reason.length(), 300)).replaceAll("[\\p{Cntrl}]", " ");
    }
}
