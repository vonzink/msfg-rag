package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;

import java.util.List;
import java.util.Map;

/**
 * Everything company-specific about one brain, loaded from a pack directory
 * at boot (see DomainPackLoader). Immutable; services inject this instead of
 * holding their own constants. Spec: docs/superpowers/specs/
 * 2026-06-10-rag-brain-platform-design.md §4.
 */
public record DomainPack(
        String slug,
        String companyName,
        String disclaimer,
        String promptTemplate,
        Guardrails guardrails,
        List<ClassifierRule> classifierRules,
        Map<String, String> acronymExpansions,
        List<ProgramRule> programRules
) {

    public DomainPack {
        classifierRules = classifierRules == null ? null : List.copyOf(classifierRules);
        acronymExpansions = acronymExpansions == null ? null : Map.copyOf(acronymExpansions);
        programRules = programRules == null ? null : List.copyOf(programRules);
    }

    public record Guardrails(
            List<String> prohibitedPhrases,
            String eligiblePhrase,
            CannedAnswers cannedAnswers
    ) {
        public Guardrails {
            prohibitedPhrases = prohibitedPhrases == null ? null : List.copyOf(prohibitedPhrases);
        }
    }

    /** The six fixed refusal/escalation texts the pipeline can return. */
    public record CannedAnswers(
            String noSource,
            String escalation,
            String legal,
            String tax,
            String liveRates,
            String fraud
    ) {}

    /** One classifier category with its regex patterns; list order = check order. */
    public record ClassifierRule(QuestionCategory category, List<String> patterns) {
        public ClassifierRule {
            patterns = patterns == null ? null : List.copyOf(patterns);
        }
    }

    /**
     * Program detection for program-aware ranking: substring keywords plus
     * word-boundary regex patterns (e.g. "\\bva\\b" so "available" never
     * matches VA). List order = priority order.
     */
    public record ProgramRule(String program, List<String> keywords, List<String> wordPatterns) {
        public ProgramRule {
            keywords = keywords == null ? null : List.copyOf(keywords);
            wordPatterns = wordPatterns == null ? null : List.copyOf(wordPatterns);
        }
    }
}
