# MSFG Mortgage RAG Brain

Source-grounded mortgage Q&A backend for the Mountain State Financial Group public website.
The bot retrieves approved guideline content first, then generates an answer **only** from
that retrieved context — with citations, audit logging, and compliance guardrails.

## Architecture

```
User Question
    → POST /api/ai/mortgage/ask
    → QuestionClassifierService (pre-retrieval guardrail: eligibility/legal/tax/rates → escalate, fraud → refuse)
    → RetrievalService (hybrid: pgvector cosine + Postgres full-text + metadata filters)
    → PromptBuilderService (locked compliance template)
    → ModelRouterService (Anthropic Claude default, OpenAI fallback)
    → AnswerValidationService (prohibited-phrase + citation gate)
    → AuditLogService (full trail, PII-redacted)
    → JSON answer with citations + disclaimer
```

**Stack:** Java 21 · Spring Boot 3.5 · Spring AI 1.1 · PostgreSQL 16 + pgvector · Flyway · Gradle

## Local setup

Prereqs: JDK 21, Docker Desktop.

```bash
# 1. Start Postgres with pgvector (local terminal, project root)
docker compose up -d

# 2. Configure secrets
cp .env.example .env
#    → fill in ANTHROPIC_API_KEY and OPENAI_API_KEY
#    (Claude answers questions; OpenAI generates embeddings)

# 3. Run the app (loads .env vars; or use your IDE's env file support)
set -a && source .env && set +a
./gradlew bootRun
```

Flyway creates the schema automatically on first start.

## Loading guideline documents

Upload via the admin API (PDF, DOCX, TXT, Markdown, or HTML):

```bash
curl -X POST http://localhost:8080/api/ai/documents/upload \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -F "file=@selling-guide-b3-3.pdf" \
  -F "title=Fannie Mae Selling Guide B3-3 Income" \
  -F "sourceName=Fannie Mae Selling Guide" \
  -F "sourceType=AGENCY_GUIDELINE" \
  -F "documentVersion=2026.01" \
  -F "effectiveDate=2026-01-01"
```

`sourceType` values: `AGENCY_GUIDELINE`, `INTERNAL_POLICY`, `INVESTOR_OVERLAY`, `EDUCATIONAL`.

## Asking a question

```bash
curl -X POST http://localhost:8080/api/ai/mortgage/ask \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "website-session-123",
    "question": "Can I use gift funds for my down payment?",
    "loanType": "conventional",
    "state": "CO"
  }'
```

Response includes `answer`, `citations[]`, `confidence`, `humanEscalationRequired`,
and the compliance `disclaimer`. If retrieval confidence is below threshold, the bot
refuses and recommends a licensed loan officer — it never answers from general knowledge.

## API reference

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/ai/mortgage/ask` | public (rate-limited) | Ask a question |
| GET  | `/api/ai/conversations/{id}` | `X-Session-Id` header | Chat history |
| POST | `/api/ai/documents/upload` | `X-Admin-Api-Key` | Upload guideline doc |
| GET  | `/api/ai/documents` | `X-Admin-Api-Key` | List documents |
| POST | `/api/ai/documents/{id}/reindex` | `X-Admin-Api-Key` | Re-chunk + re-embed |
| POST | `/api/ai/documents/{id}/activate` | `X-Admin-Api-Key` | Activate |
| POST | `/api/ai/documents/{id}/deactivate` | `X-Admin-Api-Key` | Deactivate |
| GET  | `/api/ai/documents/test-retrieval?question=...` | `X-Admin-Api-Key` | Preview retrieved chunks |

## Tests

```bash
./gradlew test          # unit tests
                        # integration tests use Testcontainers (Docker required)
```

The compliance tests in `AnswerValidationServiceTest` encode the public-website safety
rules (no "you qualify", "guaranteed", etc.). If one fails after a change, fix the change,
not the test.

## Configuration knobs (application.yml → msfg.rag.*)

| Key | Default | Meaning |
|-----|---------|---------|
| `retrieval.confidence-threshold` | 0.55 | Below this → refuse + escalate |
| `retrieval.vector-weight` / `keyword-weight` | 0.65 / 0.35 | Hybrid score mix |
| `retrieval.top-k` | 8 | Chunks sent to the model |
| `chunking.target-tokens` | 1000 | Chunk size target (max 1200, overlap 150) |
| `rate-limit.requests-per-minute` | 10 | Public ask endpoint, per client |

## AWS deployment notes (later)

- API keys move from `.env` to **Secrets Manager**
- `LocalStorageService` → S3 implementation (same `StorageService` interface)
- Postgres → **RDS** (pgvector is supported natively)
- Admin API key auth → **Cognito** JWT
- Logs → CloudWatch; app behind Nginx on EC2 per MSFG standards

## Design rules that must not regress

1. The model answers **only** from retrieved, approved source context.
2. Low-confidence retrieval → refusal with loan-officer referral, never a guess.
3. Every answer carries citations and the education-only disclaimer.
4. Prohibited approval/guarantee language is blocked by `AnswerValidationService`.
5. Every interaction is audit-logged with retrieved chunks and scores (PII-redacted).
6. "Do I qualify?", legal, tax, rate, and fraud questions are intercepted by
   `QuestionClassifierService` before retrieval — they never reach the LLM.
