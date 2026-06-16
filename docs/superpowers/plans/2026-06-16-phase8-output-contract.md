# Phase 8 — Output Contract (server-side recommendedPage/links/nextAction) + light link validator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate three NEW, optional `AskResponse` fields — `recommendedPage` (`{route,label}`), `links[]` (`{name,url,authority}`), and `nextAction` (string) — **server-side**, from the Phase 6/7 collected + authority-ordered `PlannedEvidence sideEvidence` that `AskService.ask(...)` already gathers (and today only logs). The LLM answer stays corpus-grounded and byte-identical; the boot-locked prompt is NOT touched. A new pure `OutputContractService` builds the three values and runs a **light, degrade-gracefully** link/grounding sanity check (drop a bad field, never throw, never escalate).

**Architecture:** **build, don't intercept.** `OutputContractService` is a pure, collaborator-free `@Service` (`com.msfg.rag.service.ai`) whose single public method `OutputContract build(PlannedEvidence evidence)` returns a small immutable holder record `OutputContract(RecommendedPageDto recommendedPage, List<LinkDto> links, String nextAction)`. Two new wire DTOs (`RecommendedPageDto`, `LinkDto`) live in `com.msfg.rag.dto`, mirroring `CitationDto`'s record + `@JsonProperty` style. `AskResponse` gains the three trailing optional components; BOTH construction sites in `AskService` are updated — line 204 (happy path → `outputContractService.build(sideEvidence)` exploded into the three args) and line 236 (`refuse()` → `null, List.of(), null`). `AskService` gains exactly ONE constructor parameter (13 → 14, appending `OutputContractService`). `sideEvidence` is already a local declared at AskService.java:138 in the same `ask()` method scope as the line-204 construction — NO hoisting or threading is needed. The dashboard `AskResponse` type + `TestConsole` render the new fields additively (render only when present). NO Flyway migration, NO prompt change, NO `ModelAnswer`/`ensureCitations`/`PromptBuilderService`/`RerankerService`/`RetrievalService.retrieve` change.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5 + Mockito. Build file: `build.gradle.kts` (no pom). Backend tests run via `./gradlew test`. The new tests (`OutputContractServiceTest`, the extended `AskServiceTest`) are POJO/Mockito + `TestPacks.msfg()` — **no Docker required**. Dashboard: React + Vite + TypeScript + vitest; checks via `npm run check` (tsc `--noEmit`, `noUnusedLocals` ON), `npm run build`, `npx vitest run` (run from `dashboard/`). Reused domain (already merged): `com.msfg.rag.domain.BrainPageGuide` (`getRoute()`, `getTitle()`, `getAllowedGuidance() : List<String>`), `com.msfg.rag.domain.BrainSourceLink` (`getName()`, `getUrl()`, `getAuthority() : LinkAuthority`), `com.msfg.rag.domain.LinkAuthority { PRIMARY, SECONDARY, BACKGROUND }` (`.name()` → the tier string). Reused (Phase 6/7): `com.msfg.rag.service.retrieval.PlannedEvidence` (`record(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links)` + `empty()`, already authority-ordered by `AuthorityFilterService.order` inside `RetrievalPlannerService.collect`).

---

## Backward-compatibility contract (HARD RULE — verify every task preserves it)

- **The LLM answer is byte-identical.** Corpus retrieval (`RetrievalService.retrieve`), the boot-locked 5-`%s` prompt (`packs/msfg-mortgage/prompt.yaml` + `PromptBuilderService.build`), generation, JSON parse, `ensureCitations`, and `AnswerValidationService.validate(modelAnswer, true)` are ALL untouched. The new fields are computed *alongside* the existing answer at the line-204 construction site and do not feed back into the prompt, model, or compliance validator.
- **`recommendedPage`/`links`/`nextAction` are derived SERVER-SIDE only.** They come exclusively from `sideEvidence` (the Phase 6/7 `PlannedEvidence`). The model never sees them and never emits them. `ModelAnswer` is NOT extended.
- **`AskResponse` gains exactly THREE trailing OPTIONAL components**, in this order: `RecommendedPageDto recommendedPage, List<LinkDto> links, String nextAction`. Absent/null is valid wire shape (existing JSON consumers ignore unknown extra keys; the website client is unchanged). BOTH `new AskResponse(...)` sites are updated.
- **`refuse()` (AskService.java:236) emits `null, List.of(), null`** for the three new fields — a refusal never leaks a recommended page, links, or a next action.
- **The link/grounding check is degrade-gracefully, NOT escalate.** Spec §7.8(b) says "every emitted link resolves to an active Link Registry row … else escalate." In this **server-side** model the links ARE active registry rows by construction (`SourceLinkService.activeLinks()` → `match()` → `AuthorityFilterService.order()`), so the spec's escalation branch is structurally unreachable. We therefore implement it as a defensive sanity check: a blank-url `LinkDto` is DROPPED (logged at WARN), a blank-route `recommendedPage` becomes `null` — a malformed row can never break or escalate a response. This is documented in `OutputContractService`'s Javadoc and in the Design Decisions section below.
- **`AskService` ctor 13 → 14** (append `OutputContractService outputContractService`). The THREE `new AskService(...)` builders in `AskServiceTest` (lines 114, 144, 449) each gain the 14th arg `new OutputContractService()` (the service is pure — use a real instance).
- **There is NO `new AskResponse(...)` site outside `AskService`.** Verified by grep: `AskServiceTest` reads response fields (e.g. `response.answer()`, `response.citations()`) but never constructs `AskResponse`. Adding three trailing record components therefore breaks NO test construction (only the two main sites change).
- **NO migration, NO DB change, NO pgvector/embedding work, NO telemetry/feedback persistence, NO prompt.yaml edit, NO golden change.** `MsfgGoldenPackTest`, `DomainPackLoaderTest`, and every pack/golden test stay green untouched because the prompt is unchanged. The full backend suite (`./gradlew test`) stays green.
- **Dashboard changes are additive and type-safe.** `types.ts` gains two interfaces + three optional fields on `AskResponse`; `TestConsole.tsx` renders them only when present. `npm run check` (with `noUnusedLocals`), `npm run build`, and `npx vitest run` all stay green.

---

## File Structure

### Backend — created

| Path | Responsibility |
|---|---|
| `src/main/java/com/msfg/rag/dto/RecommendedPageDto.java` | `record RecommendedPageDto(String route, String label)`. JSON keys `route`, `label` (single words → no `@JsonProperty` needed; matches `CitationDto`'s no-annotation-for-single-word convention, e.g. `section`). |
| `src/main/java/com/msfg/rag/dto/LinkDto.java` | `record LinkDto(@JsonProperty("name") String name, String url, String authority)`. JSON keys `name`, `url`, `authority` — all single words; `@JsonProperty("name")` on the first to mirror `CitationDto`'s explicit-annotation style (`url`/`authority` need none, identical to `CitationDto.section`). |
| `src/main/java/com/msfg/rag/service/ai/OutputContractService.java` | `@Service`, pure (no injected collaborators). `OutputContract build(PlannedEvidence evidence)` + nested `record OutputContract(RecommendedPageDto recommendedPage, List<LinkDto> links, String nextAction)`. Builds the recommended page (top page guide), the link list (authority-ordered, capped at 5, mapped to `LinkDto`), and the deterministic `nextAction` string; runs the light drop-on-blank sanity check. NO LLM call. |

### Backend — modified

| Path | Change |
|---|---|
| `src/main/java/com/msfg/rag/dto/AskResponse.java` | Append three trailing record components: `RecommendedPageDto recommendedPage, List<LinkDto> links, String nextAction`. |
| `src/main/java/com/msfg/rag/service/AskService.java` | (a) add `import com.msfg.rag.dto.LinkDto; import com.msfg.rag.dto.RecommendedPageDto; import com.msfg.rag.service.ai.OutputContractService;` (the first two only if referenced — they are NOT; only `OutputContractService` import is needed); (b) add ctor param `OutputContractService outputContractService` (13 → 14) + field + assignment; (c) at line 204 (happy path) build the contract from the in-scope `sideEvidence` and pass its three values; (d) at line 236 (`refuse()`) pass `null, List.of(), null`. NOTHING else changes. |

### Backend — tests created

| Path | Responsibility |
|---|---|
| `src/test/java/com/msfg/rag/service/ai/OutputContractServiceTest.java` | POJO test (`new OutputContractService()`). Covers: top guide → `RecommendedPageDto(route,title)`; links mapped to `LinkDto(name,url,authority.name())` with order preserved + capped at 5; `nextAction` template (guide allowed-guidance first, else title template, else links template, else null); no guides → recommendedPage null + links-based nextAction; empty evidence → null/empty/null; guide with null/blank route → recommendedPage null; defensive drop of a blank-url link. Fixtures via the public `BrainPageGuide`/`BrainSourceLink` 10-arg ctors. |

### Backend — tests modified

| Path | Change |
|---|---|
| `src/test/java/com/msfg/rag/service/AskServiceTest.java` | Add `import com.msfg.rag.service.ai.OutputContractService;`. The THREE `new AskService(...)` builders (lines 114, 144, 449) each gain the 14th arg `new OutputContractService()`. Add three pipeline tests: (a) happy path with a matched guide+link → `recommendedPage`/`links`/`nextAction` populated; (b) empty-evidence happy path → recommendedPage null, links empty, nextAction null, answer/citations IDENTICAL to the no-page baseline; (c) refuse path → all three null/empty. |

### Dashboard — modified

| Path | Change |
|---|---|
| `dashboard/src/types.ts` | Add `RecommendedPage { route: string; label: string }` and `Link { name: string; url: string; authority: string }` interfaces; extend `AskResponse` with `recommendedPage?: RecommendedPage \| null; links?: Link[] \| null; nextAction?: string \| null;`. |
| `dashboard/src/screens/TestConsole.tsx` | Below the existing answer/citations, render (only when present): a "Recommended page" line (`label` + `route`), a "Sources" list (each link `name → url` with an authority `Pill`), and a "Next action" line. Reuse `Pill`. |

### NOT touched (verify untouched at the end)

`service/ai/ModelAnswer.java`, `service/AskService.java`'s `ensureCitations`/`citationsFromChunks`/`parseModelAnswer` (the ctor + the two `new AskResponse` sites change; these helpers do not), `dto/CitationDto.java`, `dto/AskRequest.java`, `service/ai/PromptBuilderService.java`, `service/ai/AnswerValidationService.java`, `service/ai/QuestionClassifierService.java`, `service/ai/IntentRouterService.java`, `service/retrieval/RetrievalService.java`, `service/retrieval/RerankerService.java`, `service/retrieval/RetrievalResult.java`, `service/retrieval/RetrievedChunk.java`, `service/retrieval/PlannedEvidence.java`, `service/retrieval/RetrievalPlan.java`, `service/retrieval/RetrievalPlannerService.java`, `service/retrieval/AuthorityFilterService.java`, `service/retrieval/AuthorityTier.java`, `service/retrieval/PageGuideService.java`, `service/retrieval/SourceLinkService.java`, `domain/BrainPageGuide.java`, `domain/BrainSourceLink.java`, `domain/LinkAuthority.java`, `domain/Surface.java`, `packs/msfg-mortgage/prompt.yaml`, every pack YAML, `pack/DomainPack.java`, `pack/DomainPackLoader.java`, `controller/AskController.java`, `exception/GlobalExceptionHandler.java`, `MsfgGoldenPackTest`, `DomainPackLoaderTest`, every migration.

---

## Conventions for every backend task

- **Test commands:** backend via `./gradlew test --tests "<FQN>"` (full suite: `./gradlew test`). The build is `build.gradle.kts`. NOTE: in this environment the raw `./gradlew` invocation is hook-redirected — when *executing*, run it through the context-mode shell wrapper: `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '<FQN>'")`. This plan WRITES the literal `./gradlew test --tests "..."` command for each step.
- **Tests are JUnit 5 + Mockito, POJO.** `OutputContractServiceTest` uses `new OutputContractService()` (no mocks). `AskServiceTest` mirrors the existing file: `TestPacks.msfg()` + positional `new AskService(...)` + Mockito-mocked collaborators; the new 14th arg is a REAL `new OutputContractService()` (the service is pure — mocking it would only restate the builder and is brittle).
- **Keep test classes in the same package** as the class under test (`OutputContractServiceTest` → `com.msfg.rag.service.ai`; `AskServiceTest` → `com.msfg.rag.service`). `OutputContractService.build` and `OutputContract` are `public`, so package matching is for convention, not access.
- **No new exception path.** `OutputContractService.build` is null-safe (`build(empty)` → all-null/empty `OutputContract`) and never throws — a blank URL drops the link, a blank route nulls the page. There is no surface parsing here (surface was already handled in the planner).
- **Dashboard commands:** run from `dashboard/`: `npm run check` (tsc `--noEmit`, `noUnusedLocals` ON — every declared symbol must be used), `npm run build`, `npx vitest run`.
- **Commit** after each green task with the exact commands shown. End every commit message with the Co-Authored-By trailer.

---

## Verified anchors (do not re-research)

- **`AskService` constructor has exactly 13 positional parameters today** (AskService.java:66–78): `(DomainPack pack, QuestionClassifierService, RetrievalService, PromptBuilderService, ModelRouterService, AnswerValidationService, AuditLogService, ConversationRepository, MessageRepository, AnswerSourceRepository, ObjectMapper, IntentRouterService, RetrievalPlannerService)`. After Phase 8 it has **14** (append `OutputContractService outputContractService`).
- **`sideEvidence` is a local in `ask()`** declared at AskService.java:138 (`PlannedEvidence sideEvidence = retrievalPlannerService.collect(plan, request.question(), request.pageRoute(), request.surface());`) and logged at line 140–141. It is in the SAME method-body scope as the happy-path `new AskResponse(...)` at line 204 — directly usable, NO hoisting/threading.
- **Exactly TWO `new AskResponse(...)` sites, BOTH in `AskService`** (verified `grep -rn "new AskResponse(" src/`): AskService.java:204 (happy path) and AskService.java:236 (`refuse()`). NO test or other main file constructs `AskResponse`.
- **THREE `new AskService(...)` builder sites, all in `AskServiceTest`** (verified `grep -rn "new AskService(" src/`): AskServiceTest.java:114 (`askServiceReturning`), :144 (`askServiceClassifying`), :449 (`sideEvidenceDoesNotChangeTheCorpusAnswer`, renamed from `collectOnlySeamDiscardsNonEmptyEvidence` in Task 4). Each is positional and must gain the 14th arg.
- **`AskResponse`** (AskResponse.java) = `record AskResponse(UUID conversationId, String answer, List<CitationDto> citations, double confidence, boolean humanEscalationRequired, String disclaimer)` — six components today; nine after appending the three new ones (note `double`/`boolean` are positional 4/5; new fields go AFTER `disclaimer`).
- **`PlannedEvidence`** = `record PlannedEvidence(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links)` with accessors `pageGuides()` / `links()` and static `empty()`. Already authority-ordered: `RetrievalPlannerService.collect` returns `authorityFilterService.order(new PlannedEvidence(guides, links))` (links PRIMARY→SECONDARY→BACKGROUND; page-guide order = match order). **Do NOT re-order or re-tier in `OutputContractService` — just consume the order as given.**
- **`BrainPageGuide`** getters: `getRoute() : String` (nullable per the entity Javadoc — "editable route (nullable → topic-matched only)"), `getTitle() : String` (non-null column), `getAllowedGuidance() : List<String>` (never null — defaulted to `new ArrayList<>()`). Fixture ctor (10-arg): `new BrainPageGuide(route, title, purpose, surface, userIntents, allowedGuidance, internalLinks, sourceLinkIds, topics, createdBy)` (BrainPageGuide.java:100–115).
- **`BrainSourceLink`** getters: `getName() : String` (non-null), `getUrl() : String` (non-null column), `getAuthority() : LinkAuthority` (non-null). Fixture ctor (10-arg): `new BrainSourceLink(name, url, domain, authority, topics, freshnessRequired, allowedUse, doNotUseFor, surface, createdBy)` (BrainSourceLink.java:97–111).
- **`LinkAuthority`** = `{ PRIMARY, SECONDARY, BACKGROUND }`; `link.getAuthority().name()` → `"PRIMARY"` / `"SECONDARY"` / `"BACKGROUND"`. Surface the tier name verbatim — do NOT map through `AuthorityFilterService.tierOf` (that 5-tier enum is for ordering, not the wire field; the locked design surfaces the link's own `LinkAuthority` name).
- **`CitationDto`** style to mirror: `record CitationDto(@JsonProperty("source_name") String sourceName, @JsonProperty("document_name") String documentName, String section, @JsonProperty("page_number") String pageNumber, @JsonProperty("effective_date") String effectiveDate)` — multi-word camelCase fields get `@JsonProperty(snake_case)`; single-word fields (`section`) get NO annotation (Jackson serializes the field name as-is). All Phase 8 wire keys are single words, so only `LinkDto.name` carries an explicit (identity) `@JsonProperty("name")` to anchor the convention; the rest need none.
- **Dashboard `Pill`** (`dashboard/src/components.tsx`) accepts `tone: "green" | "amber" | "gray" | "blue" | "purple"`. The only vitest spec is `dashboard/src/api.test.ts` (api-client tests). `TestConsole.tsx` already imports `Pill` from `../components` and `AskResponse` from `../types`.

---

## Design decisions locked in this plan (state them; do not silently choose)

- **`recommendedPage` = the FIRST page guide in `evidence.pageGuides()`.** The list is already route-exact-first + topic-matched + tier-2 ordered by Phase 6/7 (`RetrievalPlannerService.collect` → `AuthorityFilterService.order`, which preserves page-guide order). So the top element is the most authoritative match. Map `guide.getRoute()` → `route`, `guide.getTitle()` → `label`. If there are no guides, OR the top guide's `getRoute()` is null/blank, `recommendedPage` is `null` (a page guide with no route is topic-matched only — there is no place to send the user, per the entity Javadoc).
- **`links` = `evidence.links()` mapped 1:1 to `LinkDto(getName(), getUrl(), getAuthority().name())`, order preserved (already authority-ordered), capped at the first 5.** Cap rationale: a website "Sources" list of more than ~5 is noise; 5 keeps the most authoritative (PRIMARY-first) links. Empty input → empty list. A link whose `getUrl()` is null/blank is DROPPED (defensive — registry URLs are non-null by column, this can only happen on a corrupted row) with a `log.warn`. The cap is applied AFTER the drop, so up to 5 *valid* links are emitted.
- **`authority` field surfaces the link's own `LinkAuthority.name()`** (e.g. `"PRIMARY"`), NOT the 5-tier `AuthorityTier`. The locked design says "surface it; do NOT re-tier here." `AuthorityFilterService` already ordered the list; `OutputContractService` only reads.
- **`nextAction` is a deterministic, server-derived string (NO LLM call), chosen by this rule, in order:**
  1. If `recommendedPage` is present AND the top guide has a non-blank first `getAllowedGuidance()` entry → use that entry trimmed (via `.strip()`) (it is the curated, surface-appropriate next step authored for the page; `.strip()` removes any accidental leading/trailing whitespace from the authored string).
  2. Else if `recommendedPage` is present (guide but no allowed-guidance) → `"See the " + guide.getTitle() + " page for detailed guidance."`.
  3. Else if `recommendedPage` is null but `links` is non-empty → `"Review the linked source(s) for authoritative detail."`.
  4. Else (no guide, no links) → `null`.
  This keeps `nextAction` grounded in curated content when available and falls back to deterministic templates otherwise. It is computed from the SAME top guide used for `recommendedPage`, so it never contradicts the recommended page.
- **Light link/grounding validation = degrade-gracefully, not escalate.** Per the backward-compat contract above: drop a blank-url `LinkDto` (WARN), null a blank-route `recommendedPage`. Never throw, never call `refuse()`, never touch `AnswerValidationService`. The spec's §7.8(b) "escalate on unresolved link" is structurally unreachable in the server-side model (links are active registry rows by construction), so the validator is a belt-and-suspenders sanity check on the data we ourselves assembled. Documented in `OutputContractService`'s Javadoc.
- **`OutputContract` is a nested public record on `OutputContractService`** (`OutputContractService.OutputContract`), not a top-level DTO — it is an internal carrier between the service and `AskService`, never serialized (only its three component values are passed into `AskResponse`). Keeping it nested avoids polluting `com.msfg.rag.dto` with a non-wire type.
- **`OutputContractService` lives in `com.msfg.rag.service.ai`** (alongside `AnswerValidationService`, the validator family it conceptually joins), is `@Service`, pure (no-arg ctor), so Spring auto-instantiates and injects it into `AskService`. No config needed.
- **`AskResponse` field order is `recommendedPage, links, nextAction`** (the locked design order), appended AFTER `disclaimer`. The two `new AskResponse(...)` sites pass them in that order.

---

## Task 1: `RecommendedPageDto` + `LinkDto` wire DTOs

**Files:**
- Create: `src/main/java/com/msfg/rag/dto/RecommendedPageDto.java`
- Create: `src/main/java/com/msfg/rag/dto/LinkDto.java`

These are tiny records with no behavior; they are exercised by `OutputContractServiceTest` (Task 2) which won't compile without them. We create them first so Task 2's test has its types.

- [ ] **Write `RecommendedPageDto.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.dto;

/**
 * The page the brain recommends the visitor visit next (spec §6.3). Derived
 * SERVER-SIDE from the top matched page guide — never emitted by the model.
 * Both keys are single words, so (mirroring {@link CitationDto#section()}) no
 * {@code @JsonProperty} is needed; Jackson serializes {@code route} / {@code label}
 * as-is.
 *
 * @param route the page guide's route (e.g. {@code "/loan-options"}); non-blank by
 *              construction (a blank route yields {@code null} recommendedPage upstream)
 * @param label the page guide's title, shown as the link text
 */
public record RecommendedPageDto(String route, String label) {
}
```

- [ ] **Write `LinkDto.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A curated external source link surfaced with the answer (spec §6.3), resolved
 * SERVER-SIDE from the active Link Registry via the Phase 6/7 planner — never
 * emitted by the model. All keys are single words; {@code @JsonProperty("name")}
 * is explicit to mirror {@link CitationDto}'s annotation style, while {@code url}
 * and {@code authority} need none (identical to {@link CitationDto#section()}).
 *
 * @param name      the link's display name
 * @param url       the link's URL (non-blank by construction)
 * @param authority the link's trust tier name (e.g. {@code "PRIMARY"}), the bare
 *                  {@link com.msfg.rag.domain.LinkAuthority} enum name
 */
public record LinkDto(
        @JsonProperty("name") String name,
        String url,
        String authority
) {
}
```

- [ ] **Compile-check (these are referenced by Task 2's test; verify they compile now):**
  `./gradlew compileJava`
  Expected: `BUILD SUCCESSFUL` (two new records compile).

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/dto/RecommendedPageDto.java \
        src/main/java/com/msfg/rag/dto/LinkDto.java
git commit -m "feat(output-contract): RecommendedPageDto + LinkDto wire records

Two optional response DTOs (spec §6.3) mirroring CitationDto's record +
@JsonProperty style. RecommendedPageDto(route,label); LinkDto(name,url,authority).
Server-derived from the planner's evidence — never model-emitted.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `OutputContractService` (build + light validator) + POJO test

**Files:**
- Create: `src/test/java/com/msfg/rag/service/ai/OutputContractServiceTest.java`
- Create: `src/main/java/com/msfg/rag/service/ai/OutputContractService.java`

TDD: write the failing test first (it references `OutputContractService` / `OutputContract` which don't exist yet), watch it fail to compile, then add the service to make it pass.

- [ ] **Write the failing test `OutputContractServiceTest.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.ai;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.LinkDto;
import com.msfg.rag.service.retrieval.PlannedEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the server-side output contract builder (spec §6.3 / §7.7).
 * No mocks — {@link OutputContractService} has no collaborators. Verifies the
 * recommendedPage / links / nextAction derivation from already-authority-ordered
 * {@link PlannedEvidence}, the 5-link cap, and the degrade-gracefully drop of a
 * blank-url link / blank-route guide.
 */
class OutputContractServiceTest {

    private final OutputContractService service = new OutputContractService();

    // --- recommendedPage + nextAction from a top guide ------------------

    @Test
    void buildsRecommendedPageFromTopGuideWithAllowedGuidanceNextAction() {
        BrainPageGuide guide = guide("/loan-options", "Loan Options",
                List.of("Compare conventional and FHA options on this page."));
        BrainSourceLink link = link("Fannie Mae", "https://fanniemae.com", LinkAuthority.PRIMARY);

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(guide), List.of(link)));

        assertEquals("/loan-options", contract.recommendedPage().route());
        assertEquals("Loan Options", contract.recommendedPage().label());
        // allowed-guidance first entry wins for nextAction.
        assertEquals("Compare conventional and FHA options on this page.", contract.nextAction());
    }

    @Test
    void nextActionFallsBackToTitleTemplateWhenNoAllowedGuidance() {
        BrainPageGuide guide = guide("/pmi", "PMI Basics", List.of());

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(guide), List.of()));

        assertEquals("/pmi", contract.recommendedPage().route());
        assertEquals("See the PMI Basics page for detailed guidance.", contract.nextAction());
    }

    // --- links mapping, order, cap --------------------------------------

    @Test
    void mapsLinksToDtoPreservingOrderAndAuthorityName() {
        // Already authority-ordered by the planner: PRIMARY then SECONDARY.
        BrainSourceLink primary = link("Fannie Mae", "https://fanniemae.com", LinkAuthority.PRIMARY);
        BrainSourceLink secondary = link("Bankrate", "https://bankrate.com", LinkAuthority.SECONDARY);

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(), List.of(primary, secondary)));

        assertEquals(
                List.of(new LinkDto("Fannie Mae", "https://fanniemae.com", "PRIMARY"),
                        new LinkDto("Bankrate", "https://bankrate.com", "SECONDARY")),
                contract.links());
        // No guide → null recommendedPage; links present → links-based nextAction.
        assertNull(contract.recommendedPage());
        assertEquals("Review the linked source(s) for authoritative detail.", contract.nextAction());
    }

    @Test
    void capsLinksAtFive() {
        List<BrainSourceLink> sevenLinks = IntStream.range(0, 7)
                .mapToObj(i -> link("Src " + i, "https://x.com/" + i, LinkAuthority.PRIMARY))
                .toList();

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(), sevenLinks));

        assertEquals(5, contract.links().size());
        // The first five (most authoritative, order preserved) survive.
        assertEquals("Src 0", contract.links().get(0).name());
        assertEquals("Src 4", contract.links().get(4).name());
    }

    // --- empty + null-ish edge cases ------------------------------------

    @Test
    void emptyEvidenceYieldsNullEmptyNull() {
        OutputContractService.OutputContract contract = service.build(PlannedEvidence.empty());

        assertNull(contract.recommendedPage());
        assertTrue(contract.links().isEmpty());
        assertNull(contract.nextAction());
    }

    @Test
    void guideWithBlankRouteYieldsNullRecommendedPage() {
        // Topic-matched-only guide (no route) → nowhere to send the user.
        BrainPageGuide noRoute = guide("   ", "Topic Only", List.of("ignored"));

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(noRoute), List.of()));

        assertNull(contract.recommendedPage());
        // No usable page and no links → nextAction null (the guide's allowed-guidance
        // is not surfaced when there is no recommendedPage to anchor it).
        assertNull(contract.nextAction());
    }

    @Test
    void dropsLinkWithBlankUrl() {
        BrainSourceLink good = link("Good", "https://good.com", LinkAuthority.PRIMARY);
        BrainSourceLink blank = link("Blank", "   ", LinkAuthority.SECONDARY);

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(), List.of(good, blank)));

        assertEquals(1, contract.links().size());
        assertEquals("Good", contract.links().get(0).name());
    }

    @Test
    void nextActionStripsLeadingTrailingWhitespaceFromAllowedGuidanceEntry() {
        // firstNonBlank() calls .strip(), so a padded authored string is trimmed.
        BrainPageGuide guide = guide("/fha", "FHA Worksheet",
                List.of("  Use the FHA worksheet.  "));

        OutputContractService.OutputContract contract = service.build(
                new PlannedEvidence(List.of(guide), List.of()));

        assertEquals("Use the FHA worksheet.", contract.nextAction());
    }

    // --- fixtures (reuse the public ctors used in Phase 6/7 tests) ------

    private static BrainPageGuide guide(String route, String title, List<String> allowedGuidance) {
        return new BrainPageGuide(
                route, title, "purpose", Surface.BOTH,
                List.of(), allowedGuidance, List.of(), List.of(), List.of("topic"), "seed");
    }

    private static BrainSourceLink link(String name, String url, LinkAuthority authority) {
        return new BrainSourceLink(
                name, url, "x.com", authority,
                List.of("topic"), false, List.of(), List.of(), Surface.BOTH, "seed");
    }
}
```

- [ ] **Run the test — see it FAIL (does not compile: `OutputContractService` / `OutputContract` do not exist yet):**
  `./gradlew test --tests "com.msfg.rag.service.ai.OutputContractServiceTest"`
  Expected: compilation failure referencing `OutputContractService` cannot be resolved.

- [ ] **Write `OutputContractService.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.ai;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.dto.LinkDto;
import com.msfg.rag.dto.RecommendedPageDto;
import com.msfg.rag.service.retrieval.PlannedEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the SERVER-SIDE output contract (spec §6.3 / §7.7): the
 * {@code recommendedPage}, {@code links}, and {@code nextAction} that ride
 * alongside the corpus-grounded answer. All three are derived purely from the
 * Phase 6/7 {@link PlannedEvidence} (already authority-ordered by
 * {@link com.msfg.rag.service.retrieval.AuthorityFilterService#order}) — the LLM
 * never emits them and the boot-locked prompt is untouched.
 *
 * <p><b>Light link/grounding validation (degrade-gracefully):</b> spec §7.8(b)
 * says an emitted link that does not resolve to an active registry row must
 * escalate. In this server-side model the links ARE active registry rows by
 * construction (they come from {@code SourceLinkService.activeLinks() → match()}),
 * so that escalation branch is structurally unreachable. This builder therefore
 * runs a belt-and-suspenders sanity check on the data it itself assembled: a link
 * with a blank URL is DROPPED (logged at WARN) and a guide with a blank route
 * yields a {@code null recommendedPage}. It NEVER throws and NEVER escalates — a
 * single corrupted row can never break or refuse a response.
 *
 * <p>Pure {@code @Service}: no injected collaborators, no LLM call, no I/O. The
 * returned {@link OutputContract} is an internal carrier (its three values are
 * passed into {@code AskResponse}); it is never serialized directly.
 */
@Service
public class OutputContractService {

    private static final Logger log = LoggerFactory.getLogger(OutputContractService.class);

    /** Maximum number of source links surfaced with an answer. */
    static final int MAX_LINKS = 5;

    /**
     * Internal carrier for the three output-contract values. Not a wire type —
     * {@code AskService} explodes it into the {@code AskResponse} components.
     */
    public record OutputContract(RecommendedPageDto recommendedPage,
                                 List<LinkDto> links,
                                 String nextAction) {
    }

    /**
     * Builds the output contract from already-authority-ordered side-evidence.
     * Null-safe and total: {@code build(PlannedEvidence.empty())} returns
     * {@code (null, [], null)}.
     */
    public OutputContract build(PlannedEvidence evidence) {
        RecommendedPageDto recommendedPage = topRecommendedPage(evidence.pageGuides());
        List<LinkDto> links = toLinkDtos(evidence.links());
        String nextAction = nextAction(recommendedPage, topGuide(evidence.pageGuides()), links);
        return new OutputContract(recommendedPage, links, nextAction);
    }

    /** The first page guide, or null when there are none. */
    private BrainPageGuide topGuide(List<BrainPageGuide> guides) {
        return guides.isEmpty() ? null : guides.get(0);
    }

    /**
     * The top guide as a {@link RecommendedPageDto}, or null when there is no
     * guide or the top guide has no usable (non-blank) route.
     */
    private RecommendedPageDto topRecommendedPage(List<BrainPageGuide> guides) {
        BrainPageGuide guide = topGuide(guides);
        if (guide == null) {
            return null;
        }
        String route = guide.getRoute();
        if (route == null || route.isBlank()) {
            return null;
        }
        return new RecommendedPageDto(route, guide.getTitle());
    }

    /**
     * Maps the (already authority-ordered) links to DTOs, dropping any with a
     * blank URL, then caps at {@link #MAX_LINKS}.
     */
    private List<LinkDto> toLinkDtos(List<BrainSourceLink> links) {
        List<LinkDto> out = new ArrayList<>();
        for (BrainSourceLink link : links) {
            String url = link.getUrl();
            if (url == null || url.isBlank()) {
                log.warn("Dropping output-contract link with blank URL: name={}", link.getName());
                continue;
            }
            out.add(new LinkDto(link.getName(), url, link.getAuthority().name()));
            if (out.size() == MAX_LINKS) {
                break;
            }
        }
        return out;
    }

    /**
     * Deterministic next-action string (NO LLM call). In order:
     * <ol>
     *   <li>recommendedPage present + guide has a non-blank first allowed-guidance
     *       entry → that entry trimmed (via {@code .strip()});</li>
     *   <li>recommendedPage present (no allowed-guidance) → "See the &lt;title&gt;
     *       page for detailed guidance.";</li>
     *   <li>recommendedPage null but links present → "Review the linked source(s)
     *       for authoritative detail.";</li>
     *   <li>otherwise → null.</li>
     * </ol>
     */
    private String nextAction(RecommendedPageDto recommendedPage, BrainPageGuide guide,
                              List<LinkDto> links) {
        if (recommendedPage != null && guide != null) {
            String guidance = firstNonBlank(guide.getAllowedGuidance());
            if (guidance != null) {
                return guidance;
            }
            return "See the " + guide.getTitle() + " page for detailed guidance.";
        }
        if (!links.isEmpty()) {
            return "Review the linked source(s) for authoritative detail.";
        }
        return null;
    }

    /** First non-blank entry of a (possibly null/empty) list, stripped of leading/trailing whitespace, or null. */
    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }
}
```

- [ ] **Run the test — see it PASS:**
  `./gradlew test --tests "com.msfg.rag.service.ai.OutputContractServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all `OutputContractServiceTest` cases green.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/ai/OutputContractService.java \
        src/test/java/com/msfg/rag/service/ai/OutputContractServiceTest.java
git commit -m "feat(output-contract): pure OutputContractService (build + light validator)

build(PlannedEvidence) derives recommendedPage (top route-bearing guide),
links (authority-ordered, capped at 5, blank-url dropped), and a deterministic
nextAction (allowed-guidance → title → links → null). Degrade-gracefully: never
throws, never escalates — a bad row can't break a response. No LLM call.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Extend `AskResponse` with the three trailing optional fields

**Files:**
- Modify: `src/main/java/com/msfg/rag/dto/AskResponse.java`

This breaks the two `new AskResponse(...)` sites in `AskService` (they now pass 6 args to a 9-arg record). We fix those in Task 4 — so this task ends with a deliberate compile failure, which is the TDD "see it fail" for the wiring. (No test constructs `AskResponse`, so only `AskService` is affected.)

- [ ] **Edit `AskResponse.java`** — replace the whole record body:

  Replace:
```java
package com.msfg.rag.dto;

import java.util.List;
import java.util.UUID;

/**
 * Public website answer response (rag.md format).
 */
public record AskResponse(
        UUID conversationId,
        String answer,
        List<CitationDto> citations,
        double confidence,
        boolean humanEscalationRequired,
        String disclaimer
) {
}
```
  With:
```java
package com.msfg.rag.dto;

import java.util.List;
import java.util.UUID;

/**
 * Public website answer response (rag.md format).
 *
 * <p>Phase 8 (spec §6.3) appends three OPTIONAL, SERVER-DERIVED fields:
 * {@code recommendedPage} (the top matched page guide as {@code {route,label}}),
 * {@code links} (active Link Registry rows resolved + authority-ordered by the
 * planner), and {@code nextAction} (a deterministic next-step string). They are
 * populated only on the success path; the refusal path sets them to
 * {@code null / [] / null}. The corpus-grounded {@code answer} and
 * {@code citations} are unchanged.
 */
public record AskResponse(
        UUID conversationId,
        String answer,
        List<CitationDto> citations,
        double confidence,
        boolean humanEscalationRequired,
        String disclaimer,
        RecommendedPageDto recommendedPage,
        List<LinkDto> links,
        String nextAction
) {
}
```

- [ ] **Run a compile — see it FAIL (the two `new AskResponse(...)` sites in `AskService` now pass too few args):**
  `./gradlew compileJava`
  Expected: compilation failure in `AskService.java` at the two `new AskResponse(...)` calls — "constructor AskResponse … cannot be applied to given types; required 9, found 6" (or similar arity mismatch). This is expected; Task 4 fixes both sites.

- [ ] **Do NOT commit yet** — the tree does not compile. Task 4 fixes the sites and then commits the record + wiring together. (If you must checkpoint, do it after Task 4.)

---

## Task 4: Wire `OutputContractService` into `AskService` (ctor 13→14, both AskResponse sites) + update the three builders + pipeline tests

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/AskService.java`
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java`

TDD: update the test builders + add the three new pipeline tests first (the new assertions reference the new `AskResponse` accessors `recommendedPage()`/`links()`/`nextAction()`, which exist from Task 3; the builders won't compile until `AskService` takes the 14th arg). Then wire `AskService` and watch everything go green.

### 4a — `AskServiceTest`: import, three builder updates, three new tests

- [ ] **Add the import** (with the other `com.msfg.rag.service.ai.*` imports — alongside `AnswerValidationService`/`IntentRouterService` at AskServiceTest.java:12–13):

```java
import com.msfg.rag.service.ai.OutputContractService;
```

- [ ] **Builder 1 (`askServiceReturning`, line 114–118).** Replace:
```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()));
```
  With:
```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()),
                new OutputContractService());
```

- [ ] **Builder 2 (`askServiceClassifying`, line 144–150).** Replace:
```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(
                        mock(PageGuideService.class), mock(SourceLinkService.class),
                        new AuthorityFilterService()));
```
  With:
```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(
                        mock(PageGuideService.class), mock(SourceLinkService.class),
                        new AuthorityFilterService()),
                new OutputContractService());
```

- [ ] **Builder 3 + rename (`sideEvidenceDoesNotChangeTheCorpusAnswer`, previously `collectOnlySeamDiscardsNonEmptyEvidence`, line 449–456).** As of Phase 8 the collected side-evidence is emitted into `recommendedPage`/`links`/`nextAction`, so the old name and comment ("discarded"/"inert") are false. Rename the test method and rewrite its leading comment; keep its assertions (answer/citations/humanEscalationRequired/confidence parity) exactly as-is. Then add the 14th `new AskService(...)` arg.

  Replace (the `@Test` declaration + `new AskService(...)` block inside the renamed test):
```java
        AskService service = new AskService(
                TestPacks.msfg(),
                localClassifier,
                localRetrieval, localPrompt, localRouter,
                new AnswerValidationService(TestPacks.msfg()),
                localAudit, localConversations, localMessages, localSources,
                new ObjectMapper(), new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()));
```
  With:
```java
        AskService service = new AskService(
                TestPacks.msfg(),
                localClassifier,
                localRetrieval, localPrompt, localRouter,
                new AnswerValidationService(TestPacks.msfg()),
                localAudit, localConversations, localMessages, localSources,
                new ObjectMapper(), new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()),
                new OutputContractService());
```

  Additionally rename the test method and update its leading comment in the same edit pass:

  - Old method name: `collectOnlySeamDiscardsNonEmptyEvidence`
  - New method name: `sideEvidenceDoesNotChangeTheCorpusAnswer`
  - Old comment (remove/replace): any comment asserting the side-evidence is "discarded" or "inert"
  - New comment to add above or inside the test:
    ```java
    // As of Phase 8 the collected side-evidence IS emitted (into recommendedPage/links/
    // nextAction); this test now asserts only that the LLM answer + citations are
    // unaffected by the presence of side-evidence. Assertions are unchanged.
    ```

- [ ] **Add the three new pipeline tests** at the end of the class (before the final closing `}` at AskServiceTest.java:480). They reuse the existing `chunk(...)` helper, `pmiQuestion()`, `pmiQuestionWith(...)`, and the `groundedJson` shape. The first test builds its own service with non-empty matcher mocks (mirroring `sideEvidenceDoesNotChangeTheCorpusAnswer`); the second and third reuse the shared `askServiceReturning` / `askServiceClassifying` builders. Append the imports `com.msfg.rag.dto.LinkDto` and `com.msfg.rag.dto.RecommendedPageDto` are NOT needed (the tests use the accessor return values, not the DTO types directly — they assert on `.route()`, `.name()`, etc.). They DO need `BrainPageGuide`, `BrainSourceLink`, `LinkAuthority`, `Surface` (all already imported at lines 20–23) and the matcher/builder mocks (already imported).

```java
    @Test
    void happyPathPopulatesRecommendedPageLinksAndNextAction() {
        // A grounded answer whose side-evidence has a matched page guide + link.
        // The Output Contract must surface recommendedPage/links/nextAction from
        // the (authority-ordered) PlannedEvidence — without changing the answer.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)));

        PageGuideService pageGuides = mock(PageGuideService.class);
        BrainPageGuide matchedGuide = new BrainPageGuide(
                "/loan-options", "Loan Options", "Overview of loan options", Surface.PUBLIC,
                List.of(), List.of("Compare your loan options on this page."),
                List.of(), List.of(), List.of("pmi"), "seed");
        when(pageGuides.match(any(), anyString(), any())).thenReturn(List.of(matchedGuide));

        SourceLinkService sourceLinks = mock(SourceLinkService.class);
        BrainSourceLink matchedLink = new BrainSourceLink(
                "Fannie Mae", "https://fanniemae.com", "fanniemae.com", LinkAuthority.PRIMARY,
                List.of("pmi"), false, List.of(), List.of(), Surface.PUBLIC, "seed");
        when(sourceLinks.match(anyString(), any())).thenReturn(List.of(matchedLink));

        RetrievalService localRetrieval = mock(RetrievalService.class);
        when(localRetrieval.retrieve(anyString())).thenReturn(new RetrievalResult(chunks, 1.0, true));
        PromptBuilderService localPrompt = mock(PromptBuilderService.class);
        when(localPrompt.build(anyString(), anyList())).thenReturn("prompt");
        when(localPrompt.disclaimer()).thenReturn("pack-disclaimer");
        ModelRouterService localRouter = mock(ModelRouterService.class);
        AiResponse localAiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(localRouter.generate(any()))
                .thenReturn(new ModelRouterService.RoutedResponse(localAiResponse, false));
        AuditLogService localAudit = mock(AuditLogService.class);
        ConversationRepository localConversations = mock(ConversationRepository.class);
        when(localConversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository localMessages = mock(MessageRepository.class);
        when(localMessages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository localSources = mock(AnswerSourceRepository.class);
        when(localSources.save(any())).thenAnswer(inv -> inv.getArgument(0));
        QuestionClassifierService localClassifier = mock(QuestionClassifierService.class);
        when(localClassifier.classify(anyString())).thenReturn(QuestionCategory.EDUCATIONAL);

        AskService service = new AskService(
                TestPacks.msfg(), localClassifier, localRetrieval, localPrompt, localRouter,
                new AnswerValidationService(TestPacks.msfg()), localAudit,
                localConversations, localMessages, localSources, new ObjectMapper(),
                new IntentRouterService(),
                new RetrievalPlannerService(pageGuides, sourceLinks, new AuthorityFilterService()),
                new OutputContractService());

        AskResponse response = service.ask(pmiQuestionWith("/loan-options", "PUBLIC"));

        // Answer is unchanged (corpus-grounded).
        assertEquals("PMI is private mortgage insurance that may be required on conventional loans.",
                response.answer());
        assertFalse(response.humanEscalationRequired());
        // recommendedPage from the top guide.
        assertEquals("/loan-options", response.recommendedPage().route());
        assertEquals("Loan Options", response.recommendedPage().label());
        // links from the (single) matched registry link.
        assertEquals(1, response.links().size());
        assertEquals("Fannie Mae", response.links().get(0).name());
        assertEquals("https://fanniemae.com", response.links().get(0).url());
        assertEquals("PRIMARY", response.links().get(0).authority());
        // nextAction = the guide's first allowed-guidance entry.
        assertEquals("Compare your loan options on this page.", response.nextAction());
    }

    @Test
    void emptyEvidenceHappyPathLeavesContractEmptyAndAnswerIdentical() {
        // The shared askServiceReturning builder stubs both matchers to List.of(),
        // so collect() returns empty PlannedEvidence → the Output Contract is
        // (null, [], null) and the answer/citations are byte-identical to baseline.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));

        AskResponse response = askServiceReturning(groundedJson, chunks).ask(pmiQuestion());

        // Backward-compat: the answer + citations are exactly as before Phase 8.
        assertEquals("PMI is private mortgage insurance that may be required on conventional loans.",
                response.answer());
        assertEquals(2, response.citations().size());
        assertFalse(response.humanEscalationRequired());
        // The Output Contract is empty when there is no side-evidence.
        assertNull(response.recommendedPage());
        assertTrue(response.links().isEmpty());
        assertNull(response.nextAction());
    }

    @Test
    void refusePathLeavesContractNullAndEmpty() {
        // A category refusal (LEGAL) routes through refuse() — the Output Contract
        // fields must be null/empty/null so a refusal never leaks a page or links.
        AskResponse response = askServiceClassifying(QuestionCategory.LEGAL).ask(pmiQuestion());

        assertTrue(response.humanEscalationRequired());
        assertNull(response.recommendedPage());
        assertTrue(response.links().isEmpty());
        assertNull(response.nextAction());
    }
```

  (`assertNull` / `assertTrue` / `assertFalse` / `assertEquals` are already imported at AskServiceTest.java:39–43; `AiResponse`, `RetrievalResult`, `RetrievedChunk`, the repositories, `PageGuideService`, `SourceLinkService`, `AuthorityFilterService`, `RetrievalPlannerService`, `IntentRouterService`, `AnswerValidationService`, `ModelRouterService`, `PromptBuilderService`, `QuestionClassifierService`, `QuestionCategory`, `TestPacks`, `BrainPageGuide`, `BrainSourceLink`, `LinkAuthority`, `Surface`, `ObjectMapper`, `mock`/`when`/`any`/`anyString`/`anyList` are all already imported — only `OutputContractService` is newly added above.)

- [ ] **Run `AskServiceTest` — see it FAIL to compile (the three builders now pass 14 args to the 13-arg `AskService` ctor):**
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: compilation failure — "constructor AskService … cannot be applied to given types; required 13, found 14". This is expected; 4b adds the 14th param.

### 4b — `AskService`: ctor 13→14 + both AskResponse sites

- [ ] **Add the import** (with the other `com.msfg.rag.service.ai.*` imports — after `import com.msfg.rag.service.ai.ModelRouterService;` at AskService.java:19, keeping alpha order is fine; place it among the `service.ai` block):

```java
import com.msfg.rag.service.ai.OutputContractService;
```

- [ ] **Add the field** (after the `retrievalPlannerService` field at AskService.java:64):

  Replace:
```java
    private final IntentRouterService intentRouterService;
    private final RetrievalPlannerService retrievalPlannerService;
```
  With:
```java
    private final IntentRouterService intentRouterService;
    private final RetrievalPlannerService retrievalPlannerService;
    private final OutputContractService outputContractService;
```

- [ ] **Add the ctor parameter (13 → 14) + assignment.**

  Replace the constructor signature tail (AskService.java:77–78):
```java
                      IntentRouterService intentRouterService,
                      RetrievalPlannerService retrievalPlannerService) {
```
  With:
```java
                      IntentRouterService intentRouterService,
                      RetrievalPlannerService retrievalPlannerService,
                      OutputContractService outputContractService) {
```

  Replace the assignment tail (AskService.java:90–91):
```java
        this.intentRouterService = intentRouterService;
        this.retrievalPlannerService = retrievalPlannerService;
    }
```
  With:
```java
        this.intentRouterService = intentRouterService;
        this.retrievalPlannerService = retrievalPlannerService;
        this.outputContractService = outputContractService;
    }
```

- [ ] **Wire the happy-path `AskResponse` (line 204).** `sideEvidence` is already in scope (declared at line 138). Build the contract just before the return and pass its three values.

  Replace:
```java
        return new AskResponse(conversation.getId(), modelAnswer.answer(), citations,
                confidence, escalate, promptBuilderService.disclaimer());
```
  With:
```java
        // Phase 8: build the SERVER-SIDE output contract from the authority-ordered
        // side-evidence collected above (line 138). recommendedPage/links/nextAction
        // ride alongside the unchanged corpus-grounded answer.
        OutputContractService.OutputContract contract = outputContractService.build(sideEvidence);
        return new AskResponse(conversation.getId(), modelAnswer.answer(), citations,
                confidence, escalate, promptBuilderService.disclaimer(),
                contract.recommendedPage(), contract.links(), contract.nextAction());
```

- [ ] **Wire the refuse `AskResponse` (line 236).** A refusal carries no page/links/action.

  Replace:
```java
        return new AskResponse(conversation.getId(), answerText, List.of(),
                retrieval.confidence(), true, promptBuilderService.disclaimer());
```
  With:
```java
        return new AskResponse(conversation.getId(), answerText, List.of(),
                retrieval.confidence(), true, promptBuilderService.disclaimer(),
                null, List.of(), null);
```

- [ ] **Run `AskServiceTest` — see it PASS:**
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: `BUILD SUCCESSFUL`. The three new tests pass; every pre-existing test (refusal salvage, citation backfill, pageRoute parity, collect-only parity) still passes — the answer/citations are unchanged, and the new fields are populated only from side-evidence.

- [ ] **Commit (record + wiring + tests together — the tree compiles again as of this task):**
```
git add src/main/java/com/msfg/rag/dto/AskResponse.java \
        src/main/java/com/msfg/rag/service/AskService.java \
        src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "feat(output-contract): emit recommendedPage/links/nextAction server-side

AskResponse gains three trailing optional fields (spec §6.3). AskService ctor
13→14 (injects OutputContractService); the happy path builds the contract from
the in-scope authority-ordered sideEvidence and passes its three values; refuse()
passes null/[]/null. The corpus-grounded answer + citations are byte-identical.
Three AskServiceTest builders gain the 14th arg; new tests cover populated /
empty-evidence / refuse paths.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Dashboard — types + TestConsole display

**Files:**
- Modify: `dashboard/src/types.ts`
- Modify: `dashboard/src/screens/TestConsole.tsx`

Additive + backward-compatible: the new fields are optional and rendered only when present. No vitest case is added — the change is type-level (matches the existing `api.test.ts` convention, which tests the api client, not screen render). Verification is `npm run check` + `npm run build` + `npx vitest run`.

- [ ] **Edit `dashboard/src/types.ts`** — add the two interfaces and extend `AskResponse`.

  Replace:
```typescript
export interface AskResponse {
  conversationId: string; answer: string; citations: Citation[];
  confidence: number; humanEscalationRequired: boolean; disclaimer: string;
}
```
  With:
```typescript
export interface RecommendedPage { route: string; label: string }
export interface Link { name: string; url: string; authority: string }
export interface AskResponse {
  conversationId: string; answer: string; citations: Citation[];
  confidence: number; humanEscalationRequired: boolean; disclaimer: string;
  recommendedPage?: RecommendedPage | null;
  links?: Link[] | null;
  nextAction?: string | null;
}
```

- [ ] **Edit `dashboard/src/screens/TestConsole.tsx`** — render the three new fields below the disclaimer, inside the existing `{answer && ( ... )}` card.

  Replace:
```tsx
          <p className="answer">{answer.answer}</p>
          {answer.citations.length > 0 && (
            <ul className="citations">
              {answer.citations.map((c, i) => (
                <li key={i}>{[c.source_name, c.section, c.page_number ? `p. ${c.page_number}` : null]
                  .filter(Boolean).join(" — ")}</li>
              ))}
            </ul>
          )}
          <p className="muted">{answer.disclaimer}</p>
```
  With:
```tsx
          <p className="answer">{answer.answer}</p>
          {answer.citations.length > 0 && (
            <ul className="citations">
              {answer.citations.map((c, i) => (
                <li key={i}>{[c.source_name, c.section, c.page_number ? `p. ${c.page_number}` : null]
                  .filter(Boolean).join(" — ")}</li>
              ))}
            </ul>
          )}
          {answer.recommendedPage && (
            <p className="recommended-page">
              Recommended page: <strong>{answer.recommendedPage.label}</strong>{" "}
              <span className="muted">{answer.recommendedPage.route}</span>
            </p>
          )}
          {answer.links && answer.links.length > 0 && (
            <ul className="sources">
              {answer.links.map((l, i) => (
                <li key={i}>
                  <a href={l.url} target="_blank" rel="noreferrer">{l.name}</a>{" "}
                  <Pill tone="blue">{l.authority}</Pill>
                </li>
              ))}
            </ul>
          )}
          {answer.nextAction && (
            <p className="next-action">Next action: {answer.nextAction}</p>
          )}
          <p className="muted">{answer.disclaimer}</p>
```

  (`Pill` is already imported from `../components` at TestConsole.tsx:4. The `RecommendedPage`/`Link` interfaces are consumed transitively via `AskResponse` — they need not be imported by name in `TestConsole.tsx`, so `noUnusedLocals` is satisfied. No new CSS class is required for the change to typecheck/build; the `recommended-page` / `sources` / `next-action` class names are styling hooks that fall back to default block layout if unstyled.)

- [ ] **Run the dashboard checks — all green (run from `dashboard/`):**
  `npm run check`
  Expected: tsc `--noEmit` passes (no unused locals, no type errors — `recommendedPage?`/`links?`/`nextAction?` are optional and guarded).
  `npm run build`
  Expected: Vite build succeeds.
  `npx vitest run`
  Expected: the existing `api.test.ts` suite passes (5 tests). No new spec added.

- [ ] **Commit:**
```
git add dashboard/src/types.ts dashboard/src/screens/TestConsole.tsx
git commit -m "feat(dashboard): show recommendedPage/links/nextAction in Test console

types.ts gains RecommendedPage + Link interfaces and three optional AskResponse
fields. TestConsole renders a Recommended page line, a Sources list (name → url
with an authority Pill), and a Next action line — only when present, below the
existing answer/citations. Additive + backward-compatible.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Full-suite no-regression + golden + dashboard verification

**Files:** none modified (verification only).

Prove the whole backend suite is green (golden unchanged because the prompt is untouched), the no-touch set is untouched, and the dashboard is green.

- [ ] **Run the full backend suite:**
  `./gradlew test`
  Expected: `BUILD SUCCESSFUL`. In particular `MsfgGoldenPackTest` and `DomainPackLoaderTest` are GREEN UNTOUCHED (the boot-locked prompt is unchanged), plus `OutputContractServiceTest`, `AskServiceTest`, `RetrievalPlannerServiceTest`, `AuthorityFilterServiceTest`, `PageGuideServiceTest`, `SourceLinkServiceTest`, `RetrievalServiceTest`, `RerankerServiceTest`, `VocabularyServiceTest`.

- [ ] **Verify the boot-lock / prompt / model no-touch set is untouched:**
  `git status --porcelain packs/ src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java src/main/java/com/msfg/rag/service/ai/ModelAnswer.java src/main/java/com/msfg/rag/service/ai/AnswerValidationService.java src/main/java/com/msfg/rag/dto/CitationDto.java src/main/java/com/msfg/rag/dto/AskRequest.java src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java src/main/java/com/msfg/rag/service/retrieval/RerankerService.java src/main/java/com/msfg/rag/service/retrieval/PlannedEvidence.java src/main/java/com/msfg/rag/service/retrieval/RetrievalPlannerService.java src/main/java/com/msfg/rag/service/retrieval/AuthorityFilterService.java src/main/java/com/msfg/rag/pack/`
  Expected: empty output.

- [ ] **Verify NO migration was added (no DB change):**
  `git status --porcelain src/main/resources/db/migration/`
  Expected: empty output.

- [ ] **Verify `ModelAnswer`/`ensureCitations`/`prompt.yaml`/`PromptBuilderService`/`MsfgGoldenPackTest` are untouched:**
  ```
  BASE=$(git merge-base HEAD origin/main)
  git diff --stat $BASE -- src/main/java/com/msfg/rag/service/ai/ModelAnswer.java
  git diff --stat $BASE -- packs/msfg-mortgage/prompt.yaml
  git diff --stat $BASE -- src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java
  git diff --stat $BASE -- src/test/java/com/msfg/rag/MsfgGoldenPackTest.java
  ```
  Expected: all four produce empty output (none of these files were touched in Phase 8). Using `$(git merge-base HEAD origin/main)` rather than `HEAD~N` makes the check robust to any commit count produced by Phase 8.

- [ ] **Run the dashboard verification suite (from `dashboard/`):**
  `npm run check && npm run build && npx vitest run`
  Expected: all green.

- [ ] **Final commit (only if any tracked verification artifact changed — normally nothing to commit here).** If `git status` is clean, skip. Otherwise:
```
git add -A
git commit -m "test(output-contract): Phase 8 full-suite + golden + dashboard no-regression

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (writing-plans)

**Spec coverage (every LOCKED DESIGN DECISION + spec §6.3 / §7.7 requirement mapped to a task):**
- §6.3 `AskResponse` gains `recommendedPage {route,label}`, `links[]` (resolved from registry), `nextAction` (string), all optional → `RecommendedPageDto`/`LinkDto` (**Task 1**), `AskResponse` +3 trailing fields (**Task 3**), populated server-side (**Task 4**).
- §7.7 Output Contract "answer_must_include … a citation OR page link when evidence exists; next best action" → `recommendedPage` (page link) + `nextAction` (next best action) built in `OutputContractService` (**Task 2**); citations are already present from the unchanged pipeline. "answer_must_not … cite unresolved links/pages" → the light validator drops blank-url links / blank-route pages (**Task 2**). We explicitly do NOT formalize the contract in `prompt.yaml` (locked design: server-side only, prompt untouched) — documented in the Design Decisions + the backward-compat contract.
- §7.8(b) "every emitted link resolves to an active registry row … else escalate" → addressed as the degrade-gracefully drop (documented as structurally unreachable in the server-side model) in `OutputContractService` Javadoc + Design Decisions + the `dropsLinkWithBlankUrl` test (**Task 2**).
- `recommendedPage` = top `pageGuides()` element, null if no guides or blank route → **Task 2** (`buildsRecommendedPageFromTopGuide…`, `guideWithBlankRouteYieldsNullRecommendedPage`).
- `links` = `evidence.links()` mapped to `LinkDto(name,url,authority.name())`, order preserved, capped at 5 → **Task 2** (`mapsLinksToDtoPreservingOrderAndAuthorityName`, `capsLinksAtFive`).
- `nextAction` deterministic rule (allowed-guidance → title template → links template → null) → **Task 2** (`buildsRecommendedPageFromTopGuideWithAllowedGuidanceNextAction`, `nextActionFallsBackToTitleTemplateWhenNoAllowedGuidance`, `mapsLinksToDtoPreservingOrderAndAuthorityName` covers links-only, `guideWithBlankRouteYieldsNullRecommendedPage` + `emptyEvidenceYieldsNullEmptyNull` cover null).
- AskService ctor 13→14, both `new AskResponse(...)` sites, refuse() null/empty, `sideEvidence` in scope at line 204 → **Task 4** (wiring + the three pipeline tests: populated / empty-evidence backward-compat / refuse).
- Golden unchanged + full suite green → **Task 6**.
- Dashboard types + TestConsole display → **Task 5**.

**Placeholder scan:** No `TODO`, `FIXME`, `...`, `<placeholder>`, or "implement here" in any code block. Every file is complete and copy-paste-ready. Every Edit shows the exact before/after text. Task 6's no-touch verification uses `$(git merge-base HEAD origin/main)` rather than `HEAD~N`, so it is robust to any commit count Phase 8 produces.

**Type / signature consistency vs the live files:**
- **`AskService` ctor arity verified = 13 today** (AskService.java:66–78) → **14** after Task 4 (append `OutputContractService outputContractService`). Field + param + assignment all added consistently.
- **EVERY `new AskResponse(` site enumerated and updated:** exactly TWO, both in `AskService` (line 204 happy → 9 args incl. the three contract values; line 236 refuse → 9 args incl. `null, List.of(), null`). Verified `grep -rn "new AskResponse(" src/` = those two only; no test constructs `AskResponse`. `AskResponse` record arity 6 → 9; both sites pass 9. **Task 3** changes the record (deliberate compile failure), **Task 4** fixes both sites in the same commit.
- **EVERY `new AskService(` site enumerated and updated:** exactly THREE, all in `AskServiceTest` (lines 114, 144, 449). Verified `grep -rn "new AskService(" src/`. All three gain the 14th arg `new OutputContractService()` in **Task 4a**; the third (`sideEvidenceDoesNotChangeTheCorpusAnswer`, renamed from `collectOnlySeamDiscardsNonEmptyEvidence`) also gets its name + leading comment updated. (The prompt anticipated 2 builders; there are 3 — all three are handled.)
- **`sideEvidence` scope:** declared at AskService.java:138 inside `ask()`, same method body as the line-204 return → directly usable, NO hoisting. Confirmed by reading the live `ask()` body. The contract is built immediately before the return.
- **`PlannedEvidence` accessors** `pageGuides()` / `links()` and `empty()` used as verified. The evidence is already authority-ordered by `RetrievalPlannerService.collect → AuthorityFilterService.order`; `OutputContractService` consumes the order, does NOT re-sort.
- **`BrainPageGuide.getRoute()`/`getTitle()`/`getAllowedGuidance()`** and **`BrainSourceLink.getName()`/`getUrl()`/`getAuthority()`** used exactly as verified; `getAuthority().name()` yields the `LinkAuthority` enum string (`"PRIMARY"` etc.). Fixtures use the verified 10-arg ctors (identical to `AskServiceTest:417–419`/`423–425` and `AuthorityFilterServiceTest`/`RetrievalPlannerServiceTest`).
- **`OutputContract` record** = `OutputContractService.OutputContract(RecommendedPageDto recommendedPage, List<LinkDto> links, String nextAction)`; accessors `recommendedPage()`/`links()`/`nextAction()` used consistently in `AskService` and the test. `MAX_LINKS = 5` matches the `capsLinksAtFive` assertion (`assertEquals(5, …)` and `Src 4` as index 4).
- **`LinkDto`** = `record(@JsonProperty("name") String name, String url, String authority)` — `name()`/`url()`/`authority()` accessors used in tests; record equality used in `mapsLinksToDtoPreservingOrderAndAuthorityName` (`new LinkDto(...)` compared via `assertEquals`, which works because records have value equality). **`RecommendedPageDto`** = `record(String route, String label)` — `route()`/`label()` used. JSON keys mirror `CitationDto` (single-word fields → no annotation except the anchoring `@JsonProperty("name")`).
- **Dashboard:** `Pill` `tone="blue"` is in the verified union (`"green" | "amber" | "gray" | "blue" | "purple"`). `RecommendedPage`/`Link` interfaces are used by `AskResponse` (so `noUnusedLocals` passes — they are referenced). `TestConsole.tsx` already imports `Pill` and `AskResponse`; no new import needed. Optional fields are guarded (`answer.recommendedPage &&`, `answer.links && answer.links.length > 0`, `answer.nextAction &&`), so the website's existing (Phase ≤7) responses without these keys render exactly as before.

**Boot-lock / golden / answer-behavior safety:** `packs/msfg-mortgage/prompt.yaml`, the 5-`%s` template, `PromptBuilderService.build`, `DomainPackLoader.validate`, `DomainPack`, `ModelAnswer`, `ensureCitations`, `AnswerValidationService`, `RetrievalService`, `RerankerService`, `RetrievalResult`, `RetrievedChunk`, `PlannedEvidence`, `RetrievalPlannerService`, `AuthorityFilterService`, and `MsfgGoldenPackTest` are untouched (Task 6 asserts the no-touch set via `git diff --stat $(git merge-base HEAD origin/main) -- <path>` + no migration). The new fields are computed from `sideEvidence` ALONGSIDE the unchanged answer, never fed back into the prompt/model/validator. `OutputContractService` (`@Service`, no-arg ctor) is auto-instantiated and injected into `AskService` — Spring wiring needs no config. The `emptyEvidenceHappyPathLeavesContractEmptyAndAnswerIdentical` test confirms the answer + citations are byte-identical to the pre-Phase-8 baseline when there is no side-evidence.
