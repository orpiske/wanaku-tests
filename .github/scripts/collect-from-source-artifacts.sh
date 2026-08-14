#!/bin/bash
#
# Collects build outputs from source checkouts into the artifacts/ directory
# in the layout expected by the test framework (TestConfiguration).
#
# Expected source layout (created by the CI workflow):
#   source/camel-integration-capability/ -> camel-integration-capability-main fat JAR
#

set -euo pipefail

ARTIFACTS_DIR="$(pwd)/artifacts"
SOURCE_DIR="$(pwd)/source"

mkdir -p "$ARTIFACTS_DIR"

# Camel Integration Capability (fat JAR from the -main module, not the -plugin module)
CIC_JAR=$(find "$SOURCE_DIR/camel-integration-capability" \
    -path "*/camel-integration-capability-main/target/*" \
    -name "*-jar-with-dependencies.jar" \
    2>/dev/null | head -1)

if [ -n "$CIC_JAR" ]; then
    echo "Collecting CIC from ${CIC_JAR}"
    mkdir -p "${ARTIFACTS_DIR}/camel-integration-capability"
    cp "$CIC_JAR" "${ARTIFACTS_DIR}/camel-integration-capability/"
else
    echo "::warning::Could not find CIC fat JAR"
fi

echo ""
echo "Collected artifacts:"
find "$ARTIFACTS_DIR" -maxdepth 2 -type f -name "*.jar" | sort
