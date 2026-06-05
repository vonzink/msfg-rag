package com.msfg.rag.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.config.RagProperties;
import com.msfg.rag.repository.ChunkSearchResult;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.service.ingestion.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hybrid retrieval: vector similarity + keyword full-text search, merged with
 * a weighted score. Only chunks from active, currently effective documents are
 * eligible (enforced in the repository queries).
 *
 * Compliance note: the sufficientEvidence flag is the gate that prevents the
 * model from answering without approved source material. Do not bypass it.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final RagProperties.Retrieval config;

    public RetrievalService(DocumentChunkRepository chunkRepository,
                            EmbeddingService embeddingService,
                            ObjectMapper objectMapper,
                            RagProperties properties) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.config = properties.retrieval();
    }

    @Transactional(readOnly = true)
    public RetrievalResult retrieve(String question) {
        if (question == null || question.isBlank()) {
            return RetrievalResult.empty();
        }

        // Fetch a wider candidate pool from each method, then merge.
        int candidatePool = config.topK() * 2;

        float[] questionEmbedding = embeddingService.embed(question);
        String vectorLiteral = EmbeddingService.toVectorLiteral(questionEmbedding);

        List<ChunkSearchResult> vectorHits = chunkRepository.searchByVector(vectorLiteral, candidatePool);
        List<ChunkSearchResult> keywordHits = chunkRepository.searchByKeyword(question, candidatePool);

        Map<UUID, MutableHit> merged = new HashMap<>();
        for (ChunkSearchResult hit : vectorHits) {
            merged.computeIfAbsent(hit.getChunkId(), id -> new MutableHit(hit))
                    .vectorScore = clamp(hit.getScore());
        }
        // Keyword ts_rank_cd values cluster low even for good matches;
        // normalize against the best hit so the top keyword match scores 1.0.
        double maxKeyword = keywordHits.stream()
                .mapToDouble(h -> h.getScore() == null ? 0 : h.getScore())
                .max().orElse(0);
        for (ChunkSearchResult hit : keywordHits) {
            double normalized = maxKeyword > 0 ? clamp(hit.getScore()) / maxKeyword : 0;
            merged.computeIfAbsent(hit.getChunkId(), id -> new MutableHit(hit))
                    .keywordScore = normalized;
        }

        List<RetrievedChunk> ranked = merged.values().stream()
                .map(this::toRetrievedChunk)
                .sorted(Comparator.comparingDouble(RetrievedChunk::combinedScore).reversed())
                .limit(config.topK())
                .toList();

        double confidence = ranked.isEmpty() ? 0.0 : ranked.getFirst().combinedScore();
        boolean sufficient = confidence >= config.confidenceThreshold()
                && ranked.size() >= Math.min(config.minResults(), config.topK());

        log.debug("Retrieval: {} vector hits, {} keyword hits, {} merged, confidence={}",
                vectorHits.size(), keywordHits.size(), ranked.size(), confidence);

        return new RetrievalResult(ranked, confidence, sufficient);
    }

    private RetrievedChunk toRetrievedChunk(MutableHit hit) {
        double combined = config.vectorWeight() * hit.vectorScore
                + config.keywordWeight() * hit.keywordScore;

        String section = null;
        Integer pageNumber = null;
        try {
            JsonNode metadata = objectMapper.readTree(
                    hit.source.getMetadataJson() == null ? "{}" : hit.source.getMetadataJson());
            if (metadata.hasNonNull("section")) {
                section = metadata.get("section").asText();
            }
            if (metadata.hasNonNull("page_number")) {
                pageNumber = metadata.get("page_number").asInt();
            }
        } catch (Exception e) {
            log.warn("Unparseable chunk metadata for chunk {}", hit.source.getChunkId());
        }

        return new RetrievedChunk(
                hit.source.getChunkId(),
                hit.source.getDocumentId(),
                hit.source.getContent(),
                hit.source.getSourceName(),
                hit.source.getSourceType(),
                hit.source.getDocumentName(),
                hit.source.getDocumentTitle(),
                section,
                pageNumber,
                hit.source.getEffectiveDate(),
                hit.vectorScore,
                hit.keywordScore,
                combined
        );
    }

    private static double clamp(Double value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    /** Accumulator while merging the two result lists. */
    private static final class MutableHit {
        final ChunkSearchResult source;
        double vectorScore;
        double keywordScore;

        MutableHit(ChunkSearchResult source) {
            this.source = source;
        }
    }
}
