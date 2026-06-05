package com.msfg.rag.domain;

/**
 * Category of a guideline source. Used for metadata filtering during retrieval
 * and to label citations in answers.
 */
public enum SourceType {
    AGENCY_GUIDELINE,   // Fannie Mae, Freddie Mac, HUD 4000.1, VA, USDA
    INTERNAL_POLICY,    // MSFG overlays and internal policy docs
    INVESTOR_OVERLAY,   // investor-specific overlays
    EDUCATIONAL         // borrower-facing educational content
}
