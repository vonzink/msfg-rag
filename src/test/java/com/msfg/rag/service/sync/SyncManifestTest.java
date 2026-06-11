package com.msfg.rag.service.sync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncManifestTest {

    private static final String JSON = """
            {
              "defaults": { "sourceName": "MSFG KB", "sourceType": "INTERNAL_POLICY" },
              "files": {
                "fha-handbook.pdf": {
                  "title": "FHA Handbook 4000.1",
                  "sourceType": "AGENCY_GUIDELINE",
                  "effectiveDate": "2026-01-01"
                },
                "old-rates.pdf": { "ingest": false, "reason": "stale rates" }
              }
            }""";

    @Test
    void entryMergesFileOverDefaults() {
        SyncManifest manifest = SyncManifest.parse(Optional.of(JSON.getBytes(StandardCharsets.UTF_8)));
        SyncManifest.Entry entry = manifest.resolve("fha-handbook.pdf");

        assertTrue(entry.ingest());
        assertEquals("FHA Handbook 4000.1", entry.title());
        assertEquals("MSFG KB", entry.sourceName());           // from defaults
        assertEquals("AGENCY_GUIDELINE", entry.sourceType());  // file overrides default
        assertEquals("2026-01-01", entry.effectiveDate());
        assertNull(entry.documentVersion());
    }

    @Test
    void ingestFalseCarriesReason() {
        SyncManifest manifest = SyncManifest.parse(Optional.of(JSON.getBytes(StandardCharsets.UTF_8)));
        SyncManifest.Entry entry = manifest.resolve("old-rates.pdf");

        assertFalse(entry.ingest());
        assertEquals("stale rates", entry.reason());
    }

    @Test
    void unknownFileDerivesTitleAndHardDefaults() {
        SyncManifest manifest = SyncManifest.parse(Optional.empty());
        SyncManifest.Entry entry = manifest.resolve("va_loan-guide.pdf");

        assertTrue(entry.ingest());
        assertEquals("va loan guide", entry.title());
        assertEquals("MSFG Knowledge Base", entry.sourceName());
        assertEquals("AGENCY_GUIDELINE", entry.sourceType());
    }

    @Test
    void malformedManifestActsAsEmpty() {
        SyncManifest manifest = SyncManifest.parse(Optional.of("not-json".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MSFG Knowledge Base", manifest.resolve("x.pdf").sourceName());
    }
}
