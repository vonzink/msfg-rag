package com.msfg.rag.controller;

import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminStatsControllerTest {

    @Test
    void statsCarryBrainIdentityAndCorpusCounts() {
        MortgageDocumentRepository docs = mock(MortgageDocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(docs.count()).thenReturn(13L);
        when(docs.countByActiveTrue()).thenReturn(9L);
        when(chunks.count()).thenReturn(1990L);

        AdminStatsController controller =
                new AdminStatsController(TestPacks.msfg(), docs, chunks);
        AdminStatsController.StatsDto stats = controller.stats();

        assertEquals("Mountain State Financial Group", stats.brain().companyName());
        assertEquals("mortgage", stats.brain().slug());
        assertEquals(9L, stats.corpus().activeDocuments());
        assertEquals(13L, stats.corpus().totalDocuments());
        assertEquals(1990L, stats.corpus().chunks());
    }
}
