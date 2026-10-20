# Test Plan: Camel Qdrant Vector Search Service Template

## Overview

This test plan verifies the built-in `camel-qdrant-search-tool` service template (issue #1182). It covers the
template source, the listing and the instantiation in Wanaku Barn, the tool registration in Wanaku Server through a
Camel Integration Capability (CIC) runtime, and an end-to-end MCP tool call against a local Qdrant vector database.

The template turns into an MCP tool in this order:

```text
camel-qdrant-search-tool template (Barn) --instantiate--> catalog (Barn) --CIC downloads--> CIC runtime :9190/mcp
    --forward--> Wanaku Server --MCP--> ${WANAKU_MCP_BASE_URL}/default/mcp --> Qdrant gRPC :6334
```

### Components under test

| Component | Needed for | Common procedure |
|-----------|------------|------------------|
| Wanaku Barn | Phases 2 to 4 (templates and catalogs) | [common/start-barn.md](common/start-barn.md) |
| Wanaku Server | Phases 5 and 6 (tool registration and MCP call) | [common/start-server.md](common/start-server.md) |
| CIC runtime (container) | Phases 5 and 6 | [common/start-cic.md](common/start-cic.md) |
| Qdrant (container or Qdrant Cloud) | Phase 6 only | Phase 0 of this plan |

The shared template checks are in [common/service-template-checks.md](common/service-template-checks.md). This plan
adds the checks that are specific to the Qdrant template.

> [!IMPORTANT]
> Qdrant similarity search needs a query vector. The template route sends the message body to Qdrant without an
> embedding step, and the tool parameter `query` is a string. Test 6.2 records whether the route returns search
> results. Until the template converts the query into a vector, expect Test 6.2 to fail. Test 6.1 checks the
> plumbing (Wanaku Server, CIC and Qdrant connection) independently of the search result.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java --version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |
| `unzip` | any | `unzip -v` |
| `docker` or `podman` | 20+ | `docker --version` or `podman --version` |

Run all commands from the root of this repository. Call the CLI as `java -jar "${CLI_JAR}"`.

### Environment variables

```bash
export TEMPLATE_NAME="camel-qdrant-search-tool"
export TEMPLATE_SYSTEM="qdrant-search"
export TEMPLATE_TOOL="qdrant-similarity-search"
export TEMPLATE_TOOL_PARAMS="query"
export TEMPLATE_SEARCH="qdrant"
export TEMPLATE_CATALOG="${TEMPLATE_CATALOG:-qdrant-search-test}"
export TEMPLATE_PROPS_FILE="${TEMPLATE_PROPS_FILE:-$(mktemp)}"

export QDRANT_IMAGE="${QDRANT_IMAGE:-docker.io/qdrant/qdrant:latest}"
export QDRANT_CONTAINER="${QDRANT_CONTAINER:-qdrant-test}"
export QDRANT_HOST="${QDRANT_HOST:-localhost}"
export QDRANT_GRPC_PORT="${QDRANT_GRPC_PORT:-6334}"
export QDRANT_REST_PORT="${QDRANT_REST_PORT:-6333}"
export QDRANT_API_KEY="${QDRANT_API_KEY:-}"
export QDRANT_COLLECTION="${QDRANT_COLLECTION:-wanaku-test-collection}"
export QDRANT_VECTOR_DIMENSION="${QDRANT_VECTOR_DIMENSION:-4}"

export CIC_NAME="${CIC_NAME:-cic-qdrant}"
export CIC_NAMESPACE="${CIC_NAMESPACE:-default}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${WANAKU_TEST_GOVERNED_NAMESPACES:-${CIC_NAMESPACE}}"
```

| Variable | Default | Description |
|----------|---------|-------------|
| `TEMPLATE_CATALOG` | `qdrant-search-test` | Name of the catalog that the instantiation creates |
| `TEMPLATE_PROPS_FILE` | `$(mktemp)` | Properties file for the instantiation (written in Phase 4) |
| `QDRANT_IMAGE` | `docker.io/qdrant/qdrant:latest` | Qdrant container image |
| `QDRANT_HOST` | `localhost` | Qdrant host, as the CIC container sees it |
| `QDRANT_GRPC_PORT` | `6334` | Qdrant gRPC port (used by `camel-qdrant`) |
| `QDRANT_REST_PORT` | `6333` | Qdrant REST port (used to seed the test data) |
| `QDRANT_API_KEY` | _(empty)_ | Qdrant API key. Empty for a local container. |
| `QDRANT_COLLECTION` | `wanaku-test-collection` | Test collection |
| `QDRANT_VECTOR_DIMENSION` | `4` | Vector size of the test collection |
| `CIC_NAME` | `cic-qdrant` | CIC container name and forward name |
| `CIC_NAMESPACE` | `default` | Wanaku namespace of the forward |
| `WANAKU_TEST_GOVERNED_NAMESPACES` | `${CIC_NAMESPACE}` | Namespaces where the test governance allows tool calls |

---

## Phase 0: Qdrant Setup (required for Phase 6)

Use a local container (Option A) or Qdrant Cloud (Option B). `curl` is used here because Qdrant is not a Wanaku
component.

### Option A: Local container

Follow [common/container-runtime.md](common/container-runtime.md) first. After completion, `CONTAINER_RUNTIME` must be
set.

```bash
${CONTAINER_RUNTIME} run -d --name "${QDRANT_CONTAINER}" \
  -p "${QDRANT_REST_PORT}:6333" -p "${QDRANT_GRPC_PORT}:6334" "${QDRANT_IMAGE}" > /dev/null

for i in $(seq 1 20); do
  curl -sf "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/healthz" > /dev/null 2>&1 && break
  sleep 2
done
curl -sf "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/healthz" > /dev/null \
  && echo "PASS: Qdrant is healthy" || { echo "FAIL: Qdrant is not healthy"; exit 1; }
```

### Option B: Qdrant Cloud

1. Create a cluster at <https://cloud.qdrant.io>.
2. Set `QDRANT_HOST` to the cluster host and `QDRANT_API_KEY` to the API key.

### Step 0.1: Seed the test collection

```bash
QDRANT_REST="http://${QDRANT_HOST}:${QDRANT_REST_PORT}"

curl -sf -X PUT "${QDRANT_REST}/collections/${QDRANT_COLLECTION}" -H "Content-Type: application/json" \
  ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} \
  -d '{"vectors": {"size": '"${QDRANT_VECTOR_DIMENSION}"', "distance": "Cosine"}}' > /dev/null \
  && echo "PASS: collection created" || { echo "FAIL: collection not created"; exit 1; }

curl -sf -X PUT "${QDRANT_REST}/collections/${QDRANT_COLLECTION}/points?wait=true" \
  -H "Content-Type: application/json" ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} \
  -d '{"points": [
        {"id": 1, "vector": [0.1, 0.2, 0.3, 0.4], "payload": {"content": "Apache Camel integration framework"}},
        {"id": 2, "vector": [0.5, 0.6, 0.7, 0.8], "payload": {"content": "Qdrant vector database"}},
        {"id": 3, "vector": [0.9, 0.1, 0.2, 0.3], "payload": {"content": "Wanaku governed action proxy"}}
      ]}' > /dev/null \
  && echo "PASS: test vectors inserted" || { echo "FAIL: test vectors not inserted"; exit 1; }

POINTS=$(curl -sf "${QDRANT_REST}/collections/${QDRANT_COLLECTION}" ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} \
  | jq '.result.points_count')
[ "${POINTS:-0}" -ge 3 ] && echo "PASS: collection has ${POINTS} points" || echo "FAIL: expected 3 points, got ${POINTS}"
```

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server with the test governance configuration

Follow [common/start-server.md](common/start-server.md) and run step 2b. After completion, `WANAKU_SERVER_URL`,
`WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID` and `WANAKU_SERVER_CONFIG` must be set.

### Step 1.2: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md). After completion, `WANAKU_BARN_URL`, `CLI_JAR` and
`WANAKU_BARN_PID` must be set.

### Step 1.3: Detect the container runtime

Follow [common/container-runtime.md](common/container-runtime.md) if Phase 0 did not run it. If no container runtime is
available, Phases 5 and 6 are skipped.

---

## Phase 2: Template Source

### Test 2.1: Shared source checks

Run the Setup and section A of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. Barn reports `Service template 'camel-qdrant-search-tool' is valid`.

### Test 2.2: The route runs a similarity search with payloads

`camel-qdrant` needs the `CamelQdrantAction` header. The header `CamelQdrantWithPayload` (not
`CamelQdrantIncludePayload`) adds the document payloads to the results.

```bash
ROUTE_FILE="${TEMPLATE_ROUTE_FILE}"

grep -A1 "name: CamelQdrantAction" "${ROUTE_FILE}" | grep -q 'SIMILARITY_SEARCH' \
  && echo "PASS: action is SIMILARITY_SEARCH" || echo "FAIL: SIMILARITY_SEARCH action missing"
grep -A1 "name: CamelQdrantWithPayload" "${ROUTE_FILE}" | grep -q '"true"' \
  && echo "PASS: CamelQdrantWithPayload is true" || echo "FAIL: CamelQdrantWithPayload missing"
grep -q 'uri: "qdrant:{{qdrant.collection}}"' "${ROUTE_FILE}" \
  && echo "PASS: route sends to qdrant:{{qdrant.collection}}" || echo "FAIL: qdrant endpoint missing"
for PARAM in 'host: "{{qdrant.host}}"' 'port: "{{qdrant.port}}"' 'apiKey: "{{qdrant.api.key}}"'; do
  grep -q "${PARAM}" "${ROUTE_FILE}" && echo "PASS: endpoint uses ${PARAM}" || echo "FAIL: endpoint lacks ${PARAM}"
done
```

### Test 2.3: Dependencies and properties

```bash
DIR="${TEMPLATE_DIR}/${TEMPLATE_SYSTEM}"

grep -q "^org.apache.camel:camel-qdrant" "${DIR}/${TEMPLATE_SYSTEM}.dependencies.txt" \
  && echo "PASS: camel-qdrant dependency" || echo "FAIL: camel-qdrant dependency missing"

PROPS="${TEMPLATE_DIR}/${TEMPLATE_PROPS_PATH}"
grep -q "^tool.description=." "${PROPS}" && echo "PASS: tool.description" || echo "FAIL: tool.description"
for KEY in qdrant.host qdrant.port qdrant.collection qdrant.api.key; do
  grep -q "^${KEY}={{${KEY}}}$" "${PROPS}" && echo "PASS: ${KEY} is a placeholder" \
    || echo "FAIL: ${KEY} is not a placeholder"
done
```

### Test 2.4: The search term does not return other vector database templates

```bash
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --search qdrant 2>&1)
echo "${OUTPUT}" | grep -Eq "pinecone|milvus|weaviate|pgvector" \
  && echo "FAIL: search 'qdrant' returns other vector database templates" \
  || echo "PASS: search 'qdrant' returns only Qdrant templates"
```

---

## Phase 3: Template Listing

### Test 3.1: Shared listing checks

Run section B of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`.

---

## Phase 4: Template Instantiation

### Step 4.1: Write the instantiation properties

Barn substitutes values by property key. The keys must match the keys in `service.properties`.

```bash
cat > "${TEMPLATE_PROPS_FILE}" <<EOF
qdrant.host=${QDRANT_HOST}
qdrant.port=${QDRANT_GRPC_PORT}
qdrant.collection=${QDRANT_COLLECTION}
qdrant.api.key=${QDRANT_API_KEY}
EOF
[ -s "${TEMPLATE_PROPS_FILE}" ] && echo "PASS: properties written" || echo "FAIL: properties file is empty"
```

### Test 4.2: Shared instantiation checks

Run section C of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. The catalog `${TEMPLATE_CATALOG}` contains the Qdrant host, port, collection
and API key.

### Test 4.3: A custom system name remaps the catalog index

`--service-system` renames the system in `index.properties`. The files stay in the directory of the original system.

```bash
CUSTOM_CATALOG="${TEMPLATE_CATALOG}-custom"
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --properties-from "${TEMPLATE_PROPS_FILE}" \
  --service-name "${CUSTOM_CATALOG}" --service-system custom-qdrant-system > /dev/null 2>&1 \
  && echo "PASS: instantiation with a custom system succeeded" || echo "FAIL: instantiation with a custom system failed"

java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${CUSTOM_CATALOG}" --version 1 --output "${TEMPLATE_WORK_DIR}/custom.zip" > /dev/null 2>&1
unzip -p "${TEMPLATE_WORK_DIR}/custom.zip" index.properties > "${TEMPLATE_WORK_DIR}/custom-index.properties"
grep -q "^catalog.services=custom-qdrant-system$" "${TEMPLATE_WORK_DIR}/custom-index.properties" \
  && echo "PASS: catalog.services is custom-qdrant-system" || echo "FAIL: catalog.services not remapped"
grep -q "^catalog.routes.custom-qdrant-system=" "${TEMPLATE_WORK_DIR}/custom-index.properties" \
  && echo "PASS: catalog.routes remapped" || echo "FAIL: catalog.routes not remapped"
```

---

## Phase 5: Serve the Catalog through the CIC

### Test 5.0: Pre-flight check

```bash
[ -n "${CONTAINER_RUNTIME}" ] && echo "PASS: container runtime ${CONTAINER_RUNTIME} is available" \
  || echo "SKIP: no container runtime -- skip Phases 5 and 6"
```

### Step 5.1: Start the CIC runtime

Follow [common/start-cic.md](common/start-cic.md) with these values:

```bash
export CIC_CATALOG="${TEMPLATE_CATALOG}"
export CIC_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}"
```

The CIC uses host networking, so `QDRANT_HOST=localhost` reaches the Qdrant container of Phase 0.

### Test 5.2: Shared tool registration checks

Run section D of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** `qdrant-similarity-search` is in `wanaku tools list` with the type `mcp-forward` and the input schema
property `query`, and it is listed at `${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp`.

---

## Phase 6: End-to-End MCP Tool Call (requires Qdrant)

### Test 6.0: Pre-flight check

```bash
PHASE6_SKIP=0
curl -sf "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/healthz" > /dev/null 2>&1 \
  || { echo "SKIP: Qdrant is not reachable"; PHASE6_SKIP=1; }
[ -z "${CONTAINER_RUNTIME}" ] && { echo "SKIP: no CIC runtime"; PHASE6_SKIP=1; }
[ "${PHASE6_SKIP}" -eq 0 ] && echo "PASS: Phase 6 pre-flight checks passed"
```

### Test 6.1: The call reaches Qdrant through Wanaku Server

The check passes when Wanaku Server forwards the call and the CIC runs the route. A Qdrant error about the query
format proves the same path. A governance denial, a connection error or an unknown tool fails the check.

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=vector database" 2>&1)
echo "${OUTPUT}"
if echo "${OUTPUT}" | grep -Eqi "governance|no_match|unable to connect|tool not found|unknown tool"; then
  echo "FAIL: the call did not reach the CIC route"
else
  echo "PASS: the call reached the CIC route"
fi
```

### Test 6.2: The search returns the seeded payloads

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=[0.5 0.6 0.7 0.8]" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -Eqi "Camel|Qdrant|Wanaku" \
  && echo "PASS: search returned seeded payloads" \
  || echo "FAIL: search did not return seeded payloads (known gap: the route has no embedding step)"
```

---

## Phase 7: Negative Tests

### Test 7.1: A call to an unknown tool fails

```bash
java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "does-not-exist-99999" --param "query=x" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: unknown tool rejected" || echo "FAIL: unknown tool accepted"
```

### Test 7.2: The tool is not served in another namespace

Wanaku Server serves a tool only in the namespace of its forward.

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool list --uri "${WANAKU_MCP_BASE_URL}/qdrant-other-ns/mcp" \
  --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "${TEMPLATE_TOOL}" \
  && echo "FAIL: tool is visible in another namespace" || echo "PASS: tool is not visible in another namespace"
```

---

## Phase 8: Cleanup

### Step 8.1: Stop the CIC runtime

Run the Shutdown section of [common/start-cic.md](common/start-cic.md).

### Step 8.2: Remove the catalogs

Run section E of [common/service-template-checks.md](common/service-template-checks.md), then:

```bash
java -jar "${CLI_JAR}" service catalog remove --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}-custom" > /dev/null 2>&1 || true
rm -f "${TEMPLATE_PROPS_FILE}"
echo "PASS: catalogs removed"
```

### Step 8.3: Remove the Qdrant data

```bash
curl -s -X DELETE "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/collections/${QDRANT_COLLECTION}" \
  ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} > /dev/null 2>&1 || true
${CONTAINER_RUNTIME} rm -f "${QDRANT_CONTAINER}" > /dev/null 2>&1 || true
echo "PASS: Qdrant test data removed"
```

### Step 8.4: Stop Barn and Wanaku Server

Run the Shutdown sections of [common/start-barn.md](common/start-barn.md) and
[common/start-server.md](common/start-server.md).

---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | 0.1 | Start Qdrant and seed the test collection | P1 |
| 1 | 1.1-1.3 | Start Wanaku Server (with test governance), Barn, detect container runtime | P0 |
| 2 | 2.1 | Shared source checks (files, index, ai-tool route, credentials, Barn validation) | P0 |
| 2 | 2.2 | Route runs SIMILARITY_SEARCH with payloads on the configured collection | P0 |
| 2 | 2.3 | camel-qdrant dependency and placeholders | P0 |
| 2 | 2.4 | Search 'qdrant' does not return other vector database templates | P2 |
| 3 | 3.1 | Shared listing checks | P1 |
| 4 | 4.1-4.2 | Instantiate and verify the substituted values, versions and negative cases | P0 |
| 4 | 4.3 | Custom system name remaps the catalog index | P1 |
| 5 | 5.0-5.2 | Serve the catalog through the CIC and verify the tool in Wanaku Server | P0 |
| 6 | 6.0-6.1 | MCP call reaches the CIC route | P1 |
| 6 | 6.2 | Search returns seeded payloads (known gap) | P2 |
| 7 | 7.1-7.2 | Unknown tool rejected, tool not visible in another namespace | P1 |
| 8 | 8.1-8.4 | Cleanup (CIC, forward, catalogs, Qdrant, processes) | P1 |
