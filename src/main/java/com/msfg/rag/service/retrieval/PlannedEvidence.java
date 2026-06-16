package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;

import java.util.List;

/**
 * Side-evidence collected alongside corpus retrieval (spec §7.5): matched page
 * guides and source links. This is SEPARATE from {@link RetrievalResult} — corpus
 * chunks are NOT merged in here, so the corpus path and the prompt/sufficiency
 * dependencies on {@link RetrievalResult} stay unchanged.
 *
 * <p><b>Phase 6 scope:</b> this is collected on the proceed path and logged only;
 * nothing reads it yet (the prompt, model, validator, and response are all
 * unchanged). It is the INERT seam Phase 8 consumes to emit
 * recommendedPage/links/nextAction.
 *
 * @param pageGuides matched active page guides (never null; possibly empty)
 * @param links      matched active source links (never null; possibly empty)
 */
public record PlannedEvidence(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links) {

    private static final PlannedEvidence EMPTY = new PlannedEvidence(List.of(), List.of());

    /** Empty side-evidence — the default when only CORPUS is planned. */
    public static PlannedEvidence empty() {
        return EMPTY;
    }
}
