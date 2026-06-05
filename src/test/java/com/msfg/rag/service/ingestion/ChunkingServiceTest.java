package com.msfg.rag.service.ingestion;

import com.msfg.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingServiceTest {

    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties(
                new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.55, 0.65, 0.35),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/test"),
                new RagProperties.Admin("test-key"),
                new RagProperties.RateLimit(10));
        chunkingService = new ChunkingService(properties);
    }

    @Test
    void emptyTextProducesNoChunks() {
        assertTrue(chunkingService.chunk("").isEmpty());
        assertTrue(chunkingService.chunk(null).isEmpty());
    }

    @Test
    void shortTextProducesSingleChunk() {
        List<TextChunk> chunks = chunkingService.chunk(
                "Gift funds may be used for down payment on a primary residence.");
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.getFirst().index());
        assertTrue(chunks.getFirst().tokenCount() > 0);
    }

    @Test
    void longTextRespectsMaxTokenLimit() {
        String paragraph = "Borrower income must be documented with W-2 forms, pay stubs, "
                + "and verification of employment covering the most recent two-year period. ";
        String text = (paragraph + "\n\n").repeat(200);

        List<TextChunk> chunks = chunkingService.chunk(text);

        assertTrue(chunks.size() > 1, "Long text should produce multiple chunks");
        for (TextChunk chunk : chunks) {
            assertTrue(chunk.tokenCount() <= 1200,
                    "Chunk " + chunk.index() + " exceeds max tokens: " + chunk.tokenCount());
        }
    }

    @Test
    void chunkIndexesAreSequential() {
        String text = ("Reserves are funds remaining after closing. ".repeat(60) + "\n\n").repeat(30);
        List<TextChunk> chunks = chunkingService.chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    void consecutiveChunksShareOverlapText() {
        String text = ("Rental income from a departing residence may be considered when "
                + "the borrower has documented equity. ".repeat(40) + "\n\n").repeat(10);
        List<TextChunk> chunks = chunkingService.chunk(text);
        assertTrue(chunks.size() >= 2);

        // The start of chunk N+1 should repeat text from the end of chunk N.
        String tailOfFirst = chunks.get(0).content()
                .substring(Math.max(0, chunks.get(0).content().length() - 200));
        String startOfSecond = chunks.get(1).content().substring(0,
                Math.min(200, chunks.get(1).content().length()));
        assertTrue(sharesAnySentence(tailOfFirst, startOfSecond),
                "Expected overlap between consecutive chunks");
    }

    @Test
    void detectsMarkdownHeadingsAsSections() {
        String text = """
                ## B3-3.1-01 Overtime Income

                Overtime income may be used when it has a consistent two-year history.
                """;
        List<TextChunk> chunks = chunkingService.chunk(text);
        assertEquals(1, chunks.size());
        assertNotNull(chunks.getFirst().heading());
        assertTrue(chunks.getFirst().heading().contains("Overtime Income"));
    }

    @Test
    void tokenCountingWorks() {
        assertEquals(0, chunkingService.countTokens(""));
        assertFalse(chunkingService.countTokens("What is PMI?") == 0);
    }

    private boolean sharesAnySentence(String a, String b) {
        for (String sentence : a.split("(?<=[.!?])\\s+")) {
            String s = sentence.strip();
            if (s.length() > 20 && b.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
