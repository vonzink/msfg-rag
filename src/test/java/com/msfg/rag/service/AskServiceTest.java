package com.msfg.rag.service;

import com.msfg.rag.dto.CitationDto;
import com.msfg.rag.service.ai.ModelAnswer;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the citation-salvage helpers. When the answer model returns a
 * grounded answer but omits the citations array, the pipeline must attach the
 * retrieved approved sources rather than discard a correct answer and escalate.
 */
class AskServiceTest {

    private RetrievedChunk chunk(String sourceName, String documentName,
                                 String section, Integer pageNumber, LocalDate effectiveDate) {
        return new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                "Some grounding content.",
                sourceName, "AGENCY_GUIDELINE",
                documentName, "Doc Title",
                section, pageNumber, effectiveDate,
                0.9, 0.7, 0.83);
    }

    @Test
    void citationsFromChunksMapsAllFields() {
        List<CitationDto> citations = AskService.citationsFromChunks(List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf",
                        "B3-3.1-01", 12, LocalDate.of(2026, 1, 1))));

        assertEquals(1, citations.size());
        CitationDto c = citations.get(0);
        assertEquals("Fannie Mae Selling Guide", c.sourceName());
        assertEquals("selling-guide.pdf", c.documentName());
        assertEquals("B3-3.1-01", c.section());
        assertEquals("12", c.pageNumber());
        assertEquals("2026-01-01", c.effectiveDate());
    }

    @Test
    void citationsFromChunksLeavesMissingMetadataNull() {
        CitationDto c = AskService.citationsFromChunks(List.of(
                chunk("FHA Handbook", "4000.1.pdf", null, null, null))).get(0);

        assertEquals("FHA Handbook", c.sourceName());
        assertNull(c.section());
        assertNull(c.pageNumber());
        assertNull(c.effectiveDate());
    }

    @Test
    void citationsFromChunksMapsEveryChunk() {
        List<CitationDto> citations = AskService.citationsFromChunks(List.of(
                chunk("S1", "d1.pdf", "sec1", 1, LocalDate.of(2026, 1, 1)),
                chunk("S2", "d2.pdf", "sec2", 2, LocalDate.of(2026, 2, 1)),
                chunk("S3", "d3.pdf", "sec3", 3, LocalDate.of(2026, 3, 1))));

        assertEquals(3, citations.size());
    }

    @Test
    void ensureCitationsBackfillsWhenModelReturnsNull() {
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", null, 0.85, false, "d");

        ModelAnswer result = AskService.ensureCitations(answer, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf",
                        "B3-3.1-01", 12, LocalDate.of(2026, 1, 1))));

        assertEquals(1, result.citations().size());
        // Salvage preserves the model's actual answer, not a refusal.
        assertEquals("PMI is mortgage insurance.", result.answer());
        assertEquals(0.85, result.confidence());
        assertFalse(result.humanEscalationRequired());
    }

    @Test
    void ensureCitationsBackfillsWhenModelReturnsEmptyList() {
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", List.of(), 0.85, false, "d");

        ModelAnswer result = AskService.ensureCitations(answer, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf",
                        "B3-3.1-01", 12, LocalDate.of(2026, 1, 1))));

        assertEquals(1, result.citations().size());
    }

    @Test
    void ensureCitationsKeepsModelProvidedCitations() {
        List<CitationDto> modelCitations = List.of(
                new CitationDto("Model Cited Source", "model.pdf", "sec", "5", "2026-01-01"));
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", modelCitations, 0.85, false, "d");

        ModelAnswer result = AskService.ensureCitations(answer, List.of(
                chunk("Retrieved Source", "retrieved.pdf", "other", 99, LocalDate.of(2026, 1, 1))));

        // The model cited its own sources; do not overwrite them with the chunks.
        assertEquals(modelCitations, result.citations());
    }
}
