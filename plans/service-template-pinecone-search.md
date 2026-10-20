# Test Plan: Camel Pinecone Vectorized Search Service Template

## Overview

This test plan verifies the built-in `camel-pinecone-tool` service template (issue #1183). It covers the template
source, the listing and the instantiation in Wanaku Barn, the tool registration in Wanaku Server through a Camel
Integration Capability (CIC) runtime, and an end-to-end MCP tool call against a Pinecone index.

The template turns into an MCP tool in this order:

```text
camel-pinecone-tool template (Barn) --instantiate--> catalog (Barn) --CIC downloads--> CIC runtime :9190/mcp
    --forward--> Wanaku Server --MCP--> ${WANAKU_MCP_BASE_URL}/default/mcp --> Pinecone index
```

### Components under test

| Component | Needed for | Common procedure |
|-----------|------------|------------------|
| Wanaku Barn | Phases 2 to 4 (templates and catalogs) | [common/start-barn.md](common/start-barn.md) |
| Wanaku Server | Phases 5 and 6 (tool registration and MCP call) | [common/start-server.md](common/start-server.md) |
| CIC runtime (container) | Phases 5 and 6 | [common/start-cic.md](common/start-cic.md) |
| Pinecone account and index | Phase 6 only | Phase 0 of this plan |

Phases 1 to 5 run without a Pinecone account. Phase 6 is skipped when `PINECONE_API_KEY` or `PINECONE_INDEX_HOST` is
not set.

The shared template checks are in [common/service-template-checks.md](common/service-template-checks.md). This plan
adds the checks that are specific to the Pinecone template.

> [!IMPORTANT]
> A Pinecone `QUERY` needs a query vector. The template route sends the message body to Pinecone without an embedding
> step, and the tool parameter `query` is a string. Test 6.2 records whether the route returns matches. Until the
> template converts the query into a vector, expect Test 6.2 to fail. Test 6.1 checks the plumbing (Wanaku Server, CIC
> and Pinecone connection) independently of the result.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java --version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |
| `unzip` | any | `unzip -v` |
| `docker` or `podman` | 20+ | `docker --version` or `podman --version` (Phases 5 and 6) |

Run all commands from the root of this repository. Call the CLI as `java -jar "${CLI_JAR}"`.

### Environment variables

```bash
export TEMPLATE_NAME="camel-pinecone-tool"
export TEMPLATE_SYSTEM="pinecone-search"
export TEMPLATE_TOOL="pinecone-search"
export TEMPLATE_TOOL_PARAMS="query"
export TEMPLATE_SEARCH="pinecone"
export TEMPLATE_CATALOG="${TEMPLATE_CATALOG:-pinecone-search-test}"
export TEMPLATE_PROPS_FILE="${TEMPLATE_PROPS_FILE:-$(mktemp)}"

export PINECONE_API_KEY="${PINECONE_API_KEY:-}"
export PINECONE_INDEX_NAME="${PINECONE_INDEX_NAME:-wanaku-test}"
export PINECONE_INDEX_HOST="${PINECONE_INDEX_HOST:-}"
export PINECONE_CLOUD="${PINECONE_CLOUD:-aws}"
export PINECONE_CLOUD_REGION="${PINECONE_CLOUD_REGION:-us-east-1}"
export PINECONE_COLLECTION="${PINECONE_COLLECTION:-default}"
export PINECONE_TOP_K="${PINECONE_TOP_K:-3}"

export CIC_NAME="${CIC_NAME:-cic-pinecone}"
export CIC_NAMESPACE="${CIC_NAMESPACE:-default}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${WANAKU_TEST_GOVERNED_NAMESPACES:-${CIC_NAMESPACE}}"
```

| Variable | Default | Description |
|----------|---------|-------------|
| `TEMPLATE_CATALOG` | `pinecone-search-test` | Name of the catalog that the instantiation creates |
| `TEMPLATE_PROPS_FILE` | `$(mktemp)` | Properties file for the instantiation (written in Phase 4) |
| `PINECONE_API_KEY` | _(empty)_ | Pinecone API key. Empty skips Phase 6. |
| `PINECONE_INDEX_NAME` | `wanaku-test` | Index name |
| `PINECONE_INDEX_HOST` | _(empty)_ | Index host URL from the Pinecone console. Empty skips Phase 6. |
| `PINECONE_CLOUD` | `aws` | Cloud of the index |
| `PINECONE_CLOUD_REGION` | `us-east-1` | Region of the index |
| `PINECONE_COLLECTION` | `default` | Value of the `pinecone:<collection>` endpoint |
| `PINECONE_TOP_K` | `3` | Number of matches. The template default is `5`. |
| `CIC_NAME` | `cic-pinecone` | CIC container name and forward name |
| `CIC_NAMESPACE` | `default` | Wanaku namespace of the forward |
| `WANAKU_TEST_GOVERNED_NAMESPACES` | `${CIC_NAMESPACE}` | Namespaces where the test governance allows tool calls |

---

## Phase 0: Pinecone Setup (required for Phase 6)

1. Create an account at <https://www.pinecone.io/>. The free tier is sufficient.
2. Create an index named `${PINECONE_INDEX_NAME}` with a small dimension (for example 4).
3. Upsert at least one vector into the index.
4. Copy the index host URL to `PINECONE_INDEX_HOST` and an API key to `PINECONE_API_KEY`.

Verify the index. `curl` is used here because Pinecone is not a Wanaku component.

```bash
if [ -n "${PINECONE_API_KEY}" ] && [ -n "${PINECONE_INDEX_HOST}" ]; then
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Api-Key: ${PINECONE_API_KEY}" \
    "${PINECONE_INDEX_HOST}/describe_index_stats")
  [ "${HTTP_CODE}" = "200" ] && echo "PASS: Pinecone index is reachable" \
    || echo "FAIL: Pinecone returned HTTP ${HTTP_CODE}"
else
  echo "SKIP: PINECONE_API_KEY or PINECONE_INDEX_HOST is not set -- Phase 6 will be skipped"
fi
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

Follow [common/container-runtime.md](common/container-runtime.md). If no container runtime is available, Phases 5 and 6
are skipped.

---

## Phase 2: Template Source

### Test 2.1: Shared source checks

Run the Setup and section A of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. Barn reports `Service template 'camel-pinecone-tool' is valid`.

### Test 2.2: The route runs a Pinecone query

```bash
ROUTE_FILE="${TEMPLATE_ROUTE_FILE}"

grep -A1 "name: CamelPineconeAction" "${ROUTE_FILE}" | grep -q '"QUERY"' \
  && echo "PASS: action is QUERY" || echo "FAIL: QUERY action missing"
grep -A1 "name: CamelPineconeQueryTopK" "${ROUTE_FILE}" | grep -q '{{pinecone.top.k}}' \
  && echo "PASS: top-k comes from pinecone.top.k" || echo "FAIL: top-k header missing"
grep -q 'uri: "pinecone:{{pinecone.collection}}"' "${ROUTE_FILE}" \
  && echo "PASS: route sends to pinecone:{{pinecone.collection}}" || echo "FAIL: pinecone endpoint missing"
for PARAM in 'token: "{{pinecone.token}}"' 'indexName: "{{pinecone.index.name}}"' 'host: "{{pinecone.index.host}}"' \
  'cloud: "{{pinecone.cloud}}"' 'cloudRegion: "{{pinecone.cloud.region}}"'; do
  grep -q "${PARAM}" "${ROUTE_FILE}" && echo "PASS: endpoint uses ${PARAM}" || echo "FAIL: endpoint lacks ${PARAM}"
done
```

### Test 2.3: Dependencies and properties

```bash
DIR="${TEMPLATE_DIR}/${TEMPLATE_SYSTEM}"

grep -q "^org.apache.camel:camel-pinecone" "${DIR}/${TEMPLATE_SYSTEM}.dependencies.txt" \
  && echo "PASS: camel-pinecone dependency" || echo "FAIL: camel-pinecone dependency missing"

PROPS="${TEMPLATE_DIR}/${TEMPLATE_PROPS_PATH}"
grep -q "^tool.description=." "${PROPS}" && echo "PASS: tool.description" || echo "FAIL: tool.description"
for KEY in pinecone.token pinecone.index.name pinecone.index.host pinecone.cloud pinecone.cloud.region \
  pinecone.collection; do
  grep -q "^${KEY}={{${KEY}}}$" "${PROPS}" && echo "PASS: ${KEY} is a placeholder" \
    || echo "FAIL: ${KEY} is not a placeholder"
done
grep -q "^pinecone.top.k=5$" "${PROPS}" && echo "PASS: pinecone.top.k default is 5" || echo "FAIL: pinecone.top.k default"
```

### Test 2.4: The search term does not return other vector database templates

```bash
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --search pinecone 2>&1)
echo "${OUTPUT}" | grep -Eq "qdrant|milvus|weaviate|pgvector" \
  && echo "FAIL: search 'pinecone' returns other vector database templates" \
  || echo "PASS: search 'pinecone' returns only Pinecone templates"
```

---

## Phase 3: Template Listing

### Test 3.1: Shared listing checks

Run section B of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`.

---

## Phase 4: Template Instantiation

### Step 4.1: Write the instantiation properties

Phases 4 and 5 work with placeholder values, because the CIC does not call Pinecone until a tool call arrives.

```bash
cat > "${TEMPLATE_PROPS_FILE}" <<EOF
pinecone.token=${PINECONE_API_KEY:-test-api-key-placeholder}
pinecone.index.name=${PINECONE_INDEX_NAME}
pinecone.index.host=${PINECONE_INDEX_HOST:-https://placeholder.svc.pinecone.io}
pinecone.cloud=${PINECONE_CLOUD}
pinecone.cloud.region=${PINECONE_CLOUD_REGION}
pinecone.collection=${PINECONE_COLLECTION}
pinecone.top.k=${PINECONE_TOP_K}
EOF
[ -s "${TEMPLATE_PROPS_FILE}" ] && echo "PASS: properties written" || echo "FAIL: properties file is empty"
```

### Test 4.2: Shared instantiation checks

Run section C of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. The catalog `${TEMPLATE_CATALOG}` contains the supplied Pinecone values.

### Test 4.3: A supplied value replaces a fixed default

`pinecone.top.k` is not a placeholder. Barn replaces it because the key exists in `service.properties`.

```bash
unzip -p "${TEMPLATE_WORK_DIR}/catalog-v1.zip" "${TEMPLATE_PROPS_PATH}" | grep -q "^pinecone.top.k=${PINECONE_TOP_K}$" \
  && echo "PASS: pinecone.top.k is ${PINECONE_TOP_K}" || echo "FAIL: pinecone.top.k was not replaced"
```

### Test 4.4: An unknown property key is ignored

Barn only replaces keys that exist in `service.properties`. It does not add new keys.

```bash
EXTRA_CATALOG="${TEMPLATE_CATALOG}-extra"
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --properties-from "${TEMPLATE_PROPS_FILE}" --property "unknown.key=value" \
  --service-name "${EXTRA_CATALOG}" > /dev/null 2>&1 \
  && echo "PASS: instantiation with an unknown key succeeded" || echo "FAIL: instantiation failed"
java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${EXTRA_CATALOG}" --version 1 --output "${TEMPLATE_WORK_DIR}/extra.zip" > /dev/null 2>&1
unzip -p "${TEMPLATE_WORK_DIR}/extra.zip" "${TEMPLATE_PROPS_PATH}" | grep -q "^unknown.key=" \
  && echo "FAIL: unknown key was added" || echo "PASS: unknown key was not added"
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

### Test 5.2: Shared tool registration checks

Run section D of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** `pinecone-search` is in `wanaku tools list` with the type `mcp-forward` and the input schema property
`query`, and it is listed at `${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp`.

---

## Phase 6: End-to-End MCP Tool Call (requires a Pinecone index)

### Test 6.0: Pre-flight check

```bash
PHASE6_SKIP=0
[ -z "${PINECONE_API_KEY}" ] && { echo "SKIP: PINECONE_API_KEY is not set"; PHASE6_SKIP=1; }
[ -z "${PINECONE_INDEX_HOST}" ] && { echo "SKIP: PINECONE_INDEX_HOST is not set"; PHASE6_SKIP=1; }
[ -z "${CONTAINER_RUNTIME}" ] && { echo "SKIP: no CIC runtime"; PHASE6_SKIP=1; }
[ "${PHASE6_SKIP}" -eq 0 ] && echo "PASS: Phase 6 pre-flight checks passed"
```

### Test 6.1: The call reaches Pinecone through Wanaku Server

The check passes when Wanaku Server forwards the call and the CIC runs the route. A Pinecone error about the query
format proves the same path. A governance denial, a connection error or an unknown tool fails the check.

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=test query" 2>&1)
echo "${OUTPUT}"
if echo "${OUTPUT}" | grep -Eqi "governance|no_match|unable to connect|tool not found|unknown tool"; then
  echo "FAIL: the call did not reach the CIC route"
else
  echo "PASS: the call reached the CIC route"
fi
```

### Test 6.2: The query returns matches

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=test query" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -qi "match\|score\|id" \
  && echo "PASS: query returned matches" \
  || echo "FAIL: query returned no matches (known gap: the route has no embedding step)"
```

---

## Phase 7: Negative Tests

### Test 7.1: A call to an unknown tool fails

```bash
java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "does-not-exist-99999" --param "query=x" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: unknown tool rejected" || echo "FAIL: unknown tool accepted"
```

### Test 7.2: The tool is gone after the forward is removed

Run this test last in the plan, before the cleanup. It removes the forward of Phase 5.

```bash
java -jar "${CLI_JAR}" forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name "${CIC_NAME}" > /dev/null 2>&1
java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1 \
  | grep -q "^${TEMPLATE_TOOL}[[:space:]]" \
  && echo "FAIL: ${TEMPLATE_TOOL} is still registered" || echo "PASS: ${TEMPLATE_TOOL} removed with its forward"
```

---

## Phase 8: Cleanup

### Step 8.1: Stop the CIC runtime

Run the Shutdown section of [common/start-cic.md](common/start-cic.md). The forward removal is idempotent.

### Step 8.2: Remove the catalogs

Run section E of [common/service-template-checks.md](common/service-template-checks.md), then:

```bash
java -jar "${CLI_JAR}" service catalog remove --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}-extra" > /dev/null 2>&1 || true
rm -f "${TEMPLATE_PROPS_FILE}"
echo "PASS: catalogs removed"
```

### Step 8.3: Stop Barn and Wanaku Server

Run the Shutdown sections of [common/start-barn.md](common/start-barn.md) and
[common/start-server.md](common/start-server.md).

---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | -- | Pinecone index setup and reachability | P1 |
| 1 | 1.1-1.3 | Start Wanaku Server (with test governance), Barn, detect container runtime | P0 |
| 2 | 2.1 | Shared source checks (files, index, ai-tool route, credentials, Barn validation) | P0 |
| 2 | 2.2 | Route runs a Pinecone QUERY with the configured endpoint options | P0 |
| 2 | 2.3 | camel-pinecone dependency, placeholders and top-k default | P0 |
| 2 | 2.4 | Search 'pinecone' does not return other vector database templates | P2 |
| 3 | 3.1 | Shared listing checks | P1 |
| 4 | 4.1-4.2 | Instantiate and verify the substituted values, versions and negative cases | P0 |
| 4 | 4.3 | Supplied value replaces a fixed default | P1 |
| 4 | 4.4 | Unknown property key is ignored | P2 |
| 5 | 5.0-5.2 | Serve the catalog through the CIC and verify the tool in Wanaku Server | P0 |
| 6 | 6.0-6.1 | MCP call reaches the CIC route | P1 |
| 6 | 6.2 | Query returns matches (known gap) | P2 |
| 7 | 7.1 | Unknown tool rejected | P1 |
| 7 | 7.2 | Tool removed with its forward | P1 |
| 8 | 8.1-8.3 | Cleanup (CIC, forward, catalogs, processes) | P1 |
