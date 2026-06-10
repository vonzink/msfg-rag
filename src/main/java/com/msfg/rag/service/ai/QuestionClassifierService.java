package com.msfg.rag.service.ai;

import com.msfg.rag.pack.DomainPack;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rule-based pre-classifier that runs BEFORE retrieval. Catches questions the
 * bot must never answer (rag.md guardrails) without spending an embedding or
 * LLM call on them.
 *
 * Rule order comes from the pack's classifier.yaml list; FRAUD first by pack
 * convention so "can I hide debt to qualify?" is refused as fraud, not
 * escalated as an eligibility question.
 *
 * COMPLIANCE-CRITICAL: patterns are defined in the domain pack.
 * Additions are fine; removals or loosening need review.
 */
@Service
public class QuestionClassifierService {

    private final Map<QuestionCategory, List<Pattern>> rules;

    public QuestionClassifierService(DomainPack pack) {
        Map<QuestionCategory, List<Pattern>> compiled = new LinkedHashMap<>();
        for (var rule : pack.classifierRules()) {
            compiled.put(rule.category(),
                    rule.patterns().stream().map(Pattern::compile).toList());
        }
        this.rules = compiled;
    }

    public QuestionCategory classify(String question) {
        if (question == null || question.isBlank()) {
            return QuestionCategory.EDUCATIONAL;
        }
        String normalized = question.toLowerCase(Locale.US).strip();

        for (Map.Entry<QuestionCategory, List<Pattern>> entry : rules.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(normalized).find()) {
                    return entry.getKey();
                }
            }
        }
        return QuestionCategory.EDUCATIONAL;
    }
}
