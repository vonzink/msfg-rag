package com.msfg.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A guideline document uploaded into the RAG knowledge base,
 * e.g. a Fannie Mae Selling Guide PDF or an MSFG internal overlay policy.
 */
@Entity
@Table(name = "brain_documents")
public class MortgageDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private SourceType sourceType;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "s3_key", length = 1000)
    private String s3Key;

    @Column(name = "document_version", length = 50)
    private String documentVersion;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getS3Key() { return s3Key; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }

    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }

    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public LocalDate getExpirationDate() { return expirationDate; }
    public void setExpirationDate(LocalDate expirationDate) { this.expirationDate = expirationDate; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
