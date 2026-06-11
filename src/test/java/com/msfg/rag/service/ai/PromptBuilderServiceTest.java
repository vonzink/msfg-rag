package com.msfg.rag.service.ai;

import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptBuilderServiceTest {

    private RulesService rulesService;
    private PromptBuilderService promptBuilder;

    @BeforeEach
    void setUp() {
        rulesService = mock(RulesService.class);
        when(rulesService.effectiveHard()).thenReturn(TestPacks.msfg().hardRules());
        when(rulesService.effectiveGuidance()).thenReturn(TestPacks.msfg().guidance());
        promptBuilder = new PromptBuilderService(TestPacks.msfg(), rulesService);
    }

    private RetrievedChunk sampleChunk() {
        return new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                "Overtime income may be used when received for two years.",
                "Fannie Mae Selling Guide", "AGENCY_GUIDELINE",
                "selling-guide.pdf", "Selling Guide 2026",
                "B3-3.1-01", 12, LocalDate.of(2026, 1, 1),
                0.9, 0.7, 0.83);
    }

    @Test
    void includesQuestionAndContext() {
        String prompt = promptBuilder.build("Can I use overtime income?", List.of(sampleChunk()));
        assertTrue(prompt.contains("Can I use overtime income?"));
        assertTrue(prompt.contains("Overtime income may be used"));
        assertTrue(prompt.contains("B3-3.1-01"));
        assertTrue(prompt.contains("Fannie Mae Selling Guide"));
    }

    @Test
    void includesComplianceRules() {
        String prompt = promptBuilder.build("What is PMI?", List.of(sampleChunk()));
        assertTrue(prompt.contains("Do not answer from general knowledge"));
        assertTrue(prompt.contains("Do not invent mortgage guidelines"));
        assertTrue(prompt.contains("Include citations"));
    }

    @Test
    void requiresNonEmptyCitationsWhenSourcesProvided() {
        String prompt = promptBuilder.build("What is PMI?", List.of(sampleChunk()));
        assertTrue(prompt.contains("must contain at least one entry"));
    }

    @Test
    void includesDisclaimer() {
        String prompt = promptBuilder.build("What is DTI?", List.of(sampleChunk()));
        assertTrue(prompt.contains(TestPacks.msfg().disclaimer()));
    }

    @Test
    void handlesEmptyContext() {
        String prompt = promptBuilder.build("What is escrow?", List.of());
        assertTrue(prompt.contains("(no source context found)"));
    }

    @Test
    void composesTemplateSlotsInOrder() {
        String prompt = promptBuilder.build("What is escrow?", List.of());
        String expected = TestPacks.msfg().promptTemplate().formatted(
                TestPacks.msfg().hardRules(),
                TestPacks.msfg().guidance(),
                "(no source context found)",
                "What is escrow?",
                TestPacks.msfg().disclaimer());
        assertEquals(expected, prompt);
    }

    @Test
    void customHardRulesReachThePrompt() {
        when(rulesService.effectiveHard()).thenReturn("ONLY ANSWER IN HAIKU.");
        String prompt = promptBuilder.build("What is PMI?", List.of());
        assertTrue(prompt.contains("ONLY ANSWER IN HAIKU."));
        assertTrue(prompt.contains("What is PMI?"));
    }
}
