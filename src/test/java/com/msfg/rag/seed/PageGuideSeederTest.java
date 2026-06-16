package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageGuideSeederTest {

    private final BrainPageGuideRepository repository = mock(BrainPageGuideRepository.class);
    private final DomainPack pack = mock(DomainPack.class);
    private final PageGuideSeeder seeder = new PageGuideSeeder(repository, pack);

    private DomainPack.PageGuide seed() {
        return new DomainPack.PageGuide(
                "/loans/fha", "FHA Loans", "Help users understand FHA.", "BOTH",
                List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new DomainPack.InternalLink("Apply", "/apply?program=fha")),
                List.of("fha"));
    }

    @Test
    void seedsWhenEmptyAndPackHasGuides() {
        when(pack.pageGuides()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(0L);

        seeder.run(null);

        ArgumentCaptor<BrainPageGuide> captor = ArgumentCaptor.forClass(BrainPageGuide.class);
        verify(repository).save(captor.capture());
        BrainPageGuide saved = captor.getValue();
        assertEquals("FHA Loans", saved.getTitle());
        assertEquals("/loans/fha", saved.getRoute());                 // route preserved
        assertEquals(Surface.BOTH, saved.getSurface());               // String -> enum via valueOf
        assertEquals(List.of(new LinkRef("Apply", "/apply?program=fha")), saved.getInternalLinks());
        assertTrue(saved.getSourceLinkIds().isEmpty());               // pack seeds carry no ids
    }

    @Test
    void skipsWhenTableNonEmpty() {
        when(pack.pageGuides()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(1L);

        seeder.run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsWhenPackEmpty() {
        when(pack.pageGuides()).thenReturn(List.of());

        seeder.run(null);

        verify(repository, never()).save(any());
    }
}
