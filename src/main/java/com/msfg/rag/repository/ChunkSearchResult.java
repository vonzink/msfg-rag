package com.msfg.rag.repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Projection for hybrid search results coming back from native queries.
 * Carries the chunk content plus everything needed to build a citation.
 */
public interface ChunkSearchResult {

    UUID getChunkId();

    UUID getDocumentId();

    String getContent();

    String getMetadataJson();

    String getSourceName();

    String getSourceType();

    String getDocumentName();

    String getDocumentTitle();

    LocalDate getEffectiveDate();

    /** Cosine similarity (vector search) or normalized ts_rank (keyword search). */
    Double getScore();
}
