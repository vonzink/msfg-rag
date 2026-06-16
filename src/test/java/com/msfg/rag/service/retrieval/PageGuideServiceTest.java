package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.PageGuideRequest;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageGuideServiceTest {

    private final BrainPageGuideRepository repo = mock(BrainPageGuideRepository.class);
    private final PageGuideService service = new PageGuideService(repo);

    private final UUID linkId = UUID.randomUUID();

    private PageGuideRequest validRequest() {
        return new PageGuideRequest(
                "  /loans/fha  ", "  FHA Loans  ", "  Help users understand FHA.  ", "BOTH",
                List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new PageGuideRequest.LinkRefRequest("FHA loans", "/loans/fha")),
                List.of(linkId.toString()), List.of("fha"));
    }

    @Test
    void createStripsAndPersistsAndInvalidates() {
        when(repo.save(any(BrainPageGuide.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(validRequest(), "admin-api");

        assertEquals("/loans/fha", dto.route());                  // stripped
        assertEquals("FHA Loans", dto.title());                   // stripped
        assertEquals("Help users understand FHA.", dto.purpose()); // stripped
        assertEquals("BOTH", dto.surface());
        assertEquals(List.of(new LinkRef("FHA loans", "/loans/fha")), dto.internalLinks());
        assertEquals(List.of(linkId.toString()), dto.sourceLinkIds());  // UUID round-tripped
        verify(repo).save(any(BrainPageGuide.class));
    }

    @Test
    void createAcceptsNullRoute() {
        when(repo.save(any(BrainPageGuide.class))).thenAnswer(inv -> inv.getArgument(0));
        PageGuideRequest req = new PageGuideRequest(
                null, "Topic guide", "Purpose", "BOTH",
                List.of(), List.of(), List.of(), List.of(), List.of());

        var dto = service.create(req, "admin-api");

        assertNull(dto.route());
        verify(repo).save(any(BrainPageGuide.class));
    }

    @Test
    void createRejectsBlankTitle() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "   ", "Purpose", "BOTH", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsBlankPurpose() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "Title", "  ", "BOTH", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsUnknownSurface() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "Title", "Purpose", "SIDEWAYS", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsMalformedSourceLinkId() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "Title", "Purpose", "BOTH",
                List.of(), List.of(), List.of(), List.of("not-a-uuid"), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.get(id));
    }

    @Test
    void updateAppliesFieldsAndInvalidates() {
        UUID id = UUID.randomUUID();
        BrainPageGuide existing = new BrainPageGuide(
                "/old", "old", "old purpose", Surface.PUBLIC,
                List.of("x"), List.of(), List.of(), List.of(), List.of("old"), "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        var dto = service.update(id, validRequest(), "admin-api");

        assertEquals("/loans/fha", existing.getRoute());
        assertEquals("FHA Loans", existing.getTitle());
        assertEquals("Help users understand FHA.", existing.getPurpose());
        assertEquals(Surface.BOTH, existing.getSurface());
        assertEquals(List.of(new LinkRef("FHA loans", "/loans/fha")), existing.getInternalLinks());
        assertEquals(List.of(linkId), existing.getSourceLinkIds());
        assertEquals("admin-api", existing.getUpdatedBy());
        assertEquals("FHA Loans", dto.title());
        verify(repo).save(existing);
    }

    @Test
    void updateThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.update(id, validRequest(), "admin-api"));
    }

    @Test
    void setActiveTogglesAndSaves() {
        UUID id = UUID.randomUUID();
        BrainPageGuide existing = new BrainPageGuide(
                "/n", "n", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of(), "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        var dto = service.setActive(id, false, "admin-api");

        assertEquals(false, dto.active());
        assertEquals("admin-api", existing.getUpdatedBy());
        verify(repo).save(existing);
    }

    @Test
    void deleteDelegatesToRepository() {
        UUID id = UUID.randomUUID();
        BrainPageGuide existing = new BrainPageGuide(
                "/n", "n", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of(), "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);

        verify(repo).delete(existing);
    }

    @Test
    void deleteThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.delete(id));
        verify(repo, never()).delete(any());
    }

    @Test
    void activePageGuidesCachesFirstReadAndReusesUntilInvalidated() {
        BrainPageGuide a = new BrainPageGuide(
                "/a", "a", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of(), "seed");
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(a));

        List<BrainPageGuide> first = service.activePageGuides();
        List<BrainPageGuide> second = service.activePageGuides();

        assertEquals(1, first.size());
        assertSame(first, second);                                  // served from cache
        verify(repo, times(1)).findByActiveTrueOrderByCreatedAtDescIdDesc();

        service.invalidate();
        service.activePageGuides();
        verify(repo, times(2)).findByActiveTrueOrderByCreatedAtDescIdDesc();  // reloaded after invalidate
    }

    // ---- match() : deterministic route + topic matching (Phase 6) -------

    private BrainPageGuide guide(String route, Surface surface, List<String> topics) {
        return new BrainPageGuide(
                route, "Title", "Purpose", surface,
                List.of(), List.of(), List.of(), List.of(), topics, "seed");
    }

    @Test
    void matchByExactRoute() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        BrainPageGuide va = guide("/loans/va", Surface.BOTH, List.of("va"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha, va));

        List<BrainPageGuide> matches = service.match("/loans/fha", "anything", null);

        assertEquals(1, matches.size());
        assertEquals("/loans/fha", matches.get(0).getRoute());
    }

    @Test
    void matchRouteIsCaseInsensitiveAndTrimmed() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertEquals(1, service.match("  /LOANS/FHA  ", "x", null).size());
    }

    @Test
    void matchByTopicSubstring() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        BrainPageGuide va = guide("/loans/va", Surface.BOTH, List.of("va"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha, va));

        // No pageRoute; topic "fha" is a substring of the question.
        List<BrainPageGuide> matches = service.match(null, "What is an FHA loan?", null);

        assertEquals(1, matches.size());
        assertEquals("/loans/fha", matches.get(0).getRoute());
    }

    @Test
    void matchRouteFirstThenTopicAndDeDupes() {
        // duplexGuide matches BOTH route and topic ("duplex") — must appear once,
        // first (route-exact ordered ahead of topic-only matches).
        BrainPageGuide duplexGuide = guide("/loans/duplex", Surface.BOTH, List.of("duplex"));
        BrainPageGuide fhaGuide = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(duplexGuide, fhaGuide));

        // Question contains both "duplex" (duplexGuide topic + route hit) and "fha".
        List<BrainPageGuide> matches =
                service.match("/loans/duplex", "fha rules for a duplex", null);

        assertEquals(2, matches.size());
        assertEquals("/loans/duplex", matches.get(0).getRoute());   // route-exact first
        assertEquals("/loans/fha", matches.get(1).getRoute());      // topic-only second
    }

    @Test
    void matchSurfacePublicKeepsPublicAndBothExcludesInternal() {
        BrainPageGuide pub = guide("/p", Surface.PUBLIC, List.of("alpha"));
        BrainPageGuide both = guide("/b", Surface.BOTH, List.of("alpha"));
        BrainPageGuide internal = guide("/i", Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, both, internal));

        List<BrainPageGuide> matches = service.match(null, "alpha topic", "PUBLIC");

        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(g -> g.getSurface() == Surface.PUBLIC));
        assertTrue(matches.stream().anyMatch(g -> g.getSurface() == Surface.BOTH));
        assertTrue(matches.stream().noneMatch(g -> g.getSurface() == Surface.INTERNAL));
    }

    @Test
    void matchNullSurfaceKeepsAllSurfaces() {
        BrainPageGuide pub = guide("/p", Surface.PUBLIC, List.of("alpha"));
        BrainPageGuide internal = guide("/i", Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, internal));

        assertEquals(2, service.match(null, "alpha topic", null).size());
    }

    @Test
    void matchBlankQuestionAndBlankRouteIsEmpty() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertTrue(service.match("   ", "   ", null).isEmpty());
        assertTrue(service.match(null, null, null).isEmpty());
    }

    @Test
    void matchBadSurfaceThrowsIllegalArgumentException() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertThrows(IllegalArgumentException.class,
                () -> service.match(null, "fha", "SIDEWAYS"));
    }
}
