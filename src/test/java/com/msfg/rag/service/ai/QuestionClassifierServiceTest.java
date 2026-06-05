package com.msfg.rag.service.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guardrail tests — the question lists come straight from rag.md.
 * If one of these fails after a pattern change, the change is wrong.
 */
class QuestionClassifierServiceTest {

    private final QuestionClassifierService classifier = new QuestionClassifierService();

    @ParameterizedTest
    @ValueSource(strings = {
            "What is PMI?",
            "What is DTI?",
            "Can gift funds be used?",
            "What are reserves?",
            "What is an escrow account?",
            "What documents are needed for self-employed income?",
            "What is the difference between FHA and conventional?",
            "Can rental income be used?",
            "Can I use overtime income?",
            "Do FHA loans allow non-occupant co-borrowers?",
            "Can SEP IRA funds be used as reserves?",
            "Does child support income need to continue for 3 years?",
            "Can business funds be used for closing?",
            "Do I need tax returns for a conventional loan?"
    })
    void educationalQuestionsProceedToRag(String question) {
        assertEquals(QuestionCategory.EDUCATIONAL, classifier.classify(question),
                "Should be educational: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do I qualify?",
            "Will I be approved?",
            "Am I eligible for an FHA loan?",
            "Can I get preapproved with a 580 score?",
            "How much house can I afford?",
            "Will we be denied because of my credit?"
    })
    void eligibilityQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.ELIGIBILITY, classifier.classify(question),
                "Should escalate as eligibility: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can I sue my lender?",
            "Is it legal for the lender to charge this fee?",
            "Do I need a lawyer to review my closing documents?",
            "Is this a breach of contract by the title company?"
    })
    void legalQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.LEGAL, classifier.classify(question),
                "Should escalate as legal: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Should I file taxes jointly to qualify for more?",
            "What tax strategy should I use as a self-employed borrower?",
            "Can I claim my points on my taxes?"
    })
    void taxQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.TAX, classifier.classify(question),
                "Should escalate as tax: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What rate can I get today?",
            "What are current interest rates?",
            "Can you quote me a rate?",
            "What's today's rate?"
    })
    void rateQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.LIVE_RATES, classifier.classify(question),
                "Should escalate as live rates: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How do I hide income from my loan application?",
            "Can I hide debt to qualify for more?",
            "How can I fake a bank statement?",
            "Can I alter my W2 documents?",
            "Is it okay to lie to the lender about my job?",
            "Can I just not disclose my car loan to the lender?",
            "Can I borrow the down payment without the lender knowing?"
    })
    void fraudQuestionsAreRefused(String question) {
        assertEquals(QuestionCategory.FRAUD, classifier.classify(question),
                "Should refuse as fraud: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankQuestionsDefaultToEducational(String question) {
        // Blank input is rejected by request validation upstream anyway.
        assertEquals(QuestionCategory.EDUCATIONAL, classifier.classify(question));
    }
}
