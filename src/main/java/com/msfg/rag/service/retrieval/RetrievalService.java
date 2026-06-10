package com.msfg.rag.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.config.RagProperties;
import com.msfg.rag.pack.DomainPack;
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
 * Acronym expansions and program detection rules come from the domain pack
 * (injected via the constructor) so they are company-specific and versioned
 * alongside the pack YAML, not hardcoded here.
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
    private final Map<String, String> acronyms;
    private final List<CompiledProgram> programs;

    public RetrievalService(DocumentChunkRepository chunkRepository,
                            EmbeddingService embeddingService,
                            RerankerService rerankerService,
                            ObjectMapper objectMapper,
                            RagProperties properties,
                            DomainPack pack) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rerankerService = rerankerService;
        this.objectMapper = objectMapper;
        this.config = properties.retrieval();
        this.acronyms = pack.acronymExpansions();
        this.programs = compilePrograms(pack.programRules());
    }

    /** A program rule with its word-boundary regexes precompiled. */
    record CompiledProgram(String name, List<String> keywords, List<java.util.regex.Pattern> patterns) {}

    static List<CompiledProgram> compilePrograms(List<DomainPack.ProgramRule> rules) {
        return rules.stream().map(r -> new CompiledProgram(
                r.program(),
                r.keywords(),
                r.wordPatterns().stream().map(java.util.regex.Pattern::compile).toList()))
                .toList();
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

        // Expand acronyms from the domain pack (e.g. a terse acronym question
        // retrieves the same definitions as its fully spelled-out phrasing).
        // Only the retrieval inputs use the expansion; program detection and
        // the reranker below still operate on the original question.
        String expandedQuestion = expandQuery(question, acronyms);

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

        java.util.Set<String> questionPrograms = detectPrograms(question, programs);
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
            String chunkProgram = detectPrograms(
                    hit.source.getSourceName() + " " + hit.source.getDocumentTitle(),
                    programs).stream().findFirst().orElse(null);
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
     * Appends expansions for any domain acronyms in the question so a terse
     * acronym question retrieves the same sources as its fully spelled-out
     * phrasing. The expanded text feeds both the embedding and the keyword
     * query; the original question still drives program detection and
     * reranking. Returns the question unchanged when it contains no known
     * acronym. Matching is token-based, so an acronym only expands as a
     * standalone word. Expansions come from the domain pack.
     */
    static String expandQuery(String question, Map<String, String> acronyms) {
        if (question == null || question.isBlank()) {
            return question;
        }
        String[] tokens = question.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .split("\\s+");
        java.util.LinkedHashSet<String> expansions = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            String expansion = acronyms.get(token);
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
     * defined by the domain pack. A comparison question naming two programs
     * returns both, so neither side is demoted in {@link #toRetrievedChunk}.
     * Rules come from the domain pack (pre-compiled via {@link #compilePrograms}).
     */
    static java.util.Set<String> detectPrograms(String text, List<CompiledProgram> programs) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        if (text == null) {
            return found;
        }
        String lower = text.toLowerCase(java.util.Locale.US);
        for (CompiledProgram program : programs) {
            boolean hit = program.keywords().stream().anyMatch(lower::contains)
                    || program.patterns().stream().anyMatch(p -> p.matcher(lower).find());
            if (hit) {
                found.add(program.name());
            }
        }
        return found;
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
