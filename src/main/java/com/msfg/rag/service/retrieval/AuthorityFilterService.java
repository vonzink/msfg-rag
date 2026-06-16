package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.SourceType;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Tiers and orders the collected side-evidence by trust authority (spec §6.4,
 * §7.6). Pure {@code @Service} — no injected collaborators.
 *
 * <p><b>Phase 7 scope (INERT seam):</b> this only re-orders the side-evidence
 * {@link RetrievalPlannerService#collect} produces, so Phase 8 can emit it
 * trust-first. It does NOT touch the corpus retrieval, the reranker, the
 * boot-locked prompt, {@code AskResponse}, or {@code ModelAnswer}. The ordered
 * {@link PlannedEvidence} remains logged-only until Phase 8 emits it.
 */
@Service
public class AuthorityFilterService {

    /**
     * Maps a link's external authority to its tier. The link row only ever
     * carries the three external authorities (tiers 1–2 come from elsewhere,
     * per {@link LinkAuthority} / spec §6.4). Exhaustive switch.
     */
    public AuthorityTier tierOf(LinkAuthority authority) {
        return switch (authority) {
            case PRIMARY -> AuthorityTier.PRIMARY_EXTERNAL;
            case SECONDARY -> AuthorityTier.SECONDARY_EXTERNAL;
            case BACKGROUND -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Maps a corpus source's {@link SourceType} to its tier. Exhaustive switch:
     * {@code INTERNAL_POLICY → COMPANY_RULE} (company rule);
     * {@code AGENCY_GUIDELINE → PRIMARY_EXTERNAL} (agency guides + HUD = primary);
     * {@code INVESTOR_OVERLAY → SECONDARY_EXTERNAL} (approved supporting overlay);
     * {@code EDUCATIONAL → BACKGROUND} (borrower-facing context).
     *
     * <p><b>Defined for completeness / Phase-8 use; NOT applied to corpus
     * reranking in Phase 7.</b> Corpus authority-rerank is a deferred opt-in
     * (the {@code authority.mode} hard-sort/boost knob, spec §7.6). Phase 7 wires
     * only {@link #tierOf(LinkAuthority)} into {@link #order}; nothing calls this
     * overload against the corpus path yet.
     */
    public AuthorityTier tierOf(SourceType sourceType) {
        return switch (sourceType) {
            case INTERNAL_POLICY -> AuthorityTier.COMPANY_RULE;
            case AGENCY_GUIDELINE -> AuthorityTier.PRIMARY_EXTERNAL;
            case INVESTOR_OVERLAY -> AuthorityTier.SECONDARY_EXTERNAL;
            case EDUCATIONAL -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Returns a NEW {@link PlannedEvidence} with {@code links} stable-sorted
     * ascending by {@code tierOf(link.getAuthority()).rank()} (PRIMARY first, then
     * SECONDARY, then BACKGROUND). Ties keep the incoming order — the matcher's
     * {@code createdAt}-desc order — because Java's sort is stable and the
     * comparator has no tiebreaker. {@code pageGuides} are all tier
     * {@link AuthorityTier#CURRENT_PAGE_GUIDE}, so their relative order is
     * preserved as-is. Null-safe: {@code order(null)} and empty evidence return
     * {@link PlannedEvidence#empty()}.
     */
    public PlannedEvidence order(PlannedEvidence evidence) {
        if (evidence == null
                || (evidence.pageGuides().isEmpty() && evidence.links().isEmpty())) {
            return PlannedEvidence.empty();
        }
        List<BrainSourceLink> sortedLinks = evidence.links().stream()
                .sorted(Comparator.comparingInt(link -> tierOf(link.getAuthority()).rank()))
                .toList();
        return new PlannedEvidence(evidence.pageGuides(), sortedLinks);
    }
}
