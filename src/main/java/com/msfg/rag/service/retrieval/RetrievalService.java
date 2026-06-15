package com.msfg.rag.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.config.RagProperties;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.ChunkSearchResult;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.service.ai.RuntimeSettings;
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
    private final RuntimeSettings settings;
    private final Map<String, String> acronyms;
    private final List<CompiledProgram> programs;

    public RetrievalService(DocumentChunkRepository chunkRepository,
                            EmbeddingService embeddingService,
                            RerankerService rerankerService,
                            ObjectMapper objectMapper,
                            RagProperties properties,
                            DomainPack pack,
                            RuntimeSettings settings) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rerankerService = rerankerService;
        this.objectMapper = objectMapper;
        this.config = properties.retrieval();
        this.settings = settings;
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

        boolean rerank = settings.rerankEnabled();
        int topK = settings.topK();
        double threshold = settings.confidenceThreshold();

        // Fetch a wider candidate pool from each method, then merge.
        int candidatePool = rerank
                ? config.rerankCandidates()
                : topK * 2;

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
        if (rerank && !ranked.isEmpty()) {
            ranked = rerankerService.rerank(question, ranked, topK);
        } else if (ranked.size() > topK) {
            ranked = ranked.subList(0, topK);
        }

        double confidence = ranked.isEmpty() ? 0.0 : ranked.getFirst().combinedScore();
        boolean sufficient = confidence >= threshold
                && ranked.size() >= Math.min(config.minResults(), topK);

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
     * Expands a question with domain synonyms so borrower/broker vocabulary
     * retrieves guideline-vocabulary chunks (e.g. "owner occupied duplex" also
     * searches "principal residence 2-unit"). Supports multi-word phrase keys
     * via greedy longest-match: longer phrases match first and are masked, so a
     * shorter overlapping phrase cannot also fire — "non-owner occupied" never
     * triggers "owner occupied" -> "principal residence".
     *
     * Matching is normalized (lowercase; hyphens/punctuation -> spaces) on both
     * the question and the keys, so "owner-occupied" and "owner occupied" behave
     * alike. Expansions are appended to the ORIGINAL question in question order;
     * the original still drives program detection and reranking. Returns the
     * question unchanged when nothing matches.
     */
    static String expandQuery(String question, Map<String, String> synonyms) {
        if (question == null || question.isBlank() || synonyms == null || synonyms.isEmpty()) {
            return question;
        }
        String work = " " + normalizeForMatch(question) + " ";

        // Longest (most words) first so phrases win over their sub-words.
        List<Map.Entry<String, String>> entries = synonyms.entrySet().stream()
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, String> e) -> wordCount(normalizeForMatch(e.getKey()))).reversed())
                .toList();

        record Match(int pos, String expansion) {}
        List<Match> matches = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : entries) {
            String key = normalizeForMatch(e.getKey());
            if (key.isBlank()) {
                continue;
            }
            String needle = " " + key + " ";
            int idx = work.indexOf(needle);
            if (idx >= 0) {
                matches.add(new Match(idx, e.getValue()));
                // Mask the matched key (keep the surrounding spaces, preserve length)
                // so a shorter overlapping phrase cannot also match this span.
                String masked = " ".repeat(needle.length());
                work = work.substring(0, idx) + masked + work.substring(idx + needle.length());
            }
        }
        if (matches.isEmpty()) {
            return question;
        }
        matches.sort(Comparator.comparingInt(Match::pos));
        java.util.LinkedHashSet<String> expansions = new java.util.LinkedHashSet<>();
        for (Match m : matches) {
            expansions.add(m.expansion());
        }
        return question + " " + String.join(" ", expansions);
    }

    /** Lowercase; collapse every run of non-alphanumerics (incl. hyphens) to a single space; trim. */
    private static String normalizeForMatch(String s) {
        return s.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static int wordCount(String normalized) {
        if (normalized.isBlank()) {
            return 0;
        }
        return normalized.split("\\s+").length;
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
