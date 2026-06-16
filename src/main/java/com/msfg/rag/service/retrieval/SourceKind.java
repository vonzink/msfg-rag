package com.msfg.rag.service.retrieval;

/**
 * The retrieval indexes a {@link RetrievalPlan} can select (spec §7.4). Phase 6
 * uses these to decide which side-indexes to collect from; {@link SourceKind#CORPUS}
 * is always present (the existing hybrid corpus retrieval is unchanged).
 *
 * <p>{@code PAGE_GUIDE} and {@code LINK_REGISTRY} drive collection from the
 * already-merged cached snapshots ({@code PageGuideService.activePageGuides()} /
 * {@code SourceLinkService.activeLinks()}); the collected evidence is logged only
 * in Phase 6 and consumed by Phase 8.
 */
public enum SourceKind {

    /** The hybrid vector + keyword corpus index — always retrieved (unchanged). */
    CORPUS,

    /** The curated page-guide registry (where to send the user). */
    PAGE_GUIDE,

    /** The curated source-link / trust registry (external references). */
    LINK_REGISTRY
}
