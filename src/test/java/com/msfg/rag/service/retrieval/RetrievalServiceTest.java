package com.msfg.rag.service.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RetrievalServiceTest {

    @Test
    void orQueryDropsStopwordsAndOrsTerms() {
        assertEquals("minimum OR credit OR score OR fha OR loan",
                RetrievalService.toOrQuery("What is the minimum credit score for an FHA loan?"));
    }

    @Test
    void orQueryDeduplicatesTerms() {
        assertEquals("gift OR funds OR down OR payment",
                RetrievalService.toOrQuery("gift funds gift funds down payment"));
    }

    @Test
    void orQueryStripsPunctuation() {
        String result = RetrievalService.toOrQuery("Can I use a co-borrower's income?");
        assertFalse(result.contains("-"));
        assertFalse(result.contains("'"));
    }

    @Test
    void expandsKnownAcronymByAppendingDefinition() {
        assertEquals("What is PMI? private mortgage insurance",
                RetrievalService.expandQuery("What is PMI?"));
    }

    @Test
    void expandsAcronymRegardlessOfCase() {
        assertEquals("what is pmi? private mortgage insurance",
                RetrievalService.expandQuery("what is pmi?"));
    }

    @Test
    void expandsMultipleAcronymsInQuestionOrder() {
        assertEquals("How do DTI and LTV affect approval? debt-to-income loan-to-value",
                RetrievalService.expandQuery("How do DTI and LTV affect approval?"));
    }

    @Test
    void leavesQuestionWithoutAcronymsUnchanged() {
        assertEquals("What documents are required to close?",
                RetrievalService.expandQuery("What documents are required to close?"));
    }

    // The expansion has to survive tokenization so the keyword arm of hybrid
    // search matches the PMI definition, not just the embedding.
    @Test
    void expandedAcronymReachesKeywordQuery() {
        assertEquals("pmi OR private OR mortgage OR insurance",
                RetrievalService.toOrQuery(RetrievalService.expandQuery("What is PMI?")));
    }
}
