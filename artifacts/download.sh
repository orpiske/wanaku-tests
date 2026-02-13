#!/bin/bash
#
# Downloads pre-built Wanaku artifacts from GitHub releases.
# Usage: ./artifacts/download.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROUTER_URL="https://github.com/wanaku-ai/wanaku/releases/download/early-access/wanaku-router-backend-0.1.0-SNAPSHOT.zip"
HTTP_URL="https://github.com/wanaku-ai/wanaku/releases/download/early-access/wanaku-tool-service-http-0.1.0-SNAPSHOT.zip"

download_and_extract() {
    local url="$1"
    local name="$2"
    local zip_file="${SCRIPT_DIR}/${name}.zip"

    echo "Downloading ${name}..."
    curl -fSL -o "${zip_file}" "${url}"

    echo "Extracting ${name}..."
    unzip -o -d "${SCRIPT_DIR}" "${zip_file}"

    rm -f "${zip_file}"
    echo "${name} ready."
}

download_and_extract "${ROUTER_URL}" "wanaku-router-backend"
download_and_extract "${HTTP_URL}" "wanaku-tool-service-http"

echo ""
echo "All artifacts downloaded to ${SCRIPT_DIR}"
