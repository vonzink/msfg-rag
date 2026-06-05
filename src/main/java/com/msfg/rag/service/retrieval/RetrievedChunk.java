package com.msfg.rag.service.retrieval;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A chunk selected by hybrid search, with all scores and citation metadata.
 */
public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String content,
        String sourceName,
        String sourceType,
        String documentName,
        String documentTitle,
        String section,
        Integer pageNumber,
        LocalDate effectiveDate,
        double vectorScore,
        double keywordScore,
        double combinedScore
) {
}
