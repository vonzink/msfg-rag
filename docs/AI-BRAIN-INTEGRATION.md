# MSFG Mortgage RAG Brain — Integration & Status (for msfg-suite)

**What it is:** the AI service that powers mortgage Q&A on the Mountain State Financial Group site.
It answers **only** from approved, retrieved guideline content — with citations, compliance
guardrails, audit logging, and (new) a control plane that returns a recommended page, trusted
source links, and a suggested next action.

**Status:** ✅ Production-ready. Base RAG pipeline + the full control plane (Phases 3–8) are built,
tested, and merged to `main` (`86bce7f`). Backward-compatible — the new fields are optional and
absent unless you opt in.

**Repo:** `github.com/vonzink/msfg-rag` · **Stack:** Java 21 · Spring Boot 3.5 · Spring AI 1.1 ·
PostgreSQL 16 + pgvector · Flyway (V1–V9) · Gradle.

---

## 1. The one endpoint msfg-suite calls

```
POST  http://<brain-host>:8090/api/ai/mortgage/ask
Content-Type: application/json
```

- **Port `8090`** (NOT 8080 — `8080` collides with msfg-suite; the brain is run with
  `--server.port=8090` / `SERVER_PORT=8090`). The README's `8080` curl examples are stale.
- Path segment `mortgage` = the brain slug (`BRAIN_SLUG`, default `mortgage`).
- **Public, no auth header**, but **rate-limited to 10 requests/min per session/IP**.

### Request body

| Field | Type | Req? | Notes |
|---|---|---|---|
| `sessionId` | string ≤255 | **yes** | your site session id |
| `question` | string ≤2000 | **yes** | the user's question |
| `conversationId` | uuid | no | pass back the value from a prior response to continue a thread |
| `loanType` | string ≤50 | no | e.g. `"conventional"` |
| `state` | string (2) | no | e.g. `"CO"` |
| `pageRoute` | string ≤200 | no | **control plane:** the page the visitor is on, e.g. `"/loan-options"` |
| `surface` | string ≤20 | no | **control plane:** `"PUBLIC"` \| `"INTERNAL"` \| `"BOTH"` — use `"PUBLIC"` for the public site (bad value → HTTP 400) |

### Response body

```jsonc
{
  "conversationId": "uuid",            // persist + send back to continue the thread
  "answer": "string",                  // the generated, source-grounded answer
  "citations": [                       // sources the answer is grounded in
    { "source_name": "...", "document_name": "...", "section": "...",
      "page_number": "...", "effective_date": "..." }
  ],
  "confidence": 0.0,                   // 0–1; below threshold the brain refuses + escalates
  "humanEscalationRequired": false,    // true → show "talk to a loan officer"
  "disclaimer": "string",              // education-only compliance disclaimer — always display

  // --- Control-plane fields (Phase 8). Present only when relevant; else null/[] ---
  "recommendedPage": { "route": "/loans/fha", "label": "FHA Loans" } | null,
  "links": [                           // trusted external sources, authority-ordered
    { "name": "HUD Handbook 4000.1", "url": "https://...", "authority": "PRIMARY" }
  ],
  "nextAction": "string" | null        // a suggested next step for the visitor
}
```

**How msfg-suite uses the control-plane fields:**
- Send `pageRoute` (the current site route) and `surface: "PUBLIC"` on every ask.
- If `recommendedPage` is non-null → render a "See also" / CTA to `recommendedPage.route` with `recommendedPage.label`.
- Render `links[]` as a trusted-sources list (each is a real, curated registry row — `authority` is `PRIMARY` > `SECONDARY` > `BACKGROUND`, already ordered).
- If `nextAction` is non-null → surface it as a suggested next step.
- These are **server-curated and safe to render directly** — links always resolve to approved registry entries; the LLM does not invent them.

### Minimal example

```bash
curl -X POST http://localhost:8090/api/ai/mortgage/ask \
  -H "Content-Type: application/json" \
  -d '{ "sessionId":"web-123", "question":"What are the FHA down payment requirements?",
        "pageRoute":"/loans/fha", "surface":"PUBLIC" }'
```

---

## 2. Behavior & guardrails (what to expect)

Request flow on every ask:

```
classify (compliance guardrail) → intent route → hybrid retrieval (pgvector + full-text)
  → [control plane: collect matching page-guides + links, authority-ordered]
  → locked compliance prompt → model (Claude default, OpenAI fallback)
  → answer validation (prohibited-phrase + citation gate) → output contract → audit log
```

**The brain refuses / escalates (never guesses) when:**
- The question is **fraud** (refused outright), or **eligibility / legal / tax / live-rates**
  (intercepted *before* the model → canned escalation to a licensed loan officer).
- Retrieval **confidence < 0.35** → refusal + loan-officer referral.
- These come back with `humanEscalationRequired: true` and **no** `recommendedPage`/`links`/`nextAction`.

**Compliance invariants (do not bypass on the front end):**
- Answers are generated **only** from retrieved approved content; always show `citations` + `disclaimer`.
- Approval/guarantee language ("you qualify", "guaranteed") is blocked server-side.
- Every interaction is audit-logged (retrieved chunks + scores, PII-redacted).

---

## 3. Other endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/ai/mortgage/ask` | public (rate-limited) | **the ask endpoint above** |
| GET | `/api/ai/conversations/{id}` | `X-Session-Id` header | fetch chat history |
| — admin / curation (all require `X-Admin-Api-Key`) — | | | |
| * | `/api/ai/documents` (+ `/{id}/reindex|activate|deactivate`, `/sync`, `/test-retrieval`) | `X-Admin-Api-Key` | corpus (guideline docs) CRUD + reindex |
| * | `/api/ai/admin/source-links` | `X-Admin-Api-Key` | **Link Registry** — trusted external sources behind `links[]` |
| * | `/api/ai/admin/page-guides` | `X-Admin-Api-Key` | **Page Guides** — per-route guidance behind `recommendedPage`/`nextAction` |
| GET/PUT | `/api/ai/admin/rules`, `/api/ai/admin/vocabulary`, `/api/ai/admin/settings` | `X-Admin-Api-Key` | prompt rules, query-expansion synonyms, runtime knobs |
| GET | `/api/ai/admin/stats`, `/api/ai/admin/audit` | `X-Admin-Api-Key` | health/counts + audit trail |

> Admin endpoints (`/api/ai/admin/**` and `/api/ai/documents`) are gated by the static
> `X-Admin-Api-Key` header. msfg-suite's public site only needs the **public** `/ask` endpoint;
> curation is done by MSFG admins (via these endpoints or the bundled admin dashboard on `:5173`).

---

## 4. What drives the AI's answers (knowledge sources)

The brain is curated, not free-form. Its behavior is governed by:
- **Corpus** — uploaded guideline documents (Fannie/Freddie/HUD/VA/USDA, internal policy), chunked + embedded in Postgres/pgvector. Source of `answer` + `citations`.
- **Link Registry** (`brain_source_links`) — approved external sources with authority tiers. Source of `links[]`.
- **Page Guides** (`brain_page_guides`) — per-route guidance + intents. Source of `recommendedPage` + `nextAction`.
- **Rules / Vocabulary / Settings** — the locked compliance prompt, query-expansion synonyms, and runtime knobs (all editable without redeploy).

Seeded defaults exist (5 agency source links, 3 page guides: FHA / conventional / duplex) and are
curated from the admin surface above.

---

## 5. Operational facts

| | |
|---|---|
| **Listen port** | **8090** (config default 8080 is overridden to avoid the msfg-suite collision) |
| **Admin dashboard** | `:5173` (React/Vite admin UI — corpus, rules, vocab, settings, source-links, page-guides, audit, test console) |
| **Models** | default **Anthropic Claude** (`claude-haiku-4-5`), fallback **OpenAI** (`gpt-4.1-nano`); embeddings `text-embedding-3-small`. Overridable via env. |
| **Database** | PostgreSQL 16 + pgvector; schema auto-migrated by Flyway on boot (V1–V9) |
| **Retrieval knobs** | `top-k=8`, `confidence-threshold=0.35`, hybrid weights vector `0.65` / keyword `0.35` (env-tunable) |
| **Rate limit** | 10 req/min per session/IP on `/ask` |
| **Secrets** | `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `ADMIN_API_KEY` (from `.env`; AWS → Secrets Manager) |
| **AWS path (future)** | RDS (pgvector), S3 storage, Cognito for admin auth, behind Nginx per MSFG standards |

---

## 6. Integration checklist for msfg-suite

1. Point the site's AI calls at `http://<brain-host>:8090/api/ai/mortgage/ask` (**8090, not 8080**).
2. On each ask, send `sessionId`, `question`, `pageRoute` (current route), `surface: "PUBLIC"`.
3. Always render `answer` + `citations` + `disclaimer`.
4. If `humanEscalationRequired` → show the loan-officer hand-off; do **not** render recommendations.
5. Otherwise, optionally render `recommendedPage` (CTA), `links[]` (trusted sources), `nextAction`.
6. Persist `conversationId` and pass it back to continue a thread.
7. Respect the 10 req/min/session rate limit (HTTP 429 on exceed).

**Deferred / not yet built** (so msfg-suite doesn't assume them): authority-weighted *corpus*
reranking (answers are corpus-grounded, links/guides don't reshape the answer text); semantic
embeddings for page-guides/links (matching is route + topic today); click/correction telemetry;
multi-tenancy. None affect the contract above.
