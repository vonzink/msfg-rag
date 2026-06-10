package com.msfg.rag.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RerankerServiceTest {

    private final RerankerService reranker =
            new RerankerService(null, new ObjectMapper());

    @Test
    void parsesCleanScoreArray() {
        double[] scores = reranker.parseScores(
                "[{\"index\":0,\"score\":7},{\"index\":1,\"score\":2},{\"index\":2,\"score\":10}]", 3);
        assertArrayEquals(new double[]{7, 2, 10}, scores);
    }

    @Test
    void parsesArrayWrappedInProse() {
        double[] scores = reranker.parseScores(
                "Here are the scores:\n[{\"index\":0,\"score\":5},{\"index\":1,\"score\":9}]\nDone.", 2);
        assertArrayEquals(new double[]{5, 9}, scores);
    }

    @Test
    void clampsOutOfRangeScores() {
        double[] scores = reranker.parseScores(
                "[{\"index\":0,\"score\":15},{\"index\":1,\"score\":-3}]", 2);
        assertArrayEquals(new double[]{10, 0}, scores);
    }

    @Test
    void ignoresOutOfBoundsIndexes() {
        double[] scores = reranker.parseScores(
                "[{\"index\":0,\"score\":6},{\"index\":99,\"score\":9}]", 2);
        assertEquals(6, scores[0]);
        assertEquals(0, scores[1]);
    }

    @Test
    void returnsNullForGarbage() {
        assertNull(reranker.parseScores("I cannot rank these.", 3));
        assertNull(reranker.parseScores("", 3));
    }

    // A malformed/empty array must fail open to the original ranking, NOT
    // silently score every candidate 0 (which collapses retrieval confidence
    // to 0.0 and forces a false "no source" refusal).

    @Test
    void returnsNullForEmptyArray() {
        assertNull(reranker.parseScores("[]", 3));
    }

    @Test
    void returnsNullWhenNoEntryHasAUsableIndex() {
        assertNull(reranker.parseScores("[{\"score\":5},{\"relevance\":9}]", 2));
    }

    @Test
    void returnsNullWhenEveryIndexIsOutOfBounds() {
        assertNull(reranker.parseScores("[{\"index\":7,\"score\":9},{\"index\":8,\"score\":4}]", 2));
    }
}
