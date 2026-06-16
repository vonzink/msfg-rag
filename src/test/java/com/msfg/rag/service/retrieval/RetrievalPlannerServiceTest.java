package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.service.ai.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the deterministic retrieval planner. {@code plan()} is pure
 * (the injected services are never touched); {@code collect()} delegates to the
 * matchers only for the planned indexes.
 */
class RetrievalPlannerServiceTest {

    private final PageGuideService pageGuides = mock(PageGuideService.class);
    private final SourceLinkService sourceLinks = mock(SourceLinkService.class);
    private final RetrievalPlannerService planner =
            new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService());

    // --- plan(): (intent x pageRoute) -> indexes ------------------------

    @Test
    void planDefaultIsCorpusOnly() {
        RetrievalPlan plan = planner.plan(Intent.GUIDELINE_QUESTION, null, null);
        assertEquals(Set.of(SourceKind.CORPUS), plan.indexes());
        verifyNoInteractions(pageGuides, sourceLinks);   // plan() is pure
    }

    @Test
    void planCalculationIsCorpusOnly() {
        assertEquals(Set.of(SourceKind.CORPUS),
                planner.plan(Intent.CALCULATION, null, null).indexes());
    }

    @Test
    void planExternalReferenceAddsLinkRegistry() {
        assertEquals(Set.of(SourceKind.CORPUS, SourceKind.LINK_REGISTRY),
                planner.plan(Intent.EXTERNAL_REFERENCE, null, null).indexes());
    }

    @Test
    void planPageGuidanceAddsBothSideIndexes() {
        assertEquals(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE, SourceKind.LINK_REGISTRY),
                planner.plan(Intent.PAGE_GUIDANCE, null, null).indexes());
    }

    @Test
    void planNonBlankPageRouteAddsPageGuideEvenForGuidelineIntent() {
        assertEquals(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE),
                planner.plan(Intent.GUIDELINE_QUESTION, "/loans/fha", null).indexes());
    }

    @Test
    void planBlankPageRouteDoesNotAddPageGuide() {
        assertEquals(Set.of(SourceKind.CORPUS),
                planner.plan(Intent.GUIDELINE_QUESTION, "   ", null).indexes());
    }

    @Test
    void planIncludesHelper() {
        RetrievalPlan plan = planner.plan(Intent.PAGE_GUIDANCE, null, null);
        assertTrue(plan.includes(SourceKind.PAGE_GUIDE));
        assertTrue(plan.includes(SourceKind.LINK_REGISTRY));
        assertTrue(plan.includes(SourceKind.CORPUS));
    }

    // --- collect(): delegate to matchers per plan -----------------------

    @Test
    void collectCorpusOnlyReturnsEmptyAndDoesNotMatch() {
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS));

        PlannedEvidence evidence = planner.collect(plan, "What is PMI?", null, null);

        assertTrue(evidence.pageGuides().isEmpty());
        assertTrue(evidence.links().isEmpty());
        verifyNoInteractions(pageGuides, sourceLinks);
    }

    @Test
    void collectPageGuideCallsOnlyPageGuideMatch() {
        BrainPageGuide g = new BrainPageGuide(
                "/loans/fha", "FHA", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of("fha"), "seed");
        when(pageGuides.match("/loans/fha", "fha", "PUBLIC")).thenReturn(List.of(g));
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE));

        PlannedEvidence evidence = planner.collect(plan, "fha", "/loans/fha", "PUBLIC");

        assertEquals(1, evidence.pageGuides().size());
        assertTrue(evidence.links().isEmpty());
        verify(pageGuides).match("/loans/fha", "fha", "PUBLIC");
        verify(sourceLinks, never()).match(anyString(), any());
    }

    @Test
    void collectLinkRegistryCallsOnlySourceLinkMatch() {
        BrainSourceLink l = new BrainSourceLink(
                "Name", "https://x.com", "x.com", LinkAuthority.PRIMARY,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        when(sourceLinks.match("fha", null)).thenReturn(List.of(l));
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.LINK_REGISTRY));

        PlannedEvidence evidence = planner.collect(plan, "fha", null, null);

        assertEquals(1, evidence.links().size());
        assertTrue(evidence.pageGuides().isEmpty());
        verify(sourceLinks).match("fha", null);
        verify(pageGuides, never()).match(any(), anyString(), any());
    }

    @Test
    void collectPageGuidanceCallsBothMatchers() {
        when(pageGuides.match(any(), anyString(), any())).thenReturn(List.of());
        when(sourceLinks.match(anyString(), any())).thenReturn(List.of());
        RetrievalPlan plan = new RetrievalPlan(
                Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE, SourceKind.LINK_REGISTRY));

        planner.collect(plan, "fha", "/loans/fha", null);

        verify(pageGuides).match("/loans/fha", "fha", null);
        verify(sourceLinks).match("fha", null);
    }

    @Test
    void collectReturnsAuthorityOrderedLinks() {
        // Matcher returns links OUT of authority order: BACKGROUND, PRIMARY, SECONDARY.
        BrainSourceLink background = new BrainSourceLink(
                "bg", "https://x.com", "x.com", LinkAuthority.BACKGROUND,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        BrainSourceLink primary = new BrainSourceLink(
                "pri", "https://x.com", "x.com", LinkAuthority.PRIMARY,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        BrainSourceLink secondary = new BrainSourceLink(
                "sec", "https://x.com", "x.com", LinkAuthority.SECONDARY,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        when(sourceLinks.match("fha", null))
                .thenReturn(List.of(background, primary, secondary));
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.LINK_REGISTRY));

        PlannedEvidence evidence = planner.collect(plan, "fha", null, null);

        // collect() returns the links authority-ordered: PRIMARY → SECONDARY → BACKGROUND.
        assertEquals(
                List.of(LinkAuthority.PRIMARY, LinkAuthority.SECONDARY, LinkAuthority.BACKGROUND),
                evidence.links().stream().map(BrainSourceLink::getAuthority).toList());
    }
}
