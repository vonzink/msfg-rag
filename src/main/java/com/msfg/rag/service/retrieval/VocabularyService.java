package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.VocabularyRevision;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.VocabularyRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live effective retrieval vocabulary (borrower/broker synonyms), read through a
 * short cache. Pattern mirrors RulesService/RuntimeSettings exactly, including
 * the Long.MIN_VALUE sentinel guard that avoids the overflow a naive
 * {@code now - Long.MIN_VALUE} check would produce. One logical document:
 * custom content fully replaces the pack default; a null-content revision
 * reverts to the pack default.
 */
@Service
public class VocabularyService {

    /** Summary for the admin API and dashboard. */
    public record VocabState(
            String content,
            String source,          // "pack" | "custom"
            OffsetDateTime updatedAt,
            String updatedBy,
            int entries) {}

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s
    private static final int  CONTENT_MAX_CHARS = 50_000;

    private final VocabularyRevisionRepository repo;
    private final DomainPack pack;

    private volatile Optional<VocabularyRevision> cache = Optional.empty();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public VocabularyService(VocabularyRevisionRepository repo, DomainPack pack) {
        this.repo = repo;
        this.pack = pack;
    }

    /** Effective synonym map consumed by RetrievalService. */
    public Map<String, String> effectiveSynonyms() {
        Optional<VocabularyRevision> latest = snapshot();
        if (latest.isPresent() && latest.get().getContent() != null) {
            return VocabularyText.parse(latest.get().getContent());
        }
        return pack.acronymExpansions();
    }

    /** Effective editable text (custom content, or the pack default serialized). */
    public String effectiveText() {
        Optional<VocabularyRevision> latest = snapshot();
        if (latest.isPresent() && latest.get().getContent() != null) {
            return latest.get().getContent();
        }
        return VocabularyText.serialize(pack.acronymExpansions());
    }

    public VocabState state() {
        Optional<VocabularyRevision> latest = snapshot();
        String text = effectiveText();
        int entries = effectiveSynonyms().size();
        if (latest.isEmpty()) {
            return new VocabState(text, "pack", null, null, entries);
        }
        VocabularyRevision rev = latest.get();
        if (rev.getContent() == null) {
            return new VocabState(text, "pack", rev.getCreatedAt(), rev.getCreatedBy(), entries);
        }
        return new VocabState(text, "custom", rev.getCreatedAt(), rev.getCreatedBy(), entries);
    }

    public List<VocabularyRevision> history() {
        return repo.findTop20ByOrderByCreatedAtDescIdDesc();
    }

    /** Expand a sample question with the live vocabulary (dashboard "test a phrase"). */
    public String previewExpansion(String question) {
        return RetrievalService.expandQuery(question, effectiveSynonyms());
    }

    @Transactional
    public void save(String content, String updatedBy) {
        VocabularyText.validate(content);
        if (content.length() > CONTENT_MAX_CHARS) {
            throw new IllegalArgumentException("Vocabulary exceeds " + CONTENT_MAX_CHARS + " characters");
        }
        repo.save(new VocabularyRevision(content, updatedBy));
        invalidate();
    }

    @Transactional
    public void revert(String updatedBy) {
        repo.save(new VocabularyRevision(null, updatedBy));
        invalidate();
    }

    public void invalidate() {
        cachedAtNanos = Long.MIN_VALUE;
    }

    private Optional<VocabularyRevision> snapshot() {
        long now = System.nanoTime();
        if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS) {
            cache = repo.findFirstByOrderByCreatedAtDescIdDesc();
            cachedAtNanos = now;
        }
        return cache;
    }
}
