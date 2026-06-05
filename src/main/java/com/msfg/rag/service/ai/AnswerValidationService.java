package com.msfg.rag.service.ai;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Compliance gate that runs on every model answer before it reaches the
 * website. An answer that fails here is never shown to the visitor —
 * the caller returns the escalation response instead.
 *
 * COMPLIANCE-CRITICAL: the prohibited phrase list implements the public
 * website safety rules in rag.md. Additions are fine; removals need review.
 */
@Service
public class AnswerValidationService {

    /** Phrases that imply approval, guarantees, or advice we cannot give. */
    private static final List<String> PROHIBITED_PHRASES = List.of(
            "you qualify",
            "you are approved",
            "you're approved",
            "you will be approved",
            "guaranteed",
            "the underwriter must accept",
            "the underwriter will accept",
            "this will close",
            "this loan will close",
            "legal advice:",
            "as your lawyer",
            "as your tax advisor"
    );

    /**
     * "You are eligible" is prohibited unless it appears as a direct quote
     * from guideline context (rag.md rule). We approximate "direct quote" as
     * the phrase appearing inside quotation marks.
     */
    private static final String ELIGIBLE_PHRASE = "you are eligible";

    public ValidationResult validate(ModelAnswer answer, boolean evidenceWasSufficient) {
        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            return ValidationResult.fail("Model returned an empty answer");
        }

        String lower = answer.answer().toLowerCase(Locale.US);

        for (String phrase : PROHIBITED_PHRASES) {
            if (lower.contains(phrase)) {
                return ValidationResult.fail("Prohibited phrase detected: \"" + phrase + "\"");
            }
        }

        if (lower.contains(ELIGIBLE_PHRASE) && !isQuoted(answer.answer(), ELIGIBLE_PHRASE)) {
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
