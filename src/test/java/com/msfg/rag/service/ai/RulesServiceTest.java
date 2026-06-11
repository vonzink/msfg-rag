package com.msfg.rag.service.ai;

import com.msfg.rag.domain.RuleRevision;
import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.repository.RuleRevisionRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RulesServiceTest {

    private final RuleRevisionRepository repo = mock(RuleRevisionRepository.class);

    private RulesService service() {
        return new RulesService(repo, TestPacks.msfg());
    }

    // ── 1. Falls back to pack defaults when repo is empty ────────────────────

    @Test
    void effectiveFallsBackToPackDefaults() {
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        RulesService s = service();

        assertEquals(TestPacks.msfg().hardRules(), s.effectiveHard());
        assertEquals(TestPacks.msfg().guidance(),   s.effectiveGuidance());

        Map<String, RulesService.RuleState> state = s.state();
        assertEquals("pack", state.get("rules.hard").source());
        assertEquals("pack", state.get("rules.guidance").source());
    }

    // ── 2. Latest non-null revision overrides the pack ───────────────────────

    @Test
    void latestRevisionOverridesPack() {
        RuleRevision rev = new RuleRevision("rules.hard", "CUSTOM HARD", "tester");
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(eq("rules.hard")))
                .thenReturn(Optional.of(rev));
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(eq("rules.guidance")))
                .thenReturn(Optional.empty());
        RulesService s = service();

        assertEquals("CUSTOM HARD", s.effectiveHard());
        assertEquals("custom", s.state().get("rules.hard").source());
        assertEquals("pack",   s.state().get("rules.guidance").source());
    }

    // ── 3. Null-content revision reverts to pack default ─────────────────────

    @Test
    void nullContentRevisionRevertsToPack() {
        // Use a mock so @PrePersist-populated fields are present without a DB
        RuleRevision revert = mock(RuleRevision.class);
        when(revert.getRuleKey()).thenReturn("rules.hard");
        when(revert.getContent()).thenReturn(null);
        when(revert.getCreatedBy()).thenReturn("admin-api");
        when(revert.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(eq("rules.hard")))
                .thenReturn(Optional.of(revert));
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(eq("rules.guidance")))
                .thenReturn(Optional.empty());
        RulesService s = service();

        // effective content falls back to pack
        assertEquals(TestPacks.msfg().hardRules(), s.effectiveHard());

        // source is "pack" but revert revision attribution is preserved
        RulesService.RuleState st = s.state().get("rules.hard");
        assertEquals("pack", st.source());
        assertEquals("admin-api", st.updatedBy());
        assertNotNull(st.updatedAt(), "revert revision's timestamp should be surfaced");
    }

    // ── 4. save() appends revision and invalidates cache ─────────────────────

    @Test
    void saveAppendsAndInvalidates() {
        // Before save: empty
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        RulesService s = service();
        s.effectiveHard(); // prime cache → 1 findFirst for rules.hard

        // After save the cache is invalidated; next read re-queries
        s.save("rules.hard", "X", "admin-api");

        verify(repo).save(argThat(r ->
                r.getRuleKey().equals("rules.hard")
                        && "X".equals(r.getContent())
                        && "admin-api".equals(r.getCreatedBy())));

        s.effectiveHard(); // must hit repo again
        // prime + post-save re-read = 2 findFirst calls for rules.hard
        verify(repo, times(2)).findFirstByRuleKeyOrderByCreatedAtDescIdDesc("rules.hard");
    }

    // ── 5. Cache suppresses extra repo reads within TTL ──────────────────────

    @Test
    void cachesWithinTtl() {
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        RulesService s = service();

        s.effectiveHard();
        s.effectiveGuidance();
        s.effectiveHard();
        s.effectiveGuidance();

        // Both keys loaded in the initial snapshot; subsequent calls must not re-read
        verify(repo, times(1)).findFirstByRuleKeyOrderByCreatedAtDescIdDesc("rules.hard");
        verify(repo, times(1)).findFirstByRuleKeyOrderByCreatedAtDescIdDesc("rules.guidance");
    }

    // ── 6. save() rejects unknown key and blank content ──────────────────────

    @Test
    void rejectsUnknownKeyAndBlankContent() {
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        RulesService s = service();

        assertThrows(IllegalArgumentException.class,
                () -> s.save("nope", "some content", "admin-api"),
                "unknown key must be rejected");

        assertThrows(IllegalArgumentException.class,
                () -> s.save("rules.hard", "  ", "admin-api"),
                "blank content must be rejected");

        verify(repo, never()).save(any());
    }

    // ── 7. revert() appends a null-content revision ──────────────────────────

    @Test
    void revertAppendsNullRevision() {
        when(repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        RulesService s = service();

        s.revert("rules.hard", "admin-api");

        verify(repo).save(argThat(r ->
                r.getRuleKey().equals("rules.hard")
                        && r.getContent() == null
                        && "admin-api".equals(r.getCreatedBy())));
    }
}
