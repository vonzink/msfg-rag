package com.msfg.rag.service.ai;

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
 * Order matters: FRAUD is checked first so "can I hide debt to qualify?" is
 * refused as fraud, not escalated as an eligibility question.
 *
 * COMPLIANCE-CRITICAL: patterns here implement the public-website escalation
 * rules. Additions are fine; removals or loosening need review.
 */
@Service
public class QuestionClassifierService {

    private static final Map<QuestionCategory, List<Pattern>> RULES = buildRules();

    public QuestionCategory classify(String question) {
        if (question == null || question.isBlank()) {
            return QuestionCategory.EDUCATIONAL;
        }
        String normalized = question.toLowerCase(Locale.US).strip();

        for (Map.Entry<QuestionCategory, List<Pattern>> entry : RULES.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(normalized).find()) {
                    return entry.getKey();
                }
            }
        }
        return QuestionCategory.EDUCATIONAL;
    }

    private static Map<QuestionCategory, List<Pattern>> buildRules() {
        // LinkedHashMap preserves check order — FRAUD must be evaluated first.
        Map<QuestionCategory, List<Pattern>> rules = new LinkedHashMap<>();

        rules.put(QuestionCategory.FRAUD, List.of(
                Pattern.compile("\\b(hide|hiding|conceal)\\b.*\\b(income|debt|loan|liabilit|asset)"),
                Pattern.compile("\\b(fake|falsif|forge|doctor|alter)\\w*\\b.*\\b(document|paystub|pay stub|w-?2|bank statement|tax return)"),
                Pattern.compile("\\b(lie|lying)\\b.*\\b(lender|application|underwriter|loan)"),
                Pattern.compile("\\bnot (tell|report|disclose)\\b.*\\b(lender|debt|income|loan)"),
                Pattern.compile("\\bwithout (the )?(lender|bank) (knowing|finding out)")
        ));

        rules.put(QuestionCategory.ELIGIBILITY, List.of(
                Pattern.compile("\\b(do|would|will|can|could) i (pre-?)?(qualify|get (pre-?)?approved)\\b"),
                Pattern.compile("\\b(am i|are we) (eligible|approved|qualified)\\b"),
                Pattern.compile("\\bwill (i|we) (be )?(approved|denied|turned down)\\b"),
                Pattern.compile("\\b(approve|deny) (me|my loan|my application)\\b"),
                Pattern.compile("\\bhow much (house|home|mortgage|loan) (can|could) (i|we) (afford|get|qualify)\\b")
        ));

        rules.put(QuestionCategory.LEGAL, List.of(
                Pattern.compile("\\b(sue|suing|lawsuit|litigation)\\b"),
                Pattern.compile("\\b(is (it|this) legal|illegal)\\b"),
                Pattern.compile("\\b(lawyer|attorney)\\b.*\\b(need|should|hire)\\b"),
                Pattern.compile("\\b(need|should|hire)\\b.*\\b(lawyer|attorney)\\b"),
                Pattern.compile("\\bbreach of contract\\b")
        ));

        rules.put(QuestionCategory.TAX, List.of(
                Pattern.compile("\\b(should|how do|how should) (i|we) file\\b.*\\btax"),
                Pattern.compile("\\btax (strategy|advice|loophole)\\b"),
                Pattern.compile("\\b(write|writing) off\\b.*\\b(mortgage|interest|points)\\b"),
                Pattern.compile("\\b(deduct|deduction)\\b.*\\b(should|can) i\\b"),
                Pattern.compile("\\bclaim\\b.*\\bon (my|our) tax(es)?\\b")
        ));

        rules.put(QuestionCategory.LIVE_RATES, List.of(
                Pattern.compile("\\bwhat('s| is| are)? (the |your |today)?\\w* ?rates? (can i get|today|right now|currently)\\b"),
                Pattern.compile("\\b(current|today'?s?) (interest )?rates?\\b"),
                Pattern.compile("\\bquote me\\b"),
                Pattern.compile("\\brate (quote|lock)\\b.*\\b(today|now|get)\\b"),
                Pattern.compile("\\bwhat rate\\b.*\\b(get|offer|give)\\b")
        ));

        return rules;
    }
}
