package com.msfg.rag.service.ai;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.domain.BrainSetting;
import com.msfg.rag.repository.BrainSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Live operational knobs backed by brain_settings, read through a short cache.
 * Every accessor falls back to the env-configured default when no row exists,
 * so an empty table behaves exactly like the pre-settings system. Model keys
 * are nullable: null means "use the provider's own default model".
 *
 * Allowed keys: answer.provider, answer.model, utility.provider, utility.model,
 * retrieval.confidence-threshold, retrieval.top-k, rerank.enabled.
 */
@Service
public class RuntimeSettings {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);
    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10s

    private final BrainSettingRepository repository;
    private final RagProperties.Routing routingDefaults;
    private final RagProperties.Retrieval retrievalDefaults;

    private volatile Map<String, String> cache = Map.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public RuntimeSettings(BrainSettingRepository repository, RagProperties properties) {
        this.repository = repository;
        this.routingDefaults = properties.routing();
        this.retrievalDefaults = properties.retrieval();
    }

    public String answerProvider() {
        return raw("answer.provider", routingDefaults.defaultProvider());
    }

    /** Null = use the resolved provider's own default model. */
    public String answerModel() {
        return raw("answer.model", null);
    }

    public String utilityProvider() {
        return raw("utility.provider", answerProvider());
    }

    /**
     * Null = use the resolved provider's own default model. Inherits the
     * answer model only when the utility lane runs on the same provider —
     * a model name must never cross providers.
     */
    public String utilityModel() {
        String explicit = raw("utility.model", null);
        if (explicit != null) {
            return explicit;
        }
        return utilityProvider().equals(answerProvider()) ? answerModel() : null;
    }

    public double confidenceThreshold() {
        return parsed("retrieval.confidence-threshold",
                retrievalDefaults.confidenceThreshold(), Double::parseDouble);
    }

    public int topK() {
        return parsed("retrieval.top-k", retrievalDefaults.topK(), Integer::parseInt);
    }

    public boolean rerankEnabled() {
        return parsed("rerank.enabled", retrievalDefaults.rerankEnabled(), v -> {
            if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                throw new IllegalArgumentException(v);
            }
            return Boolean.parseBoolean(v);
        });
    }

    /** Stored rows only (no defaults) — what the dashboard shows as overridden. */
    public Map<String, String> overrides() {
        return snapshot();
    }

    @Transactional
    public void put(String key, String value, String updatedBy) {
        BrainSetting setting = repository.findById(key)
                .map(existing -> {
                    existing.setValue(value);
                    existing.setUpdatedBy(updatedBy);
                    return existing;
                })
                .orElseGet(() -> new BrainSetting(key, value, updatedBy));
        repository.save(setting);
        invalidate();
    }

    @Transactional
    public void clear(String key) {
        repository.deleteById(key);
        invalidate();
    }

    public void invalidate() {
        cachedAtNanos = Long.MIN_VALUE;
    }

    private String raw(String key, String fallback) {
        String value = snapshot().get(key);
        return value != null ? value : fallback;
    }

    private <T> T parsed(String key, T fallback, java.util.function.Function<String, T> parser) {
        String value = snapshot().get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return parser.apply(value);
        } catch (RuntimeException e) {
            log.warn("brain_settings.{} has unparseable value '{}'; using default {}",
                    key, value, fallback);
            return fallback;
        }
    }

    private Map<String, String> snapshot() {
        long now = System.nanoTime();
        if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS) {
            Map<String, String> fresh = new HashMap<>();
            for (BrainSetting setting : repository.findAll()) {
                fresh.put(setting.getKey(), setting.getValue());
            }
            cache = Map.copyOf(fresh);
            cachedAtNanos = now;
        }
        return cache;
    }
}
