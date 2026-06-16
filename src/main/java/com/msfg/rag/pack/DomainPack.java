package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;

import java.util.List;
import java.util.Map;

/**
 * Everything company-specific about one brain, loaded from a pack directory
 * at boot (five required files plus optional source-links.yaml and page-guides.yaml; see DomainPackLoader).
 * Immutable; services inject this instead of holding their own constants.
 * Source links default to an empty list when the optional file is absent.
 * Page guides likewise default to an empty list when the optional file is absent.
 * Spec: docs/superpowers/specs/
 * 2026-06-10-rag-brain-platform-design.md §4.
 */
public record DomainPack(
        String slug,
        String companyName,
        String disclaimer,
        String promptTemplate,
        String hardRules,
        String guidance,
        Guardrails guardrails,
        List<ClassifierRule> classifierRules,
        Map<String, String> acronymExpansions,
        List<ProgramRule> programRules,
        List<SourceLink> sourceLinks,
        List<PageGuide> pageGuides
) {

    public DomainPack {
        classifierRules = classifierRules == null ? null : List.copyOf(classifierRules);
        acronymExpansions = acronymExpansions == null ? null : Map.copyOf(acronymExpansions);
        programRules = programRules == null ? null : List.copyOf(programRules);
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
        pageGuides = pageGuides == null ? List.of() : List.copyOf(pageGuides);
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

    /**
     * One optional source/link registry seed entry from source-links.yaml.
     * authority/surface are the enum NAMES (PRIMARY|SECONDARY|BACKGROUND,
     * PUBLIC|INTERNAL|BOTH); the first-boot seeder converts them via valueOf.
     */
    public record SourceLink(
            String name,
            String url,
            String domain,
            String authority,
            List<String> topics,
            boolean freshnessRequired,
            List<String> allowedUse,
            List<String> doNotUseFor,
            String surface
    ) {
        public SourceLink {
            topics = topics == null ? List.of() : List.copyOf(topics);
            allowedUse = allowedUse == null ? List.of() : List.copyOf(allowedUse);
            doNotUseFor = doNotUseFor == null ? List.of() : List.copyOf(doNotUseFor);
        }
    }

    /**
     * One optional page-guide registry seed entry from page-guides.yaml. surface
     * is the enum NAME (PUBLIC|INTERNAL|BOTH); the first-boot seeder converts it
     * via valueOf. route may be null (topic-matched only). sourceLinkIds is
     * always empty in pack seeds (links are attached later via the dashboard,
     * because seeded source-link UUIDs are not known at YAML-authoring time).
     */
    public record PageGuide(
            String route,
            String title,
            String purpose,
            String surface,
            List<String> userIntents,
            List<String> allowedGuidance,
            List<InternalLink> internalLinks,
            List<String> topics
    ) {
        public PageGuide {
            userIntents = userIntents == null ? List.of() : List.copyOf(userIntents);
            allowedGuidance = allowedGuidance == null ? List.of() : List.copyOf(allowedGuidance);
            internalLinks = internalLinks == null ? List.of() : List.copyOf(internalLinks);
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    /** One inline internal link ({label,url}) on a page-guide seed. */
    public record InternalLink(String label, String url) {}
}
