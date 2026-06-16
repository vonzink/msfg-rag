package com.msfg.rag.service.ai;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.LinkDto;
import com.msfg.rag.service.retrieval.PlannedEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the server-side output contract builder (spec §6.3 / §7.7).
 * No mocks — {@link OutputContractService} has no collaborators. Verifies the
 * recommendedPage / links / nextAction derivation from already-authority-ordered
 * {@link PlannedEvidence}, the 5-link cap, and the degrade-gracefully drop of a
 * blank-url link / blank-route guide.
 */
class OutputContractServiceTest {

    private final OutputContractService service = new OutputContractService();

    // --- recommendedPage + nextAction from a top guide ------------------

    @Test
    void buildsRecommendedPageFromTopGuideWithAllowedGuidanceNextAction() {
        BrainPageGuide guide = guide("/loan-options", "Loan Options",
                List.of("Compare conventional and FHA options on this page."));
        BrainSourceLink link = link("Fannie Mae", "https://fanniemae.com", LinkAuthority.PRIMARY);

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(guide), List.of(link)));

        assertEquals("/loan-options", contract.recommendedPage().route());
        assertEquals("Loan Options", contract.recommendedPage().label());
        // allowed-guidance first entry wins for nextAction.
        assertEquals("Compare conventional and FHA options on this page.", contract.nextAction());
    }

    @Test
    void nextActionFallsBackToTitleTemplateWhenNoAllowedGuidance() {
        BrainPageGuide guide = guide("/pmi", "PMI Basics", List.of());

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(guide), List.of()));

        assertEquals("/pmi", contract.recommendedPage().route());
        assertEquals("See the PMI Basics page for detailed guidance.", contract.nextAction());
    }

    // --- links mapping, order, cap --------------------------------------

    @Test
    void mapsLinksToDtoPreservingOrderAndAuthorityName() {
        // Already authority-ordered by the planner: PRIMARY then SECONDARY.
        BrainSourceLink primary = link("Fannie Mae", "https://fanniemae.com", LinkAuthority.PRIMARY);
        BrainSourceLink secondary = link("Bankrate", "https://bankrate.com", LinkAuthority.SECONDARY);

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(), List.of(primary, secondary)));

        assertEquals(
                List.of(new LinkDto("Fannie Mae", "https://fanniemae.com", "PRIMARY"),
                        new LinkDto("Bankrate", "https://bankrate.com", "SECONDARY")),
                contract.links());
        // No guide → null recommendedPage; links present → links-based nextAction.
        assertNull(contract.recommendedPage());
        assertEquals("Review the linked source(s) for authoritative detail.", contract.nextAction());
    }

    @Test
    void capsLinksAtFive() {
        List<BrainSourceLink> sevenLinks = IntStream.range(0, 7)
                .mapToObj(i -> link("Src " + i, "https://x.com/" + i, LinkAuthority.PRIMARY))
                .toList();

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(), sevenLinks));

        assertEquals(5, contract.links().size());
        // The first five (most authoritative, order preserved) survive.
        assertEquals("Src 0", contract.links().get(0).name());
        assertEquals("Src 4", contract.links().get(4).name());
    }

    // --- empty + null-ish edge cases ------------------------------------

    @Test
    void emptyEvidenceYieldsNullEmptyNull() {
        OutputContractService.OutputContract contract = service.build(PlannedEvidence.empty());

        assertNull(contract.recommendedPage());
        assertTrue(contract.links().isEmpty());
        assertNull(contract.nextAction());
    }

    @Test
    void guideWithBlankRouteYieldsNullRecommendedPage() {
        // Topic-matched-only guide (no route) → nowhere to send the user.
        BrainPageGuide noRoute = guide("   ", "Topic Only", List.of("ignored"));

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(noRoute), List.of()));

        assertNull(contract.recommendedPage());
        // No usable page and no links → nextAction null (the guide's allowed-guidance
        // is not surfaced when there is no recommendedPage to anchor it).
        assertNull(contract.nextAction());
    }

    @Test
    void dropsLinkWithBlankUrl() {
        BrainSourceLink good = link("Good", "https://good.com", LinkAuthority.PRIMARY);
        BrainSourceLink blank = link("Blank", "   ", LinkAuthority.SECONDARY);

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(), List.of(good, blank)));

        assertEquals(1, contract.links().size());
        assertEquals("Good", contract.links().get(0).name());
    }

    @Test
    void nextActionStripsLeadingTrailingWhitespaceFromAllowedGuidanceEntry() {
        // firstNonBlank() calls .strip(), so a padded authored string is trimmed.
        BrainPageGuide guide = guide("/fha", "FHA Worksheet",
                List.of("  Use the FHA worksheet.  "));

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(guide), List.of()));

        assertEquals("Use the FHA worksheet.", contract.nextAction());
    }

    // --- fixtures (reuse the public ctors used in Phase 6/7 tests) ------

    private static BrainPageGuide guide(String route, String title, List<String> allowedGuidance) {
        return new BrainPageGuide(
                route, title, "purpose", Surface.BOTH,
                List.of(), allowedGuidance, List.of(), List.of(), List.of("topic"), "seed");
    }

    private static BrainSourceLink link(String name, String url, LinkAuthority authority) {
        return new BrainSourceLink(
                name, url, "x.com", authority,
                List.of("topic"), false, List.of(), List.of(), Surface.BOTH, "seed");
    }
}
