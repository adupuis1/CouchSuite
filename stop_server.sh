#!/usr/bin/env bash
set -Eeuo pipefail

PID_FILE="/home/anatole/Dev/CouchSuite/couchsuite.pid"

if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE")"
  if ps -p "$PID" > /dev/null 2>&1; then
    kill -TERM "$PID" || true
    for i in $(seq 1 40); do
      if ! ps -p "$PID" > /dev/null 2>&1; then
        rm -f "$PID_FILE"
        echo "Stopped CouchSuite server (PID {"$PID"})."
        exit 0
      fi
      sleep 0.25
    end
    echo "Process did not stop cleanly; sending KILL..."
    kill -KILL "$PID" || true
  fi
  rm -f "$PID_FILE"
  echo "PID file removed."
  exit 0
else
  # Fallback: try to kill uvicorn on the specified port
  pkill -f "uvicorn server:app --host .* --port 8080" || true
  echo "No PID file; attempted best-effort stop."
  exit 0
fi