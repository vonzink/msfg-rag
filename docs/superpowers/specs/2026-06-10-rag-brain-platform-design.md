# RAG Brain Platform — Design

**Date:** 2026-06-10
**Status:** Approved in design conversation (architecture + data flow/error handling/testing sections signed off)
**Scope note:** This is the umbrella spec for converting `msfg-rag` from a single-company app into a duplicatable "AI brain" template. Implementation proceeds **phase by phase** (§12); each phase gets its own implementation plan against this spec.

---

## 1. Goal

One company-agnostic RAG brain (this Spring Boot app), configured entirely by a **domain pack** (versioned YAML) plus a **runtime settings table**, with a **React ops console** beside it.

Standing up a brain for a new site/company/project means: copy the deployment, point it at a new database and S3 prefix, write a new pack directory, load the corpus. **Zero code edits per copy; bugfixes flow to every brain from one codebase.**

First instance: MSFG mortgage Q&A (the current behavior, preserved exactly).

## 2. Locked decisions

| # | Decision | Choice |
|---|---|---|
| 1 | Duplication model | **Template + domain pack** — one codebase; per-company YAML pack; each company gets its own deployment, database, and S3 prefix |
| 2 | Model selection | **Dashboard-switchable at runtime** via settings table; separate **ANSWER** and **UTILITY** (reranker/classifier) model settings |
| 3 | Dashboard | **Internal ops console** (corpus, settings, test console, audit); admin-key auth; shaped so analytics views can bolt on later |
| 4 | Company/website knowledge | **Curated company docs in S3** (same bucket/prefix), tagged with a company source type; per-source-type answer style defined in the pack |
| 5 | S3 sync cadence | **Manual + dashboard "Sync now"** button showing the diff; can later be cron'd unchanged |

Approved sub-decisions:
- **Port `scripts/s3-ingest/sync.mjs` into the app** as a Java `SyncService` (same manifest/diff semantics); the Node script remains a CLI fallback.
- **Rename tables** `mortgage_documents` → `brain_documents`, `mortgage_document_chunks` → `brain_document_chunks` (Flyway migration). `ai_*` tables unchanged.
- **Public endpoint path preserved**: slug is property-driven with default `mortgage`, so `/api/ai/mortgage/ask` keeps working for the current website; new brains set their own slug.

## 3. Architecture

```
msfg-rag/  (the template)
├── src/main/java/…          # brain — no company-specific constants after extraction
├── packs/msfg-mortgage/     # the MSFG domain pack (YAML, git-versioned)
├── dashboard/               # React ops console (Vite build, static deploy)
├── scripts/s3-ingest/       # legacy CLI sync (fallback only)
└── docs/superpowers/specs/
```

| Component | Responsibility |
|---|---|
| `DomainPack` (+ `DomainPackLoader`) | Immutable bean holding all company-specific content, loaded once at boot from `BRAIN_PACK` path; **fail-fast** on missing/invalid pack |
| `brain_settings` table (+ `RuntimeSettings` service) | Live operational knobs read per request through a short cache |
| `ModelRouterService` (extended) | Resolves (provider, model) per request by **purpose** (ANSWER vs UTILITY) from `RuntimeSettings`; existing fallback-provider behavior unchanged |
| `SyncService` | S3 manifest diff → ingest/deactivate through the existing Tika→chunk→embed pipeline |
| Admin API (extended) | `GET/PUT /api/ai/admin/settings`, `GET /api/ai/admin/audit`, `POST /api/ai/documents/sync` — all behind the existing admin-key filter |
| Dashboard | Four screens over the admin API: Corpus, Settings, Test Console, Audit |

## 4. Domain pack

Path supplied by env `BRAIN_PACK` (e.g. `packs/msfg-mortgage`). Contents:

| File | Holds | Today's hardcoded source |
|---|---|---|
| `pack.yaml` | company name, brain slug, disclaimer text | `PromptBuilderService` |
| `prompt.yaml` | locked answer template; per-source-type style overrides (company-doc questions get lighter framing than guideline questions — no "subject to full loan review" on "what are your hours") | `PromptBuilderService.TEMPLATE` |
| `guardrails.yaml` | prohibited phrases, quoted-phrase exception rule, all six canned refusal texts (no-source, escalation, legal, tax, live-rates, fraud) | `AnswerValidationService`, `AskService` |
| `classifier.yaml` | category keyword lists | `QuestionClassifierService` |
| `retrieval.yaml` | acronym expansions, program names/aliases for program-aware ranking | `RetrievalService` |

Rules:
- Loaded **once at boot** into an immutable bean. Services inject `DomainPack`; no service keeps company constants.
- **Boot fails** with a message naming the exact file and field if the pack is missing, unparseable, or has empty guardrails/refusals. A brain never runs with an empty compliance layer.
- `pack.yaml`'s slug must equal the `brain.slug` property (env-overridable, default `mortgage`); mismatch fails boot — catches pack/deployment mix-ups.
- Change control for pack content = git review + redeploy. This is intentional: compliance text changes leave an audit trail and cannot be made live at a click.
- Secrets, DB URLs, S3 bucket/prefix, API keys stay in env — never in the pack.

## 5. Runtime settings

Table `brain_settings(key varchar primary key, value text, updated_at timestamptz, updated_by varchar)`.

| Key | Default (from existing env/yml) |
|---|---|
| `answer.provider` / `answer.model` | `anthropic` / `claude-haiku-4-5` |
| `utility.provider` / `utility.model` | same as answer until changed |
| `retrieval.confidence-threshold` | `0.35` |
| `retrieval.top-k` | `8` |
| `rerank.enabled` | `true` |

- `RuntimeSettings` reads through a short-TTL cache (~10s); **missing key falls back to env defaults** — an empty table behaves exactly like today.
- `PUT /settings` validates before write: provider must be a registered `AiModelProvider`, model string non-empty, numeric ranges sane.
- `AiRequest` gains a **purpose** (ANSWER | UTILITY). The router resolves provider+model from settings per call and passes the model to the provider; the provider's `@Value` model becomes its env-level fallback. Fallback **provider** (resilience config) stays env-only in v1.
- Audit rows already record provider/model per answer, so model switches are traceable historically.

## 6. S3 sync

Java port of `sync.mjs` semantics:
- Source: `S3_BUCKET` + `S3_PREFIX` env (per deployment); manifest at `<prefix>_manifest.json` describes titles/source types/effective dates.
- Diff against DB by filename + content hash → plan of `ADD / UPDATE / SKIP / DEACTIVATE`.
- Execute through existing ingestion (Tika → chunk → embed → pgvector); removed files are deactivated, never deleted.
- **Per-file error isolation**: one bad file never aborts the batch and never replaces the previously active version of that document.
- `POST /api/ai/documents/sync` returns the plan + per-file results; dashboard renders it as the diff. Synchronous in v1 (corpus is small).
- Company knowledge docs (decision #4) are just more entries in the same bucket/manifest, tagged with a new `SourceType.COMPANY` enum value (added in phase ⑤) — the key the per-source-type prompt style in `prompt.yaml` switches on.

## 7. Schema & endpoint changes

- Flyway `V3`: rename `mortgage_documents` → `brain_documents`, `mortgage_document_chunks` → `brain_document_chunks`; update JPA `@Table` annotations and native queries. No data transformation.
- `AskController` mapping becomes slug-driven: `/api/ai/{brain.slug}/ask` with default `mortgage` — current website integration unaffected.

## 8. Dashboard (v1)

- `dashboard/` in the same repo (copies travel with the template). React + Vite + TypeScript, static build, deployable to S3/CloudFront or any static host; API base URL configurable.
- Auth: admin key entered on a gate screen, held in browser session only, sent on the header the existing `AdminApiKeyFilter` expects. Cognito/roles deferred.
- Screens: **Corpus** (docs list, upload, activate/deactivate, Sync-now + diff), **Settings** (models per purpose, thresholds, rerank toggle), **Test Console** (ask → answer, citations, confidence, escalation flag), **Audit** (browse/filter `ai_audit_logs`).
- Routing/components structured so read-only analytics (escalation rate, top questions, confidence trends) can be added later without rework; no analytics in v1.

## 9. Data flows

- **Boot:** `BRAIN_PACK` → load + validate pack → immutable `DomainPack` → services inject it. Bad pack = no boot.
- **Ask:** classify (pack keywords) → retrieve (pack acronyms/programs; thresholds from settings; reranker on UTILITY model) → build prompt (pack template, styled per source type) → router resolves ANSWER model → generate with provider fallback → refusal-coherence check → citation backfill → validate (pack phrases) → persist + audit. (Pipeline logic unchanged from today; only its inputs move.)
- **Sync:** dashboard → `POST /sync` → plan → execute → results → rendered diff.

## 10. Error handling

- Pack: fail-fast at boot, precise file+field in the message.
- Settings: validated on write; resolution chain settings → env default; provider call failure still triggers the existing fallback-provider path.
- Sync: per-file failure collection; failed re-ingest leaves the prior active document untouched.
- All existing runtime guardrails unchanged: reranker fails open, coherent refusals, citation backfill, escalation paths, rate limiting, PII redaction in audit.

## 11. Testing

- **Golden-pack regression (the safety net):** loading `packs/msfg-mortgage` must reproduce today's constants exactly; the existing test suite (107 tests) passes unchanged — proof the extraction altered nothing.
- Pack loader: invalid packs (missing file, empty phrase list, slug mismatch) fail boot with the right message.
- `RuntimeSettings`: fallback chain, cache refresh, validation rejections.
- Router: purpose resolution (ANSWER vs UTILITY), settings-over-env precedence, fallback unchanged.
- `SyncService`: plan diffing against stubbed S3 — add/update/skip/deactivate, idempotent re-run, per-file failure isolation.
- Dashboard: light in v1; API contract covered by server-side controller tests. Live smoke ritual (curl across phrasings on an alt port) remains the end-to-end check.

## 12. Build order

| Phase | Deliverable |
|---|---|
| ① Domain pack extraction | `DomainPack` + loader + `packs/msfg-mortgage/` + golden-pack tests; constants deleted from services; table rename + slug property ride along |
| ② Settings + runtime routing | `brain_settings`, `RuntimeSettings`, purpose-based router resolution, settings endpoints |
| ③ Sync in-app | `SyncService` + `POST /sync` + plan/results contract |
| ④ Dashboard v1 | Four screens over the admin API |
| ⑤ Company knowledge | Curated docs + manifest entries + per-source-type prompt style exercised end-to-end |
| ⑥ Retrieval precision (quality) | Numeric-fact gap: dense/tabular figures (e.g. FHA 580/500 minimums) — chunking/table handling so "minimum credit score for an FHA loan" answers grounded consistently |

Definition of done for the platform: a **new-company bootstrap drill** (copy deployment → new pack → new DB/S3 prefix → corpus load → smoke) executes with zero code edits, while MSFG's brain behaves identically to today (full suite + golden pack + live smoke).

## 13. Out of scope (v1)

Multi-tenant single service; Cognito/per-user roles; website crawling; scheduled or S3-event-driven sync; analytics views (shape-ready only); per-request model override on the public endpoint.

## 14. Known context

- Answer-model variance (`claude-haiku-4-5` inconsistently synthesizes on borderline context) is the main quality residual; decision #2's live model switching is the intended lever — strong model for ANSWER, cheap for UTILITY.
- The numeric-fact retrieval gap is real and corpus-independent (content exists; retrieval under-surfaces tabular figures) — addressed as phase ⑥, not blocking the platform work.
