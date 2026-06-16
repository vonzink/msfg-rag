package com.msfg.rag.dto;

import java.util.List;
import java.util.UUID;

/**
 * Public website answer response (rag.md format).
 *
 * <p>Phase 8 (spec §6.3) appends three OPTIONAL, SERVER-DERIVED fields:
 * {@code recommendedPage} (the top matched page guide as {@code {route,label}}),
 * {@code links} (active Link Registry rows resolved + authority-ordered by the
 * planner), and {@code nextAction} (a deterministic next-step string). They are
 * populated only on the success path; the refusal path sets them to
 * {@code null / [] / null}. The corpus-grounded {@code answer} and
 * {@code citations} are unchanged.
 */
public record AskResponse(
        UUID conversationId,
        String answer,
        List<CitationDto> citations,
        double confidence,
        boolean humanEscalationRequired,
        String disclaimer,
        RecommendedPageDto recommendedPage,
        List<LinkDto> links,
        String nextAction
) {
}
