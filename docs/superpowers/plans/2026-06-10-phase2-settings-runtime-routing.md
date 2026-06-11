# Phase ② — Settings Store + Runtime Model Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Live-switchable operational knobs — answer/utility model per purpose, retrieval thresholds, rerank toggle — stored in a `brain_settings` table, read through a cached `RuntimeSettings` service, resolved per request by the router, and exposed via admin endpoints. Missing settings fall back to today's env defaults, so an empty table behaves exactly like the current system.

**Architecture:** `brain_settings` (V4, key/value) → `BrainSettingRepository` → `RuntimeSettings` (10s cache, typed accessors, env-default fallback, write+invalidate) → consumed by `ModelRouterService` (per-purpose provider+model resolution; fallback provider always uses its own default model) and `RetrievalService` (threshold/topK/rerank live). `AiRequest` gains `purpose` (ANSWER|UTILITY) and a router-populated `model` override that providers honor. New `AdminSettingsController` (GET/PUT, validation, clear-on-blank) behind `AdminApiKeyFilter`, which learns the `/api/ai/admin` prefix.

**Tech Stack:** Java 21 / Spring Boot 3.5, JPA, Flyway, JUnit 5 + Mockito, Testcontainers (repo gate only). Spec: `docs/superpowers/specs/2026-06-10-rag-brain-platform-design.md` §5.

**Prerequisites:** Branch `feat/phase2-settings-runtime-routing` (off main `0bc5bd4`). Repo root = `~/MSFG/msfg-rag`. Suite green before starting (`./gradlew test --console=plain`, 155 tests).

**Safety rules for every worker:** FIRST command `git branch --show-current` must print `feat/phase2-settings-runtime-routing` — anything else (or empty = detached HEAD) → STOP, report BLOCKED. Never `git checkout <sha>` / `git reset` / `git rebase`. Reviewers are read-only (`git show`/`git diff` only) and never run mutation experiments that touch the tree without restoring via `git checkout -- <file>` and verifying `git status` clean. Don't touch `scripts/`. Full suite before every commit.

---

## File map

| File | Task | Role |
|---|---|---|
| C `src/main/resources/db/migration/V4__create_brain_settings.sql` | 1 | settings table |
| C `src/main/java/com/msfg/rag/domain/BrainSetting.java` | 1 | entity |
| C `src/main/java/com/msfg/rag/repository/BrainSettingRepository.java` | 1 | JPA repo |
| C `src/test/java/com/msfg/rag/repository/BrainSettingRepositoryTest.java` | 1 | V4 + mapping gate (Testcontainers) |
| C `src/main/java/com/msfg/rag/service/ai/RuntimeSettings.java` (+ test) | 2 | cached typed accessors |
| M `src/main/java/com/msfg/rag/provider/AiRequest.java` | 3 | purpose + model override + factories |
| M `AnthropicProvider` / `OpenAiProvider` | 3 | honor `request.model()` |
| M `src/main/java/com/msfg/rag/service/retrieval/RerankerService.java` | 3 | `forUtility` factory |
| M `src/main/java/com/msfg/rag/service/ai/ModelRouterService.java` (+ test) | 4 | per-purpose resolution |
| M `src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java` (+ test) | 5 | live knobs |
| M `src/main/java/com/msfg/rag/config/AdminApiKeyFilter.java` (+ C test) | 6 | gate `/api/ai/admin` |
| C `src/main/java/com/msfg/rag/controller/AdminSettingsController.java` (+ test) | 6 | GET/PUT settings |
| — | 7 | E2E boot verification |

Settings keys (the ONLY allowed keys): `answer.provider`, `answer.model`, `utility.provider`, `utility.model`, `retrieval.confidence-threshold`, `retrieval.top-k`, `rerank.enabled`.

Fallback semantics (load-bearing): model keys are **nullable** — when unset, the router passes no override and each provider uses its own `@Value` default (avoids cross-provider model-name mismatches). `utility.*` falls back to `answer.*` values first, then defaults. Retrieval knobs fall back to `RagProperties.Retrieval`. The **fallback provider** path always sends `model = null` (its own default) — never the primary's model name.

---

### Task 1: brain_settings table + entity + repository

**Files:** as per file map row 1.

- [ ] **1.1** Create `src/main/resources/db/migration/V4__create_brain_settings.sql`:
```sql
-- Live operational knobs (spec §5). Missing keys fall back to env defaults,
-- so an empty table behaves exactly like the pre-settings system.
CREATE TABLE brain_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value TEXT         NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(100) NOT NULL
);
```

- [ ] **1.2** `src/main/java/com/msfg/rag/domain/BrainSetting.java`:
```java
package com.msfg.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One live operational knob (see RuntimeSettings for keys and fallbacks). */
@Entity
@Table(name = "brain_settings")
public class BrainSetting {

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    protected BrainSetting() {}

    public BrainSetting(String key, String value, String updatedBy) {
        this.key = key;
        this.value = value;
        this.updatedBy = updatedBy;
    }

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = OffsetDateTime.now();
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
```

- [ ] **1.3** `src/main/java/com/msfg/rag/repository/BrainSettingRepository.java`:
```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BrainSettingRepository extends JpaRepository<BrainSetting, String> {
}
```

- [ ] **1.4** Test gate `src/test/java/com/msfg/rag/repository/BrainSettingRepositoryTest.java`: READ `HybridSearchIntegrationTest.java` first and mirror its class-level scaffolding exactly (same Testcontainers/`@DataJpaTest` annotations, container setup, property registration). Test body:
```java
    @Test
    void savesAndReadsASetting() {
        repository.save(new BrainSetting("answer.model", "claude-haiku-4-5", "test"));

        BrainSetting loaded = repository.findById("answer.model").orElseThrow();
        assertEquals("claude-haiku-4-5", loaded.getValue());
        assertEquals("test", loaded.getUpdatedBy());
        assertNotNull(loaded.getUpdatedAt());
    }

    @Test
    void upsertOverwritesValueByKey() {
        repository.saveAndFlush(new BrainSetting("retrieval.top-k", "8", "test"));
        BrainSetting existing = repository.findById("retrieval.top-k").orElseThrow();
        existing.setValue("12");
        existing.setUpdatedBy("test2");
        repository.saveAndFlush(existing);

        assertEquals(1, repository.count());
        assertEquals("12", repository.findById("retrieval.top-k").orElseThrow().getValue());
    }
```
(inject `@Autowired BrainSettingRepository repository`.)

- [ ] **1.5** TDD order: write the test, run `./gradlew test --tests "com.msfg.rag.repository.BrainSettingRepositoryTest" --console=plain` → compile failure (entity/repo missing); implement 1.1–1.3; rerun → BUILD SUCCESSFUL (proves V4 applies + mapping). Full suite green.

- [ ] **1.6** Commit:
```bash
git add src/main/resources/db/migration/V4__create_brain_settings.sql src/main/java/com/msfg/rag/domain/BrainSetting.java src/main/java/com/msfg/rag/repository/BrainSettingRepository.java src/test/java/com/msfg/rag/repository/BrainSettingRepositoryTest.java
git commit -m "Add brain_settings table, entity, and repository"
```

---

### Task 2: RuntimeSettings service

**Files:** Create `src/main/java/com/msfg/rag/service/ai/RuntimeSettings.java`, `src/test/java/com/msfg/rag/service/ai/RuntimeSettingsTest.java`.

- [ ] **2.1** Failing tests first (`RuntimeSettingsTest`, plain Mockito — mock `BrainSettingRepository`):
```java
package com.msfg.rag.service.ai;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.domain.BrainSetting;
import com.msfg.rag.repository.BrainSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSettingsTest {

    private final BrainSettingRepository repository = mock(BrainSettingRepository.class);

    private final RagProperties props = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.RateLimit(10));

    private RuntimeSettings settings() {
        return new RuntimeSettings(repository, props);
    }

    @Test
    void emptyTableFallsBackToEnvDefaults() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();

        assertEquals("anthropic", s.answerProvider());
        assertNull(s.answerModel(), "unset model means provider default");
        assertEquals("anthropic", s.utilityProvider(), "utility falls back to answer");
        assertNull(s.utilityModel());
        assertEquals(0.35, s.confidenceThreshold());
        assertEquals(8, s.topK());
        assertTrue(s.rerankEnabled());
    }

    @Test
    void storedValuesWinOverDefaults() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("answer.provider", "openai", "t"),
                new BrainSetting("answer.model", "gpt-4.1-nano", "t"),
                new BrainSetting("retrieval.top-k", "12", "t"),
                new BrainSetting("rerank.enabled", "false", "t")));
        RuntimeSettings s = settings();

        assertEquals("openai", s.answerProvider());
        assertEquals("gpt-4.1-nano", s.answerModel());
        assertEquals("openai", s.utilityProvider(), "utility inherits stored answer values");
        assertEquals("gpt-4.1-nano", s.utilityModel());
        assertEquals(12, s.topK());
        assertEquals(false, s.rerankEnabled());
    }

    @Test
    void utilityOverridesAreIndependentOfAnswer() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("utility.provider", "openai", "t"),
                new BrainSetting("utility.model", "gpt-4.1-nano", "t")));
        RuntimeSettings s = settings();

        assertEquals("anthropic", s.answerProvider());
        assertEquals("openai", s.utilityProvider());
        assertEquals("gpt-4.1-nano", s.utilityModel());
    }

    @Test
    void cachesRepositoryReadsWithinTtl() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();
        s.answerProvider();
        s.topK();
        s.rerankEnabled();
        verify(repository, times(1)).findAll();
    }

    @Test
    void putWritesAndInvalidatesCache() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();
        s.answerProvider(); // prime cache

        s.put("retrieval.top-k", "5", "admin-api");

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(b ->
                b.getKey().equals("retrieval.top-k") && b.getValue().equals("5")
                        && b.getUpdatedBy().equals("admin-api")));
        s.topK(); // must re-read after invalidation
        verify(repository, times(2)).findAll();
    }

    @Test
    void clearDeletesTheOverride() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();
        s.clear("retrieval.top-k");
        verify(repository).deleteById("retrieval.top-k");
    }

    @Test
    void malformedNumericFallsBackToDefaultInsteadOfThrowing() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("retrieval.top-k", "not-a-number", "t")));
        RuntimeSettings s = settings();
        assertEquals(8, s.topK(), "bad stored value must not take down the ask path");
    }

    @Test
    void overridesSnapshotExposesStoredRowsOnly() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("answer.model", "m", "t")));
        RuntimeSettings s = settings();
        assertEquals(java.util.Map.of("answer.model", "m"), s.overrides());
    }
}
```

- [ ] **2.2** Run the class → compile failure. Then implement `RuntimeSettings.java`:

> **CORRECTION (found during implementation):** the `snapshot()` condition below contains an overflow bug — `now - Long.MIN_VALUE` wraps negative, so the cache would never load. The implemented code adds a sentinel check: `if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS)`. Do not copy this block without that fix (see commit 44ff87d).

```java
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

    public String utilityModel() {
        return raw("utility.model", answerModel());
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
        if (now - cachedAtNanos > CACHE_TTL_NANOS) {
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
```

- [ ] **2.3** Class green → full suite green → commit:
```bash
git add src/main/java/com/msfg/rag/service/ai/RuntimeSettings.java src/test/java/com/msfg/rag/service/ai/RuntimeSettingsTest.java
git commit -m "Add RuntimeSettings: cached live knobs with env-default fallback"
```

---

### Task 3: AiRequest purpose + model override; providers honor it

**Files:** Modify `AiRequest.java`, `AnthropicProvider.java`, `OpenAiProvider.java`, `RerankerService.java`; create `src/test/java/com/msfg/rag/provider/AiRequestTest.java`.

- [ ] **3.1** Failing test (`AiRequestTest`):
```java
package com.msfg.rag.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiRequestTest {

    @Test
    void guidelineAnswerFactoryIsAnswerPurposeWithNoOverride() {
        AiRequest request = AiRequest.forGuidelineAnswer("p");
        assertEquals(AiRequest.Purpose.ANSWER, request.purpose());
        assertNull(request.model());
        assertEquals(0.2, request.temperature());
        assertEquals(1500, request.maxTokens());
    }

    @Test
    void utilityFactoryIsUtilityPurpose() {
        AiRequest request = AiRequest.forUtility("p", 0.0, 800);
        assertEquals(AiRequest.Purpose.UTILITY, request.purpose());
        assertNull(request.model());
    }

    @Test
    void withModelKeepsEverythingElse() {
        AiRequest request = AiRequest.forUtility("p", 0.0, 800).withModel("m");
        assertEquals("m", request.model());
        assertEquals(AiRequest.Purpose.UTILITY, request.purpose());
        assertEquals("p", request.prompt());
    }
}
```

- [ ] **3.2** Run → compile failure. Implement `AiRequest.java` (full replacement):
```java
package com.msfg.rag.provider;

/**
 * Provider-agnostic request to a chat model.
 *
 * @param prompt      the fully built prompt
 * @param temperature 0..1, keep low for guideline answers
 * @param maxTokens   completion budget
 * @param purpose     which routing lane this call uses (ANSWER = customer-facing
 *                    answers; UTILITY = internal plumbing like reranking)
 * @param model       router-populated model override; null = provider default.
 *                    Callers never set this directly — use the factories.
 */
public record AiRequest(String prompt, double temperature, int maxTokens,
                        Purpose purpose, String model) {

    public enum Purpose { ANSWER, UTILITY }

    public static AiRequest forGuidelineAnswer(String prompt) {
        return new AiRequest(prompt, 0.2, 1500, Purpose.ANSWER, null);
    }

    public static AiRequest forUtility(String prompt, double temperature, int maxTokens) {
        return new AiRequest(prompt, temperature, maxTokens, Purpose.UTILITY, null);
    }

    public AiRequest withModel(String model) {
        return new AiRequest(prompt, temperature, maxTokens, purpose, model);
    }
}
```

- [ ] **3.3** `RerankerService`: replace `new AiRequest(prompt, 0.0, 800)` with `AiRequest.forUtility(prompt, 0.0, 800)`.

- [ ] **3.4** Both providers: honor the override and report the model actually used. In `AnthropicProvider.generate` (and mirror in `OpenAiProvider`):
```java
        String model = request.model() != null ? request.model() : modelName;
```
— use `model` in the options builder AND as the `modelName` argument of the returned `AiResponse` (audit truthfulness). No other changes.

- [ ] **3.5** Fix any other compile points (`grep -rn "new AiRequest(" src/` — expect only RerankerService + tests; `ModelRouterServiceTest` may construct AiRequest: update those call sites to a factory). Class + full suite green → commit:
```bash
git add src/main/java/com/msfg/rag/provider/ src/main/java/com/msfg/rag/service/retrieval/RerankerService.java src/test/java/com/msfg/rag/provider/AiRequestTest.java $(git diff --name-only -- 'src/test/*')
git commit -m "AiRequest carries purpose and router-set model override"
```

---

### Task 4: ModelRouterService resolves per purpose from RuntimeSettings

**Files:** Modify `ModelRouterService.java` + `ModelRouterServiceTest.java`.

- [ ] **4.1** READ both files first. Add failing tests to `ModelRouterServiceTest` (mirror its existing stub-provider pattern; if it uses hand-rolled fake providers, extend them to capture the received `AiRequest`):
  - `answerPurposeUsesAnswerSettings`: RuntimeSettings mock with `answerProvider()="openai"`, `answerModel()="gpt-x"` → generate(forGuidelineAnswer) calls the openai provider with `request.model()=="gpt-x"`.
  - `utilityPurposeUsesUtilitySettings`: `utilityProvider()="anthropic"`, `utilityModel()=null` → anthropic provider receives `model()==null`.
  - `unknownConfiguredProviderFallsBackToDefaultProvider`: `answerProvider()="gemini"` (not registered) → default provider used, warning logged (assert provider received the call).
  - `fallbackProviderReceivesNoModelOverride`: primary throws; fallback provider must receive `request.model()==null` even when the primary had an override (a primary's model name must never reach the other provider).
  - `providerNames` returns the registered set.
- [ ] **4.2** Run → failures/compile errors confirmed.
- [ ] **4.3** Implement in `ModelRouterService`:
  - Constructor gains `RuntimeSettings settings` (field).
  - `generate(AiRequest request)`:
```java
        boolean utility = request.purpose() == AiRequest.Purpose.UTILITY;
        String configuredProvider = utility ? settings.utilityProvider() : settings.answerProvider();
        String model = utility ? settings.utilityModel() : settings.answerModel();

        AiModelProvider primary = providers.get(configuredProvider);
        if (primary == null) {
            log.warn("Configured {} provider '{}' is not registered; using default '{}'",
                    utility ? "utility" : "answer", configuredProvider, routing.defaultProvider());
            primary = providers.get(routing.defaultProvider());
        }

        try {
            return new RoutedResponse(primary.generate(request.withModel(model)), false);
        } catch (Exception primaryFailure) {
            log.error("Primary AI provider '{}' failed: {}",
                    primary.getProviderName(), primaryFailure.getMessage());

            AiModelProvider fallback = providers.get(routing.fallbackProvider());
            if (fallback == null || fallback == primary) {
                throw primaryFailure;
            }
            log.warn("Falling back to provider '{}'", fallback.getProviderName());
            // The fallback always runs its own default model — a primary's
            // model name must never be sent to a different provider.
            return new RoutedResponse(fallback.generate(request.withModel(null)), true);
        }
```
  - Add `public java.util.Set<String> providerNames() { return providers.keySet(); }`.
- [ ] **4.4** Class green; full suite green; commit `git add src/main/java/com/msfg/rag/service/ai/ModelRouterService.java src/test/java/com/msfg/rag/service/ai/ModelRouterServiceTest.java && git commit -m "Router resolves provider and model per purpose from RuntimeSettings"`.

---

### Task 5: RetrievalService live knobs

**Files:** Modify `RetrievalService.java` + `RetrievalServiceTest.java` (construction sites only, if any build the service directly — check; the static-method tests don't).

- [ ] **5.1** Constructor gains `RuntimeSettings settings` (field, after `DomainPack pack`). Replace the three live reads inside `retrieve(...)` ONLY:
  - `config.rerankEnabled()` → `settings.rerankEnabled()` (both occurrences: candidate pool sizing + the rerank branch — capture once at method start: `boolean rerank = settings.rerankEnabled(); int topK = settings.topK(); double threshold = settings.confidenceThreshold();` and use those locals).
  - `config.topK()` → `topK` local (all occurrences in `retrieve`).
  - `config.confidenceThreshold()` → `threshold`.
  - `config.rerankCandidates()`, `config.minResults()`, `config.vectorWeight()`, `config.keywordWeight()` STAY on `config` (not live knobs in v1).
- [ ] **5.2** No behavior change with an empty table (fallback chain) — the existing suite is the regression gate. If `RetrievalServiceTest` constructs `RetrievalService`, add a `RuntimeSettings` mock returning the defaults; otherwise no test edits.
- [ ] **5.3** Full suite green; commit `git add src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java src/test/java/com/msfg/rag/service/retrieval/RetrievalServiceTest.java && git commit -m "Retrieval threshold, top-k, and rerank toggle read live settings"` (drop the test path from `git add` if untouched).

---

### Task 6: Admin settings endpoints + filter gate

**Files:** Modify `AdminApiKeyFilter.java`; create `AdminApiKeyFilterTest.java`, `src/main/java/com/msfg/rag/controller/AdminSettingsController.java`, `src/test/java/com/msfg/rag/controller/AdminSettingsControllerTest.java`.

- [ ] **6.1** Failing filter test `src/test/java/com/msfg/rag/config/AdminApiKeyFilterTest.java`:
```java
package com.msfg.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminApiKeyFilterTest {

    private final AdminApiKeyFilter filter = new AdminApiKeyFilter(new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.RateLimit(10)));

    @Test
    void gatesDocumentsAndAdminSurfaces() {
        assertFalse(filter.shouldNotFilter(get("/api/ai/documents")));
        assertFalse(filter.shouldNotFilter(get("/api/ai/admin/settings")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/conversations/abc")));
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
```
(`shouldNotFilter` must become `public`, mirroring `RateLimitFilter`.)
- [ ] **6.2** Run → fails (`/api/ai/admin/settings` not gated; visibility). Implement in `AdminApiKeyFilter`:
```java
    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        // Only admin surfaces are gated; /ask and conversation reads are public.
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/ai/documents") || uri.startsWith("/api/ai/admin"));
    }
```
- [ ] **6.3** Failing controller test `AdminSettingsControllerTest` (plain unit, Mockito — no MockMvc):
```java
package com.msfg.rag.controller;

import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.ai.RuntimeSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSettingsControllerTest {

    private final RuntimeSettings settings = mock(RuntimeSettings.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final AdminSettingsController controller =
            new AdminSettingsController(settings, router);

    @Test
    void getReturnsEffectiveValuesAndOverrides() {
        when(settings.answerProvider()).thenReturn("anthropic");
        when(settings.answerModel()).thenReturn(null);
        when(settings.utilityProvider()).thenReturn("anthropic");
        when(settings.utilityModel()).thenReturn(null);
        when(settings.confidenceThreshold()).thenReturn(0.35);
        when(settings.topK()).thenReturn(8);
        when(settings.rerankEnabled()).thenReturn(true);
        when(settings.overrides()).thenReturn(Map.of());

        Map<String, Object> body = controller.get();

        @SuppressWarnings("unchecked")
        Map<String, Object> effective = (Map<String, Object>) body.get("effective");
        assertEquals("anthropic", effective.get("answer.provider"));
        assertEquals(8, effective.get("retrieval.top-k"));
        assertEquals(Map.of(), body.get("overrides"));
    }

    @Test
    void putValidatesProviderAgainstRegistry() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("answer.provider", "gemini")));
        verify(settings, never()).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void putRejectsUnknownKeysAndBadNumbers() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("nope.key", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("retrieval.top-k", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("retrieval.confidence-threshold", "1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("rerank.enabled", "maybe")));
    }

    @Test
    void putWritesValidEntriesAndBlankClearsTheOverride() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));

        controller.put(new java.util.LinkedHashMap<>(Map.of(
                "answer.model", "claude-haiku-4-5",
                "retrieval.top-k", "12")));
        verify(settings).put("answer.model", "claude-haiku-4-5", "admin-api");
        verify(settings).put("retrieval.top-k", "12", "admin-api");

        controller.put(Map.of("answer.model", ""));
        verify(settings).clear("answer.model");
    }
}
```
- [ ] **6.4** Run → compile failure. Implement `AdminSettingsController.java`:
```java
package com.msfg.rag.controller;

import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.ai.RuntimeSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Live operational settings (models per purpose, retrieval knobs). Protected
 * by AdminApiKeyFilter. Values validate before any write; a blank value
 * clears the override so the env default applies again.
 */
@RestController
@RequestMapping("/api/ai/admin/settings")
public class AdminSettingsController {

    private static final Set<String> PROVIDER_KEYS = Set.of("answer.provider", "utility.provider");
    private static final Set<String> MODEL_KEYS = Set.of("answer.model", "utility.model");
    private static final String THRESHOLD_KEY = "retrieval.confidence-threshold";
    private static final String TOP_K_KEY = "retrieval.top-k";
    private static final String RERANK_KEY = "rerank.enabled";

    private final RuntimeSettings settings;
    private final ModelRouterService router;

    public AdminSettingsController(RuntimeSettings settings, ModelRouterService router) {
        this.settings = settings;
        this.router = router;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("answer.provider", settings.answerProvider());
        effective.put("answer.model", settings.answerModel());
        effective.put("utility.provider", settings.utilityProvider());
        effective.put("utility.model", settings.utilityModel());
        effective.put("retrieval.confidence-threshold", settings.confidenceThreshold());
        effective.put("retrieval.top-k", settings.topK());
        effective.put("rerank.enabled", settings.rerankEnabled());
        return Map.of("effective", effective, "overrides", settings.overrides());
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, String> changes) {
        // Validate everything first — a request either fully applies or not at all.
        for (Map.Entry<String, String> change : changes.entrySet()) {
            validate(change.getKey(), change.getValue());
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (change.getValue() == null || change.getValue().isBlank()) {
                settings.clear(change.getKey());
            } else {
                settings.put(change.getKey(), change.getValue().strip(), "admin-api");
            }
        }
        return get();
    }

    private void validate(String key, String value) {
        boolean known = PROVIDER_KEYS.contains(key) || MODEL_KEYS.contains(key)
                || THRESHOLD_KEY.equals(key) || TOP_K_KEY.equals(key) || RERANK_KEY.equals(key);
        if (!known) {
            throw new IllegalArgumentException("Unknown setting key: " + key);
        }
        if (value == null || value.isBlank()) {
            return; // blank = clear the override; always valid
        }
        String v = value.strip();
        if (PROVIDER_KEYS.contains(key) && !router.providerNames().contains(v)) {
            throw new IllegalArgumentException("Unknown provider '" + v
                    + "'. Registered: " + router.providerNames());
        }
        if (THRESHOLD_KEY.equals(key)) {
            double d = parseDouble(key, v);
            if (d < 0.0 || d > 1.0) {
                throw new IllegalArgumentException(key + " must be between 0 and 1");
            }
        }
        if (TOP_K_KEY.equals(key)) {
            int i = parseInt(key, v);
            if (i < 1 || i > 50) {
                throw new IllegalArgumentException(key + " must be between 1 and 50");
            }
        }
        if (RERANK_KEY.equals(key) && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
    }

    private double parseDouble(String key, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + value + "'");
        }
    }

    private int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + value + "'");
        }
    }
}
```
- [ ] **6.5** READ `GlobalExceptionHandler.java` and confirm `IllegalArgumentException` maps to a 400 (it backs the existing controllers' error paths). If it doesn't, add that mapping there following the file's existing style.
- [ ] **6.6** Both test classes green; full suite green; commit:
```bash
git add src/main/java/com/msfg/rag/config/AdminApiKeyFilter.java src/test/java/com/msfg/rag/config/AdminApiKeyFilterTest.java src/main/java/com/msfg/rag/controller/AdminSettingsController.java src/test/java/com/msfg/rag/controller/AdminSettingsControllerTest.java
git commit -m "Admin settings endpoints with validation; admin filter gates /api/ai/admin"
```
(plus `GlobalExceptionHandler.java` in the `git add` if touched.)

---

### Task 7: End-to-end verification

- [ ] **7.1** `./gradlew cleanTest test --console=plain` → BUILD SUCCESSFUL.
- [ ] **7.2** Boot smoke (`set -a && source .env && set +a`, bootRun on **:8090**, wait for health UP; V4 should apply to the dev DB — check the boot log for `Migrating schema "public" to version "4`):
  - `curl -s http://localhost:8090/api/ai/admin/settings` → **401** (no key).
  - With `-H "X-Admin-Api-Key: $ADMIN_API_KEY"`: GET → 200, `effective` shows defaults, `overrides` empty.
  - `PUT` `{"retrieval.top-k":"5"}` → 200; GET shows effective top-k 5, override present.
  - Ask `{"sessionId":"p2","question":"What is PMI?"}` on `/api/ai/mortgage/ask` → still answers coherently (now with top-k 5).
  - `PUT` `{"retrieval.top-k":""}` → override cleared; GET effective back to 8.
  - `PUT` `{"answer.provider":"gemini"}` → **400** with the unknown-provider message.
  - Kill the app; port freed.
- [ ] **7.3** Report all results. No commit expected unless verification surfaced a fix.

---

## Plan self-review (done at write time)

- **Spec coverage (§5):** table/keys/defaults → Tasks 1–2; nullable model semantics + provider-default fallback → 2–4; purpose on AiRequest + router resolution + fallback-without-override → 3–4; validated PUT → 6; cache ~10s → 2; audit traceability (AiResponse reports the actual model used) → 3.4. Dashboard consumes these in Phase ④.
- **Placeholders:** none; the two READ-first steps (1.4 scaffolding mirror, 6.5 exception-handler check) name their exact source files and decision rule.
- **Type consistency:** `RuntimeSettings` accessor names used in Tasks 4–6 match Task 2's definitions (`answerProvider/answerModel/utilityProvider/utilityModel/confidenceThreshold/topK/rerankEnabled/overrides/put/clear/invalidate`); `AiRequest.Purpose.{ANSWER,UTILITY}`, `withModel`, `forUtility` consistent across 3–4; controller ctor `(RuntimeSettings, ModelRouterService)` matches its test.
