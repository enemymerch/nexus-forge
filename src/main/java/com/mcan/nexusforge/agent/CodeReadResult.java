package com.mcan.nexusforge.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcan.nexusforge.tool.ToolOutput;

import java.util.ArrayList;
import java.util.List;

/** A compact code view of actual read_file rows; source rows are never partially delivered. */
final class CodeReadResult {
    private record Row(int line, String text) {}

    private CodeReadResult() {}

    static String render(String rawReadEnvelope, int budget) {
        if (budget <= 0) return "";
        try {
            JsonObject read = JsonParser.parseString(rawReadEnvelope).getAsJsonObject();
            String path = string(read.get("path"));
            if (path.isBlank() || path.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Invalid path.");
            int total = integer(read.get("totalLines"));
            JsonElement flag = read.get("truncated");
            if (flag == null || !flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("Missing truncation flag.");
            List<Row> rows = new ArrayList<>();
            for (JsonElement value : read.getAsJsonArray("lines")) {
                JsonObject line = value.getAsJsonObject();
                int number = integer(line.get("line"));
                String text = string(line.get("text"));
                if (number < 1 || number > total || (!rows.isEmpty() && number != rows.getLast().line() + 1)
                        || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
                    throw new IllegalArgumentException("Invalid source rows.");
                rows.add(new Row(number, text));
            }
            if (rows.isEmpty()) return noRows(budget);

            int low = 0, high = rows.size();
            String best = null;
            while (low < high) {
                int count = low + (high - low + 1) / 2;
                String candidate = prefix(path, rows, count, total, flag.getAsBoolean());
                if (candidate.length() <= budget) { low = count; best = candidate; }
                else high = count - 1;
            }
            return best == null ? noRows(budget) : best;
        } catch (RuntimeException invalid) {
            return ToolOutput.bound(rawReadEnvelope, budget);
        }
    }

    private static String prefix(String path, List<Row> rows, int count, int total, boolean wasTruncated) {
        int first = rows.getFirst().line(), last = rows.get(count - 1).line();
        boolean truncated = wasTruncated || count < rows.size() || first > 1 || last < total;
        StringBuilder result = new StringBuilder("FILE: ").append(path).append("\nLines: ")
                .append(first).append('-').append(last).append(" of ").append(total)
                .append("; truncated: ").append(truncated).append("\n\n");
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append('\n');
            result.append(rows.get(i).text());
        }
        return result.toString();
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a string.");
        return value.getAsString();
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected a source count.");
        int number = value.getAsBigDecimal().intValueExact();
        if (number < 0) throw new IllegalArgumentException("Negative source count.");
        return number;
    }

    private static String noRows(int budget) {
        return ToolOutput.bound("ERROR: No complete source row fits the output budget.", budget);
    }
}
