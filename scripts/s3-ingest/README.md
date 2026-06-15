# S3 → Mortgage Brain ingestion bridge

Syncs the curated corpus in `s3://msfg.us/rag-brain/` into the brain's vector store
through its admin API (`POST /api/ai/documents/upload`). **Idempotent by `fileName`** —
safe to re-run. This is the missing link: dropping a file in the S3 folder does nothing
until this runs.

## What it does

1. Lists objects under `rag-brain/` (excluding `_manifest.json` and folders).
2. Loads `_manifest.json` from S3 (falls back to the local `manifest.json`) for per-file
   metadata + an `ingest` flag.
3. Diffs against the brain's current docs (`GET /api/ai/documents`) and:
   - **uploads** new files,
   - **skips** already-ingested files,
   - **skips** files marked `ingest:false` or with unsupported extensions,
   - **deactivates** brain docs no longer in the corpus.

## Run

```bash
# from the brain repo root, load ADMIN_API_KEY (and any other env):
set -a && source .env && set +a

cd scripts/s3-ingest
npm install
node sync.mjs --dry-run     # preview the plan, change nothing
node sync.mjs               # apply
npm test                    # unit tests for the planning logic
```

Point at a deployed brain with `--base-url https://api.example.com` (or `BRAIN_BASE_URL`).

## Config (env or flags)

| Setting | Env | Flag | Default |
|---|---|---|---|
| Brain base URL | `BRAIN_BASE_URL` | `--base-url` | `http://localhost:8080` |
| Bucket | `S3_BUCKET` | `--bucket` | `msfg.us` |
| Prefix | `S3_PREFIX` | `--prefix` | `rag-brain/` |
| Region | `AWS_REGION` | — | `us-west-1` |
| Admin key | `ADMIN_API_KEY` | — | (required) |

AWS credentials come from the default chain (env vars or `~/.aws`).

## Editing the corpus

1. Add/replace a file in `s3://msfg.us/rag-brain/`.
2. Add its entry to `_manifest.json` (`title`, `sourceName`, `sourceType`, optional
   `documentVersion`/`effectiveDate`, or `ingest:false`). `sourceType` ∈
   `AGENCY_GUIDELINE | INTERNAL_POLICY | INVESTOR_OVERLAY | EDUCATIONAL`.
3. Re-run `node sync.mjs`.

The manifest is the source of truth for metadata; keep the S3 copy (`rag-brain/_manifest.json`)
in sync with the local one (upload with `aws s3 cp manifest.json s3://msfg.us/rag-brain/_manifest.json`).

## Known limits (v1)

- A changed file with the **same name** is treated as already-ingested (no content-hash
  diff yet). To refresh it: deactivate/delete it in the brain, then re-run — or call the
  brain's `POST /api/ai/documents/{id}/reindex`.
- `xlsx` and images are unsupported by the brain (no OCR) — convert to markdown.
- Productionized path: implement S3 reads inside the brain (`S3StorageService`) plus a
  scan endpoint, so the brain pulls from S3 directly instead of this external push.
