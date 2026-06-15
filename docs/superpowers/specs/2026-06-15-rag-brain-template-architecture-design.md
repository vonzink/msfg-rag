# RAG-Brain Template — Master Architecture Design

> **Status:** Approved design (brainstorming output). This is the architecture-level
> master spec for the whole roadmap. Each **Phase** below is scoped to become its own
> implementation plan (via `writing-plans`) when we build it. This document is the
> single source of truth those plans derive from.
>
> **Date:** 2026-06-15 · **Author:** Zack + Claude (brainstorming session)

---

## 1. Goal

Evolve the existing `msfg-rag` Spring Boot + React system into a **reusable, relocatable
RAG-brain template** — a domain-agnostic engine you instantiate per project (secure or
public), located at `/Users/zacharyzink/rag-brain`, with the MSFG mortgage brain as
**instance #1**.

On top of relocation, add the control layers a production RAG-brain needs so it *guides*
users (not just answers): an intent router, an editable vocabulary layer, a retrieval
planner, first-class **Page Guides** and **Link Registry** data layers, an authority
filter, an output contract, a grounding/link validator, and a telemetry/feedback loop —
**plus** the originally-requested Corpus management UI (assign location, add/edit/delete).

The architecture principle throughout: **insert, don't rewrite.** The existing pipeline
spine (safety gate → retrieve → prompt+rules → generate → validate → audit) is already
TDD'd and stays intact. New layers slot between existing stages.

---

## 2. Current system (what already exists)

Verified by reading the codebase 2026-06-15. The "spine" is real and tested.

**Runtime:** Java 21 / Spring Boot 3.5 (port 8090) · React 18 + Vite 5 + TS dashboard
(port 5173) · PostgreSQL + pgvector (port 5433, docker-compose) · Flyway migrations
(currently V1–V6) · `start.sh` boots all three.

**The `ask()` pipeline** (`service/AskService.java`):
1. `QuestionClassifierService.classify()` → `QuestionCategory` (FRAUD, ELIGIBILITY,
   LEGAL, TAX, LIVE_RATES, EDUCATIONAL). Only EDUCATIONAL proceeds; the rest return
   canned refusals. Rules in `packs/msfg-mortgage/classifier.yaml` (regex, FRAUD first).
2. `RetrievalService.retrieve()` — `expandQuery` (acronyms) → hybrid vector+keyword
   search → program-rule ranking → optional LLM rerank → `sufficientEvidence()` gate.
3. Insufficient evidence → canned `noSource` refusal.
4. `PromptBuilderService.build()` — assembles the locked prompt; reads **live** hard
   rules + guidance from `RulesService` (`brain_rule_revisions`, V6).
5. `ModelRouterService.generate()` — provider routing + fallback (Anthropic primary,
   OpenAI/DeepSeek/Gemini/Grok optional).
6. Parse `ModelAnswer` JSON (answer, citations, confidence, humanEscalationRequired,
   disclaimer); `ensureCitations()` salvage path.
7. `AnswerValidationService.validate()` — compliance/citation validation; fail →
   escalation (raw model text never shown).
8. Persist messages + `AnswerSource` citation trail; `AuditLogService.record()` →
   `brain_audit_logs` (full trace: question, chunks, prompt, answer, provider/model,
   confidence, fallback, escalate).

**Reusable patterns already in place:**
- **`DomainPack`** abstraction (`pack/DomainPack.java`, `DomainPackLoader`) — pack YAMLs
  in `packs/msfg-mortgage/`: `pack.yaml`, `classifier.yaml`, `retrieval.yaml`,
  `prompt.yaml`, `guardrails.yaml`. **This is the template seam.**
- **Generic `brain_*` tables** (not `mortgage_*`): `brain_documents`, `brain_settings`,
  `brain_rule_revisions`, `brain_audit_logs`, etc. `brain.slug` routing
  (`@RequestMapping("/api/ai/${brain.slug:mortgage}")`).
- **Live-config pattern** (reused 3×): `brain_settings` + `RuntimeSettings` (10s cache,
  env-default fallback, `Long.MIN_VALUE` sentinel). Same shape for `RulesService` and the
  planned `VocabularyService`.
- **Append-only revision pattern**: `brain_rule_revisions` + service cache + pack
  fallback + dashboard editor with history/revert.
- **Corpus**: `MortgageDocument` + `DocumentChunk` (pgvector); `LocalStorageService`
  (`DOCUMENT_STORAGE_PATH`); `S3CorpusSource`/`SyncService`/`SyncPlanner`/`SyncManifest`
  (manifest + SHA256 diff → ADD/UPDATE/DEACTIVATE plan, dry-run support).
  `DocumentAdminController`: upload, list, reindex, activate/deactivate, sync,
  test-retrieval. `SourceType` = {INTERNAL_POLICY, EXTERNAL_VENDOR}. Allowed extensions:
  pdf, docx, txt, md, markdown, html, htm. Max 25 MB.
- **Auth**: `AdminApiKeyFilter` (`X-Admin-Api-Key`) on `/api/ai/admin/**` and document
  endpoints; `RateLimitFilter` on public `/ask`. Dashboard stores key in `sessionStorage`.
- **Dashboard screens**: Corpus, Rules, Settings, Audit, TestConsole.

**Already-written but not built:** the **Vocabulary layer** (Phase 4.7 plan exists at
`docs/superpowers/plans/2026-06-15-phase4.7-vocabulary-layer.md`) — phrase-aware
`expandQuery`, `brain_vocabulary_revisions` (V7), `VocabularyService`,
`AdminVocabularyController`, `Vocabulary.tsx`. We fold this in as Phase 2.

---

## 3. Locked decisions (from brainstorming)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Delivery shape | **One master spec**, built phase-by-phase. |
| D2 | Consumers of `/ask` | **Both** public website (borrowers) **and** the dashboard SPA (staff). |
| D3 | Page context | **Design for both** — `pageRoute` optional on `/ask`; guides route-keyed *and* topic-matchable. |
| D4 | Records carry audience | `surface` field (`public` / `internal` / `both`) on page guides + source links. |
| D5 | External links | Centralized in the **Link Registry**; page guides reference registry rows. Internal links inline. |
| D6 | Authority tiers | Config constant (company rule > page guide > primary > secondary > background), editable later. |
| D7 | Response shape | Extend `ModelAnswer` with `recommendedPage`, `links[]`, `nextAction`; validator checks they resolve. |
| D8 | Telemetry | Extend `brain_audit_logs` + new `brain_feedback` table + `POST /api/ai/feedback`. |
| D9 | Template location | `/Users/zacharyzink/rag-brain` — **relocate this repo** (git history preserved); msfg-mortgage = bundled reference pack. |
| D10 | "Secure & unsecure" scope | **Auth posture + data sensitivity + tenant isolation** (heaviest). |
| D11 | Isolation topology | **Process-per-brain (physical)** — own process + DB + keys per instance. **No tenant-routing code in core.** |
| D12 | Storage patterns | Collections → relational tables + CRUD; single editable docs → revision pattern; runtime knobs → `brain_settings`. |

---

## 4. Target runtime pipeline (end state)

```
POST /ask { question, sessionId, pageRoute?, surface? }       ← pageRoute/surface NEW (optional)
   │
   ▼  Safety Gate                 (exists, unchanged)  fraud/legal/tax/rates/eligibility → canned refusal
   ▼  Intent Router               (NEW)                question_answering | page_guidance | external_reference | calculator | handoff
   ▼  Vocabulary Expansion        (Phase 4.7)          borrower words → guideline words
   ▼  Retrieval Planner           (NEW)                intent + pageRoute → which indexes, what weights
   ▼  Multi-index Retrieval       (extend)             corpus + page-guides + link-registry → unified evidence set
   ▼  Reranker + Authority Filter (extend)             rerank exists; add trust-tier ordering
   ▼  Prompt + Rules + Contract   (extend)             live rules exist; contract adds required shape
   ▼  Model Answer (JSON)         (exists, unchanged)
   ▼  Grounding / Link Validator  (extend)             claims grounded? links resolve? page fits intent? conflicts?
   │
   ▼  Response { answer, citations, recommendedPage?, links[], nextAction?, confidence, escalate }
   ▼  Telemetry / Feedback        (extend + NEW)       log intent/vocab/plan/links; capture click & correction
```

**Key insertion contracts:**
- The **Intent Router** runs only on the *allowed* (post-safety-gate) path. It is a
  *second, orthogonal* classifier: the safety gate decides *may we answer*; the intent
  router decides *what kind of answer/where to look*. Both are pack-configured
  (`classifier.yaml` gains an `intents:` section; safety `rules:` stay as-is).
- The **Retrieval Planner** produces a `RetrievalPlan { indexes[], weights, pageBoost }`
  consumed by retrieval. Default plan (no page context, `question_answering`) reproduces
  today's behavior exactly — backward compatible.
- **Evidence** becomes a unified type carrying its **source kind** (corpus chunk / page
  guide / registry link) and an **authority tier**, so the Authority Filter can order
  heterogeneous evidence.
- The **Output Contract** is enforced in two places: the prompt (instructs the shape) and
  the validator (rejects answers that violate it — e.g. cite an unresolvable link).

---

## 5. Template topology

```
/Users/zacharyzink/rag-brain
├── src/ , dashboard/        ← CORE ENGINE (domain-agnostic; the 9 layers; brain_* tables; admin UI; auth modes)
├── packs/<domain>/          ← DOMAIN PACK (swap → new brain): pack/classifier/retrieval/prompt/guardrails
│                              + NEW seed files: page-guides.yaml, source-links.yaml, authority.yaml
├── .env                     ← INSTANCE config: DB, provider keys, brain.slug, AUTH_MODE, SENSITIVITY, corpus location
├── data/ or S3              ← INSTANCE corpus
└── (own Postgres DB)        ← INSTANCE data: live-edited rules/vocab/guides/links/settings, seeded from pack
```

**Core never contains domain words.** Mortgage specifics live only in
`packs/msfg-mortgage/` and the MSFG instance's DB. Editable-at-runtime data is **seeded
from the pack on first boot** (idempotent) and thereafter owned by the instance DB and
edited from the dashboard — exactly how Rules already work.

**Instantiating a new brain** = copy/point at the repo, add `packs/<domain>/`, set `.env`
(new DB + slug + keys + auth/sensitivity), drop in a corpus, boot. No code changes.

---

## 6. Data models

### 6.1 New tables

**`brain_page_guides`** — *where the user should go* (route-keyed, topic-matchable):

| column | type | notes |
|---|---|---|
| id | UUID PK | |
| route | varchar NULL | e.g. `/loans/duplex`; null → topic-matched only |
| title | varchar | |
| purpose | text | |
| surface | enum | `public` \| `internal` \| `both` (D4) |
| user_intents | jsonb | string[] — what users want here |
| allowed_guidance | jsonb | string[] — what the AI MAY say here |
| internal_links | jsonb | `[{label,url}]` inline |
| source_link_ids | jsonb | UUID[] → references `brain_source_links` (D5) |
| topics | jsonb | string[] — semantic-match fallback |
| embedding | vector NULL | optional: embed purpose+intents for topic match |
| active | boolean | soft on/off |
| created_at/by, updated_at/by | | audit cols |

**`brain_source_links`** — *what external sources are allowed* (the trust layer):

| column | type | notes |
|---|---|---|
| id | UUID PK | |
| name | varchar | "Fannie Mae Selling Guide" |
| url | varchar | |
| domain | varchar | `fanniemae.com` |
| authority | enum | `primary` \| `secondary` \| `background` |
| topics | jsonb | string[] |
| freshness_required | boolean | |
| allowed_use | jsonb | string[] |
| do_not_use_for | jsonb | string[] |
| surface | enum | `public` \| `internal` \| `both` |
| active | boolean | |
| created_at/by, updated_at/by | | |

**`brain_feedback`** — *closed-loop signals* (D8):

| column | type | notes |
|---|---|---|
| id | UUID PK | |
| conversation_id | UUID | |
| message_id | UUID | which answer |
| audit_log_id | UUID NULL | join to the trace |
| type | enum | `link_click` \| `correction` \| `rating` |
| payload | jsonb | which link / corrected text / 👍👎 |
| session_id | varchar | same-session guard (mirrors conversation guard) |
| created_at | timestamptz | |

**`brain_vocabulary_revisions`** (V7, from Phase 4.7) — append-only revision doc.

### 6.2 Extended tables

- **`brain_audit_logs`** gains: `intent`, `vocab_expansions` (jsonb), `planner_indexes`
  (jsonb), `links_offered` (jsonb). Nullable/back-compatible.
- **`brain_documents`** — no schema change required for corpus mgmt; "edit metadata"
  reuses existing columns (title, sourceName, sourceType, dates, version). Hard-delete is
  a new *operation*, not a column.

### 6.3 Request / response shape

**`AskRequest`** gains optional `pageRoute` (string) and `surface` (enum). Absent →
today's behavior. `surface` lets the brain filter guides/links and pick allowed guidance
for borrower vs staff.

**`ModelAnswer` / `AskResponse`** gains `recommendedPage` (`{route,label}`), `links[]`
(resolved from registry), `nextAction` (string). All optional; the **Output Contract**
(§7.7) governs when each is required.

### 6.4 Authority tiers (config constant, D6)

```
1 Company rule / internal policy   (brain_documents sourceType=INTERNAL_POLICY + live Rules)
2 Current page guide               (the route-matched guide)
3 Primary external source          (brain_source_links authority=primary)
4 Approved secondary source        (authority=secondary)
5 General background source        (authority=background)
```

Lives as a code constant first; promotable to an editable `authority.yaml` /
`brain_settings` later without changing the filter's interface.

### 6.5 Storage-pattern rule (D12)

- **Collections of structured records** (page guides, source links, feedback, documents)
  → relational table + Flyway migration + JPA entity + repository + admin CRUD controller
  + dashboard table/form screen. Row-level CRUD. Mirrors the documents pattern.
- **Single editable text documents** (vocabulary; future editable contract/authority)
  → append-only `*_revisions` + `*Service` (10s cache, pack fallback) + dashboard editor
  with history/revert. Mirrors Rules.
- **Runtime knobs** (planner weights, rerank toggle, thresholds, intent enable) →
  `brain_settings` + `RuntimeSettings`.

---

## 7. Per-layer designs

Each is a buildable unit. "Where it slots" references §4. All new behavior is **off by
default / backward-compatible**: an instance with empty new tables and default settings
behaves exactly like today.

### 7.1 Corpus management (the original ask)
**Slots:** Corpus screen + `DocumentAdminController` + storage/sync services.
**Adds:**
- **Assign location** — corpus root becomes runtime-configurable, not boot-only. New
  `CorpusLocationService` resolves the active source from a `brain_settings`-backed config
  (`storage.path` for local, `s3.bucket`/`s3.prefix`/`region` for S3). `LocalStorageService`
  + `S3CorpusSource` read through it. Changing the location is an explicit admin action
  that records the new config and offers **Reindex all** (rebuild chunks from the new
  source). *Why runtime, not `.env`+restart:* the user asked to assign it "from the
  dashboard"; env-only would require shell access + restart.
- **Add** — `Add document` form in the dashboard hitting the existing `POST .../upload`
  (was API-only).
- **Edit** — new `PATCH /api/ai/documents/{id}` updating metadata (title, sourceName,
  sourceType, version, dates); does not re-ingest unless the file changes.
- **Delete (hard)** — new `DELETE /api/ai/documents/{id}` removing the stored file +
  chunks + row (today only soft deactivate exists). Guarded with a confirm in the UI.
**Interface:** unchanged public retrieval; admin-only mutations.
**Pack/config:** default corpus location from pack/`.env`; overridable at runtime.

### 7.2 Vocabulary layer (Phase 4.7 — already planned)
Execute the existing plan verbatim: phrase-aware `expandQuery` (greedy longest-match
masking), `brain_vocabulary_revisions` (V7), `VocabularyService` (cached, pack fallback),
`AdminVocabularyController`, `Vocabulary.tsx`. Generalize naming where it says "mortgage"
only inside the pack. **Slots:** between safety gate and retrieval (it already is the
`expandQuery` step).

### 7.3 Intent Router
**Slots:** new step after safety gate, before vocabulary.
**Design:** a second classifier on the allowed path. Pack `classifier.yaml` gains an
`intents:` section (regex first, optional LLM tie-break later) producing an `Intent`
enum: `question_answering` (default), `page_guidance`, `external_reference`,
`calculator`, `handoff`. New `IntentRouterService` mirrors `QuestionClassifierService`.
The intent is passed downstream (planner, prompt, telemetry). Unknown/ambiguous →
`question_answering` (safe default = today's behavior).
**Pack/config:** intent rules in pack; `intent.enabled` knob in `brain_settings`.

### 7.4 Retrieval Planner
**Slots:** between vocabulary and retrieval.
**Design:** `RetrievalPlannerService.plan(intent, pageRoute, surface) → RetrievalPlan
{ indexes: [CORPUS|PAGE_GUIDES|LINK_REGISTRY], weights, pageBoost }`. Pure, table-driven,
unit-testable. Examples: `page_guidance` → page-guides + link-registry (+ corpus light);
`question_answering` → corpus; `external_reference` → link-registry + corpus;
`calculator` → page-guides (calculator links). A non-null `pageRoute` adds a page-boost.
Default plan = corpus-only = today.
**Pack/config:** planning table seeded from pack, weights tunable via `brain_settings`.

### 7.5 Multi-index Retrieval
**Slots:** the retrieval step, generalized.
**Design:** `RetrievalService` executes the `RetrievalPlan` across indexes and returns a
unified `Evidence` list. Corpus path is unchanged (hybrid vector+keyword). Page guides:
route exact-match + topic/embedding match. Link registry: topic match + authority. Each
`Evidence` carries `sourceKind` + raw score + (resolved) `authorityTier`.
**Back-compat:** a CORPUS-only plan returns exactly today's chunks.

### 7.6 Reranker + Authority Filter
**Slots:** after retrieval.
**Design:** keep `RerankerService`. Add `AuthorityFilter` that orders/weights the unified
evidence by tier (§6.4), so a company rule outranks a primary source outranks a blog. The
filter is a pure function over `Evidence[]` + the tier constant. Configurable: whether
authority is a hard sort or a score boost (`authority.mode` knob).

### 7.7 Output Contract
**Slots:** prompt assembly + response shape.
**Design:** formalize the required answer shape in `prompt.yaml` (instructs the model)
and in a `OutputContract` validated server-side: `answer_must_include` (direct answer;
confidence; a citation **or** page link when evidence exists; next best action) and
`answer_must_not` (invent limits; cite unresolved links/pages; give final eligibility
without required inputs — the safety gate already blocks eligibility, this is the
belt-and-suspenders). Extends `ModelAnswer` (§6.3).
**Pack/config:** contract clauses in the pack (reuse the Rules editing pattern so they're
live-editable later).

### 7.8 Grounding / Link Validator
**Slots:** the validation step (extends `AnswerValidationService`).
**Design:** add checks — (a) every factual claim maps to retrieved evidence
(claim-grounding heuristic / LLM-judge, configurable strictness); (b) every link in the
answer resolves to an **active Link Registry row** or a known internal route; (c) the
`recommendedPage` matches the user intent/surface; (d) source-conflict detection
(contradictory tiers) surfaces a flag. Fail → existing escalation path (never show raw).
**Pack/config:** strictness via `validation.*` knobs.

### 7.9 Telemetry / Feedback loop
**Slots:** audit (extend) + new public feedback endpoint.
**Design:** extend `AuditLogService.record()` to persist intent, vocab expansions,
planner indexes, links offered (§6.2). New `FeedbackController`
`POST /api/ai/feedback { conversationId, messageId, type, payload }` (public,
rate-limited, same-session guard) → `brain_feedback`. New dashboard **Feedback** screen:
click-through rate, correction log, low-confidence answers, "links offered vs clicked."
**Sensitivity:** feedback + audit honor the redaction/encryption tier (§8).

### 7.10 Intents & Planner admin screen
Small dashboard screen to view/toggle intents and tune planner weights/authority mode
(`brain_settings`-backed). Read-only view of pack-seeded rules + editable knobs.

---

## 8. Security & multi-tenancy (D10, D11)

Three **per-instance** config knobs, enforced in core; isolation is **physical**.

**8.1 Auth posture — `AUTH_MODE`** (`public` | `jwt` | `apikey`), governs `/ask` only:
- `public` — open + `RateLimitFilter` (today's public behavior).
- `jwt` — bearer validation (Cognito/OIDC; reuse RS256 patterns from the MSFG dashboard).
- `apikey` — issued keys.
- Admin endpoints are **always** authenticated; `AdminApiKeyFilter` now, role-based
  (admin/editor/viewer) as a near-term hardening. Implemented as a pluggable
  `AskAuthenticationFilter` selected by `AUTH_MODE` — one filter, config-selected, no
  per-deployment code.

**8.2 Sensitivity tier — `SENSITIVITY`** (`public` | `internal` | `restricted`):
- `public` — minimal redaction.
- `internal` — PII redaction in telemetry/audit (configurable patterns); CORS locked.
- `restricted` — + field-level encryption at rest (AES-256-GCM, reuse MSFG dashboard
  approach) on corpus text + audit Q/A + feedback payloads; cited-source reads require
  auth. Redaction/encryption applied centrally in the audit + feedback write paths and the
  citation read path, gated by the tier so lower tiers pay nothing.

**8.3 Isolation — physical (D11):** each brain = its own **process + Postgres DB + secret
set** (provider keys, admin key, encryption key). No shared tables, no cross-brain reads,
no tenant-resolution middleware. "Multi-tenant" = deploy N independent copies. Per-instance
CORS allowlist + rate limits. Encryption key per instance so a single DB compromise can't
cross brains. **This deliberately keeps the core single-brain-per-process** — the simplest,
strongest guarantee.

---

## 9. Phased roadmap

Dependency-ordered. Each phase is independently shippable and gets its own implementation
plan when built. Phases assume the prior ones but every phase is backward-compatible
(empty new tables + default config = today's behavior).

**Phase 0 — Relocation & template-ization** *(foundation)*
- `git mv` / relocate `/Users/zacharyzink/MSFG/msfg-rag` → `/Users/zacharyzink/rag-brain`
  (preserve history). Update `start.sh`, docs, paths.
- Generalize core identifiers out of the domain: rename core Java package
  `com.msfg.rag` → `com.ragbrain` (or chosen neutral), `MortgageDocument` →
  `BrainDocument`/`CorpusDocument`, config prefix `msfg.rag.*` → `brain.*`,
  `MsfgGoldenPackTest` → pack-named test. **Risk note:** this is the most invasive
  mechanical change — do it as the first, isolated, fully-green-suite step; mortgage
  specifics remain only in `packs/msfg-mortgage/`. *Optional staging:* keep the package
  name initially and rename in a dedicated later sub-step if churn is a concern.
- Confirm pack-seeding is idempotent for all editable layers.
- Document "create a new brain" in the runbook.

**Phase 1 — Corpus management UI** (§7.1) — original ask; establishes the admin-CRUD +
location-config patterns later phases reuse.

**Phase 2 — Vocabulary layer** (§7.2) — execute the ready Phase 4.7 plan; first
correctness win.

**Phase 3 — Link Registry** (§7.6 data, §6.1) — `brain_source_links` + CRUD + dashboard +
pack seed. Foundational data layer.

**Phase 4 — Page Guides** (§7.5 data, §6.1) — `brain_page_guides` + CRUD + dashboard +
pack seed; references Link Registry.

**Phase 5 — Intent Router** (§7.3) — adds routing intents; `pageRoute`/`surface` added to
`AskRequest`.

**Phase 6 — Retrieval Planner + Multi-index Retrieval** (§7.4, §7.5) — wire indexes from
intent + page context.

**Phase 7 — Authority Filter** (§7.6) — trust-tier ordering on the unified evidence.

**Phase 8 — Output Contract + Grounding/Link Validator** (§7.7, §7.8) — required shape +
link/claim validation.

**Phase 9 — Telemetry / Feedback loop** (§7.9, §7.10) — audit extensions + feedback
endpoint + dashboard.

**Phase 10 — Security & multi-tenancy** (§8) — `AUTH_MODE` filter, `SENSITIVITY` tier
(redaction/encryption), per-instance secret/CORS/rate config, role-based admin. *Can be
pulled earlier if a secure deployment is needed before the upper layers — it's
independent of Phases 3–9.*

> Phases 1–2 are quick wins. Phases 3–4 are the "biggest addition" data layers. 5–7 make
> the brain *route and rank*. 8–9 make it *trustworthy and self-improving*. 10 makes it
> *deployable anywhere, secure or public*.

---

## 10. Cross-cutting conventions

- **TDD throughout** (matches the existing repo): failing test → implement → green →
  commit, per task. Pure logic (planner, authority filter, expandQuery, validators,
  document formats) gets exhaustive unit tests; controllers get MockMvc tests; repos get
  Testcontainers tests.
- **Golden-pack tests** stay load-bearing: any pack-default change must sync across the
  YAML, the test fixture, and the golden assertion (as the Vocabulary plan documents).
- **Migrations**: Flyway, forward-only, next is **V7** (Vocabulary). Subsequent phases
  claim V8+ in order. (Note: this is the rag-brain repo's Flyway — distinct from the MSFG
  *dashboard's* re-run-every-boot migration system; don't conflate.)
- **Config**: new knobs go through `brain_settings`/`RuntimeSettings`; new pack files
  (`page-guides.yaml`, `source-links.yaml`, `authority.yaml`) load via `DomainPackLoader`.
- **Backward compatibility is a hard requirement** per phase: empty tables + default
  settings must reproduce current behavior. Each phase's plan includes a "no-regression"
  verification against the existing golden pack.
- **Dashboard**: new screens mirror `Rules.tsx`/`Corpus.tsx` (api.ts `X-Admin-Api-Key`,
  table+form, history/revert where revisioned). Nav grouped: Knowledge / Behavior /
  Observe.
- **Ports/dev**: unchanged — 8090 brain, 5173 dashboard, 5433 Postgres; `start.sh` boots.

---

## 11. Out of scope / follow-ups

- **Investment-property retrieval path** — flagged out of scope by the Vocabulary plan;
  remains a follow-up.
- **Editable authority tiers / output contract** — ship as code constants first;
  promote to editable (`authority.yaml`, revisioned contract) later.
- **LLM-based intent tie-break** — start regex-only; add an LLM fallback if regex proves
  insufficient.
- **Shared-deployment multi-tenancy (topology B)** — explicitly *not* built (D11). If ever
  needed, it's an additive layer, not a rewrite (the core stays single-brain-per-process).
- **Surfacing any of this inside the main `dashboard.msfgco.com` SPA** — the brain's own
  React admin remains the home; embedding is a separate, later integration if desired.

---

## 12. Self-review

- **Placeholders:** none — every layer has interface + slot + pack/config surface.
- **Consistency:** the pipeline (§4), data models (§6), per-layer designs (§7), and phases
  (§9) reference the same names and `brain_*` conventions throughout.
- **Backward-compat:** asserted per phase; the default plan/empty tables reproduce today.
- **Scope:** large but **decomposed into 11 independently-shippable phases**, each its own
  future plan — satisfying the "one master spec, built phase-by-phase" decision (D1).
- **Risk flagged:** Phase 0 core-package rename is the highest-churn step; isolated and
  staged.
