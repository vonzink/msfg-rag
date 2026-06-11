package com.msfg.rag.service.sync;

import com.msfg.rag.domain.MortgageDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncPlannerTest {

    private final SyncManifest emptyManifest = SyncManifest.parse(Optional.empty());

    private MortgageDocument doc(String fileName, boolean active, String sha) {
        MortgageDocument d = new MortgageDocument();
        d.setFileName(fileName);
        d.setActive(active);
        d.setContentSha256(sha);
        d.setTitle(fileName);
        return d;
    }

    private Map<String, SyncAction.Type> byFile(List<SyncAction> plan) {
        return plan.stream().collect(java.util.stream.Collectors.toMap(
                SyncAction::fileName, SyncAction::type, (a, b) -> b));
    }

    @Test
    void newFileUploads_missingFileDeactivates_unchangedSkips() {
        MortgageDocument unchanged = doc("same.pdf", true, "aaa");
        MortgageDocument removed = doc("gone.pdf", true, "bbb");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("new.pdf", "same.pdf"),
                emptyManifest,
                List.of(unchanged, removed),
                Map.of("new.pdf", "ccc", "same.pdf", "aaa"));

        Map<String, SyncAction.Type> types = byFile(plan);
        assertEquals(SyncAction.Type.UPLOAD, types.get("new.pdf"));
        assertEquals(SyncAction.Type.SKIP, types.get("same.pdf"));
        assertEquals(SyncAction.Type.DEACTIVATE, types.get("gone.pdf"));
    }

    @Test
    void changedHashUpdates() {
        MortgageDocument stale = doc("guide.pdf", true, "old-hash");
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("guide.pdf"), emptyManifest, List.of(stale),
                Map.of("guide.pdf", "new-hash"));

        assertEquals(SyncAction.Type.UPDATE, plan.get(0).type());
    }

    @Test
    void legacyNullHashSkipsWhenActive() {
        MortgageDocument legacy = doc("legacy.pdf", true, null);
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("legacy.pdf"), emptyManifest, List.of(legacy),
                Map.of("legacy.pdf", "whatever"));

        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals("already ingested (no stored hash)", plan.get(0).reason());
    }

    @Test
    void inactiveRowReactivatesWhenHashMatchesOrIsNull() {
        MortgageDocument inactive = doc("back.pdf", false, "h1");
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("back.pdf"), emptyManifest, List.of(inactive),
                Map.of("back.pdf", "h1"));
        assertEquals(SyncAction.Type.REACTIVATE, plan.get(0).type());

        MortgageDocument inactiveChanged = doc("back2.pdf", false, "h1");
        List<SyncAction> plan2 = SyncPlanner.plan(
                List.of("back2.pdf"), emptyManifest, List.of(inactiveChanged),
                Map.of("back2.pdf", "h2"));
        assertEquals(SyncAction.Type.UPDATE, plan2.get(0).type());
    }

    @Test
    void ingestFalseSkipsAndDeactivatesExistingActive() {
        SyncManifest manifest = SyncManifest.parse(Optional.of("""
                {"files": {"skipme.pdf": {"ingest": false, "reason": "retired"}}}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        MortgageDocument existing = doc("skipme.pdf", true, "x");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("skipme.pdf"), manifest, List.of(existing), Map.of("skipme.pdf", "x"));

        // skip the file AND deactivate the now-unwanted active row (plan.mjs parity)
        assertEquals(2, plan.size());
        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals(SyncAction.Type.DEACTIVATE, plan.get(1).type());
    }

    @Test
    void unsupportedExtensionSkips() {
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("image.png"), emptyManifest, List.of(), Map.of("image.png", "h"));
        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals("unsupported extension .png", plan.get(0).reason());
    }

    @Test
    void duplicateRowsPreferTheActiveOne() {
        MortgageDocument oldInactive = doc("dup.pdf", false, "h-old");
        MortgageDocument current = doc("dup.pdf", true, "h-cur");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("dup.pdf"), emptyManifest, List.of(oldInactive, current),
                Map.of("dup.pdf", "h-cur"));

        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals("unchanged", plan.get(0).reason());
    }

    @Test
    void onlyActiveUnwantedRowsDeactivate() {
        MortgageDocument inactiveGone = doc("gone.pdf", false, "x");
        List<SyncAction> plan = SyncPlanner.plan(
                List.of(), emptyManifest, List.of(inactiveGone), Map.of());
        assertEquals(0, plan.size(), "inactive rows need no deactivation");
    }
}
