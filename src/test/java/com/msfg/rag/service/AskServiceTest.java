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
import com.msfg.rag.service.ai.IntentRouterService;
import com.msfg.rag.service.ai.ModelAnswer;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.ai.PromptBuilderService;
import com.msfg.rag.service.ai.QuestionCategory;
import com.msfg.rag.service.ai.QuestionClassifierService;
import com.msfg.rag.service.audit.AuditLogService;
import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.service.retrieval.AuthorityFilterService;
import com.msfg.rag.service.retrieval.PageGuideService;
import com.msfg.rag.service.retrieval.RetrievalPlannerService;
import com.msfg.rag.service.retrieval.RetrievalResult;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import com.msfg.rag.service.retrieval.SourceLinkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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

    /** Promoted to a field so tests can call verify(retrieval, ...) after calling ask(). */
    private RetrievalService retrieval;

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

        retrieval = mock(RetrievalService.class);
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

        PageGuideService pageGuides = mock(PageGuideService.class);
        when(pageGuides.match(any(), anyString(), any())).thenReturn(List.of());
        SourceLinkService sourceLinks = mock(SourceLinkService.class);
        when(sourceLinks.match(anyString(), any())).thenReturn(List.of());

        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()));
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
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(
                        mock(PageGuideService.class), mock(SourceLinkService.class),
                        new AuthorityFilterService()));
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

    /** A PMI question that also carries an optional pageRoute and surface. */
    private AskRequest pmiQuestionWith(String pageRoute, String surface) {
        return new AskRequest(null, "session-1", "What is PMI?", null, null, pageRoute, surface);
    }

    @Test
    void pageRouteDoesNotChangeTheAnswer() {
        // One AskService instance; intent is computed + logged only — must not
        // alter the produced answer or leak into the retrieval question string.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));

        // Re-use a single service + a single stubbed retrieval mock for both calls.
        AskService service = askServiceReturning(groundedJson, chunks);

        AskResponse without  = service.ask(pmiQuestion());
        AskResponse withPage = service.ask(pmiQuestionWith("/loan-options", "PUBLIC"));

        assertEquals(without.answer(), withPage.answer(),
                "pageRoute/surface must not change the answer in Phase 5");
        assertEquals(without.citations().size(), withPage.citations().size());
        assertEquals(without.humanEscalationRequired(), withPage.humanEscalationRequired());
        assertEquals(without.confidence(), withPage.confidence());

        // Prove retrieval always receives the raw question string — pageRoute and
        // surface must never leak into the retrieval call.
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(retrieval, atLeast(2)).retrieve(questionCaptor.capture());
        List<String> capturedQuestions = questionCaptor.getAllValues();
        // Every captured question must equal the original PMI question string.
        capturedQuestions.forEach(q ->
                assertEquals("What is PMI?", q,
                        "retrieve() must be called with the original question, not pageRoute/surface"));
    }

    @Test
    void badSurfaceThrowsIllegalArgumentException() {
        // A malformed surface on an EDUCATIONAL question surfaces as
        // IllegalArgumentException -> HTTP 400 via GlobalExceptionHandler.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AskService service = askServiceReturning(groundedJson, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))));

        assertThrows(IllegalArgumentException.class,
                () -> service.ask(pmiQuestionWith(null, "SIDEWAYS")));
    }

    @Test
    void collectOnlySeamDoesNotChangeTheAnswer() {
        // Phase 6 collect-only (empty-evidence path): the shared askServiceReturning
        // builder stubs both matchers to List.of(), so collect() returns empty
        // PlannedEvidence. Proves that empty side-evidence does not change the answer
        // or citations. The non-empty evidence case is covered by
        // collectOnlySeamDiscardsNonEmptyEvidence below.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));

        AskService service = askServiceReturning(groundedJson, chunks);

        AskResponse without  = service.ask(pmiQuestion());
        AskResponse withPage = service.ask(pmiQuestionWith("/loan-options", "PUBLIC"));

        // The collected side-evidence must not alter the answer.
        assertEquals(without.answer(), withPage.answer(),
                "collected page guides/links must not change the answer in Phase 6");
        assertEquals(without.citations(), withPage.citations(),
                "collected side-evidence must not change citations");
        assertEquals(without.humanEscalationRequired(), withPage.humanEscalationRequired());
        assertEquals(without.confidence(), withPage.confidence());

        // The corpus retrieval question must be the raw question, unchanged by the planner.
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(retrieval, atLeast(2)).retrieve(questionCaptor.capture());
        questionCaptor.getAllValues().forEach(q ->
                assertEquals("What is PMI?", q,
                        "retrieve() must receive the original question, never side-evidence"));
    }

    @Test
    void collectOnlySeamDiscardsNonEmptyEvidence() {
        // Phase 6 collect-only (NON-EMPTY evidence path): stubs the matchers to
        // return a real BrainPageGuide + BrainSourceLink so collect() yields non-empty
        // PlannedEvidence. Proves the actual Phase 6 contract: collected NON-EMPTY
        // side-evidence is discarded without altering the AskResponse answer or
        // citations — the planner seam is inert in Phase 6.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));

        // Build matcher mocks that return non-empty results — reusing the same
        // 10-arg constructors used in RetrievalPlannerServiceTest / Task 5.
        PageGuideService pageGuides = mock(PageGuideService.class);
        BrainPageGuide matchedGuide = new BrainPageGuide(
                "/loan-options", "Loan Options", "Overview of loan options", Surface.PUBLIC,
                List.of(), List.of(), List.of(), List.of(), List.of("pmi"), "seed");
        when(pageGuides.match(any(), anyString(), any())).thenReturn(List.of(matchedGuide));

        SourceLinkService sourceLinks = mock(SourceLinkService.class);
        BrainSourceLink matchedLink = new BrainSourceLink(
                "Fannie Mae", "https://fanniemae.com", "fanniemae.com", LinkAuthority.PRIMARY,
                List.of("pmi"), false, List.of(), List.of(), Surface.PUBLIC, "seed");
        when(sourceLinks.match(anyString(), any())).thenReturn(List.of(matchedLink));

        // Build a dedicated AskService with the non-empty matcher mocks.
        RetrievalService localRetrieval = mock(RetrievalService.class);
        when(localRetrieval.retrieve(anyString())).thenReturn(new RetrievalResult(chunks, 1.0, true));
        PromptBuilderService localPrompt = mock(PromptBuilderService.class);
        when(localPrompt.build(anyString(), anyList())).thenReturn("prompt");
        when(localPrompt.disclaimer()).thenReturn("pack-disclaimer");
        ModelRouterService localRouter = mock(ModelRouterService.class);
        AiResponse localAiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(localRouter.generate(any()))
                .thenReturn(new ModelRouterService.RoutedResponse(localAiResponse, false));
        AuditLogService localAudit = mock(AuditLogService.class);
        ConversationRepository localConversations = mock(ConversationRepository.class);
        when(localConversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository localMessages = mock(MessageRepository.class);
        when(localMessages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository localSources = mock(AnswerSourceRepository.class);
        when(localSources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        QuestionClassifierService localClassifier = mock(QuestionClassifierService.class);
        when(localClassifier.classify(anyString())).thenReturn(QuestionCategory.EDUCATIONAL);

        AskService service = new AskService(
                TestPacks.msfg(),
                localClassifier,
                localRetrieval, localPrompt, localRouter,
                new AnswerValidationService(TestPacks.msfg()),
                localAudit, localConversations, localMessages, localSources,
                new ObjectMapper(), new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()));

        // Baseline: no pageRoute → collect() returns empty (CORPUS-only plan).
        AskResponse without  = service.ask(pmiQuestion());
        // With pageRoute + surface → plan includes PAGE_GUIDE + LINK_REGISTRY,
        // collect() returns non-empty PlannedEvidence (matchedGuide + matchedLink).
        AskResponse withPage = service.ask(pmiQuestionWith("/loan-options", "PUBLIC"));

        // The non-empty collected evidence must NOT alter the answer or citations.
        assertEquals(without.answer(), withPage.answer(),
                "non-empty collected page guides/links must not change the answer in Phase 6");
        assertEquals(without.citations(), withPage.citations(),
                "non-empty collected side-evidence must not change citations");
        assertEquals(without.humanEscalationRequired(), withPage.humanEscalationRequired());
        assertEquals(without.confidence(), withPage.confidence());

        // The corpus retrieval question must be the raw question on both calls —
        // the planner never wraps or replaces it.
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(localRetrieval, atLeast(2)).retrieve(questionCaptor.capture());
        questionCaptor.getAllValues().forEach(q ->
                assertEquals("What is PMI?", q,
                        "retrieve() must receive the original question, not side-evidence"));
    }
}
