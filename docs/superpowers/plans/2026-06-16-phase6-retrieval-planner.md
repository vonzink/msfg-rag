# Phase 6 — Retrieval Planner + multi-index collection (collect-only seam)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a deterministic `RetrievalPlannerService` that (a) decides which indexes a question hits — `RetrievalPlan { indexes: Set<SourceKind> }` — and (b) **collects** matching page guides + source links from the already-merged cached snapshots (`PageGuideService.activePageGuides()` / `SourceLinkService.activeLinks()`). Wire it into `AskService.ask()` so an `Intent` (Phase 5) + `pageRoute`/`surface` produce a plan, the corpus retrieval stays exactly as today, and matched side-evidence is **collected + logged only**. **Phase 6 changes NO observable answer behavior** — the corpus retrieval, the boot-locked prompt, generation, validation, and BOTH `AskResponse` construction sites stay byte-identical. The collected `PlannedEvidence` is an INERT seam Phase 8 will consume.

**Architecture:** Matching is **deterministic route + topic overlap, NO embeddings** — no pgvector/embedding work, no migration, no DB change. The planner is a pure POJO `@Service`: `plan(...)` ignores its injected collaborators and returns a `Set<SourceKind>` from `intent`/`pageRoute`; `collect(...)` calls the two existing match methods only for the indexes the plan includes. Two new deterministic matchers are added to the existing services (`PageGuideService.match`, `SourceLinkService.match`), reusing each service's 10s-cached snapshot seam (`activePageGuides()`/`activeLinks()`) — they never hit the repository directly. `AskService` gains ONE constructor parameter (12 → 13) and two statements in `ask()` on the proceed path: `planner.plan(...)` (after the Phase 5 `Intent intent = ...` line, before `retrieve(...)`) and `planner.collect(...)` + a `log.info` (after the sufficiency gate passes, alongside prompt building). Nothing is passed into the prompt, the model, or the response.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5 + Mockito. Build file: `build.gradle.kts` (no pom). Backend tests run via `./gradlew test`. The planner/matcher tests are POJO/Mockito — **no Docker required**. `AskServiceTest` uses `TestPacks.msfg()` (loads the real pack YAML from disk). Reused domain (already merged): `com.msfg.rag.domain.Surface { PUBLIC, INTERNAL, BOTH }`, `com.msfg.rag.domain.BrainPageGuide`, `com.msfg.rag.domain.BrainSourceLink`. Reused (Phase 5): `com.msfg.rag.service.ai.Intent { GUIDELINE_QUESTION, PAGE_GUIDANCE, CALCULATION, EXTERNAL_REFERENCE }`, `com.msfg.rag.service.ai.IntentRouterService`.

---

## Backward-compatibility contract (HARD RULE — verify every task preserves it)

- **Default question (intent `GUIDELINE_QUESTION`, no `pageRoute`) → plan = exactly `{CORPUS}`.** No page-guide or link matching runs; `collect()` returns `PlannedEvidence.empty()`. This is today's behavior, exactly.
- **The corpus retrieval call is UNCHANGED.** `ask()` still calls `retrievalService.retrieve(request.question())` with the raw question string. The planner never alters, wraps, or replaces it. `RetrievalService.retrieve`, `RerankerService`, `VocabularyService`, and the program-boost logic are NOT touched.
- **`AskResponse`, `ModelAnswer`, `CitationDto`, `RetrievalResult`, `RetrievedChunk` are NOT touched.** No new field. BOTH `new AskResponse(...)` construction sites (success path + `refuse(...)`) and the `ensureCitations` all-args `ModelAnswer` reconstruction are unchanged. `PlannedEvidence` is a NEW, separate record — corpus chunks are NOT merged into it.
- **The boot-locked prompt is NOT touched.** `packs/msfg-mortgage/prompt.yaml` (the 5-`%s` template), `PromptBuilderService.build(...)`, and `DomainPackLoader.validate()` are untouched → no boot risk, no golden change. `MsfgGoldenPackTest` and every pack/golden test stay green untouched.
- **`PlannedEvidence` never reaches the prompt, the model, the validator, or the response.** It is collected on the proceed path, `log.info`'d (guide/link counts + plan indexes), and discarded. Phase 8 will thread it onward.
- **NO migration, NO DB change, NO pgvector/embedding work, NO `AuditLog`/telemetry persistence.** slf4j logging only.
- **Refusal semantics stay byte-identical.** `plan()` runs only after the EDUCATIONAL gate; `collect()` runs only after the sufficiency gate passes. A non-EDUCATIONAL refusal and an insufficient-evidence refusal behave exactly like today (the planner never runs on those paths). A bad `surface` reaches `collect()` only on the proceed path — mirroring Phase 5's `route()` placement — and throws `IllegalArgumentException` → 400.
- The full backend suite (`./gradlew test`) must stay green. The existing `AskServiceTest` scenarios pass **unchanged in behavior** (only the two positional `new AskService(...)` builders get a mechanical 13th arg). Final task (Task 7) is the explicit no-regression gate.

---

## File Structure

### Backend — created

| Path | Responsibility |
|---|---|
| `src/main/java/com/msfg/rag/service/retrieval/SourceKind.java` | Plain enum `{ CORPUS, PAGE_GUIDE, LINK_REGISTRY }` — the index identifiers a plan selects. |
| `src/main/java/com/msfg/rag/service/retrieval/RetrievalPlan.java` | `record RetrievalPlan(Set<SourceKind> indexes)` + `boolean includes(SourceKind k)` helper. Minimal — NO weights/pageBoost (deferred to Phase 7). |
| `src/main/java/com/msfg/rag/service/retrieval/PlannedEvidence.java` | `record PlannedEvidence(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links)` + static `empty()`. Carries the collected side-evidence; corpus stays as the existing `RetrievalResult`. |
| `src/main/java/com/msfg/rag/service/retrieval/RetrievalPlannerService.java` | `@Service`; ctor-injects `PageGuideService` + `SourceLinkService`. `RetrievalPlan plan(Intent, String pageRoute, String surface)` (PURE) + `PlannedEvidence collect(RetrievalPlan, String question, String pageRoute, String surface)`. |

### Backend — modified

| Path | Change |
|---|---|
| `src/main/java/com/msfg/rag/service/retrieval/PageGuideService.java` | Add `List<BrainPageGuide> match(String pageRoute, String question, String surface)` — deterministic route-exact OR topic-substring match over `activePageGuides()`, surface-filtered, route-first ordered, de-duped, defensive copy. |
| `src/main/java/com/msfg/rag/service/retrieval/SourceLinkService.java` | Add `List<BrainSourceLink> match(String question, String surface)` — deterministic topic-substring match over `activeLinks()`, surface-filtered, defensive copy. |
| `src/main/java/com/msfg/rag/service/AskService.java` | Add `RetrievalPlannerService` as the 13th ctor parameter; after the Phase 5 `Intent intent = ...` line and before `retrieve(...)` compute `RetrievalPlan plan = ...`; after the sufficiency gate passes, `collect(...)` + `log.info` the counts. INERT — nothing passed downstream. |

### Backend — tests created

| Path | Responsibility |
|---|---|
| `src/test/java/com/msfg/rag/service/retrieval/RetrievalPlannerServiceTest.java` | POJO test (Mockito-mocked `PageGuideService` + `SourceLinkService`): a table of (intent × pageRoute) → expected `indexes`; `collect()` delegates to the right `match()` calls per plan; absent index → empty list, no delegation. |

### Backend — tests modified

| Path | Change |
|---|---|
| `src/test/java/com/msfg/rag/service/retrieval/PageGuideServiceTest.java` | Add `match()` cases: route-exact, topic-substring, surface filtering (PUBLIC keeps PUBLIC+BOTH, excludes INTERNAL; null surface keeps all), de-dupe, blank question → empty, bad surface → `assertThrows(IllegalArgumentException)`. |
| `src/test/java/com/msfg/rag/service/retrieval/SourceLinkServiceTest.java` | Add `match()` cases: topic-substring, surface filtering, blank question → empty, bad surface → `assertThrows`. |
| `src/test/java/com/msfg/rag/service/AskServiceTest.java` | Both positional `new AskService(...)` builders (lines 101–104 and 130–133) gain a 13th arg; add a collect-only parity test proving `pageRoute` + a matching guide does NOT change the answer/citations and the corpus question is unchanged. |

### NOT touched (verify untouched at the end)

`dto/AskResponse.java`, `dto/AskRequest.java`, `service/ai/ModelAnswer.java`, `dto/CitationDto.java`, `service/ai/Intent.java`, `service/ai/IntentRouterService.java`, `service/ai/QuestionCategory.java`, `service/ai/PromptBuilderService.java`, `service/retrieval/RetrievalService.java`, `service/retrieval/RetrievalResult.java`, `service/retrieval/RetrievedChunk.java`, `service/retrieval/RerankerService.java`, `packs/msfg-mortgage/prompt.yaml`, every pack YAML, `pack/DomainPack.java`, `pack/DomainPackLoader.java`, `controller/AskController.java`, `exception/GlobalExceptionHandler.java`, `domain/Surface.java`, `MsfgGoldenPackTest`, `DomainPackLoaderTest`, every migration.

---

## Conventions for every backend task

- **Test commands:** backend via `./gradlew test --tests "<FQN>"` (full suite: `./gradlew test`). The build is `build.gradle.kts`. NOTE: in this environment the raw `./gradlew` invocation is hook-redirected — when *executing*, run it through the context-mode shell wrapper: `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '<FQN>'")`. This plan WRITES the literal `./gradlew test --tests "..."` command for each step.
- **Tests are JUnit 5 + Mockito.** `RetrievalPlannerServiceTest` is a POJO test (`new RetrievalPlannerService(mock, mock)`). The matcher tests extend the existing `PageGuideServiceTest`/`SourceLinkServiceTest`, which construct `new PageGuideService(mock(repo))` / `new SourceLinkService(mock(repo))` and stub `findByActiveTrueOrderByCreatedAtDescIdDesc()` so `activePageGuides()`/`activeLinks()` return the fixtures — **mocked repositories, NOT Testcontainers**, so no Docker. `AskServiceTest` mirrors the existing file: `TestPacks.msfg()` + positional `new AskService(...)` + Mockito-mocked collaborators.
- **Keep test classes in the same package** as the class under test for package-private access (`RetrievalPlannerServiceTest`/`PageGuideServiceTest`/`SourceLinkServiceTest` → `com.msfg.rag.service.retrieval`; `AskServiceTest` → `com.msfg.rag.service`).
- **400-vs-500 contract:** `IllegalArgumentException` is mapped to HTTP 400 `{"error": msg}` by the existing `GlobalExceptionHandler` (`@RestControllerAdvice`). Do NOT add try/catch or a new `@ExceptionHandler`. Bad-surface validation throwing `IllegalArgumentException` IS the 400 path.
- **Commit** after each green task with the exact commands shown. End every commit message with the Co-Authored-By trailer.

---

## Verified anchors (do not re-research)

- **`AskService` constructor has exactly 12 positional parameters today** (AskService.java:62–73, post-Phase-5): `DomainPack pack, QuestionClassifierService, RetrievalService, PromptBuilderService, ModelRouterService, AnswerValidationService, AuditLogService, ConversationRepository, MessageRepository, AnswerSourceRepository, ObjectMapper, IntentRouterService`. After Phase 6 it has **13** (the new `RetrievalPlannerService`, appended last).
- **The Phase 5 intent block is at AskService.java:101–108** (`Intent intent = intentRouterService.route(request.question(), request.pageRoute(), request.surface());` + its `log.info`), immediately AFTER the EDUCATIONAL gate (lines 95–99) and BEFORE `RetrievalResult retrieval = retrievalService.retrieve(request.question());` (line 111). The sufficiency gate is at lines 114–117; the prompt is built at line 120.
- **`AskServiceTest` positional `new AskService(...)` builders are at lines 101–104 (`askServiceReturning`) and 130–133 (`askServiceClassifying`)**, each ending with `new ObjectMapper(), new IntentRouterService());` (12 args). Each gains a trailing 13th arg.
- **`AskRequest` is already 7-arg** (post-Phase-5): `new AskRequest(null, "session-1", "What is PMI?", null, null, pageRoute, surface)`. `AskServiceTest` already has `pmiQuestion()` (line 137, `..., null, null`) and `pmiQuestionWith(pageRoute, surface)` (line 285). **Reuse `pmiQuestionWith` — do NOT add a new request helper.**
- **`PageGuideService`** (PageGuideService.java): `activePageGuides()` returns the 10s-cached `List<BrainPageGuide>` snapshot (line 102). Getters used by `match`: `getRoute()` (nullable String), `getTopics()` (`List<String>`), `getSurface()` (`Surface`). Constructor for fixtures: `new BrainPageGuide(route, title, purpose, surface, userIntents, allowedGuidance, internalLinks, sourceLinkIds, topics, createdBy)`.
- **`SourceLinkService`** (SourceLinkService.java): `activeLinks()` returns the 10s-cached `List<BrainSourceLink>` snapshot (line 103). Getters used by `match`: `getTopics()` (`List<String>`), `getSurface()` (`Surface`). Constructor for fixtures: `new BrainSourceLink(name, url, domain, authority, topics, freshnessRequired, allowedUse, doNotUseFor, surface, createdBy)`.
- **`Surface`** (`com.msfg.rag.domain.Surface`): `PUBLIC, INTERNAL, BOTH`.
- **`Intent`** (`com.msfg.rag.service.ai.Intent`): `GUIDELINE_QUESTION, PAGE_GUIDANCE, CALCULATION, EXTERNAL_REFERENCE`.
- The existing service tests already construct fixtures with these exact constructors (see `PageGuideServiceTest.updateAppliesFieldsAndInvalidates` line 112, `SourceLinkServiceTest.activeLinksCachesFirstReadAndReusesUntilInvalidated` line 155).

---

## Design decisions locked in this plan (state them; do not silently choose)

- **Topic match = substring, not whole-token.** A guide/link topic (lowercased) matches when it appears as a substring of the lowercased question. This mirrors the conservative-heuristic note in `IntentRouterService` (broad substring cues, intentional). It is deterministic and will be refined (word-boundary / embedding) in a later phase once side-evidence is actually consumed. Documented in each matcher's Javadoc.
- **Surface-null semantics = no filter.** When `surface` is null/blank, NO surface filter is applied (every active guide/link is eligible). When `surface` is non-blank, parse via `Surface.valueOf(surface.strip().toUpperCase(Locale.US))` (bad value → `IllegalArgumentException` → 400) and keep only records whose `surface()` equals that value OR `BOTH`. (Uppercasing matches `IntentRouterService.route`'s lenient public-facing parse, NOT the admin services' case-sensitive `Surface.valueOf(value.strip())` — these matchers are on the public ask path, so lenient parsing is correct.)
- **`collect()` placement = proceed path only**, after the sufficiency gate passes, alongside prompt building. Consequence: a bad surface on a *refused* question (non-EDUCATIONAL, or insufficient evidence) is never parsed and never 400s — acceptable and identical to Phase 5's `route()` behavior, and it keeps refusal semantics byte-identical. `plan()` is pure and runs earlier (after the intent line) but does NOT parse surface, so it never throws.
- **`RetrievalPlan` is minimal** — `Set<SourceKind> indexes` only. Weights and pageBoost (spec §7.4) are deferred to Phase 7; adding them now would be unused dead fields.
- **`PlannedEvidence` is separate from `RetrievalResult`.** Corpus chunks are NOT merged in. This keeps the corpus path and the sufficiency/prompt dependencies on `RetrievalResult` byte-identical.

---

## Task 1: `SourceKind` enum

**Files:**
- Create: `src/main/java/com/msfg/rag/service/retrieval/SourceKind.java`

Plain Java enum, UPPER_SNAKE constants (mirrors `Surface`/`Intent` style). No standalone enum unit test in this repo (enums are tested transitively via the planner); verification is a compile.

- [ ] **Write `SourceKind.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.retrieval;

/**
 * The retrieval indexes a {@link RetrievalPlan} can select (spec §7.4). Phase 6
 * uses these to decide which side-indexes to collect from; {@link SourceKind#CORPUS}
 * is always present (the existing hybrid corpus retrieval is unchanged).
 *
 * <p>{@code PAGE_GUIDE} and {@code LINK_REGISTRY} drive collection from the
 * already-merged cached snapshots ({@code PageGuideService.activePageGuides()} /
 * {@code SourceLinkService.activeLinks()}); the collected evidence is logged only
 * in Phase 6 and consumed by Phase 8.
 */
public enum SourceKind {

    /** The hybrid vector + keyword corpus index — always retrieved (unchanged). */
    CORPUS,

    /** The curated page-guide registry (where to send the user). */
    PAGE_GUIDE,

    /** The curated source-link / trust registry (external references). */
    LINK_REGISTRY
}
```

- [ ] **Verify compile:**
  `./gradlew compileJava`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/SourceKind.java
git commit -m "feat(planner): SourceKind enum for the Phase 6 retrieval plan

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `RetrievalPlan` + `PlannedEvidence` records

**Files:**
- Create: `src/main/java/com/msfg/rag/service/retrieval/RetrievalPlan.java`
- Create: `src/main/java/com/msfg/rag/service/retrieval/PlannedEvidence.java`

Two minimal records. `RetrievalPlan` carries only `Set<SourceKind> indexes` (weights/pageBoost deferred to Phase 7) plus an `includes(...)` convenience. `PlannedEvidence` carries the two collected lists plus a static `empty()`. These are tested transitively via the planner (Task 5); verification here is a compile.

- [ ] **Write `RetrievalPlan.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.retrieval;

import java.util.Set;

/**
 * Which indexes a question should hit (spec §7.4). Phase 6 keeps this minimal —
 * just the set of {@link SourceKind} indexes. Weights and page-boost (spec §7.4)
 * are deferred to Phase 7; do not add them until something consumes them.
 *
 * <p>The default plan for an ordinary guideline question (no {@code pageRoute},
 * intent {@code GUIDELINE_QUESTION}) is exactly {@code {CORPUS}} — i.e. today's
 * behavior.
 *
 * @param indexes the indexes to retrieve from; always contains {@link SourceKind#CORPUS}
 */
public record RetrievalPlan(Set<SourceKind> indexes) {

    /** True when {@code kind} is one of the planned indexes. */
    public boolean includes(SourceKind kind) {
        return indexes.contains(kind);
    }
}
```

- [ ] **Write `PlannedEvidence.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;

import java.util.List;

/**
 * Side-evidence collected alongside corpus retrieval (spec §7.5): matched page
 * guides and source links. This is SEPARATE from {@link RetrievalResult} — corpus
 * chunks are NOT merged in here, so the corpus path and the prompt/sufficiency
 * dependencies on {@link RetrievalResult} stay unchanged.
 *
 * <p><b>Phase 6 scope:</b> this is collected on the proceed path and logged only;
 * nothing reads it yet (the prompt, model, validator, and response are all
 * unchanged). It is the INERT seam Phase 8 consumes to emit
 * recommendedPage/links/nextAction.
 *
 * @param pageGuides matched active page guides (never null; possibly empty)
 * @param links      matched active source links (never null; possibly empty)
 */
public record PlannedEvidence(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links) {

    private static final PlannedEvidence EMPTY = new PlannedEvidence(List.of(), List.of());

    /** Empty side-evidence — the default when only CORPUS is planned. */
    public static PlannedEvidence empty() {
        return EMPTY;
    }
}
```

- [ ] **Verify compile:**
  `./gradlew compileJava`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/RetrievalPlan.java src/main/java/com/msfg/rag/service/retrieval/PlannedEvidence.java
git commit -m "feat(planner): RetrievalPlan + PlannedEvidence records

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `PageGuideService.match(...)` + tests

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/retrieval/PageGuideService.java`
- Modify: `src/test/java/com/msfg/rag/service/retrieval/PageGuideServiceTest.java`

Add a deterministic `List<BrainPageGuide> match(String pageRoute, String question, String surface)` reusing `activePageGuides()` (the cached snapshot — never the repo). A guide matches if:
1. `pageRoute` is non-blank AND `guide.getRoute() != null && guide.getRoute().equalsIgnoreCase(pageRoute.strip())` (route-exact), OR
2. any of `guide.getTopics()` (lowercased) appears as a substring of the lowercased question (topic match).

Then SURFACE-FILTER: if `surface` is non-blank, parse via `Surface.valueOf(surface.strip().toUpperCase(Locale.US))` (bad value → `IllegalArgumentException` → 400) and keep only guides whose `getSurface()` is that value OR `BOTH`; if `surface` is null/blank, no surface filter. Order: route-exact matches first, then topic-only matches (stable, preserving snapshot order). De-dupe so a guide matching both route and topic appears once. Return a defensive copy. A null/blank question with a blank/null pageRoute yields no matches (don't NPE); a null/blank question with a non-blank pageRoute still route-matches.

**Judgment-call (state in the Javadoc):** topic matching is substring (not whole-token) — deterministic and deliberately broad, mirroring `IntentRouterService`'s conservative-cue note; refine later. Surface parse uppercases (lenient, public-facing), diverging intentionally from the admin CRUD path's case-sensitive `Surface.valueOf(value.strip())`.

We write the failing tests first (TDD), then the implementation.

- [ ] **Write the failing tests** — append to `PageGuideServiceTest.java`, before the final closing brace. Add the import for `BrainPageGuide` if not present (it already is) and `assertEquals`/`assertTrue` (already imported). The fixtures use the existing 10-arg `BrainPageGuide` constructor and stub `findByActiveTrueOrderByCreatedAtDescIdDesc()` (the seam `activePageGuides()` reads):

```java
    // ---- match() : deterministic route + topic matching (Phase 6) -------

    private BrainPageGuide guide(String route, Surface surface, List<String> topics) {
        return new BrainPageGuide(
                route, "Title", "Purpose", surface,
                List.of(), List.of(), List.of(), List.of(), topics, "seed");
    }

    @Test
    void matchByExactRoute() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        BrainPageGuide va = guide("/loans/va", Surface.BOTH, List.of("va"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha, va));

        List<BrainPageGuide> matches = service.match("/loans/fha", "anything", null);

        assertEquals(1, matches.size());
        assertEquals("/loans/fha", matches.get(0).getRoute());
    }

    @Test
    void matchRouteIsCaseInsensitiveAndTrimmed() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertEquals(1, service.match("  /LOANS/FHA  ", "x", null).size());
    }

    @Test
    void matchByTopicSubstring() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        BrainPageGuide va = guide("/loans/va", Surface.BOTH, List.of("va"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha, va));

        // No pageRoute; topic "fha" is a substring of the question.
        List<BrainPageGuide> matches = service.match(null, "What is an FHA loan?", null);

        assertEquals(1, matches.size());
        assertEquals("/loans/fha", matches.get(0).getRoute());
    }

    @Test
    void matchRouteFirstThenTopicAndDeDupes() {
        // duplexGuide matches BOTH route and topic ("duplex") — must appear once,
        // first (route-exact ordered ahead of topic-only matches).
        BrainPageGuide duplexGuide = guide("/loans/duplex", Surface.BOTH, List.of("duplex"));
        BrainPageGuide fhaGuide = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(duplexGuide, fhaGuide));

        // Question contains both "duplex" (duplexGuide topic + route hit) and "fha".
        List<BrainPageGuide> matches =
                service.match("/loans/duplex", "fha rules for a duplex", null);

        assertEquals(2, matches.size());
        assertEquals("/loans/duplex", matches.get(0).getRoute());   // route-exact first
        assertEquals("/loans/fha", matches.get(1).getRoute());      // topic-only second
    }

    @Test
    void matchSurfacePublicKeepsPublicAndBothExcludesInternal() {
        BrainPageGuide pub = guide("/p", Surface.PUBLIC, List.of("alpha"));
        BrainPageGuide both = guide("/b", Surface.BOTH, List.of("alpha"));
        BrainPageGuide internal = guide("/i", Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, both, internal));

        List<BrainPageGuide> matches = service.match(null, "alpha topic", "PUBLIC");

        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(g -> g.getSurface() == Surface.PUBLIC));
        assertTrue(matches.stream().anyMatch(g -> g.getSurface() == Surface.BOTH));
        assertTrue(matches.stream().noneMatch(g -> g.getSurface() == Surface.INTERNAL));
    }

    @Test
    void matchNullSurfaceKeepsAllSurfaces() {
        BrainPageGuide pub = guide("/p", Surface.PUBLIC, List.of("alpha"));
        BrainPageGuide internal = guide("/i", Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, internal));

        assertEquals(2, service.match(null, "alpha topic", null).size());
    }

    @Test
    void matchBlankQuestionAndBlankRouteIsEmpty() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertTrue(service.match("   ", "   ", null).isEmpty());
        assertTrue(service.match(null, null, null).isEmpty());
    }

    @Test
    void matchBadSurfaceThrowsIllegalArgumentException() {
        BrainPageGuide fha = guide("/loans/fha", Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertThrows(IllegalArgumentException.class,
                () -> service.match(null, "fha", "SIDEWAYS"));
    }
```

- [ ] **Run it — expected FAIL:** `match` does not exist yet → compile error (`cannot find symbol: method match`).
  `./gradlew test --tests "com.msfg.rag.service.retrieval.PageGuideServiceTest"`
  Expected: compile error (test does not run).

- [ ] **Edit `PageGuideService.java`** — add the imports and the `match` method. Add these imports alongside the existing ones (`java.util.ArrayList`, `java.util.List`, `java.util.UUID` are present; add `java.util.LinkedHashSet`, `java.util.Locale`):

```java
import java.util.LinkedHashSet;
import java.util.Locale;
```

  Then add the `match` method (place it right after `activePageGuides()`, before `invalidate()`):

```java
    /**
     * Deterministic match of active page guides to a question (spec §7.5),
     * reading the cached {@link #activePageGuides()} snapshot (never the repo).
     * A guide matches when:
     * <ol>
     *   <li>{@code pageRoute} is non-blank and equals {@code guide.getRoute()}
     *       (case-insensitive, trimmed) — a route-exact hit; OR</li>
     *   <li>any of {@code guide.getTopics()} (lowercased) is a substring of the
     *       lowercased {@code question} — a topic hit.</li>
     * </ol>
     * Surface filter: when {@code surface} is non-blank it is parsed leniently via
     * {@code Surface.valueOf(surface.strip().toUpperCase(Locale.US))} (a bad value
     * throws {@link IllegalArgumentException} → HTTP 400) and only guides whose
     * {@code getSurface()} is that value or {@link Surface#BOTH} are kept; a
     * null/blank surface applies no filter.
     *
     * <p>Route-exact matches are ordered ahead of topic-only matches (each group
     * preserving snapshot order); a guide hitting both appears once. Returns a
     * defensive copy; never throws on empty inputs.
     *
     * <p><b>Heuristic note:</b> topic matching is substring (not whole-token) and
     * deliberately broad, mirroring the conservative-cue heuristic in
     * {@code IntentRouterService}. It is deterministic and will be refined
     * (word-boundary / embedding) once side-evidence is actually consumed
     * (Phase 8). The lenient uppercasing surface parse diverges intentionally
     * from the admin CRUD path's case-sensitive parse because this is the public
     * ask path.
     */
    public List<BrainPageGuide> match(String pageRoute, String question, String surface) {
        Surface required = parseSurface(surface);
        boolean hasRoute = pageRoute != null && !pageRoute.isBlank();
        String route = hasRoute ? pageRoute.strip() : null;
        String haystack = (question == null) ? "" : question.toLowerCase(Locale.US);

        LinkedHashSet<BrainPageGuide> routeHits = new LinkedHashSet<>();
        LinkedHashSet<BrainPageGuide> topicHits = new LinkedHashSet<>();

        for (BrainPageGuide guide : activePageGuides()) {
            if (required != null && guide.getSurface() != required && guide.getSurface() != Surface.BOTH) {
                continue;
            }
            if (hasRoute && guide.getRoute() != null && guide.getRoute().equalsIgnoreCase(route)) {
                routeHits.add(guide);
                continue;
            }
            if (!haystack.isBlank() && matchesTopic(guide.getTopics(), haystack)) {
                topicHits.add(guide);
            }
        }

        List<BrainPageGuide> out = new ArrayList<>(routeHits.size() + topicHits.size());
        out.addAll(routeHits);
        out.addAll(topicHits);
        return out;
    }

    /** Lenient surface parse for the public ask path; null/blank → no filter (null). */
    private static Surface parseSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return null;
        }
        return Surface.valueOf(surface.strip().toUpperCase(Locale.US));
    }

    /** True when any topic (lowercased) is a substring of the lowercased question. */
    private static boolean matchesTopic(List<String> topics, String lowerQuestion) {
        if (topics == null) {
            return false;
        }
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) {
                continue;
            }
            if (lowerQuestion.contains(topic.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Run it — expected PASS:**
  `./gradlew test --tests "com.msfg.rag.service.retrieval.PageGuideServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all existing + the 8 new `match` tests green.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/PageGuideService.java src/test/java/com/msfg/rag/service/retrieval/PageGuideServiceTest.java
git commit -m "feat(planner): deterministic PageGuideService.match (route + topic)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `SourceLinkService.match(...)` + tests

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/retrieval/SourceLinkService.java`
- Modify: `src/test/java/com/msfg/rag/service/retrieval/SourceLinkServiceTest.java`

Add a deterministic `List<BrainSourceLink> match(String question, String surface)` reusing `activeLinks()` (the cached snapshot — never the repo). A link matches if any of `link.getTopics()` (lowercased) appears as a substring of the lowercased question. Same surface-filter rule (PUBLIC keeps PUBLIC+BOTH; null/blank → no filter; bad value → `IllegalArgumentException`). Null/blank question → empty (don't NPE). Return a defensive copy. (No route concept for links — links match on topic only.)

We write the failing tests first (TDD), then the implementation.

- [ ] **Write the failing tests** — append to `SourceLinkServiceTest.java`, before the final closing brace. Fixtures use the existing 10-arg `BrainSourceLink` constructor and stub `findByActiveTrueOrderByCreatedAtDescIdDesc()` (the seam `activeLinks()` reads):

```java
    // ---- match() : deterministic topic matching (Phase 6) --------------

    private BrainSourceLink link(Surface surface, List<String> topics) {
        return new BrainSourceLink(
                "Name", "https://x.com", "x.com", LinkAuthority.PRIMARY,
                topics, false, List.of(), List.of(), surface, "seed");
    }

    @Test
    void matchByTopicSubstring() {
        BrainSourceLink fha = link(Surface.BOTH, List.of("fha"));
        BrainSourceLink va = link(Surface.BOTH, List.of("va"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha, va));

        List<BrainSourceLink> matches = service.match("What is an FHA loan?", null);

        assertEquals(1, matches.size());
        assertEquals(List.of("fha"), matches.get(0).getTopics());
    }

    @Test
    void matchSurfacePublicKeepsPublicAndBothExcludesInternal() {
        BrainSourceLink pub = link(Surface.PUBLIC, List.of("alpha"));
        BrainSourceLink both = link(Surface.BOTH, List.of("alpha"));
        BrainSourceLink internal = link(Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, both, internal));

        List<BrainSourceLink> matches = service.match("alpha topic", "PUBLIC");

        assertEquals(2, matches.size());
        assertTrue(matches.stream().noneMatch(l -> l.getSurface() == Surface.INTERNAL));
    }

    @Test
    void matchNullSurfaceKeepsAllSurfaces() {
        BrainSourceLink pub = link(Surface.PUBLIC, List.of("alpha"));
        BrainSourceLink internal = link(Surface.INTERNAL, List.of("alpha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(pub, internal));

        assertEquals(2, service.match("alpha topic", null).size());
    }

    @Test
    void matchBlankQuestionIsEmpty() {
        BrainSourceLink fha = link(Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertTrue(service.match("   ", null).isEmpty());
        assertTrue(service.match(null, null).isEmpty());
    }

    @Test
    void matchBadSurfaceThrowsIllegalArgumentException() {
        BrainSourceLink fha = link(Surface.BOTH, List.of("fha"));
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(fha));

        assertThrows(IllegalArgumentException.class,
                () -> service.match("fha", "SIDEWAYS"));
    }
```

- [ ] **Run it — expected FAIL:** `match` does not exist yet → compile error.
  `./gradlew test --tests "com.msfg.rag.service.retrieval.SourceLinkServiceTest"`
  Expected: compile error (test does not run).

- [ ] **Edit `SourceLinkService.java`** — add the imports and the `match` method. Add alongside the existing imports (`java.util.ArrayList`, `java.util.List`, `java.util.UUID` are present; add `java.util.Locale`):

```java
import java.util.Locale;
```

  Then add the `match` method (place it right after `activeLinks()`, before `invalidate()`):

```java
    /**
     * Deterministic match of active source links to a question (spec §7.5),
     * reading the cached {@link #activeLinks()} snapshot (never the repo). A link
     * matches when any of {@code link.getTopics()} (lowercased) is a substring of
     * the lowercased {@code question}.
     *
     * <p>Surface filter: when {@code surface} is non-blank it is parsed leniently
     * via {@code Surface.valueOf(surface.strip().toUpperCase(Locale.US))} (a bad
     * value throws {@link IllegalArgumentException} → HTTP 400) and only links
     * whose {@code getSurface()} is that value or {@link Surface#BOTH} are kept; a
     * null/blank surface applies no filter. A null/blank question yields an empty
     * list. Returns a defensive copy (snapshot order preserved).
     *
     * <p><b>Heuristic note:</b> topic matching is substring (not whole-token) and
     * deliberately broad, mirroring the conservative-cue heuristic in
     * {@code IntentRouterService}; deterministic and refined later (Phase 8). The
     * lenient uppercasing surface parse diverges intentionally from the admin CRUD
     * path's case-sensitive parse because this is the public ask path.
     */
    public List<BrainSourceLink> match(String question, String surface) {
        Surface required = parseSurface(surface);
        if (question == null || question.isBlank()) {
            return new ArrayList<>();
        }
        String haystack = question.toLowerCase(Locale.US);

        List<BrainSourceLink> out = new ArrayList<>();
        for (BrainSourceLink link : activeLinks()) {
            if (required != null && link.getSurface() != required && link.getSurface() != Surface.BOTH) {
                continue;
            }
            if (matchesTopic(link.getTopics(), haystack)) {
                out.add(link);
            }
        }
        return out;
    }

    /** Lenient surface parse for the public ask path; null/blank → no filter (null). */
    private static Surface parseSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return null;
        }
        return Surface.valueOf(surface.strip().toUpperCase(Locale.US));
    }

    /** True when any topic (lowercased) is a substring of the lowercased question. */
    private static boolean matchesTopic(List<String> topics, String lowerQuestion) {
        if (topics == null) {
            return false;
        }
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) {
                continue;
            }
            if (lowerQuestion.contains(topic.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }
```

  Note: `parseSurface` validates surface even when the question is blank (parse happens before the blank-question short-circuit), so a bad surface still 400s on a blank question — matching the test `matchBadSurfaceThrowsIllegalArgumentException` and the PageGuide matcher's behavior.

- [ ] **Run it — expected PASS:**
  `./gradlew test --tests "com.msfg.rag.service.retrieval.SourceLinkServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all existing + the 5 new `match` tests green.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/SourceLinkService.java src/test/java/com/msfg/rag/service/retrieval/SourceLinkServiceTest.java
git commit -m "feat(planner): deterministic SourceLinkService.match (topic)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `RetrievalPlannerService` (`plan` + `collect`) + POJO test

**Files:**
- Create: `src/main/java/com/msfg/rag/service/retrieval/RetrievalPlannerService.java`
- Test: `src/test/java/com/msfg/rag/service/retrieval/RetrievalPlannerServiceTest.java`

A `@Service` that ctor-injects `PageGuideService` + `SourceLinkService`. Two public methods:

- `RetrievalPlan plan(Intent intent, String pageRoute, String surface)` — **PURE** (ignores the injected services; never touches `surface` beyond passing it on, never parses it, never throws). Rules:
  - CORPUS always.
  - add PAGE_GUIDE if `pageRoute` is non-blank OR `intent == PAGE_GUIDANCE`.
  - add LINK_REGISTRY if `intent == EXTERNAL_REFERENCE` OR `intent == PAGE_GUIDANCE`.
  - So the default (intent `GUIDELINE_QUESTION`, no `pageRoute`) → exactly `{CORPUS}` = today.
- `PlannedEvidence collect(RetrievalPlan plan, String question, String pageRoute, String surface)` — if `plan.includes(PAGE_GUIDE)`, call `pageGuideService.match(pageRoute, question, surface)`; if `plan.includes(LINK_REGISTRY)`, call `sourceLinkService.match(question, surface)`; otherwise empty lists. Returns `PlannedEvidence`. Never throws on empty (but a bad surface propagates from the matcher → 400, which is correct).

We write the failing test first (TDD), then the implementation.

- [ ] **Write the failing test** (`RetrievalPlannerServiceTest.java`) — POJO, Mockito-mocked services:

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.service.ai.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the deterministic retrieval planner. {@code plan()} is pure
 * (the injected services are never touched); {@code collect()} delegates to the
 * matchers only for the planned indexes.
 */
class RetrievalPlannerServiceTest {

    private final PageGuideService pageGuides = mock(PageGuideService.class);
    private final SourceLinkService sourceLinks = mock(SourceLinkService.class);
    private final RetrievalPlannerService planner =
            new RetrievalPlannerService(pageGuides, sourceLinks);

    // --- plan(): (intent x pageRoute) -> indexes ------------------------

    @Test
    void planDefaultIsCorpusOnly() {
        RetrievalPlan plan = planner.plan(Intent.GUIDELINE_QUESTION, null, null);
        assertEquals(Set.of(SourceKind.CORPUS), plan.indexes());
        verifyNoInteractions(pageGuides, sourceLinks);   // plan() is pure
    }

    @Test
    void planCalculationIsCorpusOnly() {
        assertEquals(Set.of(SourceKind.CORPUS),
                planner.plan(Intent.CALCULATION, null, null).indexes());
    }

    @Test
    void planExternalReferenceAddsLinkRegistry() {
        assertEquals(Set.of(SourceKind.CORPUS, SourceKind.LINK_REGISTRY),
                planner.plan(Intent.EXTERNAL_REFERENCE, null, null).indexes());
    }

    @Test
    void planPageGuidanceAddsBothSideIndexes() {
        assertEquals(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE, SourceKind.LINK_REGISTRY),
                planner.plan(Intent.PAGE_GUIDANCE, null, null).indexes());
    }

    @Test
    void planNonBlankPageRouteAddsPageGuideEvenForGuidelineIntent() {
        assertEquals(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE),
                planner.plan(Intent.GUIDELINE_QUESTION, "/loans/fha", null).indexes());
    }

    @Test
    void planBlankPageRouteDoesNotAddPageGuide() {
        assertEquals(Set.of(SourceKind.CORPUS),
                planner.plan(Intent.GUIDELINE_QUESTION, "   ", null).indexes());
    }

    @Test
    void planIncludesHelper() {
        RetrievalPlan plan = planner.plan(Intent.PAGE_GUIDANCE, null, null);
        assertTrue(plan.includes(SourceKind.PAGE_GUIDE));
        assertTrue(plan.includes(SourceKind.LINK_REGISTRY));
        assertTrue(plan.includes(SourceKind.CORPUS));
    }

    // --- collect(): delegate to matchers per plan -----------------------

    @Test
    void collectCorpusOnlyReturnsEmptyAndDoesNotMatch() {
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS));

        PlannedEvidence evidence = planner.collect(plan, "What is PMI?", null, null);

        assertTrue(evidence.pageGuides().isEmpty());
        assertTrue(evidence.links().isEmpty());
        verifyNoInteractions(pageGuides, sourceLinks);
    }

    @Test
    void collectPageGuideCallsOnlyPageGuideMatch() {
        BrainPageGuide g = new BrainPageGuide(
                "/loans/fha", "FHA", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of("fha"), "seed");
        when(pageGuides.match("/loans/fha", "fha", "PUBLIC")).thenReturn(List.of(g));
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE));

        PlannedEvidence evidence = planner.collect(plan, "fha", "/loans/fha", "PUBLIC");

        assertEquals(1, evidence.pageGuides().size());
        assertTrue(evidence.links().isEmpty());
        verify(pageGuides).match("/loans/fha", "fha", "PUBLIC");
        verify(sourceLinks, never()).match(anyString(), any());
    }

    @Test
    void collectLinkRegistryCallsOnlySourceLinkMatch() {
        BrainSourceLink l = new BrainSourceLink(
                "Name", "https://x.com", "x.com", LinkAuthority.PRIMARY,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        when(sourceLinks.match("fha", null)).thenReturn(List.of(l));
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.LINK_REGISTRY));

        PlannedEvidence evidence = planner.collect(plan, "fha", null, null);

        assertEquals(1, evidence.links().size());
        assertTrue(evidence.pageGuides().isEmpty());
        verify(sourceLinks).match("fha", null);
        verify(pageGuides, never()).match(any(), anyString(), any());
    }

    @Test
    void collectPageGuidanceCallsBothMatchers() {
        when(pageGuides.match(any(), anyString(), any())).thenReturn(List.of());
        when(sourceLinks.match(anyString(), any())).thenReturn(List.of());
        RetrievalPlan plan = new RetrievalPlan(
                Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE, SourceKind.LINK_REGISTRY));

        planner.collect(plan, "fha", "/loans/fha", null);

        verify(pageGuides).match("/loans/fha", "fha", null);
        verify(sourceLinks).match("fha", null);
    }
}
```

- [ ] **Run it — expected FAIL:** `RetrievalPlannerService` does not exist yet → compile error.
  `./gradlew test --tests "com.msfg.rag.service.retrieval.RetrievalPlannerServiceTest"`
  Expected: compile error — `cannot find symbol: class RetrievalPlannerService` (test does not run).

- [ ] **Write the service** (`RetrievalPlannerService.java`) — complete, copy-paste-ready:

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.service.ai.Intent;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic retrieval planner (Phase 6, spec §7.4). Decides which indexes a
 * question hits ({@link #plan}) and collects the matching side-evidence from the
 * already-merged cached registries ({@link #collect}).
 *
 * <p><b>Phase 6 scope (collect-only seam):</b> the corpus retrieval is unchanged
 * and is NOT executed here — {@code AskService} still calls
 * {@code retrievalService.retrieve(question)} directly. This planner only adds the
 * SIDE indexes (page guides + source links); the collected {@link PlannedEvidence}
 * is logged by the caller and otherwise discarded. The prompt, model, validator,
 * and response are unchanged. Phase 8 consumes the collected evidence.
 *
 * <p><b>Minimal by design:</b> {@link RetrievalPlan} carries only the index set —
 * weights and page-boost (spec §7.4) are deferred to Phase 7.
 */
@Service
public class RetrievalPlannerService {

    private final PageGuideService pageGuideService;
    private final SourceLinkService sourceLinkService;

    public RetrievalPlannerService(PageGuideService pageGuideService,
                                   SourceLinkService sourceLinkService) {
        this.pageGuideService = pageGuideService;
        this.sourceLinkService = sourceLinkService;
    }

    /**
     * Pure planning function — ignores the injected services and never parses
     * {@code surface} (so it never throws). Rules:
     * <ul>
     *   <li>{@link SourceKind#CORPUS} is always included.</li>
     *   <li>{@link SourceKind#PAGE_GUIDE} is added when {@code pageRoute} is
     *       non-blank OR {@code intent == }{@link Intent#PAGE_GUIDANCE}.</li>
     *   <li>{@link SourceKind#LINK_REGISTRY} is added when
     *       {@code intent == }{@link Intent#EXTERNAL_REFERENCE} OR
     *       {@code intent == }{@link Intent#PAGE_GUIDANCE}.</li>
     * </ul>
     * The default (intent {@link Intent#GUIDELINE_QUESTION}, no {@code pageRoute})
     * is exactly {@code {CORPUS}} — today's behavior.
     */
    public RetrievalPlan plan(Intent intent, String pageRoute, String surface) {
        Set<SourceKind> indexes = new LinkedHashSet<>();
        indexes.add(SourceKind.CORPUS);

        boolean hasRoute = pageRoute != null && !pageRoute.isBlank();
        if (hasRoute || intent == Intent.PAGE_GUIDANCE) {
            indexes.add(SourceKind.PAGE_GUIDE);
        }
        if (intent == Intent.EXTERNAL_REFERENCE || intent == Intent.PAGE_GUIDANCE) {
            indexes.add(SourceKind.LINK_REGISTRY);
        }
        return new RetrievalPlan(indexes);
    }

    /**
     * Collects side-evidence for the planned indexes. Calls
     * {@code pageGuideService.match(pageRoute, question, surface)} when the plan
     * includes {@link SourceKind#PAGE_GUIDE} and
     * {@code sourceLinkService.match(question, surface)} when it includes
     * {@link SourceKind#LINK_REGISTRY}; otherwise the respective list is empty.
     * Never throws on empty inputs (a bad {@code surface} propagates from the
     * matcher as {@link IllegalArgumentException} → HTTP 400, which is correct).
     */
    public PlannedEvidence collect(RetrievalPlan plan, String question,
                                   String pageRoute, String surface) {
        var pageGuides = plan.includes(SourceKind.PAGE_GUIDE)
                ? pageGuideService.match(pageRoute, question, surface)
                : List.<BrainPageGuide>of();
        var links = plan.includes(SourceKind.LINK_REGISTRY)
                ? sourceLinkService.match(question, surface)
                : List.<BrainSourceLink>of();
        return new PlannedEvidence(pageGuides, links);
    }
}
```

- [ ] **Run it — expected PASS:**
  `./gradlew test --tests "com.msfg.rag.service.retrieval.RetrievalPlannerServiceTest"`
  Expected: `BUILD SUCCESSFUL`, 11 tests passing.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/RetrievalPlannerService.java src/test/java/com/msfg/rag/service/retrieval/RetrievalPlannerServiceTest.java
git commit -m "feat(planner): RetrievalPlannerService plan + collect + POJO test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Wire `RetrievalPlannerService` into `AskService` (ctor + plan/collect/log) + collect-only parity test

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/AskService.java`
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java`

Add `RetrievalPlannerService` as the **13th** constructor parameter (appended after `intentRouterService`) and store it. In `ask()`:
1. **after** the Phase 5 `Intent intent = ...` block (AskService.java:101–108) and **before** `RetrievalResult retrieval = retrievalService.retrieve(...)` (line 111): compute `RetrievalPlan plan = retrievalPlannerService.plan(intent, request.pageRoute(), request.surface());`. (`plan()` is pure and does NOT parse surface — it never throws here.)
2. keep the corpus retrieval call EXACTLY as-is (`retrievalService.retrieve(request.question())`).
3. **after** the sufficiency gate passes (lines 114–117), on the proceed path alongside building the prompt: `PlannedEvidence sideEvidence = retrievalPlannerService.collect(plan, request.question(), request.pageRoute(), request.surface());` then `log.info(...)` the counts (guides=N, links=M, indexes=...). **Do NOT pass `sideEvidence` into promptBuilder / the model / AskResponse** — it is collected + logged only.

**Surface-validation placement decision (locked, be consistent):** surface is parsed only inside `collect()` (which delegates to the matchers), on the proceed path. A bad surface on a *refused* question (non-EDUCATIONAL or insufficient evidence) is never parsed → no 400 on those paths. This mirrors Phase 5's `route()` placement (Phase 5 parses surface in `route()` after the EDUCATIONAL gate) and keeps refusal semantics byte-identical. We deliberately do NOT hoist surface validation earlier. Note: with `pageRoute`/`surface` from `pmiQuestionWith(...)` and an EDUCATIONAL question, `route()` already parses surface in the Phase 5 block, so a bad surface on a proceed-path question 400s at the intent step anyway (before `collect()`); `collect()` re-parsing is harmless and consistent.

We write the test first (TDD): a collect-only parity test, plus the two mechanical builder edits.

- [ ] **Edit `AskServiceTest.java`** — add the planner construction to both builders and add the parity test.

  1. Add the import (alongside the other `com.msfg.rag.service.retrieval.*` imports near the top):

```java
import com.msfg.rag.service.retrieval.PageGuideService;
import com.msfg.rag.service.retrieval.RetrievalPlannerService;
import com.msfg.rag.service.retrieval.SourceLinkService;
```

  2. In `askServiceReturning(...)`, change the `return new AskService(...)` (lines 101–104) to add a real `RetrievalPlannerService` built over Mockito-mocked services as the final argument. Replace the existing return statement with:

```java
        PageGuideService pageGuides = mock(PageGuideService.class);
        when(pageGuides.match(any(), anyString(), any())).thenReturn(List.of());
        SourceLinkService sourceLinks = mock(SourceLinkService.class);
        when(sourceLinks.match(anyString(), any())).thenReturn(List.of());

        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks));
```

  3. In `askServiceClassifying(...)`, change the `return new AskService(...)` (lines 130–133) to add a `RetrievalPlannerService` final argument. The classify path refuses before `collect()` runs, so the matchers are never called; a plain mock-backed planner is enough. Replace the existing return statement with:

```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(
                        mock(PageGuideService.class), mock(SourceLinkService.class)));
```

  4. Add the collect-only parity tests at the end of the class, before the closing brace. They reuse the existing `pmiQuestion()` (line 137) and `pmiQuestionWith(...)` (line 285) helpers — do NOT add new request helpers. (`ArgumentCaptor`, `verify`, `atLeast`, `assertEquals` are already imported in this file from Phase 5. `any`, `anyString`, `mock`, `when` are already imported from the builder steps above — confirm they are present in the import list; add any missing Mockito imports.)

  **Test A — existing parity test (keep, update comment only).** The shared `askServiceReturning` builder stubs both matchers to `List.of()`, so `collect()` always returns empty `PlannedEvidence`. Update the test's leading comment to accurately reflect what it actually proves (empty evidence path), and keep the body exactly as written below:

```java
    @Test
    void collectOnlySeamDoesNotChangeTheAnswer() {
        // Phase 6 collect-only (empty-evidence path): the shared askServiceReturning
        // builder stubs both matchers to List.of(), so collect() returns empty
        // PlannedEvidence. Proves that empty side-evidence does not change the answer
        // or citations. The non-empty evidence case is covered by
        // collectOnlySeamDiscardsNonEmptyEvidence below.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));

        AskService service = askServiceReturning(groundedJson, chunks);

        AskResponse without  = service.ask(pmiQuestion());
        AskResponse withPage = service.ask(pmiQuestionWith("/loan-options", "PUBLIC"));

        // The collected side-evidence must not alter the answer.
        assertEquals(without.answer(), withPage.answer(),
                "collected page guides/links must not change the answer in Phase 6");
        assertEquals(without.citations(), withPage.citations(),
                "collected side-evidence must not change citations");
        assertEquals(without.humanEscalationRequired(), withPage.humanEscalationRequired());
        assertEquals(without.confidence(), withPage.confidence());

        // The corpus retrieval question must be the raw question, unchanged by the planner.
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(retrieval, atLeast(2)).retrieve(questionCaptor.capture());
        questionCaptor.getAllValues().forEach(q ->
                assertEquals("What is PMI?", q,
                        "retrieve() must receive the original question, never side-evidence"));
    }
```

  **Test B — new dedicated parity test with NON-EMPTY `collect()` output.** Because `askServiceReturning` stubs matchers as local variables the test cannot reach, this test constructs its own `AskService` instance with matcher mocks it controls, stubs them to return a real `BrainPageGuide` and a real `BrainSourceLink`, and proves the `AskResponse` answer + citations are byte-identical to the baseline (no-pageRoute) call even though `collect()` returns non-empty evidence. This is the actual Phase 6 collect-only contract: collected NON-EMPTY side-evidence is discarded without altering the answer.

  Entity construction reuses the exact 10-arg constructors from the Task 5 / planner-test fixtures (verified against `PageGuideServiceTest` line 112 and `SourceLinkServiceTest` line 155):
  - `BrainPageGuide`: `(route, title, purpose, surface, userIntents, allowedGuidance, internalLinks, sourceLinkIds, topics, createdBy)`
  - `BrainSourceLink`: `(name, url, domain, authority, topics, freshnessRequired, allowedUse, doNotUseFor, surface, createdBy)`

  Mockito imports needed (confirm present; add any missing): `mock`, `when`, `any`, `anyString` — these are already imported by the builder steps in this same test class. `ArgumentCaptor`, `verify`, `atLeast` are imported from Phase 5. No new imports are required.

```java
    @Test
    void collectOnlySeamDiscardsNonEmptyEvidence() {
        // Phase 6 collect-only (NON-EMPTY evidence path): stubs the matchers to
        // return a real BrainPageGuide + BrainSourceLink so collect() yields non-empty
        // PlannedEvidence. Proves the actual Phase 6 contract: collected NON-EMPTY
        // side-evidence is discarded without altering the AskResponse answer or
        // citations — the planner seam is inert in Phase 6.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));

        // Build matcher mocks that return non-empty results — reusing the same
        // 10-arg constructors used in RetrievalPlannerServiceTest / Task 5.
        PageGuideService pageGuides = mock(PageGuideService.class);
        BrainPageGuide matchedGuide = new BrainPageGuide(
                "/loan-options", "Loan Options", "Overview of loan options", Surface.PUBLIC,
                List.of(), List.of(), List.of(), List.of(), List.of("pmi"), "seed");
        when(pageGuides.match(any(), anyString(), any())).thenReturn(List.of(matchedGuide));

        SourceLinkService sourceLinks = mock(SourceLinkService.class);
        BrainSourceLink matchedLink = new BrainSourceLink(
                "Fannie Mae", "https://fanniemae.com", "fanniemae.com", LinkAuthority.PRIMARY,
                List.of("pmi"), false, List.of(), List.of(), Surface.PUBLIC, "seed");
        when(sourceLinks.match(anyString(), any())).thenReturn(List.of(matchedLink));

        // Build a dedicated AskService with the non-empty matcher mocks.
        RetrievalService localRetrieval = mock(RetrievalService.class);
        when(localRetrieval.retrieve(anyString())).thenReturn(new RetrievalResult(chunks, true));
        PromptBuilderService localPrompt = mock(PromptBuilderService.class);
        when(localPrompt.build(anyString(), anyList())).thenReturn("prompt");
        ModelRouterService localRouter = mock(ModelRouterService.class);
        when(localRouter.route(anyString())).thenReturn(groundedJson);
        AuditLogService localAudit = mock(AuditLogService.class);
        ConversationRepository localConversations = mock(ConversationRepository.class);
        MessageRepository localMessages = mock(MessageRepository.class);
        AnswerSourceRepository localSources = mock(AnswerSourceRepository.class);

        AskService service = new AskService(
                TestPacks.msfg(),
                mock(QuestionClassifierService.class),
                localRetrieval, localPrompt, localRouter,
                new AnswerValidationService(TestPacks.msfg()),
                localAudit, localConversations, localMessages, localSources,
                new ObjectMapper(), new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks));

        // Baseline: no pageRoute → collect() returns empty (CORPUS-only plan).
        AskResponse without  = service.ask(pmiQuestion());
        // With pageRoute + surface → plan includes PAGE_GUIDE + LINK_REGISTRY,
        // collect() returns non-empty PlannedEvidence (matchedGuide + matchedLink).
        AskResponse withPage = service.ask(pmiQuestionWith("/loan-options", "PUBLIC"));

        // The non-empty collected evidence must NOT alter the answer or citations.
        assertEquals(without.answer(), withPage.answer(),
                "non-empty collected page guides/links must not change the answer in Phase 6");
        assertEquals(without.citations(), withPage.citations(),
                "non-empty collected side-evidence must not change citations");
        assertEquals(without.humanEscalationRequired(), withPage.humanEscalationRequired());
        assertEquals(without.confidence(), withPage.confidence());

        // The corpus retrieval question must be the raw question on both calls —
        // the planner never wraps or replaces it.
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(localRetrieval, atLeast(2)).retrieve(questionCaptor.capture());
        questionCaptor.getAllValues().forEach(q ->
                assertEquals("What is PMI?", q,
                        "retrieve() must receive the original question, not side-evidence"));
    }
```

  **Missing import check:** `BrainPageGuide`, `BrainSourceLink`, `LinkAuthority`, `Surface` are already imported by the Task 5 section and by the builder steps. `PageGuideService`, `SourceLinkService`, `RetrievalPlannerService` are imported by step 1 above. `RetrievalResult`, `RetrievalService`, `PromptBuilderService`, `ModelRouterService`, `AuditLogService`, `ConversationRepository`, `MessageRepository`, `AnswerSourceRepository`, `QuestionClassifierService`, `AnswerValidationService` are already imported in `AskServiceTest`. If `RetrievalResult` is not explicitly imported (it may be used only indirectly via the mock return type), add: `import com.msfg.rag.service.retrieval.RetrievalResult;`. Confirm all Mockito static imports (`mock`, `when`, `verify`, `atLeast`, `any`, `anyString`, `anyList`) are present.

- [ ] **Run it — expected FAIL:** `new AskService(...)` now passes 13 args but the constructor still takes 12 → compile error.
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: compile error — constructor `AskService(...)` cannot be applied to the given 13 arguments (test does not run).

- [ ] **Edit `AskService.java`** — add the import, the field, the ctor parameter + assignment, and the plan/collect/log statements.

  1. Add the imports (with the other `com.msfg.rag.service.retrieval.*` imports):

```java
import com.msfg.rag.service.retrieval.PlannedEvidence;
import com.msfg.rag.service.retrieval.RetrievalPlan;
import com.msfg.rag.service.retrieval.RetrievalPlannerService;
```

  2. Add the field (after the `intentRouterService` field, near line 60):

```java
    private final RetrievalPlannerService retrievalPlannerService;
```

  3. Append the constructor parameter (after `IntentRouterService intentRouterService`) and the assignment (after `this.intentRouterService = intentRouterService;`). The full constructor becomes:

```java
    public AskService(DomainPack pack,
                      QuestionClassifierService questionClassifierService,
                      RetrievalService retrievalService,
                      PromptBuilderService promptBuilderService,
                      ModelRouterService modelRouterService,
                      AnswerValidationService answerValidationService,
                      AuditLogService auditLogService,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      AnswerSourceRepository answerSourceRepository,
                      ObjectMapper objectMapper,
                      IntentRouterService intentRouterService,
                      RetrievalPlannerService retrievalPlannerService) {
        this.canned = pack.guardrails().cannedAnswers();
        this.questionClassifierService = questionClassifierService;
        this.retrievalService = retrievalService;
        this.promptBuilderService = promptBuilderService;
        this.modelRouterService = modelRouterService;
        this.answerValidationService = answerValidationService;
        this.auditLogService = auditLogService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.objectMapper = objectMapper;
        this.intentRouterService = intentRouterService;
        this.retrievalPlannerService = retrievalPlannerService;
    }
```

  4. In `ask()`, insert the `plan(...)` call right after the Phase 5 intent block and before the retrieve comment. The existing block (AskService.java:101–111) is:

```java
        // Routing seam (Phase 5): compute + log intent, validate surface — consumed by the Phase 6 planner.
        // This is a distinct post-EDUCATIONAL-gate step, not a sub-step of the compliance gate above.
        // A bad surface value throws IllegalArgumentException -> HTTP 400 via GlobalExceptionHandler.
        // Nothing else reads intent yet; the flow below is byte-identical to today.
        Intent intent = intentRouterService.route(
                request.question(), request.pageRoute(), request.surface());
        log.info("Routed question to intent {} (pageRoute={}, surface={})",
                intent, request.pageRoute(), request.surface());

        // 1. Retrieve approved source context.
        RetrievalResult retrieval = retrievalService.retrieve(request.question());
```

  Change it to (insert the plan block between the intent log and the retrieve comment; the retrieve call is UNCHANGED):

```java
        // Routing seam (Phase 5): compute + log intent, validate surface — consumed by the Phase 6 planner.
        // This is a distinct post-EDUCATIONAL-gate step, not a sub-step of the compliance gate above.
        // A bad surface value throws IllegalArgumentException -> HTTP 400 via GlobalExceptionHandler.
        // Nothing else reads intent yet; the flow below is byte-identical to today.
        Intent intent = intentRouterService.route(
                request.question(), request.pageRoute(), request.surface());
        log.info("Routed question to intent {} (pageRoute={}, surface={})",
                intent, request.pageRoute(), request.surface());

        // Planning seam (Phase 6): decide which indexes this question hits. plan()
        // is pure (no I/O, no surface parse) — for the default (GUIDELINE_QUESTION,
        // no pageRoute) it is exactly {CORPUS} = today. Side indexes (page guides /
        // link registry) are COLLECTED below on the proceed path and logged only;
        // nothing here changes the corpus retrieval, prompt, model, or response.
        RetrievalPlan plan = retrievalPlannerService.plan(
                intent, request.pageRoute(), request.surface());

        // 1. Retrieve approved source context. (Unchanged — raw question, corpus index.)
        RetrievalResult retrieval = retrievalService.retrieve(request.question());
```

  5. Insert the `collect(...)` + log right after the sufficiency gate, on the proceed path, alongside building the prompt. The existing block (AskService.java:114–122) is:

```java
        // 2. Refuse early when there is no reliable source material.
        if (!retrieval.sufficientEvidence()) {
            return refuse(conversation, request, retrieval, canned.noSource(), null,
                    "insufficient evidence");
        }

        // 3. Build the locked prompt and call the model (with fallback).
        String prompt = promptBuilderService.build(request.question(), retrieval.chunks());
```

  Change it to (insert the collect block between the sufficiency gate and the prompt comment):

```java
        // 2. Refuse early when there is no reliable source material.
        if (!retrieval.sufficientEvidence()) {
            return refuse(conversation, request, retrieval, canned.noSource(), null,
                    "insufficient evidence");
        }

        // Collect seam (Phase 6): gather matching page guides + source links for
        // the planned side indexes. INERT — collected + logged only; NOT passed to
        // the prompt, the model, the validator, or the AskResponse. Phase 8 will
        // consume this to emit recommendedPage/links/nextAction. Runs only on the
        // proceed path so refusal semantics stay byte-identical to today.
        PlannedEvidence sideEvidence = retrievalPlannerService.collect(
                plan, request.question(), request.pageRoute(), request.surface());
        log.info("Planned side-evidence: guides={}, links={}, indexes={}",
                sideEvidence.pageGuides().size(), sideEvidence.links().size(), plan.indexes());

        // 3. Build the locked prompt and call the model (with fallback).
        String prompt = promptBuilderService.build(request.question(), retrieval.chunks());
```

- [ ] **Run it — expected PASS:**
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all existing tests + the new `collectOnlySeamDoesNotChangeTheAnswer` test green.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/AskService.java src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "feat(planner): wire RetrievalPlannerService into AskService (collect + log only)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Full-suite no-regression verification

**Files:** none modified (verification gate).

Confirms (a) the existing `AskServiceTest`/`PageGuideServiceTest`/`SourceLinkServiceTest` scenarios still pass unchanged, (b) the golden pack lock and every other test stay green untouched (no pack/golden/migration/prompt/response was changed), and (c) the app still wires (`RetrievalPlannerService` ctor-injects two existing `@Service` beans, so Spring constructs it and injects it into `AskService` with no extra config).

- [ ] **Run the full backend suite — expected PASS:**
  `./gradlew test`
  Expected: `BUILD SUCCESSFUL`. Specifically confirm green: `com.msfg.rag.service.AskServiceTest`, `com.msfg.rag.service.retrieval.RetrievalPlannerServiceTest`, `com.msfg.rag.service.retrieval.PageGuideServiceTest`, `com.msfg.rag.service.retrieval.SourceLinkServiceTest`, `com.msfg.rag.service.ai.IntentRouterServiceTest`, `com.msfg.rag.pack.MsfgGoldenPackTest`, `com.msfg.rag.pack.DomainPackLoaderTest`.

- [ ] **Confirm the no-touch set is unchanged** (git should show NO modifications to these):
  `git status --porcelain src/main/java/com/msfg/rag/dto/AskResponse.java src/main/java/com/msfg/rag/dto/AskRequest.java src/main/java/com/msfg/rag/service/ai/ModelAnswer.java src/main/java/com/msfg/rag/service/ai/QuestionCategory.java src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java src/main/java/com/msfg/rag/service/retrieval/RetrievalResult.java src/main/java/com/msfg/rag/service/retrieval/RetrievedChunk.java packs/msfg-mortgage/prompt.yaml src/main/java/com/msfg/rag/pack/DomainPack.java src/main/java/com/msfg/rag/pack/DomainPackLoader.java`
  Expected: empty output (no files listed) — none of the boot-locked / response / corpus-retrieval surfaces were touched.

- [ ] **Confirm no new migration was added:**
  `git status --porcelain src/main/resources/db/migration/`
  Expected: empty output.

- [ ] **Final commit (only if any tracked verification artifact changed — normally nothing to commit here).** If `git status` is clean, skip. Otherwise:
```
git add -A
git commit -m "test(planner): Phase 6 full-suite no-regression verification

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (writing-plans)

**Spec coverage (every LOCKED DESIGN DECISION mapped to a task):**
- New enum `SourceKind { CORPUS, PAGE_GUIDE, LINK_REGISTRY }` in `com.msfg.rag.service.retrieval` → **Task 1**.
- New record `RetrievalPlan(Set<SourceKind> indexes)` + `includes(...)`, no weights/pageBoost → **Task 2**.
- New record `PlannedEvidence(List<BrainPageGuide>, List<BrainSourceLink>)` + `empty()`, separate from `RetrievalResult` → **Task 2**.
- `PageGuideService.match(String pageRoute, String question, String surface)` — route-exact OR topic-substring over `activePageGuides()`, surface-filter (PUBLIC keeps PUBLIC+BOTH; null → all; bad → IAE), route-first ordering, de-dupe, defensive copy → **Task 3**.
- `SourceLinkService.match(String question, String surface)` — topic-substring over `activeLinks()`, same surface-filter, blank question → empty, defensive copy → **Task 4**.
- `RetrievalPlannerService` `@Service`, ctor-injects `PageGuideService` + `SourceLinkService`; `plan(...)` PURE (CORPUS always; PAGE_GUIDE if pageRoute non-blank OR PAGE_GUIDANCE; LINK_REGISTRY if EXTERNAL_REFERENCE OR PAGE_GUIDANCE; default = {CORPUS}); `collect(...)` delegates per plan → **Task 5**.
- `AskService` gains the 13th ctor param; `plan(...)` after the intent line + before retrieve; corpus call unchanged; `collect(...)` + `log.info` after the sufficiency gate; nothing passed downstream → **Task 6**.
- Collect-only parity (pageRoute + matching guide → identical answer/citations; corpus question unchanged) → **Task 6** (`collectOnlySeamDoesNotChangeTheAnswer`).
- No embeddings / no migration / no DB change / slf4j only → honored throughout; **Task 7** asserts the no-touch set + no migration.
- Default → `{CORPUS}` = today, refusal semantics byte-identical → **Task 5** (`planDefaultIsCorpusOnly`) + **Task 6** placement + **Task 7** gate.

**Placeholder scan:** No `TODO`, `FIXME`, `...`, `<placeholder>`, or "implement here" in any code block. Every file is complete and copy-paste-ready. (The one prose note in Task 6 explicitly instructs DELETING an unreachable `verify(promptBuilderForCapture(...))` line and shows the final committed test body — there is no placeholder left in the committed code.)

**Type / signature consistency vs the live files:**
- `AskService` ctor arity verified = **12** today (AskService.java:62–73, post-Phase-5, ending `IntentRouterService intentRouterService`) → **13** after Task 6 (append `RetrievalPlannerService`). Field/assignment/param all added consistently.
- `AskServiceTest` positional `new AskService(...)` builders verified at **lines 101–104** (`askServiceReturning`) and **130–133** (`askServiceClassifying`), each currently ending `new ObjectMapper(), new IntentRouterService());`. Both updated to append the 13th arg. `AskRequest` is already 7-arg (Phase 5); the test already has `pmiQuestion()` (line 137) and `pmiQuestionWith(...)` (line 285) — reused, not re-added.
- `RetrievalPlannerService.collect` calls `pageGuideService.match(pageRoute, question, surface)` (3 args) and `sourceLinkService.match(question, surface)` (2 args) — EXACTLY matching the signatures added in Tasks 3 and 4. Verified consistent.
- `BrainPageGuide` fixtures use the verified 10-arg ctor `(route, title, purpose, surface, userIntents, allowedGuidance, internalLinks, sourceLinkIds, topics, createdBy)`; `BrainSourceLink` fixtures use the verified 10-arg ctor `(name, url, domain, authority, topics, freshnessRequired, allowedUse, doNotUseFor, surface, createdBy)`. Both match the existing test fixtures (PageGuideServiceTest:112, SourceLinkServiceTest:155).
- Getters used: `BrainPageGuide.getRoute()/getTopics()/getSurface()` and `BrainSourceLink.getTopics()/getSurface()` — all verified present.
- `Surface` is `com.msfg.rag.domain.Surface { PUBLIC, INTERNAL, BOTH }`; `Intent` is `com.msfg.rag.service.ai.Intent { GUIDELINE_QUESTION, PAGE_GUIDANCE, CALCULATION, EXTERNAL_REFERENCE }` — reused, not redefined.
- The existing `PageGuideServiceTest`/`SourceLinkServiceTest` use **mocked repositories** (`mock(BrainPageGuideRepository.class)` / `mock(BrainSourceLinkRepository.class)`), NOT Testcontainers, so the new `match()` tests stub `findByActiveTrueOrderByCreatedAtDescIdDesc()` (the method `activePageGuides()`/`activeLinks()` read) — no Docker. Verified against the existing `activeLinksCachesFirstReadAndReusesUntilInvalidated` / `activePageGuidesCachesFirstReadAndReusesUntilInvalidated` tests.
- Imports added where used: `java.util.LinkedHashSet`/`java.util.Locale` (PageGuideService), `java.util.Locale` (SourceLinkService), the three retrieval imports (AskService + AskServiceTest). `assertThrows`/`assertEquals`/`assertTrue`/`atLeast`/`verify`/`ArgumentCaptor`/`any`/`anyString`/`mock`/`when`/`never`/`verifyNoInteractions` are imported per file (existing or added).

**Boot-lock / golden safety:** prompt.yaml, the 5-`%s` template, `PromptBuilderService.build`, `DomainPackLoader.validate`, `DomainPack`, `RetrievalService.retrieve`, `RetrievalResult`, `RetrievedChunk`, `AskResponse`, and `MsfgGoldenPackTest` are untouched (Task 7 asserts this) — no boot, golden, or answer-behavior risk. No migration added. `RetrievalPlannerService` ctor-injects two existing beans, so Spring wiring needs no config.
