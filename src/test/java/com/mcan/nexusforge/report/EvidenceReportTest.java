package com.mcan.nexusforge.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceReportTest {
    private static JsonObject arguments(String path) {
        JsonObject args = new JsonObject();
        args.addProperty("path", path);
        return args;
    }

    private static String readOutput(String path, int totalLines,
                                     boolean truncated, String... deliveredLines) {
        JsonObject output = new JsonObject();
        output.addProperty("path", path);
        JsonArray lines = new JsonArray();
        for (int i = 0; i < deliveredLines.length; i++) {
            JsonObject line = new JsonObject();
            line.addProperty("line", i + 1);
            line.addProperty("text", deliveredLines[i]);
            lines.add(line);
        }
        output.add("lines", lines);
        output.addProperty("totalLines", totalLines);
        output.addProperty("truncated", truncated);
        return output.toString();
    }

    private static void read(EvidenceLedger ledger, String path, int totalLines,
                             boolean truncated, String... deliveredLines) {
        ledger.record("read_file", arguments(path), readOutput(path, totalLines, truncated, deliveredLines));
    }

    private static List<String> deliver(EvidenceLedger ledger, String path, String... lines) {
        String delivered = ledger.deliver("read_file", arguments(path),
                readOutput(path, lines.length, false, lines), 2400);
        JsonArray rows = JsonParser.parseString(delivered).getAsJsonObject().getAsJsonArray("lines");
        return rows.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> row.has("id")).map(row -> row.get("id").getAsString()).toList();
    }

    private static JsonObject finding(String claim, String... ids) {
        JsonArray references = new JsonArray();
        for (String id : ids) references.add(id);
        JsonObject finding = new JsonObject();
        finding.addProperty("claim", claim);
        finding.add("evidence_ids", references);
        return finding;
    }

    private static String report(JsonObject... findings) {
        JsonArray items = new JsonArray();
        for (JsonObject finding : findings) items.add(finding);
        JsonObject report = new JsonObject();
        report.add("findings", items);
        return report.toString();
    }

    @Test void onlyDeliveredReadLinesAreEvidence() {
        EvidenceLedger ledger = new EvidenceLedger();
        read(ledger, "README.md", 50, true,
                "# Nexus PIM", "Bu belge proje hedeflerini açıklar.");

        assertTrue(ledger.check("README.md", 2, "proje hedeflerini").valid());
        assertFalse(ledger.check("README.md", 3, "Dosyanın görünmeyen kısmı").valid());
        assertFalse(ledger.check("README.md", 50, "Dosyanın son satırı").valid());
        assertFalse(ledger.check("README.md", 0, "# Nexus PIM").valid());
        assertFalse(ledger.check("README.md", -1, "# Nexus PIM").valid());
        assertFalse(ledger.check("other.md", 1, "# Nexus PIM").valid());
    }

    @Test void listingsAndFailedReadsDoNotEstablishFileContents() {
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.record("list_files", arguments("."), """
                {"path":".","entries":[{"path":"README.md","type":"file"}],"truncated":false}
                """);
        ledger.record("read_file", arguments("missing.md"), "ERROR: File not found.");
        ledger.record("read_file", arguments("denied.md"), "{\"error\":\"Access denied\"}");

        assertFalse(ledger.check("README.md", 1, "Nexus PIM").valid());
        assertFalse(ledger.check("missing.md", 1, "File not found").valid());
        assertFalse(ledger.check("denied.md", 1, "Access denied").valid());
        assertTrue(ledger.evidenceIds().isEmpty());
    }

    @Test void quotesMustMatchTheirOwnLineAndCannotBeEmptyOrTooShort() {
        EvidenceLedger ledger = new EvidenceLedger();
        read(ledger, "README.md", 3, false,
                "Nexus PIM yerel geliştirme projesidir.", "OK", "    ");

        assertTrue(ledger.check("README.md", 1, "yerel geliştirme").valid());
        assertTrue(ledger.check("README.md", 2, "OK").valid());
        assertFalse(ledger.check("README.md", 1, "Nexus").valid());
        assertFalse(ledger.check("README.md", 2, "O").valid());
        assertFalse(ledger.check("README.md", 1, "").valid());
        assertFalse(ledger.check("README.md", 3, "    ").valid());
        assertFalse(ledger.check("README.md", 1, "NEXUS PIM").valid());
        assertFalse(ledger.check("README.md", 2, "yerel geliştirme").valid());
        assertFalse(ledger.check("README.md", 1, "Kanıtta bulunmayan bilgi").valid());
        assertFalse(ledger.check("README.md", 1, "Kanıtta bulunmayan bilgi").reason().isBlank());
    }

    @Test void escapedQuotesBackslashesAndTurkishTextComeFromJavaEvidence() {
        EvidenceLedger ledger = new EvidenceLedger();
        String source = "String name = \"Nexus PIM\"; // çalışma yolu C:\\repo\\pim";
        String id = deliver(ledger, "src/Main.java", source).getFirst();
        String json = report(finding("Kaynakta proje adı ve çalışma yolu belirtiliyor.", id));
        assertFalse(json.contains(source), "The model supplies an ID, not a source quote.");

        AnalystReport.Result result = AnalystReport.validate(json, ledger);

        assertTrue(result.validFormat());
        assertEquals(1, result.accepted());
        assertEquals(0, result.rejected());
        assertTrue(result.text().contains("src/Main.java:1"));
        assertTrue(result.text().contains(source));
        assertTrue(result.text().contains("SOURCE INTERPRETATION"));
    }

    @Test void unknownBlankAndStaleIdsAreRejected() {
        EvidenceLedger ledger = new EvidenceLedger();
        String stale = deliver(ledger, "README.md", "Eski içerik.").getFirst();
        String current = deliver(ledger, "README.md", "Yeni içerik.").getFirst();
        assertNotEquals(stale, current);
        AnalystReport.Result result = AnalystReport.validate(report(
                finding("Bilinmeyen kanıt.", "UNKNOWN_EVIDENCE"),
                finding("Boş kanıt.", " "),
                finding("Eski dosya içeriği.", stale)), ledger);

        assertTrue(result.validFormat());
        assertEquals(0, result.accepted());
        assertEquals(3, result.rejected());
        assertTrue(ledger.resolve(stale).isEmpty());
        assertTrue(ledger.resolve(current).isPresent());
    }

    @Test void blankSourceLinesCannotBeCited() {
        EvidenceLedger ledger = new EvidenceLedger();
        List<String> ids = deliver(ledger, "README.md", "# Nexus PIM", "", "   ");
        assertEquals(1, ids.size());
        assertEquals(1, ledger.evidenceIds().size());
        AnalystReport.Result result = AnalystReport.validate(report(
                finding("Boş satırdan üretilen bilgi.", "BLANK_LINE_ID")), ledger);
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
    }

    @Test void mixedReportKeepsOnlySupportedFindingsAndInfersSourceKinds() {
        EvidenceLedger ledger = new EvidenceLedger();
        String documentation = deliver(ledger, "README.md", "Nexus PIM katalog yönetimi hedefler.").getFirst();
        String configuration = deliver(ledger, "build.gradle", "plugins { id 'java' }").getFirst();
        AnalystReport.Result result = AnalystReport.validate(report(
                finding("Belgelerde katalog yönetimi hedefleniyor.", documentation),
                finding("Yapılandırmada Java eklentisi belirtiliyor.", configuration),
                finding("Kaynakta ana sınıf var.", "UNKNOWN_SOURCE_ID")), ledger);

        assertTrue(result.validFormat());
        assertEquals(2, result.accepted());
        assertEquals(1, result.rejected());
        assertTrue(result.text().contains("README.md:1"));
        assertTrue(result.text().contains("build.gradle:1"));
        assertTrue(result.text().contains("DOCUMENTATION CLAIM"));
        assertTrue(result.text().contains("CONFIGURATION"));
        String rendered = result.text().toLowerCase(Locale.ROOT);
        assertTrue(rendered.contains("semantic accuracy") && rendered.contains("not automatically verified"),
                "Resolving an evidence ID must not be presented as semantic verification.");
    }

    @Test void everyIdInAFindingMustExistAndSourcesMustHaveTheSameKind() {
        EvidenceLedger ledger = new EvidenceLedger();
        List<String> documentation = deliver(ledger, "README.md", "# Nexus PIM", "Katalog yönetimi hedeflenir.");
        String configuration = deliver(ledger, "build.gradle", "plugins { id 'java' }").getFirst();
        AnalystReport.Result result = AnalystReport.validate(report(
                finding("İki belge satırı.", documentation.get(0), documentation.get(1)),
                finding("Bir gerçek bir uydurma kanıt.", documentation.get(0), "UNKNOWN_ID"),
                finding("Belge ve yapılandırmayı karıştıran iddia.", documentation.get(0), configuration)), ledger);

        assertEquals(1, result.accepted());
        assertEquals(2, result.rejected());
        assertFalse(result.text().contains("Bir gerçek bir uydurma kanıt."));
        assertFalse(result.text().contains("Belge ve yapılandırmayı karıştıran iddia."));
    }

    @Test void malformedIdArraysAndNonStringIdsAreRejected() {
        EvidenceLedger ledger = new EvidenceLedger();
        String id = deliver(ledger, "README.md", "# Nexus PIM").getFirst();
        for (String malformed : new String[]{"null", "true", "7", "{}", "\"" + id + "\"",
                "[]", "[null]", "[true]", "[7]", "[{}]", "[[]]", "[\"\"]",
                "[\"" + id + "\",\"" + id + "\",\"" + id + "\"]"}) {
            JsonObject item = finding("Geçersiz kanıt biçimi.", id);
            item.add("evidence_ids", JsonParser.parseString(malformed));
            AnalystReport.Result result = AnalystReport.validate(report(item), ledger);
            assertEquals(0, result.accepted(), malformed);
            assertEquals(1, result.rejected(), malformed);
        }
    }

    @Test void missingAndExtraFindingFieldsCannotBeAccepted() {
        EvidenceLedger ledger = new EvidenceLedger();
        String id = deliver(ledger, "README.md", "# Nexus PIM").getFirst();
        for (String field : new String[]{"claim", "evidence_ids"}) {
            JsonObject item = finding("Belgenin adı Nexus PIM.", id);
            item.remove(field);
            assertEquals(0, AnalystReport.validate(report(item), ledger).accepted(), field);
        }
        for (String field : new String[]{"kind", "path", "line", "quote", "evidence"}) {
            JsonObject item = finding("Belgenin adı Nexus PIM.", id);
            item.addProperty(field, "model-authored source metadata");
            assertEquals(0, AnalystReport.validate(report(item), ledger).accepted(), field);
        }
    }

    @Test void legacyModelAuthoredPathLineAndQuoteFormatIsRejected() {
        EvidenceLedger ledger = new EvidenceLedger();
        deliver(ledger, "README.md", "# Nexus PIM");
        JsonObject legacy = JsonParser.parseString("""
                {"kind":"documentation","claim":"Belgenin adı Nexus PIM.",
                 "evidence":[{"path":"README.md","line":1,"quote":"# Nexus PIM"}]}
                """).getAsJsonObject();
        AnalystReport.Result result = AnalystReport.validate(report(legacy), ledger);
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
    }

    @Test void proseInvalidJsonAndMalformedTopLevelFormatsAreRejected() {
        EvidenceLedger ledger = new EvidenceLedger();
        for (String input : new String[]{
                "Nexus PIM incelendi, her şey başarılı.", "{broken", "null", "[]", "{}",
                "{\"findings\":\"text\"}", "{\"findings\":null}", "{\"findings\":[],\"extra\":true}",
                "```json\n{\"findings\":[]}\n```",
                report(finding("Bir", "x"), finding("İki", "x"), finding("Üç", "x"), finding("Dört", "x"))}) {
            AnalystReport.Result result = AnalystReport.validate(input, ledger);
            assertFalse(result.validFormat(), input);
            assertEquals(0, result.accepted(), input);
            assertFalse(result.text().isBlank(), input);
        }
        assertTrue(AnalystReport.validate(report(), ledger).validFormat());
    }
}
