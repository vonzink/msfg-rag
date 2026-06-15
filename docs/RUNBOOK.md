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

## Start everything (one shot)

Paste this single line into a terminal. It brings up the database, starts the
brain (waits until it's healthy), starts the dashboard, and opens the browser —
skipping anything already running. Leave the terminal open; the servers run in
the background.

```sh
cd ~/MSFG/msfg-rag && docker compose up -d postgres >/dev/null 2>&1; curl -sf -m2 http://localhost:8090/actuator/health >/dev/null 2>&1 && echo "✓ brain already running" || { echo "→ starting brain (first boot ~15s)…"; ( set -a; source .env; set +a; nohup ./gradlew bootRun --args='--server.port=8090' >/tmp/brain_api.log 2>&1 & ); }; printf "→ waiting for brain"; ok=""; for i in $(seq 1 60); do curl -sf -m2 http://localhost:8090/actuator/health >/dev/null 2>&1 && { ok=1; break; }; printf "."; sleep 2; done; echo; if [ -z "$ok" ]; then echo "✗ brain failed to start — last log lines:"; tail -n 15 /tmp/brain_api.log; else lsof -ti:5173 >/dev/null 2>&1 && echo "✓ dashboard already running" || { echo "→ starting dashboard…"; ( cd dashboard && { [ -d node_modules ] || npm install; } && nohup npm run dev >/tmp/brain_dash.log 2>&1 & ); sleep 4; }; open http://localhost:5173; echo "✓ open http://localhost:5173 — unlock with ADMIN_API_KEY from .env"; fi
```

Unlock with the admin key (`grep ADMIN_API_KEY .env | cut -d= -f2- | pbcopy`
copies it). To stop everything later: `lsof -ti:8090 -ti:5173 | xargs kill`.

## Start everything (two terminal tabs)

If you prefer to watch the logs live, run each in its own tab instead.

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
| Brain won't start, database connection error | `docker compose up -d postgres` (recreates the DB container from docker-compose.yml, data volume intact), then start the brain again |
| "Port 8090 already in use" | An old copy is running: `lsof -ti:8090 \| xargs kill`, then start again |
| Dashboard says key rejected | Re-copy the key from `.env` (no spaces); the brain must be running first |
| Sync fails | AWS credentials: the same setup the `scripts/s3-ingest` tool uses must be available |
| Old process on port 8080 | That's the pre-platform brain — safe to kill: `lsof -ti:8080 \| xargs kill` |

## Adding an AI provider

Three extra providers ship in the code — DeepSeek, Gemini, and Grok — but each
one only activates when its API key is present. Without a key the provider is
invisible to the dashboard.

**To activate a provider:**

1. Open `.env` in TextEdit:

   ```sh
   open -e ~/MSFG/msfg-rag/.env
   ```

2. Find the matching `..._API_KEY=` line and paste your key directly after the
   `=` (no spaces). For example:

   ```
   DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
   ```

3. Save the file and restart the brain (Ctrl+C in its terminal, then run the
   start command again).

4. The provider now appears in the **Settings** screen — pick it from the
   answer-lane or utility-lane dropdown and save.

**Before trusting a new provider with customer answers:** try it on the
**UTILITY** lane (reranker) first. Gemini and Grok are wired and ready but have
not been verified with a real key — routing borrower questions through them
before a quick sanity-check on the utility lane is a bigger risk than it looks.

**Data-handling reminder:** routing borrower questions to any new vendor means
that data leaves Anthropic/OpenAI — confirm the vendor's data-handling terms
are acceptable before enabling a new provider on the answer lane in production.

## Corpus updates

Drop files into `s3://msfg.us/rag-brain/` (and list them in `_manifest.json`
if they need a title/source type or should be excluded with `ingest: false`),
then press **Sync now** on the Corpus screen. **Dry run** first shows what
would change without changing anything.
