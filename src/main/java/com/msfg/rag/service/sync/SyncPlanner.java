package com.msfg.rag.service.sync;

import com.msfg.rag.domain.MortgageDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure sync planning (no IO) — port of scripts/s3-ingest/plan.mjs planSync,
 * extended with content-hash UPDATE detection (spec §6). Per corpus file:
 * ingest:false -> SKIP; unsupported extension -> SKIP; no row -> UPLOAD;
 * no active row -> REACTIVATE (or UPDATE if the hash differs); active row ->
 * SKIP when unchanged or hash unknown, UPDATE when the stored hash differs.
 * Finally every active row not wanted by the corpus -> DEACTIVATE.
 *
 * <p>Duplicate fileName rows: prefer the active row; else the last row in
 * input order (mirrors the script's single-entry Map which last-wins).
 */
public final class SyncPlanner {

    /** Mirror of DocumentAdminController.ALLOWED_EXTENSIONS. */
    static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    private SyncPlanner() {}

    public static List<SyncAction> plan(List<String> s3Files,
                                        SyncManifest manifest,
                                        List<MortgageDocument> brainDocs,
                                        Map<String, String> s3Hashes) {
        // Duplicate fileNames: prefer the active row; else the last in input order.
        Map<String, MortgageDocument> byName = new HashMap<>();
        for (MortgageDocument doc : brainDocs) {
            MortgageDocument present = byName.get(doc.getFileName());
            byName.put(doc.getFileName(),
                    (present != null && present.isActive() && !doc.isActive()) ? present : doc);
        }

        List<SyncAction> actions = new ArrayList<>();
        Set<String> wanted = new HashSet<>();

        for (String fileName : s3Files) {
            SyncManifest.Entry meta = manifest.resolve(fileName);
            if (!meta.ingest()) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.SKIP,
                        meta.reason() != null ? meta.reason() : "ingest:false", null, meta));
                continue;
            }
            String extension = extensionOf(fileName);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.SKIP,
                        "unsupported extension ." + extension, null, meta));
                continue;
            }
            wanted.add(fileName);

            MortgageDocument existing = byName.get(fileName);
            String s3Hash = s3Hashes.get(fileName);
            if (existing == null) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.UPLOAD, null, null, meta));
            } else if (existing.getContentSha256() != null && s3Hash != null
                    && !existing.getContentSha256().equals(s3Hash)) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.UPDATE,
                        "content changed", existing.getId(), meta));
            } else if (existing.isActive()) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.SKIP,
                        existing.getContentSha256() == null
                                ? "already ingested (no stored hash)" : "unchanged",
                        existing.getId(), meta));
            } else {
                actions.add(SyncAction.of(fileName, SyncAction.Type.REACTIVATE,
                        null, existing.getId(), meta));
            }
        }

        for (MortgageDocument doc : brainDocs) {
            if (doc.isActive() && !wanted.contains(doc.getFileName())) {
                actions.add(SyncAction.of(doc.getFileName(), SyncAction.Type.DEACTIVATE,
                        "not in current corpus", doc.getId(), null));
            }
        }
        return actions;
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.US);
    }
}
