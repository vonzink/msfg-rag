package com.msfg.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only revision of the editable retrieval vocabulary. NULL content = revert-to-pack marker. */
@Entity
@Table(name = "brain_vocabulary_revisions")
public class VocabularyRevision {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    protected VocabularyRevision() {}

    public VocabularyRevision(String content, String createdBy) {
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onPersist() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getContent() { return content; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
