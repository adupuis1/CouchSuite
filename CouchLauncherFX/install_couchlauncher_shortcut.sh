#!/usr/bin/env bash
# Install a desktop shortcut for the CouchLauncherFX client.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LAUNCHER_PATH="${SCRIPT_DIR}/launch_couchlauncher.sh"
ICON_PATH="${PROJECT_ROOT}/icons/couchsuite-status.svg"
DESKTOP_DIR="${HOME}/Desktop"
DESKTOP_FILE="${DESKTOP_DIR}/CouchLauncher.desktop"

if [ ! -x "${LAUNCHER_PATH}" ]; then
    echo "Launcher helper not found or not executable at ${LAUNCHER_PATH}" >&2
    exit 1
fi

if [ ! -f "${ICON_PATH}" ]; then
    echo "Icon not found at ${ICON_PATH}" >&2
    exit 1
fi

mkdir -p "${DESKTOP_DIR}"

cat > "${DESKTOP_FILE}" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=CouchLauncherFX
Comment=Start the CouchSuite JavaFX launcher
Exec="${LAUNCHER_PATH}"
Path="${SCRIPT_DIR}"
Terminal=false
Icon=${ICON_PATH}
Categories=Game;Utility;
DESKTOP

chmod +x "${DESKTOP_FILE}"

echo "Launcher written to ${DESKTOP_FILE}"

if command -v gio >/dev/null 2>&1; then
    gio set "${DESKTOP_FILE}" "metadata::trusted" true >/dev/null 2>&1 || true
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${DESKTOP_DIR}" >/dev/null 2>&1 || true
fi

echo "Done. Double-click the desktop icon to launch CouchLauncherFX."
