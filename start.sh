#!/usr/bin/env bash
# Start the RAG brain + dashboard and open it in the browser.
# Idempotent — re-run anytime; it skips whatever is already up.
#   Run with:  ~/MSFG/msfg-rag/start.sh
# Stop everything later:  lsof -ti:8090 -ti:5173 | xargs kill

REPO="$HOME/MSFG/msfg-rag"
cd "$REPO" || { echo "✗ repo not found at $REPO"; exit 1; }

echo "→ database"
docker compose up -d postgres >/dev/null 2>&1 \
  || { echo "  ✗ couldn't start Postgres — is Docker Desktop running?"; exit 1; }

echo "→ brain (port 8090)"
if curl -sf -m2 http://localhost:8090/actuator/health >/dev/null 2>&1; then
  echo "  already running ✓"
else
  echo "  starting (first boot ~15s)…"
  ( set -a; source .env; set +a; nohup ./gradlew bootRun --args='--server.port=8090' >/tmp/brain_api.log 2>&1 & )
  printf "  waiting"
  ok=""
  for _ in $(seq 1 90); do
    if curl -sf -m2 http://localhost:8090/actuator/health >/dev/null 2>&1; then ok=1; break; fi
    printf "."; sleep 2
  done
  echo
  if [ -z "$ok" ]; then
    echo "  ✗ brain failed to start — last log lines:"; tail -n 15 /tmp/brain_api.log; exit 1
  fi
  echo "  up ✓"
fi

echo "→ dashboard (port 5173)"
if lsof -ti:5173 >/dev/null 2>&1; then
  echo "  already running ✓"
else
  ( cd dashboard && { [ -d node_modules ] || npm install; } && nohup npm run dev >/tmp/brain_dash.log 2>&1 & )
  sleep 4
  echo "  up ✓"
fi

open http://localhost:5173
echo
echo "✓ dashboard open → http://localhost:5173"
echo "  unlock with your ADMIN_API_KEY (in .env)"
echo "  copy the key:  grep ADMIN_API_KEY ~/MSFG/msfg-rag/.env | cut -d= -f2- | pbcopy"
echo "  stop later:    lsof -ti:8090 -ti:5173 | xargs kill"
