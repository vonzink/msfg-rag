package com.msfg.rag.service.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin wrapper around Spring AI's EmbeddingModel so the rest of the app
 * never depends on a specific provider. Currently backed by OpenAI
 * text-embedding-3-small (Anthropic has no embeddings API).
 *
 * Handles OpenAI 429 rate limits with exponential backoff — large documents
 * (e.g. the 1,000+ page FHA handbook) can exhaust the tokens-per-minute
 * budget mid-ingest, and the correct behavior is to wait, not fail.
 *
 * To switch providers (e.g. Bedrock Titan), change the injected bean —
 * no other code changes. NOTE: if the new model has a different dimension,
 * re-embed all chunks and migrate the vector(1536) column accordingly.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final int MAX_ATTEMPTS = 6;
    private static final long INITIAL_BACKOFF_MS = 5_000;

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        return withRateLimitRetry(() -> embeddingModel.embed(text));
    }

    public List<float[]> embedBatch(List<String> texts) {
        return withRateLimitRetry(() -> embeddingModel.embed(texts));
    }

    /** Formats a vector as a pgvector literal, e.g. "[0.12,-0.34,...]". */
    public static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Retries 429 rate-limit errors with exponential backoff
     * (5s, 10s, 20s, 40s, 80s). Other errors propagate immediately.
     */
    private <T> T withRateLimitRetry(java.util.function.Supplier<T> call) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!isRateLimit(e) || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Embedding rate limit hit (attempt {}/{}); waiting {}ms before retry",
                        attempt, MAX_ATTEMPTS, backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff *= 2;
            }
        }
    }

    private boolean isRateLimit(RuntimeException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("429") || message.contains("rate_limit_exceeded"));
    }
}
