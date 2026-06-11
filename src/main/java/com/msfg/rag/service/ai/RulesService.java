package com.msfg.rag.service.ai;

import com.msfg.rag.domain.RuleRevision;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.RuleRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Live effective text for the two owner-editable rule blocks, read through a
 * short cache.  Pattern mirrors RuntimeSettings exactly, including the fixed
 * Long.MIN_VALUE sentinel guard that avoids the overflow present in a naïve
 * {@code now - Long.MIN_VALUE} check.
 */
@Service
public class RulesService {

    /** Per-key summary exposed to callers (state endpoint, dashboard). */
    public record RuleState(
            String key,
            String content,
            String source,          // "pack" | "custom"
            OffsetDateTime updatedAt,
            String updatedBy) {}

    // ── constants ─────────────────────────────────────────────────────────────

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s
    private static final int  CONTENT_MAX_CHARS = 20_000;
    public static final Set<String>  KEYS = Set.of("rules.hard", "rules.guidance");

    // ── dependencies ──────────────────────────────────────────────────────────

    private final RuleRevisionRepository repo;
    private final DomainPack pack;

    // ── cache ─────────────────────────────────────────────────────────────────

    private volatile Map<String, Optional<RuleRevision>> cache = Map.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public RulesService(RuleRevisionRepository repo, DomainPack pack) {
        this.repo = repo;
        this.pack = pack;
    }

    // ── public accessors ──────────────────────────────────────────────────────

    public String effectiveHard() {
        return effective("rules.hard", pack.hardRules());
    }

    public String effectiveGuidance() {
        return effective("rules.guidance", pack.guidance());
    }

    /** Per-key state map for the admin API and dashboard. */
    public Map<String, RuleState> state() {
        Map<String, Optional<RuleRevision>> snap = snapshot();
        return Map.of(
                "rules.hard",     toState("rules.hard",     snap.get("rules.hard"),     pack.hardRules()),
                "rules.guidance", toState("rules.guidance", snap.get("rules.guidance"), pack.guidance()));
    }

    /** History (newest-first, up to 20 revisions) for a validated key. */
    public List<RuleRevision> history(String key) {
        requireKnownKey(key);
        return repo.findTop20ByRuleKeyOrderByCreatedAtDescIdDesc(key);
    }

    // ── mutation ──────────────────────────────────────────────────────────────

    @Transactional
    public void save(String key, String content, String updatedBy) {
        requireKnownKey(key);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Content must be non-blank for key: " + key);
        }
        if (content.length() > CONTENT_MAX_CHARS) {
            throw new IllegalArgumentException(
                    "Content exceeds " + CONTENT_MAX_CHARS + " characters for key: " + key);
        }
        repo.save(new RuleRevision(key, content, updatedBy));
        invalidate();
    }

    @Transactional
    public void revert(String key, String updatedBy) {
        requireKnownKey(key);
        repo.save(new RuleRevision(key, null, updatedBy));
        invalidate();
    }

    public void invalidate() {
        cachedAtNanos = Long.MIN_VALUE;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String effective(String key, String packDefault) {
        Optional<RuleRevision> latest = snapshot().get(key);
        if (latest != null && latest.isPresent() && latest.get().getContent() != null) {
            return latest.get().getContent();
        }
        return packDefault;
    }

    private RuleState toState(String key, Optional<RuleRevision> latest, String packDefault) {
        if (latest == null || latest.isEmpty()) {
            // no revision at all — pure pack default
            return new RuleState(key, packDefault, "pack", null, null);
        }
        RuleRevision rev = latest.get();
        if (rev.getContent() == null) {
            // explicit revert-to-pack marker: source is "pack" but attribution preserved
            return new RuleState(key, packDefault, "pack", rev.getCreatedAt(), rev.getCreatedBy());
        }
        return new RuleState(key, rev.getContent(), "custom", rev.getCreatedAt(), rev.getCreatedBy());
    }

    /**
     * Refreshes the cache when stale.  The sentinel condition
     * {@code cachedAtNanos == Long.MIN_VALUE} is tested BEFORE the arithmetic
     * expression to avoid the overflow that {@code now - Long.MIN_VALUE} would
     * produce on a freshly-initialised (or invalidated) instance.
     */
    private Map<String, Optional<RuleRevision>> snapshot() {
        long now = System.nanoTime();
        if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS) {
            cache = Map.of(
                    "rules.hard",     repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc("rules.hard"),
                    "rules.guidance", repo.findFirstByRuleKeyOrderByCreatedAtDescIdDesc("rules.guidance"));
            cachedAtNanos = now;
        }
        return cache;
    }

    private void requireKnownKey(String key) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("Unknown rule key: " + key + ". Must be one of " + KEYS);
        }
    }
}
