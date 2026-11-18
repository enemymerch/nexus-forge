package com.mcan.nexusforge.tool;



import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounds evidence without turning a complete source line into a partial quote. */
public final class ToolOutput {
    private ToolOutput() { }

    public static String bound(String output, int budget) {
        if (budget <= 0) return "";
        if (output == null) return error("Tool returned no output.", budget);
        if (output.length() <= budget) return output;
        if (output.startsWith("ERROR:")) return error(output.substring(6).stripLeading(), budget);

        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(output);
            if (!parsed.isJsonObject()) return error("Output exceeds character budget.", budget);
            object = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return error("Output exceeds character budget.", budget);
        }
        String rowsKey = object.has("lines") ? "lines" : object.has("entries") ? "entries" : null;
        if (rowsKey == null || !object.get(rowsKey).isJsonArray()) {
            return error("Output exceeds character budget.", budget);
        }
        JsonArray rows = object.getAsJsonArray(rowsKey);
        object.addProperty("truncated", true);
        object.add(rowsKey, new JsonArray());
        String empty = object.toString();
        if (empty.length() > budget) return error("Budget too small for evidence.", budget);

        // Binary search keeps large files with many short lines inexpensive to bound.
        int low = 0;
        int high = rows.size();
        String best = empty;
        while (low < high) {
            int count = low + (high - low + 1) / 2;
            JsonArray prefix = new JsonArray(count);
            for (int i = 0; i < count; i++) prefix.add(rows.get(i));
            object.add(rowsKey, prefix);
            String candidate = object.toString();
            if (candidate.length() <= budget) {
                low = count;
                best = candidate;
            } else {
                high = count - 1;
            }
        }
        return best;
    }

    private static String error(String message, int budget) {
        if (budget < 6) return "";
        String result = "ERROR: " + message;
        return result.substring(0, Math.min(result.length(), budget));
    }
}
