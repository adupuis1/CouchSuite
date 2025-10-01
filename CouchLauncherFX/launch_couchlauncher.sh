#!/usr/bin/env bash
# Launch the CouchLauncherFX JavaFX client using the same sequence proven manually.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLEW="${SCRIPT_DIR}/gradlew"
if [ ! -x "${GRADLEW}" ]; then
    echo "gradlew not found in ${SCRIPT_DIR}" >&2
    exit 1
fi

cd "${SCRIPT_DIR}"
./gradlew --no-daemon clean build
exec ./gradlew --no-daemon run "$@"
