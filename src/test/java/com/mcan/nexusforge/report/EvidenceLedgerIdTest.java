package com.mcan.nexusforge.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceLedgerIdTest {
    private static JsonObject arguments(String path) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", path);
        return arguments;
    }

    private static JsonObject read(String path, String... text) {
        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        JsonArray lines = new JsonArray();
        for (int i = 0; i < text.length; i++) {
            JsonObject line = new JsonObject();
            line.addProperty("line", i + 1);
            line.addProperty("text", text[i]);
            lines.add(line);
        }
        result.add("lines", lines);
        result.addProperty("totalLines", text.length);
        result.addProperty("truncated", false);
        return result;
    }

    private static JsonArray rows(String output) {
        return JsonParser.parseString(output).getAsJsonObject().getAsJsonArray("lines");
    }

    @Test void annotationIsBoundedBeforeOnlyDeliveredIdentitiesAreRegistered() {
        EvidenceLedger ledger = new EvidenceLedger();
        String raw = read("README.md", "a".repeat(60), "b".repeat(60)).toString();
        String delivered = ledger.deliver("read_file", arguments("README.md"), raw, raw.length());

        assertTrue(delivered.length() <= raw.length());
        assertEquals(1, rows(delivered).size(), "The added identity must count against the same output budget.");
        assertEquals(List.of("E1"), ledger.evidenceIds());
        assertTrue(ledger.resolve("E1").isPresent());
        assertTrue(ledger.resolve("E2").isEmpty(), "An ID minted for a dropped row must never resolve.");
        assertFalse(ledger.check("README.md", 2, "b".repeat(60)).valid());
        assertTrue(ledger.audit().contains("1/2 [TRUNCATED]"));
    }

    @Test void deliverAlwaysCapsOutputAt6500IncludingIdentityOverhead() {
        EvidenceLedger ledger = new EvidenceLedger();
        String[] lines = IntStream.range(0, 100).mapToObj(i -> "line " + i + " " + "x".repeat(100)).toArray(String[]::new);
        String delivered = ledger.deliver("read_file", arguments("build.gradle"),
                read("build.gradle", lines).toString(), 100_000);

        assertTrue(delivered.length() <= 6500);
        JsonArray deliveredRows = rows(delivered);
        assertTrue(deliveredRows.size() < lines.length);
        assertEquals(deliveredRows.size(), ledger.evidenceIds().size());
        for (int i = 0; i < deliveredRows.size(); i++) {
            String id = deliveredRows.get(i).getAsJsonObject().get("id").getAsString();
            assertEquals(lines[i], ledger.resolve(id).orElseThrow().text());
        }
    }

    @Test void oversizedLineIsDroppedWholeAndReplacesPriorEvidenceWithEmptySnapshot() {
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.deliver("read_file", arguments("README.md"), read("README.md", "old source").toString(), 6500);
        String delivered = ledger.deliver("read_file", arguments("README.md"),
                read("README.md", "x".repeat(7000), "next line").toString(), 6500);

        assertEquals(0, rows(delivered).size());
        assertTrue(ledger.evidenceIds().isEmpty());
        assertTrue(ledger.resolve("E1").isEmpty());
        assertTrue(ledger.resolve("E2").isEmpty());
        assertTrue(ledger.resolve("E3").isEmpty());
        assertFalse(ledger.check("README.md", 1, "old source").valid());
    }

    @Test void listingsErrorsAndBlankRowsNeverCreateSourceIdentities() {
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.deliver("list_files", arguments("."),
                "{\"path\":\".\",\"entries\":[{\"path\":\"README.md\",\"id\":\"E1\"}],\"truncated\":false}", 6500);
        ledger.deliver("read_file", arguments("missing"), "ERROR: File not found.", 6500);
        ledger.deliver("read_file", arguments("denied"), "{\"error\":\"Access denied\",\"id\":\"E1\"}", 6500);
        String delivered = ledger.deliver("read_file", arguments("README.md"),
                read("README.md", "", " \t ").toString(), 6500);

        assertTrue(ledger.evidenceIds().isEmpty());
        assertTrue(ledger.resolve("E1").isEmpty());
        rows(delivered).forEach(row -> assertFalse(row.getAsJsonObject().has("id")));
    }

    @Test void successfulRereadRevokesEarlierIdsEvenWhenContentDidNotChange() {
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.deliver("read_file", arguments("README.md"), read("README.md", "same bytes").toString(), 6500);
        ledger.deliver("read_file", arguments("pom.xml"), read("pom.xml", "<project/>").toString(), 6500);
        ledger.deliver("read_file", arguments("README.md"), read("README.md", "same bytes").toString(), 6500);

        assertTrue(ledger.resolve("E1").isEmpty());
        assertTrue(ledger.resolve("E2").isPresent(), "A different file's current snapshot remains valid.");
        assertEquals("same bytes", ledger.resolve("E3").orElseThrow().text());

        ledger.deliver("read_file", arguments("README.md"), read("README.md", "changed bytes").toString(), 6500);
        assertTrue(ledger.resolve("E3").isEmpty());
        assertEquals("changed bytes", ledger.resolve("E4").orElseThrow().text());
        assertFalse(ledger.check("README.md", 1, "same bytes").valid());
    }

    @Test void undeliverableNewSnapshotClearsOlderIdsAndUnderlyingLineEvidence() {
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.deliver("read_file", arguments("README.md"), read("README.md", "old source").toString(), 6500);
        assertEquals("", ledger.deliver("read_file", arguments("README.md"),
                read("README.md", "new source").toString(), 0));

        assertTrue(ledger.evidenceIds().isEmpty());
        assertFalse(ledger.check("README.md", 1, "old source").valid());
        ledger.deliver("read_file", arguments("README.md"), read("README.md", "final source").toString(), 6500);
        assertEquals(List.of("E3"), ledger.evidenceIds(), "Discarded identities are never reused.");
    }

    @Test void rawOutputCannotForgeIdsAndBlankRowsHaveTheirSuppliedIdsRemoved() {
        EvidenceLedger ledger = new EvidenceLedger();
        JsonObject forged = read("README.md", "first source", " ", "third source");
        forged.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("id", "E999");
        forged.getAsJsonArray("lines").get(1).getAsJsonObject().addProperty("id", "E1");
        forged.getAsJsonArray("lines").get(2).getAsJsonObject().addProperty("id", "E1");
        String delivered = ledger.deliver("read_file", arguments("README.md"), forged.toString(), 6500);

        assertEquals("E1", rows(delivered).get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(rows(delivered).get(1).getAsJsonObject().has("id"));
        assertEquals("E2", rows(delivered).get(2).getAsJsonObject().get("id").getAsString());
        assertTrue(ledger.resolve("E999").isEmpty());
        assertEquals("first source", ledger.resolve("E1").orElseThrow().text());
        assertEquals("third source", ledger.resolve("E2").orElseThrow().text());

        ledger.record("read_file", arguments("README.md"), forged.toString());
        assertTrue(ledger.evidenceIds().isEmpty(), "Legacy record may replace a snapshot but cannot register supplied IDs.");
    }

    @Test void resolvedEvidenceKeepsTheExactSourceRatherThanAReconstructedQuote() {
        EvidenceLedger ledger = new EvidenceLedger();
        String exact = "\tString name = \"Nexus PIM\"; // \u00e7al\u0131\u015fma yolu C:\\repo\\pim";
        String delivered = ledger.deliver("read_file", arguments("src/Main.java"),
                read("src/Main.java", exact).toString(), 6500);
        String id = rows(delivered).get(0).getAsJsonObject().get("id").getAsString();
        EvidenceLedger.SourceEvidence source = ledger.resolve(id).orElseThrow();

        assertEquals(id, source.id());
        assertEquals("src/Main.java", source.path());
        assertEquals(1, source.line());
        assertEquals(exact, source.text());
        assertEquals("source", source.kind());
        assertTrue(ledger.check(source.path(), source.line(), source.text()).valid());
    }
}
