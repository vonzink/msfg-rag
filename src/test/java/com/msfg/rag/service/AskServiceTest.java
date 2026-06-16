package com.msfg.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.dto.CitationDto;
import com.msfg.rag.provider.AiResponse;
import com.msfg.rag.repository.AnswerSourceRepository;
import com.msfg.rag.repository.ConversationRepository;
import com.msfg.rag.repository.MessageRepository;
import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.service.ai.AnswerValidationService;
import com.msfg.rag.service.ai.ModelAnswer;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.ai.PromptBuilderService;
import com.msfg.rag.service.ai.QuestionCategory;
import com.msfg.rag.service.ai.QuestionClassifierService;
import com.msfg.rag.service.audit.AuditLogService;
import com.msfg.rag.service.retrieval.RetrievalResult;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the AskService pipeline and its citation-salvage helpers.
 *
 * Helper tests: when the answer model returns a grounded answer but omits the
 * citations array, the pipeline must attach the retrieved approved sources
 * rather than discard a correct answer and escalate.
 *
 * Pipeline tests: when the answer model REFUSES (flags escalation) despite
 * sufficient retrieval, the pipeline must return a single coherent refusal —
 * never the model's raw refusal text decorated with backfilled citations.
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

    // ---- Pipeline tests ------------------------------------------------

    /** Builds an AskService whose model returns exactly {@code modelJson}. */
    private AskService askServiceReturning(String modelJson, List<RetrievedChunk> chunks) {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString()))
                .thenReturn(new RetrievalResult(chunks, 1.0, true));

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer()).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        AiResponse aiResponse = new AiResponse(modelJson, "anthropic", "claude", 10, 10);
        when(router.generate(any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper());
    }

    /** Builds an AskService that classifies every question as {@code category}. */
    private AskService askServiceClassifying(QuestionCategory category) {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString())).thenReturn(category);

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString())).thenReturn(RetrievalResult.empty());

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer()).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);

        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper());
    }

    private AskRequest pmiQuestion() {
        return new AskRequest(null, "session-1", "What is PMI?", null, null, null, null);
    }

    @ParameterizedTest
    @EnumSource(value = QuestionCategory.class,
            names = {"LEGAL", "TAX", "LIVE_RATES", "FRAUD", "ELIGIBILITY"})
    void categoryRefusalsReturnTheMatchingCannedAnswer(QuestionCategory category) {
        AskResponse response = askServiceClassifying(category).ask(pmiQuestion());
        var canned = TestPacks.msfg().guardrails().cannedAnswers();
        String expected = switch (category) {
            case LEGAL -> canned.legal();
            case TAX -> canned.tax();
            case LIVE_RATES -> canned.liveRates();
            case FRAUD -> canned.fraud();
            case ELIGIBILITY -> canned.escalation();
            case EDUCATIONAL -> throw new IllegalStateException("not a refusal category");
        };
        assertEquals(expected, response.answer(), "wrong canned answer for " + category);
        assertTrue(response.humanEscalationRequired());
    }

    @Test
    void modelRefusalReturnsCleanRefusalWithoutBackfilledCitations() {
        // The model nondeterministically refuses despite strong retrieval:
        // empty citations, zero confidence, escalation flagged.
        String refusalJson = """
                {"answer":"I cannot find enough information in the approved source context to answer that.",
                 "citations":[],
                 "confidence":0.0,
                 "human_escalation_required":true,
                 "disclaimer":"d"}""";

        AskResponse response = askServiceReturning(refusalJson, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1))))
                .ask(pmiQuestion());

        assertTrue(response.humanEscalationRequired(), "a refused answer must escalate");
        // The bug: backfilled citations were attached to a refusal, producing a
        // self-contradictory response (refusal text + 8 citations).
        assertTrue(response.citations().isEmpty(),
                "a model refusal must not be decorated with backfilled citations");
        assertEquals(TestPacks.msfg().guardrails().cannedAnswers().noSource(), response.answer(),
                "a refusal must return the canned refusal text, never the model's raw refusal");
        assertEquals("pack-disclaimer", response.disclaimer(),
                "response disclaimer must come from the pack, not the model echo");
    }

    @Test
    void groundedAnswerWithoutCitationsStillGetsBackfilled() {
        // Guards that the refusal branch does not over-trigger: a genuine
        // grounded answer (escalation=false) that merely omits citations must
        // still be salvaged with the retrieved sources.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";

        AskResponse response = askServiceReturning(groundedJson, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1))))
                .ask(pmiQuestion());

        assertFalse(response.humanEscalationRequired(), "a grounded answer must not escalate");
        assertEquals(2, response.citations().size(), "omitted citations must be backfilled");
        assertEquals("PMI is private mortgage insurance that may be required on conventional loans.",
                response.answer());
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
