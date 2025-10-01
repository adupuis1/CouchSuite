#!/usr/bin/env bash
# Launch the CouchServer Manager GUI with the local virtual environment when available.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${SCRIPT_DIR}/.venv"

if [ -x "${VENV_DIR}/bin/python" ]; then
    PYTHON_BIN="${VENV_DIR}/bin/python"
else
    if command -v python3 >/dev/null 2>&1; then
        PYTHON_BIN="$(command -v python3)"
    else
        echo "python3 not found on PATH" >&2
        exit 1
    fi
fi

exec "${PYTHON_BIN}" "${SCRIPT_DIR}/manager_gui.py"
