package com.msfg.rag.service.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The corpus manifest (<prefix>_manifest.json): per-file metadata over
 * defaults. Mirror of scripts/s3-ingest/plan.mjs resolveEntry — including the
 * hard fallbacks, so a missing or broken manifest never blocks a sync.
 */
public final class SyncManifest {

    private static final Logger log = LoggerFactory.getLogger(SyncManifest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Resolved metadata for one file; dates stay strings until execution. */
    public record Entry(String fileName, boolean ingest, String reason, String title,
                        String sourceName, String sourceType, String documentVersion,
                        String effectiveDate, String expirationDate) {}

    private final JsonNode root;

    private SyncManifest(JsonNode root) {
        this.root = root;
    }

    public static SyncManifest parse(Optional<byte[]> manifestBytes) {
        if (manifestBytes.isEmpty()) {
            return new SyncManifest(JSON.createObjectNode());
        }
        try {
            return new SyncManifest(JSON.readTree(manifestBytes.get()));
        } catch (Exception e) {
            log.warn("Corpus manifest is unreadable ({}); syncing with defaults only", e.getMessage());
            return new SyncManifest(JSON.createObjectNode());
        }
    }

    public Entry resolve(String fileName) {
        JsonNode defaults = root.path("defaults");
        JsonNode entry = root.path("files").path(fileName);
        return new Entry(
                fileName,
                !entry.path("ingest").isBoolean() || entry.path("ingest").asBoolean(),
                text(entry, "reason", null),
                text(entry, "title", deriveTitle(fileName)),
                text(entry, "sourceName", text(defaults, "sourceName", "MSFG Knowledge Base")),
                text(entry, "sourceType", text(defaults, "sourceType", "AGENCY_GUIDELINE")),
                text(entry, "documentVersion", null),
                text(entry, "effectiveDate", null),
                text(entry, "expirationDate", null));
    }

    /** "va_loan-guide.pdf" -> "va loan guide" (plan.mjs deriveTitle). */
    static String deriveTitle(String fileName) {
        return fileName.replaceFirst("\\.[^.]+$", "").replaceAll("[_-]+", " ").strip();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }
}
