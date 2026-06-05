# MSFG Mortgage Brain — Website Integration Contract

For the team/session building the public website chat widget.
The brain is a REST API; the website only needs two endpoints.

Base URL (local dev): `http://localhost:8080`
Base URL (production): TBD — will be `https://api.<domain>` behind Nginx on EC2.

---

## 1. Ask a question

```
POST /api/ai/mortgage/ask
Content-Type: application/json
```

Request body:

```json
{
  "sessionId": "any-stable-id-for-this-visitor",
  "conversationId": "uuid-from-previous-response-or-omit",
  "question": "Can I use gift funds for my down payment?",
  "loanType": "conventional",
  "state": "CO"
}
```

Field rules:
- `sessionId` (required, ≤255 chars): generate once per visitor (e.g. `crypto.randomUUID()`
  stored in `sessionStorage`). The same sessionId must be sent for follow-up questions.
- `conversationId` (optional): omit on first question; echo back the value from the
  previous response to continue the same conversation thread.
- `question` (required, ≤2000 chars).
- `loanType`, `state` (optional context hints).

Response (200):

```json
{
  "conversationId": "e5e48b02-...",
  "answer": "Gift funds may generally be used...",
  "citations": [
    {
      "source_name": "Fannie Mae Selling Guide",
      "document_name": "fannie mae sellers guide.pdf",
      "section": "B3-4.3-04 Personal Gifts",
      "page_number": "412",
      "effective_date": "2026-01-01"
    }
  ],
  "confidence": 0.8,
  "humanEscalationRequired": false,
  "disclaimer": "This answer is for general mortgage education only and is not a loan approval, underwriting decision, legal advice, or tax advice."
}
```

UI requirements (compliance — not optional):
- ALWAYS render the `disclaimer` with every answer.
- When `humanEscalationRequired` is true, show a prominent "Talk to a licensed
  loan officer" call-to-action alongside the answer.
- Render `citations` under the answer ("Sources: ..."). Fields may be null — skip
  null fields when formatting.
- Citation fields can contain newlines; sanitize for display.

Errors:
- `400` — validation problem, body `{"error": "question is required"}`. Show the message.
- `429` — rate limited (10 questions/minute per IP), body `{"error": "..."}`.
  Show "You're asking questions quickly — give it a few seconds."
- `500` — body `{"error": "..."}`. Show a generic retry message.

Latency: typically 3–10 seconds (retrieval + reranking + generation).
Show a typing/thinking indicator; do not set client timeouts below 60s.

---

## 2. Conversation history (optional)

```
GET /api/ai/conversations/{conversationId}
X-Session-Id: <the same sessionId used to create it>
```

Returns 404 if the conversationId doesn't exist OR belongs to a different session.

```json
{
  "id": "e5e48b02-...",
  "createdAt": "2026-06-05T13:55:00Z",
  "messages": [
    {"role": "user", "content": "...", "createdAt": "..."},
    {"role": "assistant", "content": "...", "createdAt": "..."}
  ]
}
```

---

## CORS

Allowed origins are configured server-side via `CORS_ALLOWED_ORIGINS`.
Local dev defaults: `http://localhost:3000`, `http://localhost:5173`.
Tell the brain team the website's dev/prod origins so they can be added.
Only `POST /api/ai/mortgage/**` and `GET /api/ai/conversations/**` are exposed
cross-origin. Admin endpoints are not, by design.

---

## Things the website must NOT do

- Do not call admin endpoints (`/api/ai/documents/**`) from the browser, ever.
- Do not embed any API keys in frontend code. The public endpoints need none.
- Do not rewrite, truncate, or paraphrase `answer`, `disclaimer`, or citations.
- Do not retry failed requests in a tight loop (the rate limiter will lock the visitor out).
- Do not implement your own mortgage answers as fallback when the API refuses —
  the refusal IS the correct, compliant behavior.
