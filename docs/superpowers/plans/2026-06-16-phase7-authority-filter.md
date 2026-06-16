# Phase 7 — Authority Filter (tier + order the collected side-evidence)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tier and **order** the side-evidence the Phase 6 `RetrievalPlannerService.collect(...)` already gathers, so Phase 8 can emit it trust-first. Introduce an `AuthorityTier` enum (five tiers with an explicit `int rank`, spec §6.4) and a pure `AuthorityFilterService` that (a) maps a `LinkAuthority`/`SourceType` to its tier and (b) `order(PlannedEvidence)` — returns a NEW `PlannedEvidence` with `links` **stable-sorted ascending by tier rank** (PRIMARY → SECONDARY → BACKGROUND) and `pageGuides` left as-is (all one tier). Wire it into `RetrievalPlannerService.collect(...)` so the returned `PlannedEvidence` is already authority-ordered. **Phase 7 changes NO observable answer behavior** — the corpus retrieval, the reranker, the boot-locked prompt, `AskResponse`, and `ModelAnswer` are all untouched. The ordered `PlannedEvidence` stays an INERT seam (logged only via the unchanged Phase 6 `collect`-path `log.info`) until Phase 8 emits it.

**Architecture:** **insert, don't rewrite.** `AuthorityFilterService` is a pure, collaborator-free POJO `@Service` (Spring instantiates it with the no-arg constructor; no beans injected). `RetrievalPlannerService` gains ONE constructor parameter (2 → 3, appending `AuthorityFilterService`) and `collect(...)`'s single `return` statement is wrapped: `return authorityFilterService.order(new PlannedEvidence(guides, links));`. `AskService` is **UNCHANGED** — it still injects only `RetrievalPlannerService` (its 13-arg ctor is byte-identical); its existing `collect`-path `log.info("Planned side-evidence: ...")` is byte-identical before and after Phase 7 — it emits only counts (`.pageGuides().size()`, `.links().size()`) and the index set, so log output is unchanged; the ordering becomes observable only when Phase 8 emits the evidence. Sorting is `List.sort` / `stream().sorted(Comparator)` — Java's sort is **guaranteed stable**, so ties (same tier) keep the incoming order (the matcher's `createdAt`-desc snapshot order). The `SourceType → tier` mapping is authored for completeness/Phase-8 use but is **NOT** applied to corpus reranking in Phase 7 (corpus authority-rerank is a deferred opt-in per `authority.mode`, spec §7.6).

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5 + Mockito. Build file: `build.gradle.kts` (no pom). Backend tests run via `./gradlew test`. The new tests are POJO/Mockito — **no Docker required**. Reused domain (already merged): `com.msfg.rag.domain.LinkAuthority { PRIMARY, SECONDARY, BACKGROUND }`, `com.msfg.rag.domain.SourceType { AGENCY_GUIDELINE, INTERNAL_POLICY, INVESTOR_OVERLAY, EDUCATIONAL }`, `com.msfg.rag.domain.BrainSourceLink` (getter `getAuthority() : LinkAuthority`), `com.msfg.rag.domain.BrainPageGuide`, `com.msfg.rag.domain.Surface { PUBLIC, INTERNAL, BOTH }`. Reused (Phase 6): `com.msfg.rag.service.retrieval.PlannedEvidence` (`record(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links)` + `empty()`), `RetrievalPlan`, `SourceKind`, `RetrievalPlannerService`, `PageGuideService.match(...)`, `SourceLinkService.match(...)`.

---

## Backward-compatibility contract (HARD RULE — verify every task preserves it)

- **`order(...)` never changes WHICH evidence is present — only its order.** Same guides, same links, same counts; only the `links` list is reordered (stable sort by tier rank). `pageGuides` order is preserved exactly. So every Phase 6 `collect()` count/membership assertion still holds.
- **The corpus retrieval call is UNCHANGED.** The reranker (`RerankerService`), `RetrievalService.retrieve`, `RetrievalResult`, `RetrievedChunk`, and the program-boost logic are NOT touched. The `SourceType → tier` map exists but is applied to NOTHING in Phase 7 (no corpus rerank).
- **`AskService` is byte-identical.** Its 13-arg constructor, its `ask()` body, and BOTH `new AskResponse(...)` sites are unchanged. The side-evidence it `collect()`s is now authority-ordered (achieved entirely inside `RetrievalPlannerService.collect`), but `AskService`'s `log.info` emits only counts and the index set, so log output is byte-identical before and after Phase 7; the ordering becomes observable only when Phase 8 emits the evidence.
- **`AskResponse`, `ModelAnswer`, `CitationDto`, `RetrievalResult`, `RetrievedChunk`, `PlannedEvidence`, `RetrievalPlan`, `SourceKind` are NOT touched.** No new field. `PlannedEvidence` keeps its exact 2-component shape and `empty()`; `order(...)` returns a NEW `PlannedEvidence` built from the same record components.
- **The boot-locked prompt is NOT touched.** `packs/msfg-mortgage/prompt.yaml` (the 5-`%s` template), `PromptBuilderService.build(...)`, and `DomainPackLoader.validate()` are untouched → no boot risk, no golden change. `MsfgGoldenPackTest` and every pack/golden test stay green untouched.
- **The ordered `PlannedEvidence` never reaches the prompt, the model, the validator, or the response.** It is ordered inside `collect`, `log.info`'d by the unchanged `AskService` call site, and discarded. Phase 8 will thread it onward.
- **NO migration, NO DB change, NO pgvector/embedding work, NO `AuditLog`/telemetry persistence, NO `authority.yaml`.** The tier table lives as a code constant (the enum) per spec §6.4 / D6 — promotable to `authority.yaml` later without changing `AuthorityFilterService`'s interface. slf4j only (and Phase 7 adds no new log lines).
- The full backend suite (`./gradlew test`) must stay green. The existing `RetrievalPlannerServiceTest` gets a mechanical 3rd ctor arg plus one new ordered-collect assertion; `AskServiceTest`'s three `new RetrievalPlannerService(...)` builder call-sites each get a mechanical 3rd ctor arg. **No `AskService` ctor change.** Final task (Task 4) is the explicit no-regression gate.

---

## File Structure

### Backend — created

| Path | Responsibility |
|---|---|
| `src/main/java/com/msfg/rag/service/retrieval/AuthorityTier.java` | `enum AuthorityTier` — five tiers, each with a `final int rank` (lower = higher authority) per spec §6.4: `COMPANY_RULE(1), CURRENT_PAGE_GUIDE(2), PRIMARY_EXTERNAL(3), SECONDARY_EXTERNAL(4), BACKGROUND(5)`. `int rank()` accessor. |
| `src/main/java/com/msfg/rag/service/retrieval/AuthorityFilterService.java` | `@Service`, pure, no injected collaborators. `AuthorityTier tierOf(LinkAuthority)` (exhaustive switch), `AuthorityTier tierOf(SourceType)` (exhaustive switch; `INTERNAL_POLICY → COMPANY_RULE`, others → external tiers), `PlannedEvidence order(PlannedEvidence)` (stable-sort links by tier rank, preserve guide order, null/empty → `PlannedEvidence.empty()`). |

### Backend — modified

| Path | Change |
|---|---|
| `src/main/java/com/msfg/rag/service/retrieval/RetrievalPlannerService.java` | Add `AuthorityFilterService` as the **3rd** ctor parameter (after `PageGuideService`, `SourceLinkService`); store it; in `collect(...)` change the final statement from `return new PlannedEvidence(pageGuides, links);` to `return authorityFilterService.order(new PlannedEvidence(pageGuides, links));`. Add the import + a one-line Javadoc note. NOTHING else changes (`plan(...)` untouched). |

### Backend — tests created

| Path | Responsibility |
|---|---|
| `src/test/java/com/msfg/rag/service/retrieval/AuthorityFilterServiceTest.java` | POJO test (`new AuthorityFilterService()`). Covers: `AuthorityTier` rank ordering (`COMPANY_RULE < CURRENT_PAGE_GUIDE < PRIMARY_EXTERNAL < SECONDARY_EXTERNAL < BACKGROUND`); `tierOf(LinkAuthority)` exhaustive; `tierOf(SourceType)` exhaustive (incl. `INTERNAL_POLICY → COMPANY_RULE`); `order()` sorts links PRIMARY→SECONDARY→BACKGROUND with stable ties; preserves page-guide order; `order(null)`/`order(empty)` → `empty()`. |

### Backend — tests modified

| Path | Change |
|---|---|
| `src/test/java/com/msfg/rag/service/retrieval/RetrievalPlannerServiceTest.java` | Update the field initializer `new RetrievalPlannerService(pageGuides, sourceLinks)` → `new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService())` (the filter is pure → use a real instance, not a mock). Add a `collectReturnsAuthorityOrderedLinks` test: feed the `sourceLinks.match(...)` mock links in NON-rank order (BACKGROUND, PRIMARY, SECONDARY) and assert `collect(...)` returns them PRIMARY-first. |
| `src/test/java/com/msfg/rag/service/AskServiceTest.java` | The three `new RetrievalPlannerService(...)` call-sites (lines 117, 147–148, 454) each gain a 3rd arg `new AuthorityFilterService()`. Add the `import com.msfg.rag.service.retrieval.AuthorityFilterService;`. **No `AskService` ctor change**; the existing collect-only parity assertions (answer/citations unchanged) continue to prove answer behavior is unchanged with ordering active. |

### NOT touched (verify untouched at the end)

`service/AskService.java`, `dto/AskResponse.java`, `dto/AskRequest.java`, `service/ai/ModelAnswer.java`, `dto/CitationDto.java`, `service/ai/Intent.java`, `service/ai/IntentRouterService.java`, `service/ai/QuestionCategory.java`, `service/ai/PromptBuilderService.java`, `service/retrieval/RetrievalService.java`, `service/retrieval/RetrievalResult.java`, `service/retrieval/RetrievedChunk.java`, `service/retrieval/RerankerService.java`, `service/retrieval/PlannedEvidence.java`, `service/retrieval/RetrievalPlan.java`, `service/retrieval/SourceKind.java`, `service/retrieval/PageGuideService.java`, `service/retrieval/SourceLinkService.java`, `domain/LinkAuthority.java`, `domain/SourceType.java`, `domain/BrainSourceLink.java`, `domain/BrainPageGuide.java`, `domain/Surface.java`, `packs/msfg-mortgage/prompt.yaml`, every pack YAML, `pack/DomainPack.java`, `pack/DomainPackLoader.java`, `controller/AskController.java`, `exception/GlobalExceptionHandler.java`, `MsfgGoldenPackTest`, `DomainPackLoaderTest`, every migration.

---

## Conventions for every backend task

- **Test commands:** backend via `./gradlew test --tests "<FQN>"` (full suite: `./gradlew test`). The build is `build.gradle.kts`. NOTE: in this environment the raw `./gradlew` invocation is hook-redirected — when *executing*, run it through the context-mode shell wrapper: `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '<FQN>'")`. This plan WRITES the literal `./gradlew test --tests "..."` command for each step.
- **Tests are JUnit 5 + Mockito, POJO.** `AuthorityFilterServiceTest` uses `new AuthorityFilterService()` (no mocks needed). `RetrievalPlannerServiceTest` keeps its Mockito-mocked `PageGuideService`/`SourceLinkService` and uses a REAL `new AuthorityFilterService()` (the filter is pure — mocking it would only restate the sort and is brittle). `AskServiceTest` mirrors the existing file: `TestPacks.msfg()` + positional `new AskService(...)` + Mockito-mocked collaborators; only the nested `new RetrievalPlannerService(...)` arg list grows by one.
- **Keep test classes in the same package** as the class under test for package-private access (`AuthorityFilterServiceTest`/`RetrievalPlannerServiceTest` → `com.msfg.rag.service.retrieval`; `AskServiceTest` → `com.msfg.rag.service`).
- **No new exception path.** `AuthorityFilterService` never throws on the values it switches over (the switches are exhaustive over closed enums). `order(null)` is null-safe (returns `empty()`), so it cannot NPE. There is no surface parsing in Phase 7.
- **Commit** after each green task with the exact commands shown. End every commit message with the Co-Authored-By trailer.

---

## Verified anchors (do not re-research)

- **`RetrievalPlannerService` constructor has exactly 2 positional parameters today** (RetrievalPlannerService.java:33–37): `(PageGuideService pageGuideService, SourceLinkService sourceLinkService)`. After Phase 7 it has **3** (append `AuthorityFilterService authorityFilterService`).
- **`RetrievalPlannerService.collect(...)`** (RetrievalPlannerService.java:76–85) ends with `return new PlannedEvidence(pageGuides, links);`. `pageGuides` is `List<BrainPageGuide>`, `links` is `List<BrainSourceLink>`. The `plan(...)` method (lines 53–65) is PURE and is NOT touched.
- **`PlannedEvidence`** (PlannedEvidence.java) = `record PlannedEvidence(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links)` with accessors `pageGuides()` / `links()` and static `empty()` (returns a shared `EMPTY` with two empty lists). `order(...)` builds a NEW `PlannedEvidence(guides, sortedLinks)`.
- **`LinkAuthority`** (`com.msfg.rag.domain.LinkAuthority`) = `{ PRIMARY, SECONDARY, BACKGROUND }` (exactly three). **`SourceType`** (`com.msfg.rag.domain.SourceType`) = `{ AGENCY_GUIDELINE, INTERNAL_POLICY, INVESTOR_OVERLAY, EDUCATIONAL }` (exactly four).
- **`BrainSourceLink.getAuthority()`** returns `LinkAuthority` (BrainSourceLink.java:126). Fixture ctor (10-arg): `new BrainSourceLink(name, url, domain, authority, topics, freshnessRequired, allowedUse, doNotUseFor, surface, createdBy)` (BrainSourceLink.java:97–111). This is the EXACT ctor `RetrievalPlannerServiceTest`/`SourceLinkServiceTest`/`AskServiceTest` already use for fixtures (e.g. RetrievalPlannerServiceTest:113–115, SourceLinkServiceTest:155–159).
- **`BrainPageGuide`** fixture ctor (10-arg): `new BrainPageGuide(route, title, purpose, surface, userIntents, allowedGuidance, internalLinks, sourceLinkIds, topics, createdBy)` (BrainPageGuide.java:100–115). Used at RetrievalPlannerServiceTest:97–99.
- **`new RetrievalPlannerService(` call-sites** (entire repo, verified via grep over `src/`):
  - `src/main/java/...` — **NONE** (Spring wires it; the only producer is `@Service` + ctor injection).
  - `src/test/java/com/msfg/rag/service/retrieval/RetrievalPlannerServiceTest.java:33` — `new RetrievalPlannerService(pageGuides, sourceLinks)` (field initializer).
  - `src/test/java/com/msfg/rag/service/AskServiceTest.java:117` — `new RetrievalPlannerService(pageGuides, sourceLinks)` (in `askServiceReturning`).
  - `src/test/java/com/msfg/rag/service/AskServiceTest.java:147–148` — `new RetrievalPlannerService(mock(PageGuideService.class), mock(SourceLinkService.class))` (in `askServiceClassifying`).
  - `src/test/java/com/msfg/rag/service/AskServiceTest.java:454` — `new RetrievalPlannerService(pageGuides, sourceLinks)` (in the collect-only parity test).
  Every one of these four call-sites is updated to the 3-arg form (Tasks 2 + 3). `AskService`'s constructor stays at **13** params (it injects only `RetrievalPlannerService`, not `AuthorityFilterService`).
- **`Java's `List.sort` / `Collections.sort` / `Stream.sorted(Comparator)` are GUARANTEED stable**** (TimSort). Ties (equal tier rank) preserve the incoming order. This is the mechanism by which `createdAt`-desc snapshot order is preserved within a tier — do NOT add a secondary comparator.

---

## Design decisions locked in this plan (state them; do not silently choose)

- **`AuthorityTier` uses an explicit `final int rank` field, not `ordinal()`.** Spec §6.4 numbers the tiers 1–5; encoding the rank as data (not enum declaration position) decouples the comparator from accidental reordering of enum constants and reads self-documenting (`COMPANY_RULE(1)`). The Javadoc states `rank()` is the sort key; lower = higher authority.
- **`SourceType → tier` mapping (documented, defined, NOT applied to corpus rerank in Phase 7):** `INTERNAL_POLICY → COMPANY_RULE(1)`; `AGENCY_GUIDELINE → PRIMARY_EXTERNAL(3)` (agency selling/servicing guides + HUD are the canonical primary external authority, mirroring `LinkAuthority.PRIMARY`); `INVESTOR_OVERLAY → SECONDARY_EXTERNAL(4)` (approved supporting overlay); `EDUCATIONAL → BACKGROUND(5)` (borrower-facing context). This method exists for completeness and Phase-8 use (e.g. tiering corpus chunks once an `Evidence` carries `sourceType`); **Phase 7 wires only `tierOf(LinkAuthority)` into `order(...)`** and applies NOTHING to corpus reranking — corpus authority-rerank is a deferred opt-in (`authority.mode`, spec §7.6). The `tierOf(SourceType)` Javadoc says exactly this.
- **`tierOf(LinkAuthority)` mapping:** `PRIMARY → PRIMARY_EXTERNAL(3)`, `SECONDARY → SECONDARY_EXTERNAL(4)`, `BACKGROUND → BACKGROUND(5)`. (The link row only ever carries the three external authorities — tiers 1–2 come from elsewhere, per `LinkAuthority`'s own Javadoc and spec §6.4 note.)
- **`order(...)` sorts ONLY the links; page guides are untouched.** All matched page guides are tier `CURRENT_PAGE_GUIDE(2)` (the route/topic-matched guide), so they are mutually tie-equal — sorting them is a no-op, and preserving the incoming order is both correct and cheaper. The Javadoc documents that guides keep their `match()` order as-is.
- **Tie-stability is delegated to Java's stable sort.** The links comparator is `Comparator.comparingInt(link -> tierOf(link.getAuthority()).rank())` with NO tiebreaker — equal-rank links keep the matcher's `createdAt`-desc order. Adding a secondary key (e.g. by name) would REORDER ties and break the contract; do not.
- **Null/empty safety:** `order(null)` → `PlannedEvidence.empty()`. An evidence whose `links()` is empty/null → guides preserved, empty links (which equals `empty()` only when guides are also empty; otherwise a new `PlannedEvidence(guides, List.of())`). The method never returns the input instance when sorting is needed — it always returns a new record (records are immutable; the new `links` list is a fresh sorted copy).
- **`AuthorityFilterService` is wired into `RetrievalPlannerService`, NOT `AskService`.** This keeps `AskService`'s 13-arg ctor byte-identical and localizes the ordering to the one place evidence is produced. Spring instantiates `AuthorityFilterService` (no-arg ctor, `@Service`) and injects it into `RetrievalPlannerService` automatically.

---

## Task 1: `AuthorityTier` enum + `AuthorityFilterService` (tierOf + order) + POJO test

**Files:**
- Create: `src/main/java/com/msfg/rag/service/retrieval/AuthorityTier.java`
- Create: `src/main/java/com/msfg/rag/service/retrieval/AuthorityFilterService.java`
- Create: `src/test/java/com/msfg/rag/service/retrieval/AuthorityFilterServiceTest.java`

TDD: write the failing test first (it references types that don't compile yet), watch it fail to compile, then add the enum + service to make it pass.

- [ ] **Write the failing test `AuthorityFilterServiceTest.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.domain.Surface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the authority tier mapping + side-evidence ordering
 * (spec §6.4 / §7.6). No mocks — {@link AuthorityFilterService} has no
 * collaborators. Verifies tier rank ordering, the exhaustive {@code tierOf}
 * mappings, and that {@code order()} stable-sorts links PRIMARY→SECONDARY→
 * BACKGROUND while preserving page-guide order.
 */
class AuthorityFilterServiceTest {

    private final AuthorityFilterService filter = new AuthorityFilterService();

    // --- AuthorityTier rank ordering (spec §6.4) ------------------------

    @Test
    void tierRanksAreStrictlyAscendingPerSpec() {
        assertEquals(1, AuthorityTier.COMPANY_RULE.rank());
        assertEquals(2, AuthorityTier.CURRENT_PAGE_GUIDE.rank());
        assertEquals(3, AuthorityTier.PRIMARY_EXTERNAL.rank());
        assertEquals(4, AuthorityTier.SECONDARY_EXTERNAL.rank());
        assertEquals(5, AuthorityTier.BACKGROUND.rank());

        assertTrue(AuthorityTier.COMPANY_RULE.rank() < AuthorityTier.CURRENT_PAGE_GUIDE.rank());
        assertTrue(AuthorityTier.CURRENT_PAGE_GUIDE.rank() < AuthorityTier.PRIMARY_EXTERNAL.rank());
        assertTrue(AuthorityTier.PRIMARY_EXTERNAL.rank() < AuthorityTier.SECONDARY_EXTERNAL.rank());
        assertTrue(AuthorityTier.SECONDARY_EXTERNAL.rank() < AuthorityTier.BACKGROUND.rank());
    }

    // --- tierOf(LinkAuthority) : exhaustive -----------------------------

    @Test
    void tierOfLinkAuthorityMapsEveryValue() {
        assertEquals(AuthorityTier.PRIMARY_EXTERNAL, filter.tierOf(LinkAuthority.PRIMARY));
        assertEquals(AuthorityTier.SECONDARY_EXTERNAL, filter.tierOf(LinkAuthority.SECONDARY));
        assertEquals(AuthorityTier.BACKGROUND, filter.tierOf(LinkAuthority.BACKGROUND));
    }

    // --- tierOf(SourceType) : exhaustive (incl INTERNAL_POLICY) ----------

    @Test
    void tierOfSourceTypeMapsEveryValue() {
        assertEquals(AuthorityTier.COMPANY_RULE, filter.tierOf(SourceType.INTERNAL_POLICY));
        assertEquals(AuthorityTier.PRIMARY_EXTERNAL, filter.tierOf(SourceType.AGENCY_GUIDELINE));
        assertEquals(AuthorityTier.SECONDARY_EXTERNAL, filter.tierOf(SourceType.INVESTOR_OVERLAY));
        assertEquals(AuthorityTier.BACKGROUND, filter.tierOf(SourceType.EDUCATIONAL));
    }

    // --- order() : stable sort of links by tier rank --------------------

    @Test
    void orderSortsLinksPrimaryThenSecondaryThenBackground() {
        // Built out of rank order: BACKGROUND, PRIMARY, SECONDARY.
        BrainSourceLink background = link(LinkAuthority.BACKGROUND, "bg");
        BrainSourceLink primary = link(LinkAuthority.PRIMARY, "pri");
        BrainSourceLink secondary = link(LinkAuthority.SECONDARY, "sec");

        PlannedEvidence ordered = filter.order(
                new PlannedEvidence(List.of(), List.of(background, primary, secondary)));

        assertEquals(
                List.of(LinkAuthority.PRIMARY, LinkAuthority.SECONDARY, LinkAuthority.BACKGROUND),
                ordered.links().stream().map(BrainSourceLink::getAuthority).toList());
    }

    @Test
    void orderKeepsIncomingOrderWithinSameTier() {
        // Two PRIMARY links + one SECONDARY interleaved; the two PRIMARYs must
        // keep their incoming relative order (stable sort, no tiebreaker).
        BrainSourceLink primaryA = link(LinkAuthority.PRIMARY, "A");
        BrainSourceLink secondary = link(LinkAuthority.SECONDARY, "S");
        BrainSourceLink primaryB = link(LinkAuthority.PRIMARY, "B");

        PlannedEvidence ordered = filter.order(
                new PlannedEvidence(List.of(), List.of(primaryA, secondary, primaryB)));

        assertEquals(List.of("A", "B", "S"),
                ordered.links().stream().map(BrainSourceLink::getName).toList());
    }

    @Test
    void orderPreservesPageGuideOrder() {
        BrainPageGuide first = guide("/a", "First");
        BrainPageGuide second = guide("/b", "Second");

        PlannedEvidence ordered = filter.order(
                new PlannedEvidence(List.of(first, second), List.of()));

        assertEquals(List.of("First", "Second"),
                ordered.pageGuides().stream().map(BrainPageGuide::getTitle).toList());
        assertNotSame(PlannedEvidence.empty(), ordered);     // non-empty result is a fresh record, not the EMPTY singleton
    }

    @Test
    void orderReturnsANewEvidenceWithSortedLinks() {
        PlannedEvidence input = new PlannedEvidence(
                List.of(), List.of(link(LinkAuthority.SECONDARY, "s"), link(LinkAuthority.PRIMARY, "p")));

        PlannedEvidence ordered = filter.order(input);

        assertNotSame(input, ordered);                       // new record, links resorted
        assertEquals(2, ordered.links().size());             // same membership
    }

    @Test
    void orderNullReturnsEmpty() {
        assertSame(PlannedEvidence.empty(), filter.order(null));
    }

    @Test
    void orderEmptyReturnsEmpty() {
        assertSame(PlannedEvidence.empty(), filter.order(PlannedEvidence.empty()));
    }

    // --- fixtures (reuse the public ctors used in Phase 6 tests) --------

    private static BrainSourceLink link(LinkAuthority authority, String name) {
        return new BrainSourceLink(
                name, "https://x.com", "x.com", authority,
                List.of("topic"), false, List.of(), List.of(), Surface.BOTH, "seed");
    }

    private static BrainPageGuide guide(String route, String title) {
        return new BrainPageGuide(
                route, title, "purpose", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of("topic"), "seed");
    }
}
```

- [ ] **Run the test — see it FAIL (does not compile: `AuthorityTier` / `AuthorityFilterService` do not exist yet):**
  `./gradlew test --tests "com.msfg.rag.service.retrieval.AuthorityFilterServiceTest"`
  Expected: compilation failure referencing `AuthorityTier` / `AuthorityFilterService` cannot be resolved.

- [ ] **Write `AuthorityTier.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.retrieval;

/**
 * Trust tier used to order evidence (spec §6.4, D6). Lower {@link #rank()} = higher
 * authority. The five tiers, in authority order:
 * <ol>
 *   <li>{@link #COMPANY_RULE} — company rule / internal policy
 *       ({@code brain_documents sourceType=INTERNAL_POLICY} + live Rules).</li>
 *   <li>{@link #CURRENT_PAGE_GUIDE} — the route/topic-matched page guide.</li>
 *   <li>{@link #PRIMARY_EXTERNAL} — primary external source
 *       ({@code brain_source_links authority=PRIMARY}: agency guides, HUD).</li>
 *   <li>{@link #SECONDARY_EXTERNAL} — approved secondary source ({@code authority=SECONDARY}).</li>
 *   <li>{@link #BACKGROUND} — general background / context ({@code authority=BACKGROUND}).</li>
 * </ol>
 *
 * <p><b>Why an explicit {@code rank} field (not {@code ordinal()}):</b> spec §6.4
 * numbers the tiers 1–5; encoding the rank as data keeps the sort key decoupled
 * from enum declaration order and reads self-documenting. {@code rank()} is the
 * sort key used by {@link AuthorityFilterService#order}.
 *
 * <p>This lives as a code constant first (D6); it is promotable to an editable
 * {@code authority.yaml} / {@code brain_settings} later without changing the
 * filter's interface.
 */
public enum AuthorityTier {

    /** Company rule / internal policy — highest authority. */
    COMPANY_RULE(1),

    /** The current route/topic-matched page guide. */
    CURRENT_PAGE_GUIDE(2),

    /** Primary external source (agency selling/servicing guides, HUD handbook). */
    PRIMARY_EXTERNAL(3),

    /** Approved supporting/secondary external source. */
    SECONDARY_EXTERNAL(4),

    /** General background / context only — lowest authority. */
    BACKGROUND(5);

    private final int rank;

    AuthorityTier(int rank) {
        this.rank = rank;
    }

    /** Sort key — lower = higher authority (spec §6.4). */
    public int rank() {
        return rank;
    }
}
```

- [ ] **Write `AuthorityFilterService.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.SourceType;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Tiers and orders the collected side-evidence by trust authority (spec §6.4,
 * §7.6). Pure {@code @Service} — no injected collaborators.
 *
 * <p><b>Phase 7 scope (INERT seam):</b> this only re-orders the side-evidence
 * {@link RetrievalPlannerService#collect} produces, so Phase 8 can emit it
 * trust-first. It does NOT touch the corpus retrieval, the reranker, the
 * boot-locked prompt, {@code AskResponse}, or {@code ModelAnswer}. The ordered
 * {@link PlannedEvidence} remains logged-only until Phase 8 emits it.
 */
@Service
public class AuthorityFilterService {

    /**
     * Maps a link's external authority to its tier. The link row only ever
     * carries the three external authorities (tiers 1–2 come from elsewhere,
     * per {@link LinkAuthority} / spec §6.4). Exhaustive switch.
     */
    public AuthorityTier tierOf(LinkAuthority authority) {
        return switch (authority) {
            case PRIMARY -> AuthorityTier.PRIMARY_EXTERNAL;
            case SECONDARY -> AuthorityTier.SECONDARY_EXTERNAL;
            case BACKGROUND -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Maps a corpus source's {@link SourceType} to its tier. Exhaustive switch:
     * {@code INTERNAL_POLICY → COMPANY_RULE} (company rule);
     * {@code AGENCY_GUIDELINE → PRIMARY_EXTERNAL} (agency guides + HUD = primary);
     * {@code INVESTOR_OVERLAY → SECONDARY_EXTERNAL} (approved supporting overlay);
     * {@code EDUCATIONAL → BACKGROUND} (borrower-facing context).
     *
     * <p><b>Defined for completeness / Phase-8 use; NOT applied to corpus
     * reranking in Phase 7.</b> Corpus authority-rerank is a deferred opt-in
     * (the {@code authority.mode} hard-sort/boost knob, spec §7.6). Phase 7 wires
     * only {@link #tierOf(LinkAuthority)} into {@link #order}; nothing calls this
     * overload against the corpus path yet.
     */
    public AuthorityTier tierOf(SourceType sourceType) {
        return switch (sourceType) {
            case INTERNAL_POLICY -> AuthorityTier.COMPANY_RULE;
            case AGENCY_GUIDELINE -> AuthorityTier.PRIMARY_EXTERNAL;
            case INVESTOR_OVERLAY -> AuthorityTier.SECONDARY_EXTERNAL;
            case EDUCATIONAL -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Returns a NEW {@link PlannedEvidence} with {@code links} stable-sorted
     * ascending by {@code tierOf(link.getAuthority()).rank()} (PRIMARY first, then
     * SECONDARY, then BACKGROUND). Ties keep the incoming order — the matcher's
     * {@code createdAt}-desc order — because Java's sort is stable and the
     * comparator has no tiebreaker. {@code pageGuides} are all tier
     * {@link AuthorityTier#CURRENT_PAGE_GUIDE}, so their relative order is
     * preserved as-is. Null-safe: {@code order(null)} and empty evidence return
     * {@link PlannedEvidence#empty()}.
     */
    public PlannedEvidence order(PlannedEvidence evidence) {
        if (evidence == null
                || (evidence.pageGuides().isEmpty() && evidence.links().isEmpty())) {
            return PlannedEvidence.empty();
        }
        List<BrainSourceLink> sortedLinks = evidence.links().stream()
                .sorted(Comparator.comparingInt(link -> tierOf(link.getAuthority()).rank()))
                .toList();
        return new PlannedEvidence(evidence.pageGuides(), sortedLinks);
    }
}
```

- [ ] **Run the test — see it PASS:**
  `./gradlew test --tests "com.msfg.rag.service.retrieval.AuthorityFilterServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all `AuthorityFilterServiceTest` cases green.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/AuthorityTier.java \
        src/main/java/com/msfg/rag/service/retrieval/AuthorityFilterService.java \
        src/test/java/com/msfg/rag/service/retrieval/AuthorityFilterServiceTest.java
git commit -m "feat(authority): AuthorityTier enum + pure AuthorityFilterService (tierOf + order)

Five trust tiers per spec §6.4 with an explicit int rank; tierOf maps
LinkAuthority/SourceType to a tier (INTERNAL_POLICY→COMPANY_RULE); order()
stable-sorts collected links PRIMARY→SECONDARY→BACKGROUND, preserving
page-guide order and tie order. INERT seam — logged only until Phase 8.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Wire `AuthorityFilterService` into `RetrievalPlannerService.collect` + update its test

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/retrieval/RetrievalPlannerService.java`
- Modify: `src/test/java/com/msfg/rag/service/retrieval/RetrievalPlannerServiceTest.java`

TDD: update the test first (3-arg ctor + the new ordered-collect assertion), watch the new assertion fail (collect still returns unordered links), then wire the filter into `collect`.

- [ ] **Update `RetrievalPlannerServiceTest.java` — change the field initializer to the 3-arg form and add the ordered-collect test.** First change the field (line 33):

  Replace:
```java
    private final RetrievalPlannerService planner =
            new RetrievalPlannerService(pageGuides, sourceLinks);
```
  With:
```java
    private final RetrievalPlannerService planner =
            new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService());
```

  Then add this test method to the `// --- collect(): delegate to matchers per plan` section (after `collectPageGuidanceCallsBothMatchers`, before the closing brace):

```java
    @Test
    void collectReturnsAuthorityOrderedLinks() {
        // Matcher returns links OUT of authority order: BACKGROUND, PRIMARY, SECONDARY.
        BrainSourceLink background = new BrainSourceLink(
                "bg", "https://x.com", "x.com", LinkAuthority.BACKGROUND,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        BrainSourceLink primary = new BrainSourceLink(
                "pri", "https://x.com", "x.com", LinkAuthority.PRIMARY,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        BrainSourceLink secondary = new BrainSourceLink(
                "sec", "https://x.com", "x.com", LinkAuthority.SECONDARY,
                List.of("fha"), false, List.of(), List.of(), Surface.BOTH, "seed");
        when(sourceLinks.match("fha", null))
                .thenReturn(List.of(background, primary, secondary));
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.LINK_REGISTRY));

        PlannedEvidence evidence = planner.collect(plan, "fha", null, null);

        // collect() returns the links authority-ordered: PRIMARY → SECONDARY → BACKGROUND.
        assertEquals(
                List.of(LinkAuthority.PRIMARY, LinkAuthority.SECONDARY, LinkAuthority.BACKGROUND),
                evidence.links().stream().map(BrainSourceLink::getAuthority).toList());
    }
```

  (`BrainSourceLink`, `LinkAuthority`, `Surface`, `List`, `Set`, `assertEquals`, `when` are all already imported in this file — see RetrievalPlannerServiceTest.java:3–21.)

- [ ] **Run the test — see the NEW assertion FAIL (collect still returns unordered links — BACKGROUND first):**
  `./gradlew test --tests "com.msfg.rag.service.retrieval.RetrievalPlannerServiceTest"`
  Expected: `collectReturnsAuthorityOrderedLinks` fails — expected `[PRIMARY, SECONDARY, BACKGROUND]` but got `[BACKGROUND, PRIMARY, SECONDARY]`. (The 3-arg ctor compiles because `AuthorityFilterService` exists from Task 1; the rest of the suite still passes.)

- [ ] **Wire the filter into `RetrievalPlannerService`.** No new import is needed — `AuthorityFilterService`, `AuthorityTier`, and `PlannedEvidence` are all in `com.msfg.rag.service.retrieval`, the same package as `RetrievalPlannerService`. Only the field/ctor/return change. Edit the class as follows.

  Replace the two fields + constructor (lines 30–37):
```java
    private final PageGuideService pageGuideService;
    private final SourceLinkService sourceLinkService;

    public RetrievalPlannerService(PageGuideService pageGuideService,
                                   SourceLinkService sourceLinkService) {
        this.pageGuideService = pageGuideService;
        this.sourceLinkService = sourceLinkService;
    }
```
  With:
```java
    private final PageGuideService pageGuideService;
    private final SourceLinkService sourceLinkService;
    private final AuthorityFilterService authorityFilterService;

    public RetrievalPlannerService(PageGuideService pageGuideService,
                                   SourceLinkService sourceLinkService,
                                   AuthorityFilterService authorityFilterService) {
        this.pageGuideService = pageGuideService;
        this.sourceLinkService = sourceLinkService;
        this.authorityFilterService = authorityFilterService;
    }
```

  Replace the final return of `collect(...)` (line 84):
```java
        return new PlannedEvidence(pageGuides, links);
```
  With:
```java
        // Phase 7: tier + order the collected side-evidence (links PRIMARY-first)
        // so Phase 8 can emit it trust-first. INERT — same membership, only order.
        return authorityFilterService.order(new PlannedEvidence(pageGuides, links));
```

- [ ] **Update the class Javadoc note (optional but recommended for accuracy).** Append to the existing class Javadoc, after the "Phase 8 consumes the collected evidence." sentence (RetrievalPlannerService.java:22):

  Add this sentence inside the same `<p>` paragraph or as a new line before the closing `*/`:
```java
     * <p><b>Phase 7:</b> {@code collect} now returns the side-evidence already
     * authority-ordered via {@link AuthorityFilterService#order} (links
     * PRIMARY→SECONDARY→BACKGROUND, page-guide order preserved). This is still an
     * INERT seam — the caller logs it and otherwise discards it until Phase 8.
```

- [ ] **Run the test — see it PASS:**
  `./gradlew test --tests "com.msfg.rag.service.retrieval.RetrievalPlannerServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all `RetrievalPlannerServiceTest` cases green (incl. `collectReturnsAuthorityOrderedLinks` and every existing `plan()`/`collect()` case — membership/delegation assertions are unaffected by ordering).

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/RetrievalPlannerService.java \
        src/test/java/com/msfg/rag/service/retrieval/RetrievalPlannerServiceTest.java
git commit -m "feat(authority): collect() returns authority-ordered side-evidence

RetrievalPlannerService ctor 2→3 (injects AuthorityFilterService); collect()
wraps its return in authorityFilterService.order(...) so links come back
PRIMARY-first. INERT — same membership, only order; AskService unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Update the three `new RetrievalPlannerService(...)` call-sites in `AskServiceTest` (no AskService ctor change)

**Files:**
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java`

`AskService` itself is NOT touched (it injects only `RetrievalPlannerService`). But `AskServiceTest` constructs `RetrievalPlannerService` directly in three builders — each must grow the 3rd arg so the file compiles. This is a mechanical change; the existing assertions (incl. the collect-only parity test) then prove answer behavior is unchanged with ordering active.

- [ ] **Add the import** (with the other `com.msfg.rag.service.retrieval.*` imports — AskServiceTest.java already imports `PageGuideService` at line 24 and `SourceLinkService` at line 29; add alongside):

```java
import com.msfg.rag.service.retrieval.AuthorityFilterService;
```

- [ ] **Call-site 1 (`askServiceReturning`, line 117).** Replace:
```java
                new RetrievalPlannerService(pageGuides, sourceLinks));
```
  With:
```java
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()));
```

- [ ] **Call-site 2 (`askServiceClassifying`, lines 147–148).** Replace:
```java
                new RetrievalPlannerService(
                        mock(PageGuideService.class), mock(SourceLinkService.class)));
```
  With:
```java
                new RetrievalPlannerService(
                        mock(PageGuideService.class), mock(SourceLinkService.class),
                        new AuthorityFilterService()));
```

- [ ] **Call-site 3 (collect-only parity test, line 454).** Replace:
```java
                new RetrievalPlannerService(pageGuides, sourceLinks));
```
  With:
```java
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()));
```

  > Note: there are two `new RetrievalPlannerService(pageGuides, sourceLinks))` occurrences (call-sites 1 and 3). When editing, disambiguate by surrounding context — call-site 1 is in `askServiceReturning` (preceded by `new IntentRouterService(),`), call-site 3 is in the parity test (preceded by `new ObjectMapper(), new IntentRouterService(),`). Edit each in its own builder.

- [ ] **Run `AskServiceTest` — see it PASS (compiles with the 3-arg ctor; all existing scenarios green):**
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: `BUILD SUCCESSFUL`. The collect-only parity test still asserts identical answer/citations/escalation/confidence with and without a matching page route — proving the now-ordered side-evidence does NOT change answer behavior. The corpus question is still the raw question on both calls.

- [ ] **Commit:**
```
git add src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "test(authority): update AskServiceTest planner call-sites to 3-arg ctor

Three new RetrievalPlannerService(...) builders gain new AuthorityFilterService();
AskService ctor stays 13. Existing parity assertions confirm authority-ordered
side-evidence does not change the answer or citations.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Full-suite no-regression verification

**Files:** none modified (verification only).

Prove the whole backend suite is green and the no-touch set is untouched.

- [ ] **Run the full backend suite:**
  `./gradlew test`
  Expected: `BUILD SUCCESSFUL`. In particular `MsfgGoldenPackTest`, `DomainPackLoaderTest`, `AskServiceTest`, `RetrievalPlannerServiceTest`, `AuthorityFilterServiceTest`, `PageGuideServiceTest`, `SourceLinkServiceTest`, `RetrievalServiceTest`, `RerankerServiceTest`, `VocabularyServiceTest` all green.

- [ ] **Verify `AskService` is untouched (no diff):**
  `git status --porcelain src/main/java/com/msfg/rag/service/AskService.java`
  Expected: empty output (AskService.java was never edited — its 13-arg ctor is byte-identical).

- [ ] **Verify the boot-lock / corpus / response no-touch set is untouched:**
  `git status --porcelain src/main/resources/packs/ src/main/java/com/msfg/rag/dto/AskResponse.java src/main/java/com/msfg/rag/service/ai/ModelAnswer.java src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java src/main/java/com/msfg/rag/service/retrieval/RerankerService.java src/main/java/com/msfg/rag/service/retrieval/PlannedEvidence.java src/main/java/com/msfg/rag/service/retrieval/RetrievalPlan.java src/main/java/com/msfg/rag/service/retrieval/SourceKind.java src/main/java/com/msfg/rag/service/retrieval/PageGuideService.java src/main/java/com/msfg/rag/service/retrieval/SourceLinkService.java src/main/java/com/msfg/rag/domain/`
  Expected: empty output.

- [ ] **Verify NO migration was added (no DB change):**
  `git status --porcelain src/main/resources/db/migration/`
  Expected: empty output.

- [ ] **Final commit (only if any tracked verification artifact changed — normally nothing to commit here).** If `git status` is clean, skip. Otherwise:
```
git add -A
git commit -m "test(authority): Phase 7 full-suite no-regression verification

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (writing-plans)

**Spec coverage (every LOCKED DESIGN DECISION mapped to a task):**
- New enum `AuthorityTier` in `com.msfg.rag.service.retrieval` — five tiers with explicit `int rank` `COMPANY_RULE(1)…BACKGROUND(5)` + `rank()` accessor, per spec §6.4 → **Task 1**.
- New `AuthorityFilterService` `@Service`, pure (no collaborators): `tierOf(LinkAuthority)` exhaustive (PRIMARY→PRIMARY_EXTERNAL, SECONDARY→SECONDARY_EXTERNAL, BACKGROUND→BACKGROUND) → **Task 1**.
- `tierOf(SourceType)` exhaustive (INTERNAL_POLICY→COMPANY_RULE; AGENCY_GUIDELINE→PRIMARY_EXTERNAL; INVESTOR_OVERLAY→SECONDARY_EXTERNAL; EDUCATIONAL→BACKGROUND), documented as NOT applied to corpus rerank in Phase 7 → **Task 1** (Javadoc + `tierOfSourceTypeMapsEveryValue`).
- `order(PlannedEvidence)` — new `PlannedEvidence` with links stable-sorted ascending by tier rank, page-guide order preserved, `order(null)`/empty → `empty()` → **Task 1** (`orderSorts…`, `orderKeepsIncomingOrderWithinSameTier`, `orderPreservesPageGuideOrder`, `orderNullReturnsEmpty`, `orderEmptyReturnsEmpty`).
- Wire `AuthorityFilterService` into `RetrievalPlannerService` (ctor 2→3) and `collect(...)` returns `authorityFilterService.order(new PlannedEvidence(guides, links))`; `plan(...)` untouched → **Task 2**.
- `AskService` UNCHANGED (still injects only `RetrievalPlannerService`; 13-arg ctor byte-identical); its `collect`-path `log.info` is byte-identical (emits only counts + index set; ordering observable only in Phase 8) → **Task 3** (no AskService edit) + **Task 4** (`git status` proves AskService.java untouched).
- No corpus/reranker/prompt/AskResponse/ModelAnswer change, no migration, no DB change → honored throughout; **Task 4** asserts the no-touch set + no migration.

**Placeholder scan:** No `TODO`, `FIXME`, `...`, `<placeholder>`, or "implement here" in any code block. Every file is complete and copy-paste-ready. Every Edit shows the exact before/after text.

**Type / signature consistency vs the live files:**
- `RetrievalPlannerService` ctor arity verified = **2** today (RetrievalPlannerService.java:33–37: `(PageGuideService, SourceLinkService)`) → **3** after Task 2 (append `AuthorityFilterService`). Field + assignment + param all added consistently. `collect(...)`'s return changed from `new PlannedEvidence(pageGuides, links)` to `authorityFilterService.order(new PlannedEvidence(pageGuides, links))`; `plan(...)` untouched.
- **EVERY `new RetrievalPlannerService(` call-site enumerated and updated:** src/main = NONE (Spring wires the `@Service`). src/test = exactly four: `RetrievalPlannerServiceTest.java:33` (Task 2, → real `new AuthorityFilterService()`), `AskServiceTest.java:117` (Task 3), `AskServiceTest.java:147–148` (Task 3), `AskServiceTest.java:454` (Task 3). All four → 3-arg form. Verified by `grep -rn "new RetrievalPlannerService(" src/`.
- **`AskService` ctor stays 13** — `AskService.java` is NOT in any task's modify list; Task 4 `git status --porcelain` asserts it is byte-identical. `AuthorityFilterService` is injected into `RetrievalPlannerService`, never into `AskService`.
- `AuthorityFilterService.tierOf(LinkAuthority)` switches over `{ PRIMARY, SECONDARY, BACKGROUND }` — exhaustive over the verified 3-value `LinkAuthority` (no `default`, compiles as exhaustive). `tierOf(SourceType)` switches over `{ AGENCY_GUIDELINE, INTERNAL_POLICY, INVESTOR_OVERLAY, EDUCATIONAL }` — exhaustive over the verified 4-value `SourceType`.
- `order(...)` uses `evidence.pageGuides()` / `evidence.links()` (the verified `PlannedEvidence` record accessors) and `PlannedEvidence.empty()` (verified static); builds `new PlannedEvidence(guides, sortedLinks)` (verified 2-arg canonical ctor). `BrainSourceLink.getAuthority()` returns `LinkAuthority` (verified BrainSourceLink.java:126).
- Fixtures use the verified 10-arg ctors: `BrainSourceLink(name, url, domain, authority, topics, freshnessRequired, allowedUse, doNotUseFor, surface, createdBy)` and `BrainPageGuide(route, title, purpose, surface, userIntents, allowedGuidance, internalLinks, sourceLinkIds, topics, createdBy)` — identical to RetrievalPlannerServiceTest:97–99/113–115, SourceLinkServiceTest:155–159, AskServiceTest:415–423.
- Imports: `AuthorityFilterService.java` imports `BrainSourceLink`, `LinkAuthority`, `SourceType`, `java.util.Comparator`, `java.util.List`, `org.springframework.stereotype.Service` (all used). `AuthorityFilterServiceTest` imports the domain types + JUnit assertions (`assertEquals`, `assertNotSame`, `assertSame`, `assertTrue`) used. `RetrievalPlannerServiceTest` reuses its existing imports (no new import needed — `AuthorityFilterService` is same-package). `AskServiceTest` adds `import com.msfg.rag.service.retrieval.AuthorityFilterService;` (Task 3).
- **Tie-stability:** `order(...)` uses `Comparator.comparingInt(...)` with NO secondary key; Java's `Stream.sorted` is guaranteed stable (TimSort), so equal-rank links keep the matcher's `createdAt`-desc snapshot order. `orderKeepsIncomingOrderWithinSameTier` proves it. Documented as a locked decision and in the `order` Javadoc — do not add a tiebreaker.

**Boot-lock / golden / answer-behavior safety:** `prompt.yaml`, the 5-`%s` template, `PromptBuilderService.build`, `DomainPackLoader.validate`, `DomainPack`, `RetrievalService`, `RerankerService`, `RetrievalResult`, `RetrievedChunk`, `AskResponse`, `ModelAnswer`, `PlannedEvidence`, `RetrievalPlan`, `SourceKind`, and `MsfgGoldenPackTest` are untouched (Task 4 asserts this). No migration added. `AuthorityFilterService` (`@Service`, no-arg ctor) is auto-instantiated and injected into `RetrievalPlannerService` — Spring wiring needs no config. `AskService` is byte-identical, so answer behavior is unchanged; the existing `AskServiceTest` parity test confirms the now-ordered side-evidence does not alter the answer or citations.
