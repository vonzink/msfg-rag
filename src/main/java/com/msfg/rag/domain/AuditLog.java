package com.msfg.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full compliance/debugging audit record for every AI interaction.
 * The user question is stored AFTER PII redaction — see PiiRedactionService.
 */
@Entity
@Table(name = "ai_audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "user_question", nullable = false, columnDefinition = "text")
    private String userQuestion;

    @Column(name = "rewritten_question", columnDefinition = "text")
    private String rewrittenQuestion;

    /** The retrieved chunks with their scores, as sent to the prompt builder. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_context", columnDefinition = "jsonb")
    private List<Map<String, Object>> retrievedContext;

    @Column(name = "final_prompt", columnDefinition = "text")
    private String finalPrompt;

    @Column(name = "final_answer", columnDefinition = "text")
    private String finalAnswer;

    @Column(name = "model_provider", length = 50)
    private String modelProvider;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "human_escalation_required", nullable = false)
    private boolean humanEscalationRequired;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public String getUserQuestion() { return userQuestion; }
    public void setUserQuestion(String userQuestion) { this.userQuestion = userQuestion; }

    public String getRewrittenQuestion() { return rewrittenQuestion; }
    public void setRewrittenQuestion(String rewrittenQuestion) { this.rewrittenQuestion = rewrittenQuestion; }

    public List<Map<String, Object>> getRetrievedContext() { return retrievedContext; }
    public void setRetrievedContext(List<Map<String, Object>> retrievedContext) { this.retrievedContext = retrievedContext; }

    public String getFinalPrompt() { return finalPrompt; }
    public void setFinalPrompt(String finalPrompt) { this.finalPrompt = finalPrompt; }

    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }

    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String modelProvider) { this.modelProvider = modelProvider; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public boolean isFallbackUsed() { return fallbackUsed; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }

    public boolean isHumanEscalationRequired() { return humanEscalationRequired; }
    public void setHumanEscalationRequired(boolean humanEscalationRequired) { this.humanEscalationRequired = humanEscalationRequired; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
