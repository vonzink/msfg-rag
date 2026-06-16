package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceLinkSeederTest {

    private final BrainSourceLinkRepository repository = mock(BrainSourceLinkRepository.class);
    private final DomainPack pack = mock(DomainPack.class);
    private final SourceLinkSeeder seeder = new SourceLinkSeeder(repository, pack);

    private DomainPack.SourceLink seed() {
        return new DomainPack.SourceLink(
                "Fannie Mae Selling Guide", "https://selling-guide.fanniemae.com/", "fanniemae.com",
                "PRIMARY", List.of("conventional"), true,
                List.of("cite guideline sections"), List.of("legal advice"), "BOTH");
    }

    @Test
    void seedsWhenEmptyAndPackHasLinks() {
        when(pack.sourceLinks()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(0L);

        seeder.run(null);

        ArgumentCaptor<BrainSourceLink> captor = ArgumentCaptor.forClass(BrainSourceLink.class);
        verify(repository).save(captor.capture());
        BrainSourceLink saved = captor.getValue();
        assertEquals("Fannie Mae Selling Guide", saved.getName());
        assertEquals(LinkAuthority.PRIMARY, saved.getAuthority());   // String -> enum via valueOf
        assertEquals(Surface.BOTH, saved.getSurface());              // String -> enum via valueOf
    }

    @Test
    void skipsWhenTableNonEmpty() {
        when(pack.sourceLinks()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(1L);

        seeder.run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsWhenPackEmpty() {
        when(pack.sourceLinks()).thenReturn(List.of());

        seeder.run(null);

        verify(repository, never()).save(any());
    }
}
