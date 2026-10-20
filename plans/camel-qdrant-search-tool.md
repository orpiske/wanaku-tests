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

## Phase S: Setup

### Step S.1: Set environment variables

```bash run
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

**Expected result:**

- The commands exit with code 0 (inferred).

## Phase 0: Qdrant Setup (required for Phase 6)

### Step 0.0: Prepare the external backend

Choose the applicable backend option below; run only that option.

Use a local container (Option A) or Qdrant Cloud (Option B). `curl` is used here because Qdrant is not a Wanaku
component.

#### Option A: Local container

Follow [common/container-runtime.md](common/container-runtime.md).

first. After completion, `CONTAINER_RUNTIME` must be
set.


Run this block as a separate `bash` script, not in the shared session.

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

#### Option B: Qdrant Cloud

1. Create a cluster at <https://cloud.qdrant.io>.
2. Set `QDRANT_HOST` to the cluster host and `QDRANT_API_KEY` to the API key.

**Expected result:**

- For the local backend option, the output includes `PASS: Qdrant is healthy`; for the cloud/API alternative, the specified backend settings are configured (inferred).
- The commands exit with code 0.

### Step 0.1: Seed the test collection

Run this block as a separate `bash` script, not in the shared session.

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

**Expected result:**

- The output includes `PASS: collection created`.
- The output includes `PASS: test vectors inserted`.
- The output includes `PASS: collection has <POINTS> points`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server with the test governance configuration

Follow [common/start-server.md](common/start-server.md).

and run step 2b. After completion, `WANAKU_SERVER_URL`,
`WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID` and `WANAKU_SERVER_CONFIG` must be set.

**Expected result:**

- All invoked procedure steps pass (inferred).

### Step 1.2: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md).

After completion, `WANAKU_BARN_URL`, `CLI_JAR` and
`WANAKU_BARN_PID` must be set.

**Expected result:**

- All invoked procedure steps pass (inferred).

### Step 1.3: Detect the container runtime

Follow [common/container-runtime.md](common/container-runtime.md).

if Phase 0 did not run it. If no container runtime is
available, Phases 5 and 6 are skipped.


**Expected result:**

- All invoked procedure steps pass (inferred).

---

## Phase 2: Template Source

### Test 2.1: Shared source checks

Run the Setup and section A of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. Barn reports `Service template 'camel-qdrant-search-tool' is valid`.

Run this block as a separate `bash` script, not in the shared session.

```bash
export TEMPLATE_CATALOG="${TEMPLATE_CATALOG:-${TEMPLATE_NAME}-test}"
export TEMPLATE_BASE_DIR="${TEMPLATE_BASE_DIR:-services/service-templates/src/main/services}"
export TEMPLATE_WORK_DIR="${TEMPLATE_WORK_DIR:-$(mktemp -d)}"
export TEMPLATE_DIR="${TEMPLATE_BASE_DIR}/${TEMPLATE_NAME}"

for VAR in TEMPLATE_NAME TEMPLATE_SYSTEM TEMPLATE_TOOL TEMPLATE_TOOL_PARAMS TEMPLATE_SEARCH TEMPLATE_PROPS_FILE \
  WANAKU_BARN_URL CLI_JAR; do
  eval "VALUE=\${${VAR}}"
  if [ -z "${VALUE}" ]; then
    echo "FAIL: ${VAR} must be set"
    exit 1
  fi
done
[ -f "${TEMPLATE_PROPS_FILE}" ] || { echo "FAIL: ${TEMPLATE_PROPS_FILE} not found"; exit 1; }
echo "PASS: template check variables are set (catalog: ${TEMPLATE_CATALOG})"
```

#### A.1: The template directory has the required files

The route file is the file that `catalog.routes.<system>` declares. The properties file is the file that
`catalog.properties.<system>` declares, or `<system>/service.properties` by convention.

Run this block as a separate `bash` script, not in the shared session.

```bash
INDEX="${TEMPLATE_DIR}/index.properties"
[ -f "${INDEX}" ] && echo "PASS: index.properties exists" || { echo "FAIL: ${INDEX} missing"; exit 1; }

ROUTES_REL=$(grep "^catalog.routes.${TEMPLATE_SYSTEM}=" "${INDEX}" | cut -d= -f2-)
DEPS_REL=$(grep "^catalog.dependencies.${TEMPLATE_SYSTEM}=" "${INDEX}" | cut -d= -f2-)
TEMPLATE_PROPS_PATH=$(grep "^catalog.properties.${TEMPLATE_SYSTEM}=" "${INDEX}" | cut -d= -f2-)
TEMPLATE_PROPS_PATH="${TEMPLATE_PROPS_PATH:-${TEMPLATE_SYSTEM}/service.properties}"
export TEMPLATE_ROUTE_FILE="${TEMPLATE_DIR}/${ROUTES_REL}"
export TEMPLATE_PROPS_PATH

for FILE in "${TEMPLATE_ROUTE_FILE}" "${TEMPLATE_DIR}/${TEMPLATE_PROPS_PATH}"; do
  [ -n "${FILE}" ] && [ -f "${FILE}" ] && echo "PASS: ${FILE} exists" || echo "FAIL: ${FILE} missing"
done

if [ -n "${DEPS_REL}" ]; then
  [ -f "${TEMPLATE_DIR}/${DEPS_REL}" ] && echo "PASS: ${DEPS_REL} exists" || echo "FAIL: ${DEPS_REL} missing"
fi
```

#### A.2: index.properties declares the catalog

```bash
grep -q "^catalog.name=${TEMPLATE_NAME}$" "${INDEX}" && echo "PASS: catalog.name" || echo "FAIL: catalog.name"
grep -q "^catalog.description=." "${INDEX}" && echo "PASS: catalog.description" || echo "FAIL: catalog.description"
grep "^catalog.services=" "${INDEX}" | grep -q "${TEMPLATE_SYSTEM}" \
  && echo "PASS: catalog.services contains ${TEMPLATE_SYSTEM}" || echo "FAIL: catalog.services"
```

#### A.3: The route is an MCP tool route

The CIC serves a route as an MCP tool when the route starts from `ai-tool:<name>`. Templates do not use rules files
(`*.rules.yaml`). Rules files are optional since 0.3.0.

```bash
grep -q "uri: ai-tool:${TEMPLATE_TOOL}$" "${TEMPLATE_ROUTE_FILE}" \
  && echo "PASS: route starts from ai-tool:${TEMPLATE_TOOL}" || echo "FAIL: ai-tool:${TEMPLATE_TOOL} not found"
grep -q "tags: wanaku" "${TEMPLATE_ROUTE_FILE}" \
  && echo "PASS: route has the wanaku tag" || echo "FAIL: wanaku tag missing"
grep -q "description:" "${TEMPLATE_ROUTE_FILE}" \
  && echo "PASS: tool description present" || echo "FAIL: tool description missing"

for PARAM in ${TEMPLATE_TOOL_PARAMS}; do
  grep -q "parameter.${PARAM}:" "${TEMPLATE_ROUTE_FILE}" \
    && echo "PASS: tool parameter '${PARAM}' declared" || echo "FAIL: tool parameter '${PARAM}' missing"
done
```

#### A.4: No credentials are hard-coded

```bash
FOUND=0
for FILE in $(find "${TEMPLATE_DIR}" -type f); do
  if grep -Eq '(sk-[a-zA-Z0-9]{20,}|key-[a-zA-Z0-9]{20,}|pcsk_[a-zA-Z0-9]{20,}|Bearer [a-zA-Z0-9]{20,})' "${FILE}"; then
    echo "FAIL: possible hard-coded credential in ${FILE}"
    FOUND=1
  fi
done
[ "${FOUND}" -eq 0 ] && echo "PASS: no hard-coded credentials"
```

#### A.5: The catalog name is unique among the templates

```bash
DUP=$(grep -l "^catalog.name=${TEMPLATE_NAME}$" "${TEMPLATE_BASE_DIR}"/*/index.properties | wc -l | tr -d ' ')
[ "${DUP}" -eq 1 ] && echo "PASS: catalog.name is unique" || echo "FAIL: catalog.name is used ${DUP} times"
```

#### A.6: Barn validates the template

`wanaku service validate` sends the package to Barn. Barn does not deploy it. Warnings (for example a missing
`catalog.icon`) do not fail the validation.

```bash
OUTPUT=$(java -jar "${CLI_JAR}" service validate --template --path="${TEMPLATE_DIR}" \
  --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service template '${TEMPLATE_NAME}' is valid" \
  && echo "PASS: Barn reports the template as valid" || echo "FAIL: template validation failed (exit ${EXIT_CODE})"
```

**Expected result:**

- every line is `PASS`. Barn reports `Service template 'camel-qdrant-search-tool' is valid`.
- The output includes `PASS: template check variables are set (catalog: <TEMPLATE_CATALOG>)`.
- The output includes `PASS: index.properties exists`.
- The output includes `PASS: <FILE> exists`.
- If `DEPS_REL` is nonempty, the output includes `PASS: <DEPS_REL> exists`.
- The output includes `PASS: catalog.name`.
- The output includes `PASS: catalog.description`.
- The output includes `PASS: catalog.services contains <TEMPLATE_SYSTEM>`.
- The output includes `PASS: route starts from ai-tool:<TEMPLATE_TOOL>`.
- The output includes `PASS: route has the wanaku tag`.
- The output includes `PASS: tool description present`.
- The output includes `PASS: tool parameter '<PARAM>' declared`.
- The output includes `PASS: no hard-coded credentials`.
- The output includes `PASS: catalog.name is unique`.
- The output includes `PASS: Barn reports the template as valid`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 2.2: The route runs a similarity search with payloads

`camel-qdrant` needs the `CamelQdrantAction` header. The header `CamelQdrantWithPayload` (not
`CamelQdrantIncludePayload`) adds the document payloads to the results.

```bash run
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

**Expected result:**

- The output includes `PASS: action is SIMILARITY_SEARCH`.
- The output includes `PASS: CamelQdrantWithPayload is true`.
- The output includes `PASS: route sends to qdrant:{{qdrant.collection}}`.
- The output includes `PASS: endpoint uses host: "{{qdrant.host}}"`.
- The output includes `PASS: endpoint uses port: "{{qdrant.port}}"`.
- The output includes `PASS: endpoint uses apiKey: "{{qdrant.api.key}}"`.
- The output contains no line starting with `FAIL:`.

### Test 2.3: Dependencies and properties

```bash run
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

**Expected result:**

- The output includes `PASS: camel-qdrant dependency`.
- The output includes `PASS: tool.description`.
- The output includes `PASS: qdrant.host is a placeholder`.
- The output includes `PASS: qdrant.port is a placeholder`.
- The output includes `PASS: qdrant.collection is a placeholder`.
- The output includes `PASS: qdrant.api.key is a placeholder`.
- The output contains no line starting with `FAIL:`.

### Test 2.4: The search term does not return other vector database templates

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --search qdrant 2>&1)
echo "${OUTPUT}" | grep -Eq "pinecone|milvus|weaviate|pgvector" \
  && echo "FAIL: search 'qdrant' returns other vector database templates" \
  || echo "PASS: search 'qdrant' returns only Qdrant templates"
```

**Expected result:**

- The output includes `PASS: search 'qdrant' returns only Qdrant templates`.
- The output contains no line starting with `FAIL:`.

---

## Phase 3: Template Listing

### Test 3.1: Shared listing checks

Run section B of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`.

---

#### B.1: The CLI lists the template

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_NAME}[[:space:]]" \
  && echo "PASS: template list contains ${TEMPLATE_NAME}" || echo "FAIL: ${TEMPLATE_NAME} not in template list"
```

#### B.2: The search finds the template

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --search "${TEMPLATE_SEARCH}" 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_NAME}[[:space:]]" \
  && echo "PASS: search '${TEMPLATE_SEARCH}' finds ${TEMPLATE_NAME}" || echo "FAIL: search did not find ${TEMPLATE_NAME}"
```

#### B.3: A search for an unrelated term does not find the template (negative)

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --search "zzz-nonexistent-zzz" 2>&1)
echo "${OUTPUT}" | grep -q "${TEMPLATE_NAME}" \
  && echo "FAIL: unrelated search returned ${TEMPLATE_NAME}" \
  || { echo "${OUTPUT}" | grep -q "No service templates found" \
       && echo "PASS: unrelated search returns no templates" || echo "FAIL: unexpected output: ${OUTPUT}"; }
```

#### B.4: The template detail, properties and package endpoints

The CLI has no command for the template detail, the property list and the current package. `curl` is acceptable
here (see [contributing-test-plans.md](../../../docs/contributing-test-plans.md)).

```bash run
DETAIL=$(curl -s "${WANAKU_BARN_URL}/api/v1/service-template/get?name=${TEMPLATE_NAME}")
echo "${DETAIL}" | jq -e --arg n "${TEMPLATE_NAME}" '.error == null and .data.name == $n' > /dev/null \
  && echo "PASS: detail returns ${TEMPLATE_NAME}" || echo "FAIL: detail response: ${DETAIL}"
echo "${DETAIL}" | jq -e --arg s "${TEMPLATE_SYSTEM}" '[.data.services[].name] | index($s) != null' > /dev/null \
  && echo "PASS: detail lists system ${TEMPLATE_SYSTEM}" || echo "FAIL: system ${TEMPLATE_SYSTEM} not in detail"

PROPS=$(curl -s "${WANAKU_BARN_URL}/api/v1/service-template/properties?name=${TEMPLATE_NAME}")
echo "${PROPS}" | jq -e --arg s "${TEMPLATE_SYSTEM}" '.data[$s] | type == "object"' > /dev/null \
  && echo "PASS: properties endpoint returns the properties of ${TEMPLATE_SYSTEM}" \
  || echo "FAIL: properties response: ${PROPS}"

curl -s "${WANAKU_BARN_URL}/api/v1/service-template/download?name=${TEMPLATE_NAME}" \
  | jq -r '.data.data' | base64 -d > "${TEMPLATE_WORK_DIR}/template.zip" 2>/dev/null
unzip -l "${TEMPLATE_WORK_DIR}/template.zip" 2>/dev/null | grep -q "index.properties" \
  && echo "PASS: download returns a ZIP package with index.properties" || echo "FAIL: download is not a valid package"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  "${WANAKU_BARN_URL}/api/v1/service-template/get?name=does-not-exist-99999")
[ "${HTTP_CODE}" = "404" ] && echo "PASS: unknown template returns 404" || echo "FAIL: expected 404, got ${HTTP_CODE}"
```

**Expected result:**

- every line is `PASS`.
- The output includes `PASS: template list contains <TEMPLATE_NAME>`.
- The output includes `PASS: search '<TEMPLATE_SEARCH>' finds <TEMPLATE_NAME>`.
- The output includes `PASS: unrelated search returns no templates`.
- The output includes `PASS: detail returns <TEMPLATE_NAME>`.
- The output includes `PASS: detail lists system <TEMPLATE_SYSTEM>`.
- The output includes `PASS: properties endpoint returns the properties of <TEMPLATE_SYSTEM>`.
- The output includes `PASS: download returns a ZIP package with index.properties`.
- The output includes `PASS: unknown template returns 404`.
- The output contains no line starting with `FAIL:`.

## Phase 4: Template Instantiation

### Step 4.1: Write the instantiation properties

Barn substitutes values by property key. The keys must match the keys in `service.properties`.

```bash run
cat > "${TEMPLATE_PROPS_FILE}" <<EOF
qdrant.host=${QDRANT_HOST}
qdrant.port=${QDRANT_GRPC_PORT}
qdrant.collection=${QDRANT_COLLECTION}
qdrant.api.key=${QDRANT_API_KEY}
EOF
[ -s "${TEMPLATE_PROPS_FILE}" ] && echo "PASS: properties written" || echo "FAIL: properties file is empty"
```

**Expected result:**

- The output includes `PASS: properties written`.
- The output contains no line starting with `FAIL:`.

### Test 4.2: Shared instantiation checks

Run section C of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. The catalog `${TEMPLATE_CATALOG}` contains the Qdrant host, port, collection
and API key.

#### C.1: Instantiate the template with a properties file

The plan writes `TEMPLATE_PROPS_FILE` before this step.

Run this block as a separate `bash` script, not in the shared session.

```bash
[ -s "${TEMPLATE_PROPS_FILE}" ] || { echo "FAIL: ${TEMPLATE_PROPS_FILE} is empty"; exit 1; }
OUTPUT=$(java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" \
  --properties-from "${TEMPLATE_PROPS_FILE}" \
  --service-name "${TEMPLATE_CATALOG}" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog created successfully from template" \
  && echo "PASS: catalog ${TEMPLATE_CATALOG} created" || { echo "FAIL: instantiation failed (exit ${EXIT_CODE})"; exit 1; }
```

#### C.2: The catalog is in the catalog list

```bash
java -jar "${CLI_JAR}" service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1 \
  | grep -q "^${TEMPLATE_CATALOG}[[:space:]]" \
  && echo "PASS: ${TEMPLATE_CATALOG} is in the catalog list" || echo "FAIL: ${TEMPLATE_CATALOG} not in the catalog list"
```

#### C.3: The catalog contains the supplied values

The first instantiation creates version 1 of the catalog. Barn writes the properties with `java.util.Properties`,
which escapes some characters (for example `https\://`). The check compares the keys and makes sure that no supplied
key keeps its `{{placeholder}}`.

Run this block as a separate `bash` script, not in the shared session.

```bash
java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}" --version 1 --output "${TEMPLATE_WORK_DIR}/catalog-v1.zip" > /dev/null 2>&1 \
  && echo "PASS: version 1 downloaded" || { echo "FAIL: cannot download version 1"; exit 1; }

unzip -p "${TEMPLATE_WORK_DIR}/catalog-v1.zip" index.properties > "${TEMPLATE_WORK_DIR}/index.properties"
unzip -p "${TEMPLATE_WORK_DIR}/catalog-v1.zip" "${TEMPLATE_PROPS_PATH}" > "${TEMPLATE_WORK_DIR}/service.properties"

grep -q "^catalog.name=${TEMPLATE_CATALOG}$" "${TEMPLATE_WORK_DIR}/index.properties" \
  && echo "PASS: catalog.name is ${TEMPLATE_CATALOG}" || echo "FAIL: catalog.name was not set"
grep -q "^catalog.properties\." "${TEMPLATE_WORK_DIR}/index.properties" \
  && echo "FAIL: catalog.properties.* is still declared (the package is still a template)" \
  || echo "PASS: catalog.properties.* removed"

grep -v '^[[:space:]]*#' "${TEMPLATE_PROPS_FILE}" | grep '=' | cut -d= -f1 | while read -r KEY; do
  LINE=$(grep "^${KEY}=" "${TEMPLATE_WORK_DIR}/service.properties")
  if [ -z "${LINE}" ]; then
    echo "FAIL: ${KEY} missing in the catalog"
  elif echo "${LINE}" | grep -q '{{'; then
    echo "FAIL: ${KEY} still has a placeholder: ${LINE}"
  else
    echo "PASS: ${KEY} has the supplied value"
  fi
done
```

#### C.4: A second instantiation creates a new version

```bash
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --properties-from "${TEMPLATE_PROPS_FILE}" \
  --service-name "${TEMPLATE_CATALOG}" > /dev/null 2>&1 \
  && java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
    --name "${TEMPLATE_CATALOG}" --version 2 --output "${TEMPLATE_WORK_DIR}/catalog-v2.zip" > /dev/null 2>&1 \
  && echo "PASS: second instantiation created version 2" || echo "FAIL: version 2 not created"
```

#### C.5: Instantiation of an unknown template fails (negative)

```bash
OUTPUT=$(java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name does-not-exist-99999 --property key=value 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "does not exist" \
  && echo "PASS: unknown template rejected" || echo "FAIL: unknown template not rejected (exit ${EXIT_CODE})"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_BARN_URL}/api/v1/service-template/instantiate" \
  -H "Content-Type: application/json" -d '{"templateName": "does-not-exist-99999", "properties": {}}')
[ "${HTTP_CODE}" = "404" ] && echo "PASS: REST returns 404 for an unknown template" \
  || echo "FAIL: expected 404, got ${HTTP_CODE}"
```

#### C.6: An invalid service name is rejected (negative)

```bash
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --properties-from "${TEMPLATE_PROPS_FILE}" \
  --service-name "invalid name/../x" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: invalid service name rejected" || echo "FAIL: invalid service name accepted"
```

**Expected result:**

- every line is `PASS`. The catalog `${TEMPLATE_CATALOG}` contains the Qdrant host, port, collection and API key.
- The output includes `PASS: catalog <TEMPLATE_CATALOG> created`.
- The output includes `PASS: <TEMPLATE_CATALOG> is in the catalog list`.
- The output includes `PASS: version 1 downloaded`.
- The output includes `PASS: catalog.name is <TEMPLATE_CATALOG>`.
- The output includes `PASS: catalog.properties.* removed`.
- The output includes `PASS: <KEY> has the supplied value`.
- The output includes `PASS: second instantiation created version 2`.
- The output includes `PASS: unknown template rejected`.
- The output includes `PASS: REST returns 404 for an unknown template`.
- The output includes `PASS: invalid service name rejected`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.3: A custom system name remaps the catalog index

`--service-system` renames the system in `index.properties`. The files stay in the directory of the original system.

```bash run
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

**Expected result:**

- The output includes `PASS: instantiation with a custom system succeeded`.
- The output includes `PASS: catalog.services is custom-qdrant-system`.
- The output includes `PASS: catalog.routes remapped`.
- The output contains no line starting with `FAIL:`.

---

## Phase 5: Serve the Catalog through the CIC

### Test 5.0: Pre-flight check

```bash run
[ -n "${CONTAINER_RUNTIME}" ] && echo "PASS: container runtime ${CONTAINER_RUNTIME} is available" \
  || echo "SKIP: no container runtime -- skip Phases 5 and 6"
```

**Expected result:**

- The output includes `PASS: container runtime <CONTAINER_RUNTIME> is available` or `SKIP: no container runtime -- skip Phases 5 and 6`.

### Step 5.S: Set CIC catalog inputs

```bash run
export CIC_CATALOG="${TEMPLATE_CATALOG}"
export CIC_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 5.1: Start the CIC runtime

Follow [common/start-cic.md](common/start-cic.md).




The CIC uses host networking, so `QDRANT_HOST=localhost` reaches the Qdrant container of Phase 0.

**Expected result:**

- All invoked procedure steps pass (inferred).

### Test 5.2: Shared tool registration checks

Run section D of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** `qdrant-similarity-search` is in `wanaku tools list` with the type `mcp-forward` and the input schema
property `query`, and it is listed at `${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp`.

---

Run this section after [start-cic.md](common/start-cic.md) with `CIC_CATALOG="${TEMPLATE_CATALOG}"` and
`CIC_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}"`. `CIC_NAME` and `CIC_NAMESPACE` must be set.

These rules apply:

- Wanaku Server identifies a tool by its name only. Do not register two forwards that expose the same tool name, also
  not in different namespaces. The last discovery moves the tool to its forward and namespace.
- Wanaku Server adds an `x-request-id` property to the input schema of each tool (see the audit trail documentation
  of Wanaku Server). The checks below ignore it.
- List operations are not governed. A tool call is denied unless governance permits it. Plans that call the tool
  must start Wanaku Server with the test governance configuration (step 2b of [start-server.md](common/start-server.md),
  with `CIC_NAMESPACE` in `WANAKU_TEST_GOVERNED_NAMESPACES`). Without it, the call returns
  `No governance policy permits this action.` or `evaluator: no_match`.

#### D.1: Wanaku Server discovered the tool

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_TOOL}[[:space:]]" \
  && echo "PASS: ${TEMPLATE_TOOL} is in the tool list" \
  || { echo "FAIL: ${TEMPLATE_TOOL} not in the tool list"; echo "${OUTPUT}"; }
```

#### D.2: The tool has the declared parameters

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" tools show --host "${WANAKU_SERVER_URL}" --no-auth --plain "${TEMPLATE_TOOL}" 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "mcp-forward" && echo "PASS: tool type is mcp-forward" || echo "FAIL: tool type"
for PARAM in ${TEMPLATE_TOOL_PARAMS}; do
  echo "${OUTPUT}" | grep -q "^${PARAM}[[:space:]]" \
    && echo "PASS: input schema has '${PARAM}'" || echo "FAIL: input schema has no '${PARAM}'"
done
```

#### D.3: MCP clients see the tool in the namespace of the forward

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool list --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_TOOL}[[:space:]]" \
  && echo "PASS: ${TEMPLATE_TOOL} is listed at ${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" \
  || { echo "FAIL: ${TEMPLATE_TOOL} not listed on the MCP endpoint"; echo "${OUTPUT}"; }
```

**Expected result:**

- `qdrant-similarity-search` is in `wanaku tools list` with the type `mcp-forward` and the input schema property `query`, and it is listed at `${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp`.
- The output includes `PASS: <TEMPLATE_TOOL> is in the tool list`.
- The output includes `PASS: tool type is mcp-forward`.
- The output includes `PASS: input schema has '<PARAM>'`.
- The output includes `PASS: <TEMPLATE_TOOL> is listed at <WANAKU_MCP_BASE_URL>/<CIC_NAMESPACE>/mcp`.
- The output contains no line starting with `FAIL:`.

## Phase 6: End-to-End MCP Tool Call (requires Qdrant)

### Test 6.0: Pre-flight check

```bash run
PHASE6_SKIP=0
curl -sf "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/healthz" > /dev/null 2>&1 \
  || { echo "SKIP: Qdrant is not reachable"; PHASE6_SKIP=1; }
[ -z "${CONTAINER_RUNTIME}" ] && { echo "SKIP: no CIC runtime"; PHASE6_SKIP=1; }
[ "${PHASE6_SKIP}" -eq 0 ] && echo "PASS: Phase 6 pre-flight checks passed"
```

**Expected result:**

- The output includes `PASS: Phase 6 pre-flight checks passed` or `SKIP: Qdrant is not reachable` or `SKIP: no CIC runtime`.

### Test 6.1: The call reaches Qdrant through Wanaku Server

The check passes when Wanaku Server forwards the call and the CIC runs the route. A Qdrant error about the query
format proves the same path. A governance denial, a connection error or an unknown tool fails the check.

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=vector database" 2>&1)
echo "${OUTPUT}"
if echo "${OUTPUT}" | grep -Eqi "governance|no_match|unable to connect|tool not found|unknown tool"; then
  echo "FAIL: the call did not reach the CIC route"
else
  echo "PASS: the call reached the CIC route"
fi
```

**Expected result:**

- The output includes `PASS: the call reached the CIC route`.
- The output contains no line starting with `FAIL:`.

### Test 6.2: The search returns the seeded payloads

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=[0.5 0.6 0.7 0.8]" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -Eqi "Camel|Qdrant|Wanaku" \
  && echo "PASS: search returned seeded payloads" \
  || echo "FAIL: search did not return seeded payloads (known gap: the route has no embedding step)"
```

**Expected result:**

- The output includes `PASS: search returned seeded payloads`.
- The output contains no line starting with `FAIL:`.

---

## Phase 7: Negative Tests

### Test 7.1: A call to an unknown tool fails

```bash run
java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "does-not-exist-99999" --param "query=x" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: unknown tool rejected" || echo "FAIL: unknown tool accepted"
```

**Expected result:**

- The output includes `PASS: unknown tool rejected`.
- The output contains no line starting with `FAIL:`.

### Test 7.2: The tool is not served in another namespace

Wanaku Server serves a tool only in the namespace of its forward.

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool list --uri "${WANAKU_MCP_BASE_URL}/qdrant-other-ns/mcp" \
  --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "${TEMPLATE_TOOL}" \
  && echo "FAIL: tool is visible in another namespace" || echo "PASS: tool is not visible in another namespace"
```

**Expected result:**

- The output includes `PASS: tool is not visible in another namespace`.
- The output contains no line starting with `FAIL:`.

---

## Phase 8: Cleanup

### Step 8.1: Stop the CIC runtime

Run the Shutdown section of [common/start-cic.md](common/start-cic.md).

#### start-cic shutdown

```bash run
java -jar "${CLI_JAR}" forwards remove --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${CIC_NAME:-cic-test}" 2>/dev/null || true
${CONTAINER_RUNTIME} stop "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
${CONTAINER_RUNTIME} rm "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
echo "PASS: CIC runtime stopped and its forward removed"
```

**Expected result:**

- The output includes `PASS: CIC runtime stopped and its forward removed`.

### Step 8.2: Remove the catalogs

Run section E of [common/service-template-checks.md](common/service-template-checks.md), then:

Barn does not delete a removed catalog. It keeps the catalog and its versions, and it reserves the name: a new deploy
or instantiation with the same name returns HTTP 409 until you run `wanaku service restore`. A fresh Barn home per
run (see [start-barn.md](common/start-barn.md)) avoids this.

```bash run
java -jar "${CLI_JAR}" service catalog remove --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}" > /dev/null 2>&1 || true
java -jar "${CLI_JAR}" service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1 \
  | grep -q "^${TEMPLATE_CATALOG}[[:space:]]" \
  && echo "FAIL: ${TEMPLATE_CATALOG} is still listed" || echo "PASS: ${TEMPLATE_CATALOG} removed"
rm -rf "${TEMPLATE_WORK_DIR}"
```

```bash run
java -jar "${CLI_JAR}" service catalog remove --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}-custom" > /dev/null 2>&1 || true
rm -f "${TEMPLATE_PROPS_FILE}"
echo "PASS: catalogs removed"
```

#### start-barn shutdown

```bash run
if [ -n "${WANAKU_BARN_PID}" ]; then
  kill "${WANAKU_BARN_PID}" 2>/dev/null || true
  wait "${WANAKU_BARN_PID}" 2>/dev/null || true
  echo "PASS: Wanaku Barn stopped"
fi
rm -rf "${WANAKU_BARN_HOME}"
```

Do **not** use `kill -9`. It bypasses shutdown hooks and can leave the Infinispan store in an inconsistent state.

**Expected result:**

- The output includes `PASS: <TEMPLATE_CATALOG> removed`.
- The output includes `PASS: catalogs removed`.
- The output includes `PASS: Wanaku Barn stopped`.
- The output contains no line starting with `FAIL:`.

### Step 8.3: Remove the Qdrant data

```bash run
curl -s -X DELETE "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/collections/${QDRANT_COLLECTION}" \
  ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} > /dev/null 2>&1 || true
${CONTAINER_RUNTIME} rm -f "${QDRANT_CONTAINER}" > /dev/null 2>&1 || true
echo "PASS: Qdrant test data removed"
```

**Expected result:**

- The output includes `PASS: Qdrant test data removed`.

### Step 8.4: Stop Barn and Wanaku Server

Run the Shutdown sections of [common/start-barn.md](common/start-barn.md) and
[common/start-server.md](common/start-server.md).

---

#### start-barn shutdown

```bash run
if [ -n "${WANAKU_BARN_PID}" ]; then
  kill "${WANAKU_BARN_PID}" 2>/dev/null || true
  wait "${WANAKU_BARN_PID}" 2>/dev/null || true
  echo "PASS: Wanaku Barn stopped"
fi
rm -rf "${WANAKU_BARN_HOME}"
```

Do **not** use `kill -9`. It bypasses shutdown hooks and can leave the Infinispan store in an inconsistent state.

**Expected result:**

- The output includes `PASS: Wanaku Barn stopped`.

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
| Setup | S.1 | Set environment variables | Setup |
| Setup | 0.0 | Prepare the external backend | Setup |
| Setup | 5.S | Set CIC catalog inputs | Setup |
