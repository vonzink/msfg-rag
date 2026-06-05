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
}
