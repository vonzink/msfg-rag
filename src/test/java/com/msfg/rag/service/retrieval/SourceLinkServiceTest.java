package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceLinkServiceTest {

    private final BrainSourceLinkRepository repo = mock(BrainSourceLinkRepository.class);
    private final SourceLinkService service = new SourceLinkService(repo);

    private SourceLinkRequest validRequest() {
        return new SourceLinkRequest(
                "  Fannie Mae Selling Guide  ", "https://selling-guide.fanniemae.com", "fanniemae.com",
                "PRIMARY", List.of("conventional"), true,
                List.of("cite guideline sections"), List.of("legal advice"), "BOTH");
    }

    @Test
    void createStripsAndPersistsAndInvalidates() {
        when(repo.save(any(BrainSourceLink.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(validRequest(), "admin-api");

        assertEquals("Fannie Mae Selling Guide", dto.name());        // stripped
        assertEquals("PRIMARY", dto.authority());
        assertEquals("BOTH", dto.surface());
        assertTrue(dto.freshnessRequired());
        verify(repo).save(any(BrainSourceLink.class));
    }

    @Test
    void createRejectsBlankName() {
        SourceLinkRequest req = new SourceLinkRequest(
                "   ", "https://x.com", null, "PRIMARY", List.of(), false, List.of(), List.of(), "BOTH");
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsBlankUrl() {
        SourceLinkRequest req = new SourceLinkRequest(
                "Name", "  ", null, "PRIMARY", List.of(), false, List.of(), List.of(), "BOTH");
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsUnknownAuthority() {
        SourceLinkRequest req = new SourceLinkRequest(
                "Name", "https://x.com", null, "NOPE", List.of(), false, List.of(), List.of(), "BOTH");
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsUnknownSurface() {
        SourceLinkRequest req = new SourceLinkRequest(
                "Name", "https://x.com", null, "PRIMARY", List.of(), false, List.of(), List.of(), "SIDEWAYS");
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
        BrainSourceLink existing = new BrainSourceLink(
                "old", "https://old.com", "old.com", LinkAuthority.BACKGROUND,
                List.of("x"), false, List.of(), List.of(), Surface.PUBLIC, "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        var dto = service.update(id, validRequest(), "admin-api");

        assertEquals("Fannie Mae Selling Guide", existing.getName());
        assertEquals("https://selling-guide.fanniemae.com", existing.getUrl());
        assertEquals(LinkAuthority.PRIMARY, existing.getAuthority());
        assertEquals(Surface.BOTH, existing.getSurface());
        assertEquals("admin-api", existing.getUpdatedBy());
        assertEquals("Fannie Mae Selling Guide", dto.name());
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
        BrainSourceLink existing = new BrainSourceLink(
                "n", "https://n.com", null, LinkAuthority.PRIMARY,
                List.of(), false, List.of(), List.of(), Surface.BOTH, "seed");
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
        BrainSourceLink existing = new BrainSourceLink(
                "n", "https://n.com", null, LinkAuthority.PRIMARY,
                List.of(), false, List.of(), List.of(), Surface.BOTH, "seed");
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

    // ---- match() : deterministic topic matching (Phase 6) --------------

    private BrainSourceLink link(Surface surface, List<String> topics) {
        return new BrainSourceLink(
                "Name", "https://x.com", "x.com", LinkAuthority.PRIMARY,
                topics, false, List.of(), List.of(), surface, "seed");
    }

    @Test
    void matchByTopicSubstring() {
        BrainSourceLink fha = link(Surface.BOTH, List.of("fha"));
        BrainSourceLink va = link(Surface.BOTH, List.of("va"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha, va));

        List<BrainSourceLink> matches = service.match("What is an FHA loan?", null);

        assertEquals(1, matches.size());
        assertEquals(List.of("fha"), matches.get(0).getTopics());
    }

    @Test
    void matchSurfacePublicKeepsPublicAndBothExcludesInternal() {
        BrainSourceLink pub = link(Surface.PUBLIC, List.of("alpha"));
        BrainSourceLink both = link(Surface.BOTH, List.of("alpha"));
        BrainSourceLink internal = link(Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, both, internal));

        List<BrainSourceLink> matches = service.match("alpha topic", "PUBLIC");

        assertEquals(2, matches.size());
        assertTrue(matches.stream().noneMatch(l -> l.getSurface() == Surface.INTERNAL));
    }

    @Test
    void matchNullSurfaceKeepsAllSurfaces() {
        BrainSourceLink pub = link(Surface.PUBLIC, List.of("alpha"));
        BrainSourceLink internal = link(Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, internal));

        assertEquals(2, service.match("alpha topic", null).size());
    }

    @Test
    void matchBlankQuestionIsEmpty() {
        BrainSourceLink fha = link(Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertTrue(service.match("   ", null).isEmpty());
        assertTrue(service.match(null, null).isEmpty());
    }

    @Test
    void matchBadSurfaceThrowsIllegalArgumentException() {
        BrainSourceLink fha = link(Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertThrows(IllegalArgumentException.class,
                () -> service.match("fha", "SIDEWAYS"));
    }

    @Test
    void activeLinksCachesFirstReadAndReusesUntilInvalidated() {
        BrainSourceLink a = new BrainSourceLink(
                "a", "https://a.com", null, LinkAuthority.PRIMARY,
                List.of(), false, List.of(), List.of(), Surface.BOTH, "seed");
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(a));

        List<BrainSourceLink> first = service.activeLinks();
        List<BrainSourceLink> second = service.activeLinks();

        assertEquals(1, first.size());
        assertSame(first, second);                                  // served from cache
        verify(repo, times(1)).findByActiveTrueOrderByCreatedAtDescIdDesc();

        service.invalidate();
        service.activeLinks();
        verify(repo, times(2)).findByActiveTrueOrderByCreatedAtDescIdDesc();  // reloaded after invalidate
    }
}
