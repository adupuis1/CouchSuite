#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="/home/anatole/Dev/CouchSuite/couchserver"
PID_FILE="/home/anatole/Dev/CouchSuite/couchsuite.pid"
LOG_FILE="/home/anatole/Dev/CouchSuite/server.log"
PORT="8080"
HOST="0.0.0.0"

mkdir -p "$(dirname "$PID_FILE")"
touch "$LOG_FILE"

# If already running, exit OK
if [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" > /dev/null 2>&1; then
  echo "CouchSuite server appears to be running (PID $(cat "$PID_FILE"))."
  exit 0
fi

cd "$PROJECT_DIR"

# Prefer local virtualenv if present
if [[ -f "/home/anatole/Dev/CouchSuite/.venv/bin/activate" ]]; then
  source "/home/anatole/Dev/CouchSuite/.venv/bin/activate"
fi

# Start uvicorn in background
nohup uvicorn server:app --host "$HOST" --port "$PORT" --reload > "$LOG_FILE" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
echo "Started CouchSuite server (PID $PID)."

# Simple health check loop (max ~20s)
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:8080/health" > /dev/null 2>&1; then
    echo "Health check OK."
    exit 0
  fi
  sleep 0.5
done

echo "Warning: health check failed; server may still be starting. Check $LOG_FILE"
exit 0