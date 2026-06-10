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
import java.util.stream.Collectors;

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
    private final RerankerService rerankerService;
    private final ObjectMapper objectMapper;
    private final RagProperties.Retrieval config;

    public RetrievalService(DocumentChunkRepository chunkRepository,
                            EmbeddingService embeddingService,
                            RerankerService rerankerService,
                            ObjectMapper objectMapper,
                            RagProperties properties) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rerankerService = rerankerService;
        this.objectMapper = objectMapper;
        this.config = properties.retrieval();
    }

    @Transactional(readOnly = true)
    public RetrievalResult retrieve(String question) {
        if (question == null || question.isBlank()) {
            return RetrievalResult.empty();
        }

        // Fetch a wider candidate pool from each method, then merge.
        int candidatePool = config.rerankEnabled()
                ? config.rerankCandidates()
                : config.topK() * 2;

        // Expand mortgage acronyms (PMI -> private mortgage insurance) so terse
        // acronym questions retrieve the same definitions as their fuller
        // phrasing. Only the retrieval inputs use the expansion; detectProgram
        // and the reranker below still operate on the original question.
        String expandedQuestion = expandQuery(question);

        float[] questionEmbedding = embeddingService.embed(expandedQuestion);
        String vectorLiteral = EmbeddingService.toVectorLiteral(questionEmbedding);

        List<ChunkSearchResult> vectorHits = chunkRepository.searchByVector(vectorLiteral, candidatePool);
        List<ChunkSearchResult> keywordHits = chunkRepository.searchByKeyword(
                toOrQuery(expandedQuestion), candidatePool);

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

        java.util.Set<String> questionPrograms = detectPrograms(question);
        List<RetrievedChunk> ranked = merged.values().stream()
                .map(hit -> toRetrievedChunk(hit, questionPrograms))
                .sorted(Comparator.comparingDouble(RetrievedChunk::combinedScore).reversed())
                .limit(candidatePool)
                .toList();

        // LLM rerank: hybrid scores find the neighborhood, the reranker picks
        // the truly relevant chunks. Replaces combinedScore with rerank score.
        if (config.rerankEnabled() && !ranked.isEmpty()) {
            ranked = rerankerService.rerank(question, ranked, config.topK());
        } else if (ranked.size() > config.topK()) {
            ranked = ranked.subList(0, config.topK());
        }

        double confidence = ranked.isEmpty() ? 0.0 : ranked.getFirst().combinedScore();
        boolean sufficient = confidence >= config.confidenceThreshold()
                && ranked.size() >= Math.min(config.minResults(), config.topK());

        log.debug("Retrieval: {} vector hits, {} keyword hits, {} merged, confidence={}",
                vectorHits.size(), keywordHits.size(), ranked.size(), confidence);

        return new RetrievalResult(ranked, confidence, sufficient);
    }

    private RetrievedChunk toRetrievedChunk(MutableHit hit, java.util.Set<String> questionPrograms) {
        double combined = config.vectorWeight() * hit.vectorScore
                + config.keywordWeight() * hit.keywordScore;

        // Program-aware ranking: when the question names loan program(s), boost
        // sources for any named program and demote clearly mismatched ones.
        // Prevents e.g. Fannie Mae's 620 conventional minimum from answering an
        // FHA credit-score question, while still letting a question that names
        // TWO programs ("FHA vs conventional") retrieve both sides.
        if (!questionPrograms.isEmpty()) {
            String chunkProgram = detectProgram(
                    hit.source.getSourceName() + " " + hit.source.getDocumentTitle());
            combined = Math.min(1.0, combined * programScoreFactor(questionPrograms, chunkProgram));
        }

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

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does",
            "for", "from", "how", "i", "in", "is", "it", "my", "of", "on", "or",
            "the", "to", "use", "used", "we", "what", "when", "which", "will", "with");

    /**
     * Common mortgage acronyms mapped to their expansions. Terse acronym
     * questions ("What is PMI?") embed weakly and seldom keyword-match the
     * definition, so the answer model self-escalates. Appending the expansion
     * to the retrieval text closes the gap with fuller phrasings without
     * re-embedding the corpus or touching the stored chunks. Keys are lowercase.
     */
    private static final Map<String, String> ACRONYM_EXPANSIONS = Map.ofEntries(
            Map.entry("pmi", "private mortgage insurance"),
            Map.entry("mip", "mortgage insurance premium"),
            Map.entry("dti", "debt-to-income"),
            Map.entry("ltv", "loan-to-value"),
            Map.entry("cltv", "combined loan-to-value"),
            Map.entry("piti", "principal interest taxes insurance"),
            Map.entry("arm", "adjustable-rate mortgage"),
            Map.entry("heloc", "home equity line of credit"),
            Map.entry("hoa", "homeowners association"),
            Map.entry("apr", "annual percentage rate"),
            Map.entry("aus", "automated underwriting system"),
            Map.entry("fha", "Federal Housing Administration"),
            Map.entry("va", "Veterans Affairs"),
            Map.entry("usda", "United States Department of Agriculture"));

    /**
     * Appends expansions for any mortgage acronyms in the question so "What is
     * PMI?" retrieves the same sources as "What is private mortgage insurance?".
     * The expanded text feeds both the embedding and the keyword query; the
     * original question still drives program detection and reranking. Returns
     * the question unchanged when it contains no known acronym. Matching is
     * token-based, so an acronym only expands as a standalone word (the "va" in
     * "available" never triggers).
     */
    static String expandQuery(String question) {
        if (question == null || question.isBlank()) {
            return question;
        }
        String[] tokens = question.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .split("\\s+");
        java.util.LinkedHashSet<String> expansions = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            String expansion = ACRONYM_EXPANSIONS.get(token);
            if (expansion != null) {
                expansions.add(expansion);
            }
        }
        if (expansions.isEmpty()) {
            return question;
        }
        return question + " " + String.join(" ", expansions);
    }

    /**
     * Converts a natural-language question into an OR'd tsquery
     * ("minimum | credit | score | fha | loan"). websearch_to_tsquery ANDs
     * every word, so one missing term zeroes the whole match — far too
     * brittle for conversational questions against guideline text.
     */
    static String toOrQuery(String question) {
        String[] words = question.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .split("\\s+");
        return java.util.Arrays.stream(words)
                .filter(w -> w.length() > 1 && !STOPWORDS.contains(w))
                .distinct()
                .collect(Collectors.joining(" OR "));
    }

    /**
     * Detects every loan program a piece of text refers to, in priority order
     * (FHA, VA, USDA, CONVENTIONAL). A comparison question naming two programs
     * returns both, so neither side is demoted in {@link #toRetrievedChunk}.
     */
    static java.util.Set<String> detectPrograms(String text) {
        java.util.LinkedHashSet<String> programs = new java.util.LinkedHashSet<>();
        if (text == null) {
            return programs;
        }
        String lower = text.toLowerCase(java.util.Locale.US);
        if (lower.contains("fha") || lower.contains("hud") || lower.contains("4000.1")) {
            programs.add("FHA");
        }
        if (lower.matches(".*\\bva\\b.*") || lower.contains("veteran")) {
            programs.add("VA");
        }
        if (lower.contains("usda") || lower.contains("rural development")) {
            programs.add("USDA");
        }
        if (lower.contains("conventional") || lower.contains("fannie")
                || lower.contains("freddie") || lower.contains("conforming")) {
            programs.add("CONVENTIONAL");
        }
        return programs;
    }

    /** Single highest-priority program for a chunk's source text (null if none). */
    private static String detectProgram(String text) {
        return detectPrograms(text).stream().findFirst().orElse(null);
    }

    /**
     * Program-match multiplier for a chunk: 1.2 when the chunk's program is one
     * the question named, 0.4 when the question named program(s) but not this
     * one, 1.0 when the question named no program or the chunk has none. A
     * two-program comparison boosts both named programs.
     */
    static double programScoreFactor(java.util.Set<String> questionPrograms, String chunkProgram) {
        if (questionPrograms.isEmpty() || chunkProgram == null) {
            return 1.0;
        }
        return questionPrograms.contains(chunkProgram) ? 1.2 : 0.4;
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
