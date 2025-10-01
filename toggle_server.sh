#!/usr/bin/env bash
set -Eeuo pipefail

PID_FILE="/home/anatole/Dev/CouchSuite/couchsuite.pid"
ICON_DIR="/home/anatole/Dev/CouchSuite/icons"
STATUS_ICON="/home/anatole/Dev/CouchSuite/icons/couchsuite-status.svg"
GREEN_ICON="/home/anatole/Dev/CouchSuite/icons/green.svg"
RED_ICON="/home/anatole/Dev/CouchSuite/icons/red.svg"

# helper: swap icon to indicate current state
set_icon() {
  local color="$1"  # "green" or "red"
  if [[ "$color" == "green" ]]; then
    cp -f "$GREEN_ICON" "$STATUS_ICON"
  else
    cp -f "$RED_ICON" "$STATUS_ICON"
  fi
}

running=0
if [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" > /dev/null 2>&1; then
  running=1
fi

if [[ "$running" -eq 1 ]]; then
  notify-send "CouchSuite" "Stopping server..." || true
  "/home/anatole/Dev/CouchSuite/stop_server.sh" || true
  set_icon "green"
  notify-send "CouchSuite" "Server stopped." || true
  echo "stopped"
else
  notify-send "CouchSuite" "Starting server..." || true
  "/home/anatole/Dev/CouchSuite/start_server.sh" || true
  set_icon "red"
  notify-send "CouchSuite" "Server running." || true
  echo "started"
fi