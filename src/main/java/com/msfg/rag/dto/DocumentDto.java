package com.msfg.rag.dto;

import com.msfg.rag.domain.MortgageDocument;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Admin view of a guideline document.
 */
public record DocumentDto(
        UUID id,
        String title,
        String sourceName,
        String sourceType,
        String fileName,
        String documentVersion,
        LocalDate effectiveDate,
        LocalDate expirationDate,
        boolean active
) {

    public static DocumentDto from(MortgageDocument doc) {
        return new DocumentDto(
                doc.getId(),
                doc.getTitle(),
                doc.getSourceName(),
                doc.getSourceType().name(),
                doc.getFileName(),
                doc.getDocumentVersion(),
                doc.getEffectiveDate(),
                doc.getExpirationDate(),
                doc.isActive()
        );
    }
}
