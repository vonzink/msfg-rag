# RAG Brain — Runbook

How to start, use, and stop the brain and its dashboard on this machine.
Everything runs locally; nothing here touches production.

## Get your admin key

The dashboard lock screen wants the admin API key from `.env`:

```sh
cd ~/MSFG/msfg-rag
grep ADMIN_API_KEY .env          # prints the line; the key is after the "="
```

Copy it to the clipboard without printing it:

```sh
grep ADMIN_API_KEY .env | cut -d= -f2- | pbcopy
```

Open the whole `.env` in TextEdit (to read or change any setting):

```sh
open -e .env
```

After changing `.env`, restart the brain (Ctrl+C in its terminal, run the
start command again) — env values are read at startup.

## Start everything (two terminal tabs)

**Tab 1 — the brain (API on port 8090):**

```sh
cd ~/MSFG/msfg-rag && set -a && source .env && set +a && ./gradlew bootRun --args='--server.port=8090'
```

Ready when the log prints `Started MsfgRagApplication` (~10 s). Leave running.

**Tab 2 — the dashboard (port 5173):**

```sh
cd ~/MSFG/msfg-rag/dashboard && npm run dev
```

Then open **http://localhost:5173** in a browser and unlock with the admin key.

## Stop everything

Press `Ctrl + C` in each terminal tab, or from anywhere:

```sh
lsof -ti:8090 -ti:5173 | xargs kill
```

## The five dashboard screens

| Screen | What it does |
|---|---|
| Corpus | Documents in the brain; **Sync now** / **Dry run** pull from S3 (`msfg.us/rag-brain/`); activate/deactivate/reindex |
| Settings | Switch AI models (answer lane / utility lane) and retrieval knobs — live in ~10 s, no restart |
| Test console | Ask the brain as if you were a website visitor, or "Retrieval only" to see which sources it found |
| Audit | Every question ever asked: confidence, model used, escalations; click a row for the full answer |
| Rules | **Your control panel for answers.** Hard rules (no wiggle room) and Strong recommendations (guidance). Edit → Save as new revision → live in ~10 s. Preview full prompt shows exactly what the AI is told. Revert to pack default any time; full history kept |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Brain won't start, database connection error | `docker start msfg-rag-postgres`, then start the brain again |
| "Port 8090 already in use" | An old copy is running: `lsof -ti:8090 \| xargs kill`, then start again |
| Dashboard says key rejected | Re-copy the key from `.env` (no spaces); the brain must be running first |
| Sync fails | AWS credentials: the same setup the `scripts/s3-ingest` tool uses must be available |
| Old process on port 8080 | That's the pre-platform brain — safe to kill: `lsof -ti:8080 \| xargs kill` |

## Corpus updates

Drop files into `s3://msfg.us/rag-brain/` (and list them in `_manifest.json`
if they need a title/source type or should be excluded with `ingest: false`),
then press **Sync now** on the Corpus screen. **Dry run** first shows what
would change without changing anything.
