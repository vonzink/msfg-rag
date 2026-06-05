package com.msfg.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.domain.AnswerSource;
import com.msfg.rag.domain.Conversation;
import com.msfg.rag.domain.Message;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.dto.CitationDto;
import com.msfg.rag.provider.AiRequest;
import com.msfg.rag.repository.AnswerSourceRepository;
import com.msfg.rag.repository.ConversationRepository;
import com.msfg.rag.repository.MessageRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * End-to-end question pipeline:
 * retrieve -> build prompt -> generate -> validate -> cite -> persist -> audit.
 *
 * Two refusal paths, both compliance-required:
 * 1. Insufficient evidence  -> "could not find enough information" response.
 * 2. Failed validation      -> escalation response (never the raw model text).
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    public static final String NO_SOURCE_ANSWER =
            "I could not find enough information in the approved mortgage guidelines to answer "
            + "that confidently. Please contact a licensed loan officer for review.";

    public static final String ESCALATION_ANSWER =
            "This question depends on your full loan file and should be reviewed by a licensed "
            + "loan officer. I can explain the general guideline, but I cannot determine approval "
            + "or eligibility here.";

    public static final String LEGAL_ANSWER =
            "I can't provide legal advice. For legal questions about your mortgage or lender, "
            + "please consult a licensed attorney. I'm happy to explain general mortgage "
            + "guidelines if that helps.";

    public static final String TAX_ANSWER =
            "I can't provide tax advice. A licensed tax professional can review your specific "
            + "situation. I'm happy to explain general mortgage guidelines if that helps.";

    public static final String LIVE_RATES_ANSWER =
            "I don't have access to live rate data, and rates depend on your full loan scenario. "
            + "A licensed loan officer at Mountain State Financial Group can provide a current, "
            + "personalized quote.";

    public static final String FRAUD_ANSWER =
            "I can't help with that. Misrepresenting income, debts, or documents on a mortgage "
            + "application is fraud. If you have questions about what must be disclosed, a "
            + "licensed loan officer can walk you through the requirements.";

    private final QuestionClassifierService questionClassifierService;
    private final RetrievalService retrievalService;
    private final PromptBuilderService promptBuilderService;
    private final ModelRouterService modelRouterService;
    private final AnswerValidationService answerValidationService;
    private final AuditLogService auditLogService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final ObjectMapper objectMapper;

    public AskService(QuestionClassifierService questionClassifierService,
                      RetrievalService retrievalService,
                      PromptBuilderService promptBuilderService,
                      ModelRouterService modelRouterService,
                      AnswerValidationService answerValidationService,
                      AuditLogService auditLogService,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      AnswerSourceRepository answerSourceRepository,
                      ObjectMapper objectMapper) {
        this.questionClassifierService = questionClassifierService;
        this.retrievalService = retrievalService;
        this.promptBuilderService = promptBuilderService;
        this.modelRouterService = modelRouterService;
        this.answerValidationService = answerValidationService;
        this.auditLogService = auditLogService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AskResponse ask(AskRequest request) {
        Conversation conversation = resolveConversation(request);
        saveMessage(conversation, Message.ROLE_USER, request.question(), null);

        // 0. Pre-retrieval guardrail: questions we must not answer are caught
        //    here before any embedding or LLM spend.
        QuestionCategory category = questionClassifierService.classify(request.question());
        if (category != QuestionCategory.EDUCATIONAL) {
            return refuse(conversation, request, RetrievalResult.empty(),
                    categoryAnswer(category), null, "classified as " + category);
        }

        // 1. Retrieve approved source context.
        RetrievalResult retrieval = retrievalService.retrieve(request.question());

        // 2. Refuse early when there is no reliable source material.
        if (!retrieval.sufficientEvidence()) {
            return refuse(conversation, request, retrieval, NO_SOURCE_ANSWER, null,
                    "insufficient evidence");
        }

        // 3. Build the locked prompt and call the model (with fallback).
        String prompt = promptBuilderService.build(request.question(), retrieval.chunks());
        ModelRouterService.RoutedResponse routed =
                modelRouterService.generate(AiRequest.forGuidelineAnswer(prompt));

        // 4. Parse the model's JSON answer.
        ModelAnswer modelAnswer = parseModelAnswer(routed.response().content());
        if (modelAnswer == null) {
            return refuse(conversation, request, retrieval, ESCALATION_ANSWER, prompt,
                    "unparseable model response");
        }

        // 5. Compliance validation — failed answers are never shown.
        var validation = answerValidationService.validate(modelAnswer, true);
        if (!validation.valid()) {
            log.warn("Answer rejected by validator: {}", validation.failureReason());
            return refuse(conversation, request, retrieval, ESCALATION_ANSWER, prompt,
                    validation.failureReason());
        }

        boolean escalate = Boolean.TRUE.equals(modelAnswer.humanEscalationRequired());
        double confidence = modelAnswer.confidence() != null
                ? modelAnswer.confidence()
                : retrieval.confidence();
        List<CitationDto> citations = modelAnswer.citations() == null
                ? List.of()
                : modelAnswer.citations();

        // 6. Persist the assistant message and its citation trail.
        Message assistantMessage = saveMessage(conversation, Message.ROLE_ASSISTANT,
                modelAnswer.answer(), routed.response());
        saveAnswerSources(assistantMessage, retrieval.chunks());

        // 7. Audit log (own transaction, survives rollbacks).
        auditLogService.record(conversation.getId(), request.question(), retrieval.chunks(),
                prompt, modelAnswer.answer(), routed.response().providerName(),
                routed.response().modelName(), confidence, routed.fallbackUsed(), escalate);

        return new AskResponse(conversation.getId(), modelAnswer.answer(), citations,
                confidence, escalate, PromptBuilderService.DISCLAIMER);
    }

    // ------------------------------------------------------------------

    private String categoryAnswer(QuestionCategory category) {
        return switch (category) {
            case ELIGIBILITY -> ESCALATION_ANSWER;
            case LEGAL -> LEGAL_ANSWER;
            case TAX -> TAX_ANSWER;
            case LIVE_RATES -> LIVE_RATES_ANSWER;
            case FRAUD -> FRAUD_ANSWER;
            case EDUCATIONAL -> throw new IllegalStateException(
                    "EDUCATIONAL questions must go through the RAG pipeline");
        };
    }

    private AskResponse refuse(Conversation conversation,
                               AskRequest request,
                               RetrievalResult retrieval,
                               String answerText,
                               String prompt,
                               String reason) {
        log.info("Refusal/escalation for conversation {}: {}", conversation.getId(), reason);

        Message assistantMessage = saveMessage(conversation, Message.ROLE_ASSISTANT, answerText, null);
        saveAnswerSources(assistantMessage, retrieval.chunks());

        auditLogService.record(conversation.getId(), request.question(), retrieval.chunks(),
                prompt, answerText, null, null, retrieval.confidence(), false, true);

        return new AskResponse(conversation.getId(), answerText, List.of(),
                retrieval.confidence(), true, PromptBuilderService.DISCLAIMER);
    }

    private Conversation resolveConversation(AskRequest request) {
        if (request.conversationId() != null) {
            Conversation existing = conversationRepository
                    .findById(request.conversationId()).orElse(null);
            // The conversation must belong to the same website session —
            // prevents one visitor from reading or extending another's chat.
            if (existing != null
                    && Objects.equals(existing.getUserSessionId(), request.sessionId())) {
                return existing;
            }
        }
        Conversation conversation = new Conversation();
        conversation.setUserSessionId(request.sessionId());
        conversation.setSource("website");
        return conversationRepository.save(conversation);
    }

    private Message saveMessage(Conversation conversation, String role, String content,
                                com.msfg.rag.provider.AiResponse aiResponse) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        if (aiResponse != null) {
            message.setModelProvider(aiResponse.providerName());
            message.setModelName(aiResponse.modelName());
            message.setPromptTokens(aiResponse.promptTokens());
            message.setCompletionTokens(aiResponse.completionTokens());
        }
        return messageRepository.save(message);
    }

    private void saveAnswerSources(Message message, List<RetrievedChunk> chunks) {
        for (RetrievedChunk chunk : chunks) {
            AnswerSource source = new AnswerSource();
            source.setMessageId(message.getId());
            source.setDocumentId(chunk.documentId());
            source.setChunkId(chunk.chunkId());
            source.setSimilarityScore(chunk.combinedScore());
            source.setSourceName(chunk.sourceName());
            source.setDocumentName(chunk.documentName());
            source.setSection(chunk.section());
            source.setPageNumber(chunk.pageNumber());
            source.setEffectiveDate(chunk.effectiveDate());
            answerSourceRepository.save(source);
        }
    }

    /**
     * Models sometimes wrap JSON in markdown fences or add prose around it;
     * extract the outermost JSON object before parsing.
     */
    private ModelAnswer parseModelAnswer(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = content.strip();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readValue(json.substring(start, end + 1), ModelAnswer.class);
        } catch (Exception e) {
            log.error("Failed to parse model answer JSON: {}", e.getMessage());
            return null;
        }
    }
}
