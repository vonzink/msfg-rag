# Handoff — Control-Plane Build (Link Registry → Output Contract, Phases 3–8)

**Date:** 2026-06-15
**Repo:** `/Users/zacharyzink/MSFG/msfg-rag` (`github.com/vonzink/msfg-rag`) — the staging brain that feeds **staging.msfg.us**. We are finishing this work **here, in one session.**
**Supersedes:** the prior `docs/HANDOFF-phase3-4-page-guides-link-registry.md` and the root `~/rag-brain-SESSION-HANDOFF.md` (both folded in here and removed).

---

## 1. TL;DR — where things stand (post-cleanup)

- **`main` is clean and green.** HEAD = the Phase 1a merge (`Merge Phase 1a (Corpus document CRUD) into main`), built on the Phase 4.7 vocabulary merge.
  - Backend `./gradlew test` → **BUILD SUCCESSFUL**. Dashboard `npm run build` → ✓ 42 modules. `vitest run` → **5/5**.
- **Cleanup done this session:**
  - **PR #1 (Corpus CRUD) merged** into `main` (`--no-ff`); the one real conflict (`dashboard/src/types.ts`) was an additive `VocabState`/`VocabRevisionDto` vs `DocumentUpdate` collision — kept all three.
  - **Leftover worktree** `/Users/zacharyzink/MSFG/msfg-rag-phase1a` removed.
  - **9 merged local branches pruned** (multi-provider, phase2-settings, phase3-s3-sync, phase4-dashboard, phase4.7-vocabulary, rules-editor, terse-acronym, fix/citation-escalation, phase1a-corpus-crud). All were ancestors of `main` — recoverable by SHA if ever needed. Only `main` remains.
  - **`start.sh` tracked** (runbook helper; boots DB + brain :8090 + dashboard :5173).
- **Two layers already shipped + merged:** Phase 4.7 **Vocabulary** (editable, dashboard screen, fixed "owner occupied duplex" retrieval) and Phase 1a **Corpus document CRUD** (add/edit/delete + `api.patch`/`api.del`).
- **`main` pushed to `origin/main`.** PR #1 auto-closed as merged.

## 2. ✅ DECISION (was pending — now locked)

**Option B — go all the way: Phases 3–8, built as staged plans, back-to-back.**
Not just the data layers (Option A) — the full integration that makes the brain *USE* links + page guides in answers. This touches the **boot-locked prompt contract** and the **core ask pipeline**, so it is sequenced as discrete, individually-verified, backward-compatible phases. Hard rule every phase: **empty new tables + default config = today's behavior** (no-regression check against the golden pack each phase).

**Execution order (each its own plan + verify loop):**
1. **Phase 3 — Link / Source Registry** (`brain_source_links`): table + entity + repo + service (CRUD, first-boot seed) + AdminController + dashboard screen + pack file `source-links.yaml`. **Foundation — everything downstream references it.**
2. **Phase 4 — Page Guides** (`brain_page_guides`): mirrors Phase 3; `source_link_ids` references registry rows (jsonb UUID[], not a relational FK). Pack file `page-guides.yaml`.
3. **Phase 5 — Intent Router**: adds optional `pageRoute` + `surface` to `AskRequest`; classifies question vs page-guidance vs calculator vs external-reference.
4. **Phase 6 — Retrieval Planner + Multi-index**: `RetrievalPlannerService.plan(intent, pageRoute, surface) → RetrievalPlan{indexes,weights,pageBoost}` (pure, table-driven; default = corpus-only = today). Unified `Evidence` carrying `sourceKind` + `authorityTier`.
5. **Phase 7 — Authority Filter**: trust-tier ordering on the reranker (`authority.mode` hard-sort or boost). Tier source code-constant first (D6), promotable to `authority.yaml` later.
6. **Phase 8 — Output Contract + Link/Grounding Validator**: `recommendedPage`/`links[]`/`nextAction` in the answer (folded into the prompt as literal JSON keys — see landmine §8); server-side validator: every emitted link resolves to an active registry row or known internal route, else escalate.

(Phases 9 Telemetry / 10 Security-multitenancy remain deferred.)

## 3. Multi-repo context (folded from the retired root handoff — read once, then ignore the other repo)

There is a **sibling template repo** `/Users/zacharyzink/rag-brain` (`github.com/vonzink/rag-brain`) — the reusable multi-brain template, more advanced than this one. **We are NOT touching it this session.** Why it matters here:

- **Do not conflate the two.** Both are Java 21 / Spring Boot + React/Vite/TS, both use package `com.msfg.rag`, both have a `dashboard/` with Corpus/Rules/Settings. Different remotes, different purposes.
- The template **already has** (do not "discover" these as missing here): Phase 5 per-brain corpus incl. `LocalFolderCorpusSource`, Phase 6/6b multi-brain platform (Brains screen, brain CRUD API, pack templating `packs/_template`), Phase 4b-2 SSRF `LocalEndpointValidator`. Those are template features; this staging brain is a single instance.
- The architecture spec (`docs/superpowers/specs/2026-06-15-rag-brain-template-architecture-design.md`) was written against **this** repo's (older) state. It is the authoritative design for the work below. Treat the template-flavored bits as aspirational; the §6.1 data models / §6.3 request-response / §7.4–7.8 retrieval-authority-output / §9 phases are what we build against.
- **Collision protocol still applies** (the user runs parallel sessions): before any destructive git op, `git status` — uncommitted changes you didn't make = another live session; pin SHAs and use throwaway worktrees to verify. (Memory: `msfg-rag-live-concurrent-editing`.)

## 4. The design (from the spec — enough to plan against)

### Data models (§6.1)

`brain_source_links` (the trust layer — Phase 3):
`id UUID PK | name varchar | url varchar | domain varchar | authority enum(primary|secondary|background) | topics jsonb(string[]) | freshness_required boolean | allowed_use jsonb(string[]) | do_not_use_for jsonb(string[]) | surface enum(public|internal|both) | active boolean | created_at/by, updated_at/by`

`brain_page_guides` (where to send the user — Phase 4):
`id UUID PK | route varchar NULL (e.g. /loans/duplex; null → topic-matched only) | title varchar | purpose text | surface enum(public|internal|both) | user_intents jsonb(string[]) | allowed_guidance jsonb(string[]) | internal_links jsonb([{label,url}]) | source_link_ids jsonb(UUID[] → references brain_source_links, NOT a relational FK) | topics jsonb(string[]) | embedding vector NULL (optional) | active boolean | created_at/by, updated_at/by`

### Authority tiers (§6.4) — code constant first (D6), promotable to `authority.yaml` later (Phase 7, NOT now):
1 Company rule/internal policy (sourceType=INTERNAL_POLICY + live Rules) · 2 Current page guide · 3 Primary external (`authority=primary`) · 4 Secondary · 5 Background.
Note: the `brain_source_links.authority` COLUMN enum is only the 3 external values; tiers 1–2 come from elsewhere.

### Request/response (§6.3) — **integration scope (Phases 5/8):**
- `AskRequest` gains optional `pageRoute` (string) + `surface` (enum). Absent → today.
- `ModelAnswer`/`AskResponse` gain `recommendedPage {route,label}`, `links[]` (resolved from registry), `nextAction` (string). All optional; Output Contract (§7.7) governs when required.

### Retrieval/authority/output (§7.4–7.8) — **integration scope (Phases 6/7/8):**
`RetrievalPlannerService.plan(intent, pageRoute, surface) → RetrievalPlan { indexes:[CORPUS|PAGE_GUIDES|LINK_REGISTRY], weights, pageBoost }` (pure, table-driven; default = corpus-only = today). Multi-index retrieval returns unified `Evidence` carrying `sourceKind` + `authorityTier`. `AuthorityFilter` orders by tier (hard-sort or boost via `authority.mode`). Output Contract formalized in `prompt.yaml` + server-side validator; link validator: every emitted link resolves to an active registry row or known internal route, else escalate.

### Pack seeding
New pack files **`source-links.yaml`**, **`page-guides.yaml`** (and later `authority.yaml`). Seeded into DB **on first boot, idempotent**; thereafter owned by the instance DB and edited from the dashboard (collections pattern, §6.5 — relational table + CRUD, mirrors the documents pattern). **The spec gives filenames + semantics but NO example YAML body — you author the YAML schema, mapping to the §6.1 columns.**

## 5. Codebase anchors (verbatim facts gathered — don't re-research)

- **Ask pipeline:** `src/main/java/com/msfg/rag/service/AskService.java` — `ask()` order: persist user msg → classify (non-EDUCATIONAL → canned refuse) → `retrievalService.retrieve(question)` → sufficiency gate → `promptBuilderService.build(question, chunks)` → `modelRouterService.generate(AiRequest.forGuidelineAnswer(prompt))` → `parseModelAnswer` → escalation/citation-salvage (`ensureCitations`) → `answerValidationService.validate` → assemble `AskResponse` → persist + `saveAnswerSources` → `auditLogService.record` → return.
  - **`AskResponse` is constructed at TWO sites:** the happy path AND inside `refuse(...)`. Extending it touches both.
  - **`ModelAnswer` is reconstructed via all-args ctor in `ensureCitations(...)`** — extending the record breaks that call.
- **DTOs:**
  - `dto/AskRequest.java`: `record AskRequest(UUID conversationId, @NotBlank @Size(255) String sessionId, @NotBlank @Size(2000) String question, @Size(50) String loanType, @Size(2) String state)`. Add trailing `pageRoute`/`surface` (backward-compatible JSON binding).
  - `dto/AskResponse.java`: `record AskResponse(UUID conversationId, String answer, List<CitationDto> citations, double confidence, boolean humanEscalationRequired, String disclaimer)`.
  - `service/ai/ModelAnswer.java`: `@JsonIgnoreProperties(ignoreUnknown=true) record ModelAnswer(String answer, List<CitationDto> citations, Double confidence, @JsonProperty("human_escalation_required") Boolean humanEscalationRequired, String disclaimer)`.
  - `dto/CitationDto.java`: `record CitationDto(@JsonProperty("source_name") String sourceName, @JsonProperty("document_name") String documentName, String section, @JsonProperty("page_number") String pageNumber, @JsonProperty("effective_date") String effectiveDate)` — mirror this JSON-key style for a new `LinkDto`.
- **Retrieval:** `RetrievalService.retrieve(String question) : RetrievalResult`. `RetrievalResult(List<RetrievedChunk> chunks, double confidence, boolean sufficientEvidence)`. Constructor already injects `DomainPack, RuntimeSettings, VocabularyService`, etc. Cleanest integration shape: **inject parallel `SourceLinkService`/`PageGuideService` into `AskService`** (it already orchestrates many services) rather than overloading RetrievalService.
- **Prompt contract is BOOT-LOCKED to exactly 5 `%s`:** `PromptBuilderService.build()` calls `template.formatted(hardRules, guidance, context, question, disclaimer)` (5 args) and `DomainPackLoader.validate` asserts the template has exactly 5 `%s` (`split("%s",-1).length == 6`) + runs a `formatted("","","","","")` smoke test at boot. To extend the answer JSON (recommendedPage/links/nextAction) you can add JSON keys as **literal text inside the existing template** (no new `%s`) — keep arg count 5. Any literal `%` must be `%%`. Touching the count requires updating build() + the loader check + the golden lock test together.
- **jsonb columns** are stored/handled as **String JSON** and (de)serialized with `ObjectMapper` (see `RetrievedChunk` metadata handling). For the new entities, use `String` JSON fields + convert to typed `List<String>`/`List<UUID>`/`List<{label,url}>` in the DTO/service. Avoids Hibernate jsonb-type complexity.
- **CRUD pattern to mirror:**
  - Backend: `controller/AdminVocabularyController.java` (closest full edit pattern; inner `record ContentBody`, `@GetMapping/@PutMapping/@PostMapping("/revert")/@GetMapping("/history")`, `UPDATED_BY="admin-api"`, throws `IllegalArgumentException`). For collections add `@PostMapping` create, `@PutMapping("/{id}")`, `@DeleteMapping("/{id}")`, `@PostMapping("/{id}/activate|deactivate")` (mirror `DocumentAdminController.setActive`). Put both new controllers under the **gated `/api/ai/admin/...`** prefix.
  - Frontend: `dashboard/src/screens/Corpus.tsx` (table/list idiom: `tbl`, `Pill`, `row-actions`, `busy` state, now with add-form + edit-modal + delete-confirm from PR #1) and `Vocabulary.tsx` (edit-form idiom). Add `SourceLinks.tsx` + `PageGuides.tsx`, register in `dashboard/src/App.tsx` (import + `<NavLink>` + `<Route>`). **`api.ts` now HAS `del` and `patch`** (added in PR #1) — reuse them. Types go in `dashboard/src/types.ts`.
- **Flyway:** highest is **V7**. New migrations → **`V8__create_brain_source_links.sql`**, **`V9__create_brain_page_guides.sql`** (V8 before V9; guides reference links). Use `brain_`-prefixed table names.
- **DomainPackLoader:** `load()` does 5 mandatory `read()` calls; `read()` **throws if the file is missing**. New pack files must be loaded via an **optional** variant (pre-check `Files.isRegularFile` / `readOptional()` returning null) so existing/future packs without them still boot. YAML mapper is `KEBAB_CASE` + `FAIL_ON_UNKNOWN_PROPERTIES` → every YAML key needs a matching record component. Add `SourceLinksFile`/`PageGuidesFile` intermediate records + new `DomainPack` components (default to empty list) + `validate()` blocks.
- **First-boot seeding:** no startup-seeder exists yet for collections. Add an `ApplicationRunner` / `@EventListener(ApplicationReadyEvent)` that, if `brain_source_links` (resp. `brain_page_guides`) is empty, inserts rows from the pack file. Idempotent (seed only when empty).
- **SourceType enum** has FOUR values: `AGENCY_GUIDELINE, INTERNAL_POLICY, INVESTOR_OVERLAY, EDUCATIONAL` (not three). Authority-tier mapping (SourceType → tier) is net-new (Phase 7).

## 6. Golden-pack + test conventions (verified — see memory `msfg-rag-test-and-pack-conventions`)

- `TestPacks.msfg()` **loads the production YAML from disk** (no fixture map). Pack-default changes are a **2-way sync**: the YAML + the literal in `MsfgGoldenPackTest`. Adding pack components/files **will require updating `MsfgGoldenPackTest`** (it asserts the full pack shape) — never weaken assertions, fix the literal/YAML.
- Admin controllers are **POJO unit tests** (`new XController(mock)`, `assertThrows`, Mockito `verify`) — NOT MockMvc. `IllegalArgumentException` → HTTP 400 globally via `GlobalExceptionHandler` (`@RestControllerAdvice`).
- Repo tests use Testcontainers (`@DataJpaTest`, mirror `BrainSettingRepositoryTest` / `VocabularyRevisionRepositoryTest`). Docker must be up.
- Cache services share the 10s `System.nanoTime()` TTL + `Long.MIN_VALUE` sentinel pattern (`RuntimeSettings`/`RulesService`/`VocabularyService`).

## 7. Recommended execution (the loop that worked for Vocabulary + Corpus CRUD)

1. Branch off `main`: `git checkout main && git checkout -b feat/phase3-link-registry`.
2. **Write the plan** with `superpowers:writing-plans` — model on the vocabulary plan `docs/superpowers/plans/2026-06-15-phase4.7-vocabulary-layer.md` (complete code, bite-sized TDD steps, no placeholders). For data layers per table → migration, entity (String-JSON fields), repo (+Testcontainers test), Service (CRUD + ObjectMapper convert + first-boot seed), DTO, AdminController (+POJO test), optional pack-file load + DomainPack component + golden update, dashboard screen + types + nav. Page Guides mirrors Link Registry.
3. **Adversarially verify the plan** before building — a Workflow with parallel reviewers (coverage / TDD-discipline / signature-match-vs-repo / correctness / integration-regression) then synthesis. (Caught a blocking defect in the vocab plan.)
4. **Build via subagent-driven development** — a background Workflow: per task, implementer (TDD) → spec reviewer → quality reviewer, fix loops, **stop-on-failure**.
5. **Independently verify:** `./gradlew test` (via `ctx_execute` — the context-mode hook redirects raw `./gradlew`), `cd dashboard && npm run build && npx vitest run` (vitest `run` mode — `npm run test` defaults to watch and hangs), then live: restart brain on :8090 from the branch and exercise the new admin endpoints.
6. **Merge to main** if clean (`git merge-base --is-ancestor main <branch>` then `--no-ff`). Push.
7. Update memory `msfg-rag-control-plane-roadmap` as each phase lands.

## 8. Key gotchas to NOT trip on (landmines)

- Prompt template is boot-locked to **exactly 5 `%s`** — don't add placeholders (Phase 8: fold new JSON keys in as literal text; escape literal `%` as `%%`).
- `AskResponse` built at **2 sites**; `ModelAnswer` reconstructed in `ensureCitations` — both break if you extend the records (Phases 5/8).
- New pack files are **mandatory by default** in `read()` — make them optional or every pack breaks at boot.
- **`MsfgGoldenPackTest` will fail** the moment you change the `DomainPack` shape — update it deliberately (it's the lock, not a bug).
- Backward-compat: empty new tables + no pack file must boot and behave exactly like today; include a golden/no-regression check.
- **Build tooling:** raw `./gradlew test` and `npm run build` get redirected by the context-mode hook → run them via `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", ...)`. `npm run test` is vitest **watch** (hangs) — use `npx vitest run`.
- **Never use port 8080** (memory `msfg-rag-port-8080-collision`) — brain runs on **:8090**, dashboard on **:5173**.

## 9. Pointers

- Architecture spec (authoritative design): `docs/superpowers/specs/2026-06-15-rag-brain-template-architecture-design.md`
- Vocabulary plan (template for plan style): `docs/superpowers/plans/2026-06-15-phase4.7-vocabulary-layer.md`
- Phase 1a corpus-CRUD plan (now MERGED; CRUD UI pattern reference): `docs/superpowers/plans/2026-06-15-phase1a-corpus-document-crud.md`
- Memory (auto-loaded): `msfg-rag-control-plane-roadmap`, `msfg-rag-test-and-pack-conventions`, `msfg-rag-borrower-vocabulary-retrieval-gap`, `msfg-rag-live-concurrent-editing`, `msfg-rag-port-8080-collision`
- Run it: `~/MSFG/msfg-rag/start.sh` (DB + brain :8090 + dashboard :5173). Admin key: `grep ADMIN_API_KEY .env | cut -d= -f2- | pbcopy`. Retrieval probe: `GET /api/ai/documents/test-retrieval?question=...` (admin-gated). **Never use port 8080.**
