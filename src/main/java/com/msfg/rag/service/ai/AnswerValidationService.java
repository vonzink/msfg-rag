package com.msfg.rag.service.ai;

import com.msfg.rag.pack.DomainPack;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Compliance gate that runs on every model answer before it reaches the
 * website. An answer that fails here is never shown to the visitor —
 * the caller returns the escalation response instead.
 *
 * COMPLIANCE-CRITICAL: phrase lists come from the domain pack (guardrails
 * section). Additions to the pack are fine; removals need review.
 */
@Service
public class AnswerValidationService {

    private final List<String> prohibitedPhrases;
    private final String eligiblePhrase;

    public AnswerValidationService(DomainPack pack) {
        this.prohibitedPhrases = pack.guardrails().prohibitedPhrases();
        this.eligiblePhrase = pack.guardrails().eligiblePhrase();
    }

    public ValidationResult validate(ModelAnswer answer, boolean evidenceWasSufficient) {
        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            return ValidationResult.fail("Model returned an empty answer");
        }

        String lower = answer.answer().toLowerCase(Locale.US);

        for (String phrase : prohibitedPhrases) {
            if (lower.contains(phrase)) {
                return ValidationResult.fail("Prohibited phrase detected: \"" + phrase + "\"");
            }
        }

        if (lower.contains(eligiblePhrase) && !isQuoted(answer.answer(), eligiblePhrase)) {
            return ValidationResult.fail("\"You are eligible\" used outside a direct guideline quote");
        }

        // An answer built on sufficient evidence must cite its sources.
        if (evidenceWasSufficient
                && (answer.citations() == null || answer.citations().isEmpty())) {
            return ValidationResult.fail("Answer is missing citations");
        }

        return ValidationResult.pass();
    }

    private boolean isQuoted(String text, String phrase) {
        String lower = text.toLowerCase(Locale.US);
        int idx = lower.indexOf(phrase);
        while (idx >= 0) {
            boolean openQuoteBefore = text.lastIndexOf('"', idx) >= 0
                    || text.lastIndexOf('“', idx) >= 0;
            int end = idx + phrase.length();
            boolean closeQuoteAfter = text.indexOf('"', end) >= 0
                    || text.indexOf('”', end) >= 0;
            if (!(openQuoteBefore && closeQuoteAfter)) {
                return false;
            }
            idx = lower.indexOf(phrase, end);
        }
        return true;
    }

    public record ValidationResult(boolean valid, String failureReason) {

        static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
