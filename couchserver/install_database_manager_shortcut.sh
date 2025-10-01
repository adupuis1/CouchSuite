#!/usr/bin/env bash
# Create a desktop launcher for the CouchServer Manager GUI

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MANAGER_PATH="${SCRIPT_DIR}/manager_gui.py"
LAUNCHER_PATH="${SCRIPT_DIR}/launch_manager_gui.sh"
ICON_PATH="${PROJECT_ROOT}/icons/couchsuite-status.svg"
DESKTOP_DIR="${HOME}/Desktop"
DESKTOP_FILE="${DESKTOP_DIR}/DatabaseServerController.desktop"

if [ ! -f "${MANAGER_PATH}" ]; then
    echo "Manager GUI not found at ${MANAGER_PATH}" >&2
    exit 1
fi

if [ ! -x "${LAUNCHER_PATH}" ]; then
    echo "Launcher helper not found or not executable at ${LAUNCHER_PATH}" >&2
    exit 1
fi

mkdir -p "${DESKTOP_DIR}"

cat > "${DESKTOP_FILE}" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=CouchSuite Server Controller
Comment=Start, stop, and inspect the CouchSuite FastAPI server and database
Exec="${LAUNCHER_PATH}"
Path="${SCRIPT_DIR}"
Terminal=false
Icon=${ICON_PATH}
Categories=Utility;
DESKTOP

chmod +x "${DESKTOP_FILE}"

echo "Launcher written to ${DESKTOP_FILE}"

if command -v gio >/dev/null 2>&1; then
    gio set "${DESKTOP_FILE}" "metadata::trusted" true >/dev/null 2>&1 || true
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${DESKTOP_DIR}" >/dev/null 2>&1 || true
fi

echo "Done. Double-click the desktop icon to launch the controller."
