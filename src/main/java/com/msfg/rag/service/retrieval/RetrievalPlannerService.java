package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.service.ai.Intent;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic retrieval planner (Phase 6, spec §7.4). Decides which indexes a
 * question hits ({@link #plan}) and collects the matching side-evidence from the
 * already-merged cached registries ({@link #collect}).
 *
 * <p><b>Phase 6 scope (collect-only seam):</b> the corpus retrieval is unchanged
 * and is NOT executed here — {@code AskService} still calls
 * {@code retrievalService.retrieve(question)} directly. This planner only adds the
 * SIDE indexes (page guides + source links); the collected {@link PlannedEvidence}
 * is logged by the caller and otherwise discarded. The prompt, model, validator,
 * and response are unchanged. Phase 8 consumes the collected evidence.
 *
 * <p><b>Minimal by design:</b> {@link RetrievalPlan} carries only the index set —
 * weights and page-boost (spec §7.4) are deferred to Phase 7.
 *
 * <p><b>Phase 7:</b> {@code collect} now returns the side-evidence already
 * authority-ordered via {@link AuthorityFilterService#order} (links
 * PRIMARY→SECONDARY→BACKGROUND, page-guide order preserved). This is still an
 * INERT seam — the caller logs it and otherwise discards it until Phase 8.
 */
@Service
public class RetrievalPlannerService {

    private final PageGuideService pageGuideService;
    private final SourceLinkService sourceLinkService;
    private final AuthorityFilterService authorityFilterService;

    public RetrievalPlannerService(PageGuideService pageGuideService,
                                   SourceLinkService sourceLinkService,
                                   AuthorityFilterService authorityFilterService) {
        this.pageGuideService = pageGuideService;
        this.sourceLinkService = sourceLinkService;
        this.authorityFilterService = authorityFilterService;
    }

    /**
     * Pure planning function — ignores the injected services and never parses
     * {@code surface} (so it never throws). Rules:
     * <ul>
     *   <li>{@link SourceKind#CORPUS} is always included.</li>
     *   <li>{@link SourceKind#PAGE_GUIDE} is added when {@code pageRoute} is
     *       non-blank OR {@code intent == }{@link Intent#PAGE_GUIDANCE}.</li>
     *   <li>{@link SourceKind#LINK_REGISTRY} is added when
     *       {@code intent == }{@link Intent#EXTERNAL_REFERENCE} OR
     *       {@code intent == }{@link Intent#PAGE_GUIDANCE}.</li>
     * </ul>
     * The default (intent {@link Intent#GUIDELINE_QUESTION}, no {@code pageRoute})
     * is exactly {@code {CORPUS}} — today's behavior.
     */
    public RetrievalPlan plan(Intent intent, String pageRoute, String surface) {
        Set<SourceKind> indexes = new LinkedHashSet<>();
        indexes.add(SourceKind.CORPUS);

        boolean hasRoute = pageRoute != null && !pageRoute.isBlank();
        if (hasRoute || intent == Intent.PAGE_GUIDANCE) {
            indexes.add(SourceKind.PAGE_GUIDE);
        }
        if (intent == Intent.EXTERNAL_REFERENCE || intent == Intent.PAGE_GUIDANCE) {
            indexes.add(SourceKind.LINK_REGISTRY);
        }
        return new RetrievalPlan(indexes);
    }

    /**
     * Collects side-evidence for the planned indexes. Calls
     * {@code pageGuideService.match(pageRoute, question, surface)} when the plan
     * includes {@link SourceKind#PAGE_GUIDE} and
     * {@code sourceLinkService.match(question, surface)} when it includes
     * {@link SourceKind#LINK_REGISTRY}; otherwise the respective list is empty.
     * Never throws on empty inputs (a bad {@code surface} propagates from the
     * matcher as {@link IllegalArgumentException} → HTTP 400, which is correct).
     */
    public PlannedEvidence collect(RetrievalPlan plan, String question,
                                   String pageRoute, String surface) {
        var pageGuides = plan.includes(SourceKind.PAGE_GUIDE)
                ? pageGuideService.match(pageRoute, question, surface)
                : List.<BrainPageGuide>of();
        var links = plan.includes(SourceKind.LINK_REGISTRY)
                ? sourceLinkService.match(question, surface)
                : List.<BrainSourceLink>of();
        // Phase 7: tier + order the collected side-evidence (links PRIMARY-first)
        // so Phase 8 can emit it trust-first. INERT — same membership, only order.
        return authorityFilterService.order(new PlannedEvidence(pageGuides, links));
    }
}
