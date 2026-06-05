package com.msfg.rag.service.ingestion;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin wrapper around Spring AI's EmbeddingModel so the rest of the app
 * never depends on a specific provider. Currently backed by OpenAI
 * text-embedding-3-small (Anthropic has no embeddings API).
 *
 * To switch providers (e.g. Bedrock Titan), change the injected bean —
 * no other code changes. NOTE: if the new model has a different dimension,
 * re-embed all chunks and migrate the vector(1536) column accordingly.
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    public List<float[]> embedBatch(List<String> texts) {
        return embeddingModel.embed(texts);
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
}
