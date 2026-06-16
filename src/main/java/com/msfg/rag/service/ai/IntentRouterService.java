package com.msfg.rag.service.ai;

import com.msfg.rag.domain.Surface;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic, code-driven intent router (Phase 5). Computes a heuristic
 * {@link Intent} for an EDUCATIONAL question AFTER the compliance
 * {@link QuestionCategory} gate and BEFORE retrieval. It NEVER replaces the
 * category gate and NEVER folds intent into {@link QuestionCategory}.
 *
 * <p><b>Phase 5 scope:</b> the result is computed and logged by the caller only;
 * nothing reads it yet, so the pipeline behaves identically to today. This is a
 * forward seam for Phase 6.
 *
 * <p><b>Not pack-driven on purpose:</b> the keyword sets below are code
 * constants, not an {@code intent.yaml}. They are a small, deliberately
 * conservative heuristic, NOT the final taxonomy — refine in a later phase once
 * intent is actually consumed.
 *
 * <p><b>Broad substring cues (intentional):</b> keywords like {@code rate},
 * {@code source}, and {@code link} are deliberately broad — they match
 * substrings such as "sepa<b>rate</b>", "re<b>source</b>ful", and
 * "<b>link</b>age". This is acceptable for Phase 5 because nothing consumes
 * intent yet; tighten to word-boundary or phrase matches when Phase 6 actually
 * reads intent routing results.
 *
 * <p><b>Surface parsing divergence (intentional):</b> {@code route()} parses
 * surface as {@code Surface.valueOf(surface.strip().toUpperCase(Locale.US))}
 * (lenient, public-facing — accepts {@code "public"} or {@code "Public"}),
 * whereas the admin {@code SourceLinkService}/{@code PageGuideService} parsers
 * use {@code Surface.valueOf(value.strip())} (case-sensitive). A shared
 * {@code Surface.parse(String)} helper could unify them later; not required
 * for Phase 5.
 *
 * <p><b>Deterministic rule order:</b>
 * <ol>
 *   <li>Validate {@code surface} first (regardless of which branch wins): if
 *       non-null and non-blank, {@code Surface.valueOf(surface.strip().toUpperCase())};
 *       a bad value throws {@link IllegalArgumentException} (mapped to HTTP 400 by
 *       the global handler). Null/blank surface is accepted.</li>
 *   <li>If {@code pageRoute} is non-blank → {@link Intent#PAGE_GUIDANCE}.</li>
 *   <li>If {@code question} is null/blank → {@link Intent#GUIDELINE_QUESTION}
 *       (preserves the classifier's proceed-by-default anchor).</li>
 *   <li>If the question contains any calculation cue → {@link Intent#CALCULATION}.</li>
 *   <li>If the question contains any external-reference cue →
 *       {@link Intent#EXTERNAL_REFERENCE}.</li>
 *   <li>Otherwise → {@link Intent#GUIDELINE_QUESTION}.</li>
 * </ol>
 * Calculation is intentionally checked before external-reference, so a question
 * carrying both cues resolves to {@code CALCULATION} (acceptable for a heuristic
 * seam that nothing consumes yet).
 */
@Service
public class IntentRouterService {

    /** Numeric / calculation cues (matched case-insensitively, substring). */
    private static final List<String> CALCULATION_CUES = List.of(
            "calculate", "payment", "how much", "monthly",
            "dti", "ltv", "amortiz", "rate", "%");

    /** Official / external-source cues (matched case-insensitively, substring). */
    private static final List<String> EXTERNAL_REFERENCE_CUES = List.of(
            "official", "source", "link", "where can i find",
            "guideline number", "handbook");

    /**
     * Computes the heuristic intent. Validates {@code surface} up front so a bad
     * value yields a clean 400 regardless of the winning branch.
     *
     * @param question  the user's question (may be null/blank)
     * @param pageRoute optional page route the caller is on (may be null/blank)
     * @param surface   optional audience string ("PUBLIC"/"INTERNAL"/"BOTH",
     *                  case-insensitive); a bad value throws IllegalArgumentException
     * @return the computed {@link Intent}; never null
     * @throws IllegalArgumentException if {@code surface} is non-blank and not a
     *                                  valid {@link Surface} name
     */
    public Intent route(String question, String pageRoute, String surface) {
        // 1. Validate surface first (independent of which branch wins).
        if (surface != null && !surface.isBlank()) {
            Surface.valueOf(surface.strip().toUpperCase(Locale.US));
        }

        // 2. pageRoute wins.
        if (pageRoute != null && !pageRoute.isBlank()) {
            return Intent.PAGE_GUIDANCE;
        }

        // 3. Proceed-by-default anchor for empty questions.
        if (question == null || question.isBlank()) {
            return Intent.GUIDELINE_QUESTION;
        }

        String normalized = question.toLowerCase(Locale.US).strip();

        // 4. Calculation cues (checked before external-reference by design).
        if (containsAny(normalized, CALCULATION_CUES)) {
            return Intent.CALCULATION;
        }

        // 5. External-reference cues.
        if (containsAny(normalized, EXTERNAL_REFERENCE_CUES)) {
            return Intent.EXTERNAL_REFERENCE;
        }

        // 6. Neutral default.
        return Intent.GUIDELINE_QUESTION;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
