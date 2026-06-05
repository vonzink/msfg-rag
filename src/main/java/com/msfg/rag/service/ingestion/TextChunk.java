package com.msfg.rag.service.ingestion;

/**
 * Intermediate result from the chunker, before embedding and persistence.
 *
 * @param index      0-based position of this chunk within the document
 * @param content    the chunk text
 * @param tokenCount token count measured with the cl100k_base tokenizer
 * @param heading    nearest preceding heading, if detected (for citations)
 */
public record TextChunk(int index, String content, int tokenCount, String heading) {
}
