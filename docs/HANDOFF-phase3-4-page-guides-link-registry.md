# Handoff — Link Registry + Page Guides (RAG-brain Phases 3–4)

**Date:** 2026-06-15
**Purpose:** Resume building the next control-plane layers without re-doing research. Everything the next session needs is in this file or the linked docs.

---

## 1. TL;DR — where things stand

- **Vocabulary layer (Phase 2 / "Phase 4.7") is DONE, verified, and MERGED to `main`** (merge commit `4d72778`). It fixed the reported bug: "owner occupied duplex" now retrieves the Fannie Eligibility Matrix at confidence 1.0 and answers "95% / 75%" with 2 citations instead of escalating. Guardrail holds ("non-owner occupied" → investment, not principal residence). Dashboard **Vocabulary** screen (edit/save/revert/history/preview) works. Full Gradle suite green; dashboard builds.
  - `main` is **ahead of `origin/main` by the vocab merge — NOT pushed** (push when ready: `git push origin main`).
  - Branch `feat/phase4.7-vocabulary-layer` still exists (merged).
- **The brain is running the new code on port 8090** (restarted during verification). Dashboard on 5173 if still up.
- **Next work:** Link Registry (Phase 3) + Page Guides (Phase 4), per the approved architecture spec.

## 2. ⛳ PENDING DECISION (answer this first)

The user must choose **how deep** to build, because the spec phases data separately from integration:

- **Option A — Data layers only (Phases 3–4) [RECOMMENDED]:** the two tables + CRUD + dashboard screens + pack seed, so links/page-guides can be *curated*. Backward-compatible (empty tables = today). The brain does NOT yet use them in answers.
- **Option B — Go all the way (Phases 3–8):** data layers PLUS the integration that makes the brain USE them — `pageRoute`/`surface` on `/ask`, retrieval planner, multi-index retrieval, authority filter, and `recommendedPage`/`links`/`nextAction` in answers + link validator. Much larger; touches the core ask pipeline and the boot-locked prompt contract. Build as staged plans, back-to-back.
- **Option C — Just the Link Registry (Phase 3) first.**

Recommendation: **A** (low-risk, matches the spec's phasing, is the prerequisite for B anyway, ships a usable curation UI). Then tee up B.

> The user ended the prior session at exactly this question. Start by confirming the scope, then proceed.

## 3. Roadmap context

Authoritative design: **`docs/superpowers/specs/2026-06-15-rag-brain-template-architecture-design.md`** (read §3 decisions, §6.1 data models, §6.3 request/response, §7.4–7.8 retrieval/authority/output-contract, §9 phases).

Phase roadmap (from the spec):
- Phase 0 — Relocation/template-ization · Phase 1 — Corpus mgmt UI (plan ready: `docs/superpowers/plans/2026-06-15-phase1a-corpus-document-crud.md`, **not built**) · **Phase 2 — Vocabulary ✅ DONE+MERGED** · **Phase 3 — Link Registry (NEXT)** · **Phase 4 — Page Guides (NEXT, depends on 3)** · Phase 5 — Intent Router (adds `pageRoute`/`surface`) · Phase 6 — Retrieval Planner + Multi-index · Phase 7 — Authority Filter · Phase 8 — Output Contract + Link/Grounding Validator · Phase 9 — Telemetry/Feedback · Phase 10 — Security & multi-tenancy.
- Hard rule every phase: **backward-compatible** — empty new tables + default config = today's behavior. Each phase plan includes a no-regression check against the golden pack.

## 4. The design (from the spec — enough to plan against)

### Data models (§6.1)

`brain_source_links` (the trust layer — Phase 3):
`id UUID PK | name varchar | url varchar | domain varchar | authority enum(primary|secondary|background) | topics jsonb(string[]) | freshness_required boolean | allowed_use jsonb(string[]) | do_not_use_for jsonb(string[]) | surface enum(public|internal|both) | active boolean | created_at/by, updated_at/by`

`brain_page_guides` (where to send the user — Phase 4):
`id UUID PK | route varchar NULL (e.g. /loans/duplex; null → topic-matched only) | title varchar | purpose text | surface enum(public|internal|both) | user_intents jsonb(string[]) | allowed_guidance jsonb(string[]) | internal_links jsonb([{label,url}]) | source_link_ids jsonb(UUID[] → references brain_source_links, NOT a relational FK) | topics jsonb(string[]) | embedding vector NULL (optional) | active boolean | created_at/by, updated_at/by`

### Authority tiers (§6.4) — code constant first (D6), promotable to `authority.yaml` later (Phase 7, NOT now):
1 Company rule/internal policy (sourceType=INTERNAL_POLICY + live Rules) · 2 Current page guide · 3 Primary external (`authority=primary`) · 4 Secondary · 5 Background.
Note: the `brain_source_links.authority` COLUMN enum is only the 3 external values; tiers 1–2 come from elsewhere.

### Request/response (§6.3) — **integration scope (Option B), not data layers:**
- `AskRequest` gains optional `pageRoute` (string) + `surface` (enum). Absent → today.
- `ModelAnswer`/`AskResponse` gain `recommendedPage {route,label}`, `links[]` (resolved from registry), `nextAction` (string). All optional; Output Contract (§7.7) governs when required.

### Retrieval/authority/output (§7.4–7.8) — **integration scope (Option B):**
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
  - Frontend: `dashboard/src/screens/Corpus.tsx` (table/list idiom: `tbl`, `Pill`, `row-actions`, `busy` state) and `Vocabulary.tsx` (edit-form idiom). Add `SourceLinks.tsx` + `PageGuides.tsx`, register in `dashboard/src/App.tsx` (import + `<NavLink>` + `<Route>`). **`api.ts` has NO `delete` method — add `del: <T>(path)=>request<T>(path,{method:"DELETE"})`.** Types go in `dashboard/src/types.ts`.
- **Flyway:** highest is **V7**. New migrations → **`V8__create_brain_source_links.sql`**, **`V9__create_brain_page_guides.sql`** (V8 before V9; guides reference links). Use `brain_`-prefixed table names.
- **DomainPackLoader:** `load()` does 5 mandatory `read()` calls; `read()` **throws if the file is missing**. New pack files must be loaded via an **optional** variant (pre-check `Files.isRegularFile` / `readOptional()` returning null) so existing/future packs without them still boot. YAML mapper is `KEBAB_CASE` + `FAIL_ON_UNKNOWN_PROPERTIES` → every YAML key needs a matching record component. Add `SourceLinksFile`/`PageGuidesFile` intermediate records + new `DomainPack` components (default to empty list) + `validate()` blocks.
- **First-boot seeding:** no startup-seeder exists yet for collections. Add an `ApplicationRunner` / `@EventListener(ApplicationReadyEvent)` that, if `brain_source_links` (resp. `brain_page_guides`) is empty, inserts rows from the pack file. Idempotent (seed only when empty).
- **SourceType enum** has FOUR values: `AGENCY_GUIDELINE, INTERNAL_POLICY, INVESTOR_OVERLAY, EDUCATIONAL` (not three). Authority-tier mapping (SourceType → tier) is net-new (Phase 7).

## 6. Golden-pack + test conventions (verified — see memory `msfg-rag-test-and-pack-conventions`)

- `TestPacks.msfg()` **loads the production YAML from disk** (no fixture map). Pack-default changes are a **2-way sync**: the YAML + the literal in `MsfgGoldenPackTest`. Adding pack components/files **will require updating `MsfgGoldenPackTest`** (it asserts the full pack shape) — never weaken assertions, fix the literal/YAML.
- Admin controllers are **POJO unit tests** (`new XController(mock)`, `assertThrows`, Mockito `verify`) — NOT MockMvc. `IllegalArgumentException` → HTTP 400 globally via `GlobalExceptionHandler` (`@RestControllerAdvice`).
- Repo tests use Testcontainers (`@DataJpaTest`, mirror `BrainSettingRepositoryTest` / `VocabularyRevisionRepositoryTest`). Docker must be up.
- Cache services share the 10s `System.nanoTime()` TTL + `Long.MIN_VALUE` sentinel pattern (`RuntimeSettings`/`RulesService`/`VocabularyService`).

## 7. Recommended execution (the loop that worked for Vocabulary)

1. **Confirm scope** (§2). Branch off `main`: `git checkout main && git checkout -b feat/phase3-link-registry` (and/or a combined `feat/phase3-4-...`).
2. **Write the plan** with the `superpowers:writing-plans` skill — model it on the vocabulary plan `docs/superpowers/plans/2026-06-15-phase4.7-vocabulary-layer.md` (complete code, bite-sized TDD steps, no placeholders). For data layers: per table → migration, entity (String-JSON fields), repo (+Testcontainers test), Service (CRUD + ObjectMapper convert + first-boot seed), DTO, AdminController (+POJO test), optional pack-file load + DomainPack component + golden update, dashboard screen + types + `api.del` + nav. Page Guides mirrors Link Registry (reference the just-built SourceLink files as the template).
3. **Adversarially verify the plan** before building — a Workflow with parallel reviewers (coverage / TDD-discipline / signature-match-vs-repo / correctness / integration-regression) then synthesis. (This caught a blocking defect in the vocab plan — worth it. Prior script: `…/workflows/scripts/verify-vocab-plan-*.js`.)
4. **Build via subagent-driven-development** — a background Workflow: per task, sonnet implementer (TDD) → opus spec reviewer → opus quality reviewer, fix loops, **stop-on-failure**. (Prior script: `…/workflows/scripts/build-vocab-layer-*.js` — adapt task list.)
5. **Independently verify:** `./gradlew test`, `cd dashboard && npm run build`, then live: restart brain on 8090 from the branch and exercise the new admin endpoints (create/list/edit/delete a link and a guide; confirm pack seed populated on first boot).
6. **Merge to main** if clean (conditional merge: only if `git merge-base --is-ancestor main <branch>`), `--no-ff`. Offer to push.
7. Update memory `msfg-rag-control-plane-roadmap` (mark Phase 3/4 done).

## 8. Key gotchas to NOT trip on (landmines)

- Prompt template is boot-locked to **exactly 5 `%s`** — don't add placeholders (Option B: fold new JSON keys in as literal text).
- `AskResponse` built at **2 sites**; `ModelAnswer` reconstructed in `ensureCitations` — both break if you extend the records (Option B).
- New pack files are **mandatory by default** in `read()` — make them optional or every pack breaks at boot.
- **`MsfgGoldenPackTest` will fail** the moment you change the `DomainPack` shape — update it deliberately (it's the lock, not a bug).
- `api.ts` has no `delete` — add it.
- Backward-compat: empty new tables + no pack file must boot and behave exactly like today; include a golden/no-regression check.

## 9. Pointers

- Architecture spec (authoritative design): `docs/superpowers/specs/2026-06-15-rag-brain-template-architecture-design.md`
- Vocabulary plan (template for plan style): `docs/superpowers/plans/2026-06-15-phase4.7-vocabulary-layer.md`
- Phase 1a corpus-CRUD plan (ready, unbuilt; CRUD UI pattern reference): `docs/superpowers/plans/2026-06-15-phase1a-corpus-document-crud.md`
- Memory (auto-loaded): `msfg-rag-control-plane-roadmap`, `msfg-rag-test-and-pack-conventions`, `msfg-rag-borrower-vocabulary-retrieval-gap`
- Run it: `~/MSFG/msfg-rag/start.sh` (DB + brain :8090 + dashboard :5173). Admin key: `grep ADMIN_API_KEY .env | cut -d= -f2- | pbcopy`. Retrieval probe: `GET /api/ai/documents/test-retrieval?question=...` (admin-gated). Never use port 8080.
