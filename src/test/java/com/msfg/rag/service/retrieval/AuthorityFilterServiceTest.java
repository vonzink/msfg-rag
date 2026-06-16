package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.domain.Surface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the authority tier mapping + side-evidence ordering
 * (spec §6.4 / §7.6). No mocks — {@link AuthorityFilterService} has no
 * collaborators. Verifies tier rank ordering, the exhaustive {@code tierOf}
 * mappings, and that {@code order()} stable-sorts links PRIMARY→SECONDARY→
 * BACKGROUND while preserving page-guide order.
 */
class AuthorityFilterServiceTest {

    private final AuthorityFilterService filter = new AuthorityFilterService();

    // --- AuthorityTier rank ordering (spec §6.4) ------------------------

    @Test
    void tierRanksAreStrictlyAscendingPerSpec() {
        assertEquals(1, AuthorityTier.COMPANY_RULE.rank());
        assertEquals(2, AuthorityTier.CURRENT_PAGE_GUIDE.rank());
        assertEquals(3, AuthorityTier.PRIMARY_EXTERNAL.rank());
        assertEquals(4, AuthorityTier.SECONDARY_EXTERNAL.rank());
        assertEquals(5, AuthorityTier.BACKGROUND.rank());

        assertTrue(AuthorityTier.COMPANY_RULE.rank() < AuthorityTier.CURRENT_PAGE_GUIDE.rank());
        assertTrue(AuthorityTier.CURRENT_PAGE_GUIDE.rank() < AuthorityTier.PRIMARY_EXTERNAL.rank());
        assertTrue(AuthorityTier.PRIMARY_EXTERNAL.rank() < AuthorityTier.SECONDARY_EXTERNAL.rank());
        assertTrue(AuthorityTier.SECONDARY_EXTERNAL.rank() < AuthorityTier.BACKGROUND.rank());
    }

    // --- tierOf(LinkAuthority) : exhaustive -----------------------------

    @Test
    void tierOfLinkAuthorityMapsEveryValue() {
        assertEquals(AuthorityTier.PRIMARY_EXTERNAL, filter.tierOf(LinkAuthority.PRIMARY));
        assertEquals(AuthorityTier.SECONDARY_EXTERNAL, filter.tierOf(LinkAuthority.SECONDARY));
        assertEquals(AuthorityTier.BACKGROUND, filter.tierOf(LinkAuthority.BACKGROUND));
    }

    // --- tierOf(SourceType) : exhaustive (incl INTERNAL_POLICY) ----------

    @Test
    void tierOfSourceTypeMapsEveryValue() {
        assertEquals(AuthorityTier.COMPANY_RULE, filter.tierOf(SourceType.INTERNAL_POLICY));
        assertEquals(AuthorityTier.PRIMARY_EXTERNAL, filter.tierOf(SourceType.AGENCY_GUIDELINE));
        assertEquals(AuthorityTier.SECONDARY_EXTERNAL, filter.tierOf(SourceType.INVESTOR_OVERLAY));
        assertEquals(AuthorityTier.BACKGROUND, filter.tierOf(SourceType.EDUCATIONAL));
    }

    // --- order() : stable sort of links by tier rank --------------------

    @Test
    void orderSortsLinksPrimaryThenSecondaryThenBackground() {
        // Built out of rank order: BACKGROUND, PRIMARY, SECONDARY.
        BrainSourceLink background = link(LinkAuthority.BACKGROUND, "bg");
        BrainSourceLink primary = link(LinkAuthority.PRIMARY, "pri");
        BrainSourceLink secondary = link(LinkAuthority.SECONDARY, "sec");

        PlannedEvidence ordered = filter.order(
                new PlannedEvidence(List.of(), List.of(background, primary, secondary)));

        assertEquals(
                List.of(LinkAuthority.PRIMARY, LinkAuthority.SECONDARY, LinkAuthority.BACKGROUND),
                ordered.links().stream().map(BrainSourceLink::getAuthority).toList());
    }

    @Test
    void orderKeepsIncomingOrderWithinSameTier() {
        // Two PRIMARY links + one SECONDARY interleaved; the two PRIMARYs must
        // keep their incoming relative order (stable sort, no tiebreaker).
        BrainSourceLink primaryA = link(LinkAuthority.PRIMARY, "A");
        BrainSourceLink secondary = link(LinkAuthority.SECONDARY, "S");
        BrainSourceLink primaryB = link(LinkAuthority.PRIMARY, "B");

        PlannedEvidence ordered = filter.order(
                new PlannedEvidence(List.of(), List.of(primaryA, secondary, primaryB)));

        assertEquals(List.of("A", "B", "S"),
                ordered.links().stream().map(BrainSourceLink::getName).toList());
    }

    @Test
    void orderPreservesPageGuideOrder() {
        BrainPageGuide first = guide("/a", "First");
        BrainPageGuide second = guide("/b", "Second");

        PlannedEvidence ordered = filter.order(
                new PlannedEvidence(List.of(first, second), List.of()));

        assertEquals(List.of("First", "Second"),
                ordered.pageGuides().stream().map(BrainPageGuide::getTitle).toList());
        assertNotSame(PlannedEvidence.empty(), ordered);     // non-empty result is a fresh record, not the EMPTY singleton
    }

    @Test
    void orderReturnsANewEvidenceWithSortedLinks() {
        PlannedEvidence input = new PlannedEvidence(
                List.of(), List.of(link(LinkAuthority.SECONDARY, "s"), link(LinkAuthority.PRIMARY, "p")));

        PlannedEvidence ordered = filter.order(input);

        assertNotSame(input, ordered);                       // new record, links resorted
        assertEquals(2, ordered.links().size());             // same membership
    }

    @Test
    void orderNullReturnsEmpty() {
        assertSame(PlannedEvidence.empty(), filter.order(null));
    }

    @Test
    void orderEmptyReturnsEmpty() {
        assertSame(PlannedEvidence.empty(), filter.order(PlannedEvidence.empty()));
    }

    // --- fixtures (reuse the public ctors used in Phase 6 tests) --------

    private static BrainSourceLink link(LinkAuthority authority, String name) {
        return new BrainSourceLink(
                name, "https://x.com", "x.com", authority,
                List.of("topic"), false, List.of(), List.of(), Surface.BOTH, "seed");
    }

    private static BrainPageGuide guide(String route, String title) {
        return new BrainPageGuide(
                route, title, "purpose", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of("topic"), "seed");
    }
}
