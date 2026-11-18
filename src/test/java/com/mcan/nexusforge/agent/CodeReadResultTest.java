package com.mcan.nexusforge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcan.nexusforge.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeReadResultTest {
    @Test void completeCodePreservesActualQuotesBackslashesAndIndentation() {
        List<String> lines = List.of("package sample", "", "class Variant {",
                "    val pattern = Regex(\"^[a-z0-9_\\\\-]{2,64}$\")", "    val quote = \"a\\\"b\"", "}");
        String raw = envelope("src/Variant.kt", 1, lines, 6, false);

        String rendered = CodeReadResult.render(raw, 2_400);

        assertEquals("FILE: src/Variant.kt\nLines: 1-6 of 6; truncated: false\n\n" + String.join("\n", lines), rendered);
        assertTrue(rendered.length() <= 2_400);
        assertFalse(rendered.contains("\"text\":"));
    }

    @Test void shortBudgetDropsOnlyCompleteTrailingRowsAndReportsTruncation() {
        String first = "val first = 1";
        String second = "val second = \"" + "x".repeat(200) + "\"";
        String raw = envelope("src/Example.kt", 1, List.of(first, second, "val tail = 3"), 3, false);
        String expected = "FILE: src/Example.kt\nLines: 1-1 of 3; truncated: true\n\n" + first;

        String rendered = CodeReadResult.render(raw, expected.length() + 5);

        assertEquals(expected, rendered);
        assertFalse(rendered.contains("val second"));
        assertFalse(rendered.contains("val tail"));
    }

    @Test void previouslyTruncatedReadRetainsActualRangeAndTotal() {
        String raw = envelope("src/Partial.kt", 5, List.of("fun first() = 1", "fun second() = 2"), 12, true);
        assertEquals("FILE: src/Partial.kt\nLines: 5-6 of 12; truncated: true\n\nfun first() = 1\nfun second() = 2",
                CodeReadResult.render(raw, 1_000));
    }

    @Test void oversizedSourceRowReturnsBoundedErrorWithoutLeakingAPartialRow() {
        String raw = envelope("src/Large.kt", 1, List.of("val oversized = \"" + "x".repeat(1_000) + "\""), 1, false);
        String rendered = CodeReadResult.render(raw, 120);
        assertTrue(rendered.startsWith("ERROR:"));
        assertTrue(rendered.length() <= 120);
        assertFalse(rendered.contains("val oversized"));
        assertFalse(rendered.contains("FILE:"));
        assertEquals("", CodeReadResult.render(raw, 0));
    }

    @Test void evidenceIdsFromEnvelopeMetadataAreNotInventedOrForwarded() {
        JsonObject read = com.google.gson.JsonParser.parseString(envelope("src/Example.kt", 1, List.of("val answer = 42"), 1, false)).getAsJsonObject();
        read.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("id", "E9999");

        String rendered = CodeReadResult.render(read.toString(), 1_000);

        assertEquals("FILE: src/Example.kt\nLines: 1-1 of 1; truncated: false\n\nval answer = 42", rendered);
        assertFalse(rendered.contains("E9999"));
    }

    @Test void invalidAndErrorInputsUseTheExistingBoundedFallback() {
        for (String raw : List.of("ERROR: Missing source file. " + "detail ".repeat(80), "not JSON ".repeat(80),
                "{\"path\":\"src/Example.kt\",\"lines\":[]}",
                envelope("src/Empty.kt", 1, List.of(), 0, false))) {
            for (int budget : List.of(0, 5, 50, 1_000)) {
                String rendered = CodeReadResult.render(raw, budget);
                assertTrue(rendered.length() <= budget);
                if (!raw.contains("src/Empty.kt")) assertEquals(ToolOutput.bound(raw, budget), rendered);
                else assertFalse(rendered.startsWith("FILE:"));
            }
        }
    }

    @Test void invalidRowNumbersCannotBePresentedAsAContinuousSourceRange() {
        JsonObject read = com.google.gson.JsonParser.parseString(envelope("src/Example.kt", 1, List.of("one", "two"), 3, false)).getAsJsonObject();
        read.getAsJsonArray("lines").get(1).getAsJsonObject().addProperty("line", 3);
        String raw = read.toString();
        assertEquals(ToolOutput.bound(raw, 1_000), CodeReadResult.render(raw, 1_000));
        assertFalse(CodeReadResult.render(raw, 1_000).startsWith("FILE:"));
    }

    private static String envelope(String path, int firstLine, List<String> text, int total, boolean truncated) {
        JsonObject read = new JsonObject();
        read.addProperty("path", path);
        JsonArray rows = new JsonArray();
        for (int i = 0; i < text.size(); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("line", firstLine + i);
            row.addProperty("text", text.get(i));
            rows.add(row);
        }
        read.add("lines", rows);
        read.addProperty("totalLines", total);
        read.addProperty("truncated", truncated);
        return read.toString();
    }
}
