# Test Plan: Wanaku Server REST-Only API

## Overview

This test plan verifies the Wanaku Server management API routes that have no Wanaku CLI command. Because these
routes are REST-only, `curl` is the correct client for them (see
[contributing-test-plans.md](../../docs/contributing-test-plans.md#use-the-wanaku-cli-not-raw-api-clients)). The plan
uses the CLI for everything that has a command: forwards, namespaces, tool lists and MCP calls.

This plan replaces the earlier "REST API v2 Features" plan (`api-v2-features.md`). The Java router endpoints
`/api/v2/tool-calls/*` (tool call history and SSE notifications) and `/api/v2/code-execution-engine/*` do not exist in
Wanaku Server or in Wanaku Barn. The nearest replacements are:

| Removed router feature | Replacement in Wanaku Server | Phase |
|------------------------|------------------------------|-------|
| Tool call history (`/api/v2/tool-calls/history`) | Audit trail (`/api/v1/audit/events`) with `x-request-id` correlation | 6 |
| Tool call SSE notifications | None. Poll the audit trail. The Deployment Debugger plugin shows live calls in the UI. | — |
| Code execution engine | None. Run code through an MCP server that is registered as a forward. | — |
| `/q/health/ready`, `/q/openapi`, `/q/metrics` | `/health`, `/openapi.json`, `/api/v1/metrics` | 2, 5 |

The plan covers:

- Service routes: health, server information, statistics, OpenAPI document and CORS (Phase 2)
- The response envelope and the status codes `400`, `404` and `409` (Phase 3)
- Enable and disable of tools, resources and prompts, and the effect on MCP clients (Phase 4)
- Metrics (Phase 5)
- The audit trail (Phase 6)
- Removed endpoints and other negative cases (Phase 7)

**Components:** Wanaku Server only. Wanaku Barn is not necessary.

Wanaku Server is fail-safe. It denies `tools/call`, `resources/read` and `prompts/get` unless the governance
configuration allows them. The plan allows them only in the namespace `REST_NS`. The namespace `REST_DENIED_NS` keeps
the fail-safe default, so Phase 6 can verify a denial.

Every step is automatable.

### Response format

Most management routes return this envelope:

```json
{"data": <payload>, "error": null}
```

For an error, `data` is `null` and `error` is a message string. `/openapi.json` returns the OpenAPI document without
the envelope.


## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java -version` |
| `curl` | 7.68+ | `curl --version` |
| `jq` | 1.6+ | `jq --version` |
| `node` / `npx` | 18+ | `node --version` |

### Environment variables



| Variable | Default | Description |
|----------|---------|-------------|
| `WANAKU_REPO_ROOT` | `.` | Path to this repository |
| `CLI_JAR` | `apps/wanaku-cli/target/quarkus-app/quarkus-run.jar` | Wanaku CLI jar |
| `REST_NS` | `rest-<epoch>` | Namespace where governance allows calls. It holds the forward of the plan. |
| `REST_DENIED_NS` | `${REST_NS}-denied` | Namespace that keeps the fail-safe governance defaults |
| `REST_TOOL` | `echo` | Tool of the reference MCP server (argument `message`) |
| `REST_RESOURCE` | `architecture.md` | Resource name in the Wanaku registry |
| `REST_RESOURCE_URI` | `demo://resource/static/document/architecture.md` | URI of `REST_RESOURCE` |
| `REST_PROMPT` | `simple-prompt` | Prompt without arguments |

### CLI invocation

Use `java -jar "${CLI_JAR}"` directly. Do **not** assign the full command to one variable (for example
`WANAKU_CLI="java -jar path/to/jar"`), because zsh treats it as one token.


---

## Phase S: Setup

### Step S.1: Set environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export CLI_JAR="${CLI_JAR:-apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
export REST_NS="${REST_NS:-rest-$(date +%s)}"
export REST_DENIED_NS="${REST_DENIED_NS:-${REST_NS}-denied}"
export REST_TOOL="${REST_TOOL:-echo}"
export REST_RESOURCE="${REST_RESOURCE:-architecture.md}"
export REST_RESOURCE_URI="${REST_RESOURCE_URI:-demo://resource/static/document/architecture.md}"
export REST_PROMPT="${REST_PROMPT:-simple-prompt}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

## Phase 0: Prerequisite Verification

### Test 0.1: Verify required tools

Run this block as a separate `bash` script, not in the shared session.

```bash
TOOLS_OK=true
for TOOL in java curl jq npx; do
  command -v "${TOOL}" > /dev/null 2>&1 \
    && echo "PASS: ${TOOL} is available" \
    || { echo "FAIL: ${TOOL} is not installed"; TOOLS_OK=false; }
done
[ -f "${CLI_JAR}" ] && echo "PASS: CLI jar found" || { echo "FAIL: ${CLI_JAR} not found"; TOOLS_OK=false; }
[ "${TOOLS_OK}" = "true" ] || { echo "FAIL: missing prerequisites — aborting"; exit 1; }
```

**Expected result:**

- The output includes `PASS: java is available`.
- The output includes `PASS: curl is available`.
- The output includes `PASS: jq is available`.
- The output includes `PASS: npx is available`.
- The output includes `PASS: CLI jar found`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 1: Setup

### Step 1.1: Start Wanaku Server

Follow [common/start-server.md](common/start-server.md).

steps 1 to 6. In step 2b, set
`WANAKU_TEST_GOVERNED_NAMESPACES="${REST_NS}"`. Do not add `REST_DENIED_NS`.


After completion, `WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID` and `WANAKU_SERVER_DATA_DIR` must
be set.

**Expected result:**

- All invoked procedure steps pass (inferred).

### Step 1.2: Start the reference MCP server

Follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md).

with `TEST_MCP_NAMESPACE="${REST_NS}"`.
After completion, `TEST_MCP_URL`, `TEST_MCP_PID` and `TEST_MCP_FORWARD` must be set.

**Expected result:**

- All invoked procedure steps pass (inferred).

### Step 1.3: Create the denied namespace

```bash run
java -jar "${CLI_JAR}" namespaces create "${REST_DENIED_NS}" --host "${WANAKU_SERVER_URL}" --no-auth
[ $? -eq 0 ] && echo "PASS: namespace ${REST_DENIED_NS} created" || echo "FAIL: namespace not created"
```

**Expected result:**

- The output includes `PASS: namespace <REST_DENIED_NS> created`.
- The output contains no line starting with `FAIL:`.

### Test 1.4: The test entries were discovered

Run this block as a separate `bash` script, not in the shared session.

```bash
for KIND in tools resources prompts; do
  case "${KIND}" in
    tools) NAME="${REST_TOOL}" ;;
    resources) NAME="${REST_RESOURCE}" ;;
    prompts) NAME="${REST_PROMPT}" ;;
  esac
  java -jar "${CLI_JAR}" "${KIND}" list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
    | awk 'NR>1 {print $1}' | grep -qx "${NAME}" \
    && echo "PASS: ${KIND} entry '${NAME}' discovered" \
    || { echo "FAIL: ${KIND} entry '${NAME}' not found"; exit 1; }
done
```

**Expected result:**

- The output includes `PASS: tools entry '<NAME>' discovered`.
- The output includes `PASS: resources entry '<NAME>' discovered`.
- The output includes `PASS: prompts entry '<NAME>' discovered`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 2: Service Routes

### Test 2.1: Health endpoints return the envelope

```bash run
for PATH_ in /health /healthz; do
  STATUS=$(curl -s "${WANAKU_SERVER_URL}${PATH_}" | jq -r '.data.status')
  [ "${STATUS}" = "ok" ] && echo "PASS: ${PATH_} reports status ok" || echo "FAIL: ${PATH_} returned '${STATUS}'"
done
```

**Expected result:**

- The output includes `PASS: /health reports status ok`.
- The output includes `PASS: /healthz reports status ok`.
- The output contains no line starting with `FAIL:`.

### Test 2.2: Server information

```bash run
RESPONSE=$(curl -s "${WANAKU_SERVER_URL}/api/v1/management/info")
NAME=$(echo "${RESPONSE}" | jq -r '.data.name')
VERSION=$(echo "${RESPONSE}" | jq -r '.data.version')
if [ "${NAME}" = "wanaku-server" ] && [ -n "${VERSION}" ] && [ "${VERSION}" != "null" ]; then
  echo "PASS: server ${NAME} version ${VERSION}"
else
  echo "FAIL: unexpected info response: ${RESPONSE}"
fi
```

**Expected result:**

- The output includes `PASS: server <NAME> version <VERSION>`.
- The output contains no line starting with `FAIL:`.

### Test 2.3: Statistics match the registry

The statistics route returns `toolsCount`, `resourcesCount`, `promptsCount`, `forwardsCount` and `dataStoresCount`.
`dataStoresCount` is always `0`, because data stores belong to Barn.

```bash run
STATS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/management/statistics")
API_TOOLS=$(echo "${STATS}" | jq '.data.toolsCount')
API_FORWARDS=$(echo "${STATS}" | jq '.data.forwardsCount')
CLI_TOOLS=$(java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null | awk 'NR>1' | wc -l | tr -d ' ')
CLI_FORWARDS=$(java -jar "${CLI_JAR}" forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null | awk 'NR>1' | wc -l | tr -d ' ')
if [ "${API_TOOLS}" = "${CLI_TOOLS}" ] && [ "${API_FORWARDS}" = "${CLI_FORWARDS}" ] && [ "${API_TOOLS}" -gt 0 ]; then
  echo "PASS: statistics match the registry (${API_TOOLS} tools, ${API_FORWARDS} forwards)"
else
  echo "FAIL: statistics ${API_TOOLS}/${API_FORWARDS} differ from CLI ${CLI_TOOLS}/${CLI_FORWARDS}"
fi
echo "${STATS}" | jq -e '.data.dataStoresCount == 0' > /dev/null \
  && echo "PASS: dataStoresCount is 0" || echo "FAIL: dataStoresCount is not 0"
```

**Expected result:**

- The output includes `PASS: statistics match the registry (<API_TOOLS> tools, <API_FORWARDS> forwards)`.
- The output includes `PASS: dataStoresCount is 0`.
- The output contains no line starting with `FAIL:`.

### Test 2.4: OpenAPI document

```bash run
DOC=$(curl -s "${WANAKU_SERVER_URL}/openapi.json")
echo "${DOC}" | jq -e '.openapi | startswith("3.")' > /dev/null \
  && echo "PASS: OpenAPI version $(echo "${DOC}" | jq -r '.openapi')" || echo "FAIL: not an OpenAPI document"
for ROUTE in "/api/v1/tools/{name}/enable" "/api/v1/tools/{name}/disable" "/api/v1/audit/events" "/api/v1/management/statistics"; do
  echo "${DOC}" | jq -e --arg r "${ROUTE}" '.paths | has($r)' > /dev/null \
    && echo "PASS: ${ROUTE} is documented" || echo "FAIL: ${ROUTE} is not documented"
done
V2=$(echo "${DOC}" | jq '[.paths | keys[] | select(startswith("/api/v2"))] | length')
[ "${V2}" -eq 0 ] && echo "PASS: no /api/v2 routes are documented" || echo "FAIL: ${V2} /api/v2 routes are documented"
```

**Expected result:**

- The output includes `PASS: OpenAPI version`.
- The output includes `PASS: /api/v1/tools/{name}/enable is documented`.
- The output includes `PASS: /api/v1/tools/{name}/disable is documented`.
- The output includes `PASS: /api/v1/audit/events is documented`.
- The output includes `PASS: /api/v1/management/statistics is documented`.
- The output includes `PASS: no /api/v2 routes are documented`.
- The output contains no line starting with `FAIL:`.

### Test 2.5: CORS header on management responses

The default value of `WANAKU_CORS_ORIGIN` is `*`.

```bash run
ORIGIN=$(curl -s -D - -o /dev/null "${WANAKU_SERVER_URL}/api/v1/management/info" \
  | tr -d '\r' | awk 'tolower($1)=="access-control-allow-origin:" {print $2}')
[ "${ORIGIN}" = "${WANAKU_CORS_ORIGIN:-*}" ] && echo "PASS: Access-Control-Allow-Origin is '${ORIGIN}'" \
  || echo "FAIL: Access-Control-Allow-Origin is '${ORIGIN}'"
```

**Expected result:**

- The output includes `PASS: Access-Control-Allow-Origin is '<ORIGIN>'`.
- The output contains no line starting with `FAIL:`.

---

## Phase 3: Envelope and Status Codes

### Test 3.1: A successful GET uses the envelope

```bash run
RESPONSE=$(curl -s "${WANAKU_SERVER_URL}/api/v1/tools/${REST_TOOL}")
if echo "${RESPONSE}" | jq -e --arg n "${REST_TOOL}" '.error == null and .data.name == $n and (.data.enabled | type) == "boolean"' > /dev/null; then
  echo "PASS: tool returned in the envelope with an 'enabled' field"
else
  echo "FAIL: unexpected response: ${RESPONSE}"
fi
```

**Expected result:**

- The output includes `PASS: tool returned in the envelope with an 'enabled' field`.
- The output contains no line starting with `FAIL:`.

### Test 3.2: Unknown entries return 404 with an error message

```bash run
for ROUTE in tools/no-such-tool resources/no-such-resource prompts/no-such-prompt \
             forwards/no-such-forward namespaces/no-such-namespace; do
  RESPONSE=$(curl -s -w "\n%{http_code}" "${WANAKU_SERVER_URL}/api/v1/${ROUTE}")
  CODE=$(echo "${RESPONSE}" | tail -1)
  ERROR=$(echo "${RESPONSE}" | head -1 | jq -r '.error')
  if [ "${CODE}" = "404" ] && [ -n "${ERROR}" ] && [ "${ERROR}" != "null" ]; then
    echo "PASS: GET ${ROUTE} returns 404 (${ERROR})"
  else
    echo "FAIL: GET ${ROUTE} returned HTTP ${CODE} (${ERROR})"
  fi
done
```

**Expected result:**

- The output includes `PASS: GET tools/no-such-tool returns 404 (<ERROR>)`.
- The output includes `PASS: GET resources/no-such-resource returns 404 (<ERROR>)`.
- The output includes `PASS: GET prompts/no-such-prompt returns 404 (<ERROR>)`.
- The output includes `PASS: GET forwards/no-such-forward returns 404 (<ERROR>)`.
- The output includes `PASS: GET namespaces/no-such-namespace returns 404 (<ERROR>)`.
- The output contains no line starting with `FAIL:`.

### Test 3.3: Invalid namespace name returns 400

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/namespaces" \
  -H "Content-Type: application/json" -d '{"name":"Bad Name"}')
[ "${CODE}" = "400" ] && echo "PASS: invalid namespace name returns 400" || echo "FAIL: returned HTTP ${CODE}"
```

**Expected result:**

- The output includes `PASS: invalid namespace name returns 400`.
- The output contains no line starting with `FAIL:`.

### Test 3.4: Malformed JSON returns 400

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/namespaces" \
  -H "Content-Type: application/json" -d '{bad')
[ "${CODE}" = "400" ] && echo "PASS: malformed JSON returns 400" || echo "FAIL: returned HTTP ${CODE}"
```

**Expected result:**

- The output includes `PASS: malformed JSON returns 400`.
- The output contains no line starting with `FAIL:`.

### Test 3.5: Forward without an address returns 400

```bash run
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/forwards" \
  -H "Content-Type: application/json" -d "{\"name\":\"${REST_NS}-noaddr\"}")
CODE=$(echo "${RESPONSE}" | tail -1)
echo "${RESPONSE}" | head -1 | jq -r '.error' | grep -q "address" && [ "${CODE}" = "400" ] \
  && echo "PASS: missing forward address returns 400" || echo "FAIL: returned HTTP ${CODE}"
```

**Expected result:**

- The output includes `PASS: missing forward address returns 400`.
- The output contains no line starting with `FAIL:`.

### Test 3.6: Duplicate A2A agent returns 409

```bash run
AGENT="{\"name\":\"${REST_NS}-agent\",\"namespace\":\"${REST_NS}\",\"description\":\"rest plan\",\"address\":\"http://localhost:9/\"}"
FIRST=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/agents" \
  -H "Content-Type: application/json" -d "${AGENT}")
SECOND=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/agents" \
  -H "Content-Type: application/json" -d "${AGENT}")
if [ "${FIRST}" = "200" ] && [ "${SECOND}" = "409" ]; then
  echo "PASS: duplicate agent returns 409"
else
  echo "FAIL: first create HTTP ${FIRST}, duplicate HTTP ${SECOND}"
fi
curl -s -o /dev/null -X DELETE "${WANAKU_SERVER_URL}/api/v1/agents/${REST_NS}/${REST_NS}-agent"
```

**Expected result:**

- The output includes `PASS: duplicate agent returns 409`.
- The output contains no line starting with `FAIL:`.

### Test 3.7: Creating an existing namespace does not duplicate it

The namespace create route is idempotent. It returns `200` and keeps one entry.

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/namespaces" \
  -H "Content-Type: application/json" -d "{\"name\":\"${REST_NS}\"}")
COUNT=$(java -jar "${CLI_JAR}" namespaces list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
  | awk 'NR>1 {print $1}' | grep -cx "${REST_NS}")
[ "${CODE}" = "200" ] && [ "${COUNT}" -eq 1 ] && echo "PASS: namespace create is idempotent" \
  || echo "FAIL: HTTP ${CODE}, ${COUNT} entries named ${REST_NS}"
```

**Expected result:**

- The output includes `PASS: namespace create is idempotent`.
- The output contains no line starting with `FAIL:`.

---

## Phase 4: Enable and Disable

The routes `PUT /api/v1/{tools,resources,prompts}/{name}/{enable,disable}` have no CLI command. A disabled entry
stays in the management API with `"enabled": false`. MCP clients do not see it, and calls to it are rejected.

### Test 4.1: Disable a tool

```bash run
RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/${REST_TOOL}/disable")
CODE=$(echo "${RESPONSE}" | tail -1)
ENABLED=$(echo "${RESPONSE}" | head -1 | jq '.data.enabled')
[ "${CODE}" = "200" ] && [ "${ENABLED}" = "false" ] && echo "PASS: tool disabled" \
  || echo "FAIL: HTTP ${CODE}, enabled=${ENABLED}"
```

**Expected result:**

- The output includes `PASS: tool disabled`.
- The output contains no line starting with `FAIL:`.

### Test 4.2: The disabled tool stays in the management API

```bash run
ENABLED=$(curl -s "${WANAKU_SERVER_URL}/api/v1/tools" | jq --arg n "${REST_TOOL}" '.data[] | select(.name == $n) | .enabled')
[ "${ENABLED}" = "false" ] && echo "PASS: tool listed with enabled=false" || echo "FAIL: enabled=${ENABLED}"
```

**Expected result:**

- The output includes `PASS: tool listed with enabled=false`.
- The output contains no line starting with `FAIL:`.

### Test 4.3: MCP clients do not see or call the disabled tool

```bash run
MCP_URI="${WANAKU_MCP_BASE_URL}/${REST_NS}/mcp"
java -jar "${CLI_JAR}" mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null | awk '{print $1}' | grep -qx "${REST_TOOL}" \
  && echo "FAIL: disabled tool is in tools/list" || echo "PASS: disabled tool is not in tools/list"

OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${MCP_URI}" --no-auth --name "${REST_TOOL}" --param message=rest --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "tool not found: ${REST_TOOL}"; then
  echo "PASS: call to the disabled tool is rejected"
else
  echo "FAIL: call to the disabled tool was not rejected (exit ${EXIT_CODE})"
fi
```

**Expected result:**

- The output includes `PASS: disabled tool is not in tools/list`.
- The output includes `PASS: call to the disabled tool is rejected`.
- The output contains no line starting with `FAIL:`.

### Test 4.4: The disabled state survives a forward refresh

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/forwards/${TEST_MCP_FORWARD}/refreshes")
ENABLED=$(curl -s "${WANAKU_SERVER_URL}/api/v1/tools/${REST_TOOL}" | jq '.data.enabled')
[ "${CODE}" = "200" ] && [ "${ENABLED}" = "false" ] && echo "PASS: refresh keeps the tool disabled" \
  || echo "FAIL: refresh HTTP ${CODE}, enabled=${ENABLED}"
```

**Expected result:**

- The output includes `PASS: refresh keeps the tool disabled`.
- The output contains no line starting with `FAIL:`.

### Test 4.5: Enable the tool again

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/${REST_TOOL}/enable")
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${MCP_URI}" --no-auth --name "${REST_TOOL}" --param message=rest-enabled --plain 2>&1)
if [ "${CODE}" = "200" ] && echo "${OUTPUT}" | grep -q "Echo: rest-enabled"; then
  echo "PASS: enabled tool answers through MCP"
else
  echo "FAIL: enable HTTP ${CODE}; output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: enabled tool answers through MCP`.
- The output contains no line starting with `FAIL:`.

### Test 4.6: Disable and enable a resource

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/resources/${REST_RESOURCE}/disable")
java -jar "${CLI_JAR}" mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "${REST_RESOURCE_URI}" --plain > /dev/null 2>&1
READ_WHILE_DISABLED=$?
curl -s -o /dev/null -X PUT "${WANAKU_SERVER_URL}/api/v1/resources/${REST_RESOURCE}/enable"
java -jar "${CLI_JAR}" mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "${REST_RESOURCE_URI}" --plain > /dev/null 2>&1
READ_WHILE_ENABLED=$?
if [ "${CODE}" = "200" ] && [ "${READ_WHILE_DISABLED}" -ne 0 ] && [ "${READ_WHILE_ENABLED}" -eq 0 ]; then
  echo "PASS: resource read follows the enabled state"
else
  echo "FAIL: disable HTTP ${CODE}, read exit codes ${READ_WHILE_DISABLED}/${READ_WHILE_ENABLED}"
fi
```

**Expected result:**

- The output includes `PASS: resource read follows the enabled state`.
- The output contains no line starting with `FAIL:`.

### Test 4.7: Disable and enable a prompt

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/prompts/${REST_PROMPT}/disable")
LISTED=$(java -jar "${CLI_JAR}" mcp prompt list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null | awk '{print $1}' | grep -cx "${REST_PROMPT}")
curl -s -o /dev/null -X PUT "${WANAKU_SERVER_URL}/api/v1/prompts/${REST_PROMPT}/enable"
java -jar "${CLI_JAR}" mcp prompt --uri "${MCP_URI}" --no-auth --name "${REST_PROMPT}" --plain > /dev/null 2>&1
GET_EXIT=$?
if [ "${CODE}" = "200" ] && [ "${LISTED}" -eq 0 ] && [ "${GET_EXIT}" -eq 0 ]; then
  echo "PASS: prompt visibility follows the enabled state"
else
  echo "FAIL: disable HTTP ${CODE}, listed while disabled=${LISTED}, get exit=${GET_EXIT}"
fi
```

**Expected result:**

- The output includes `PASS: prompt visibility follows the enabled state`.
- The output contains no line starting with `FAIL:`.

### Test 4.8: Unknown entries return 404 (negative)

```bash run
for KIND in tools resources prompts; do
  for ACTION in enable disable; do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/${KIND}/no-such-entry/${ACTION}")
    [ "${CODE}" = "404" ] && echo "PASS: ${ACTION} unknown ${KIND} entry returns 404" \
      || echo "FAIL: ${ACTION} unknown ${KIND} entry returned HTTP ${CODE}"
  done
done
```

**Expected result:**

- The output includes `PASS: enable unknown tools entry returns 404`.
- The output includes `PASS: disable unknown tools entry returns 404`.
- The output includes `PASS: enable unknown resources entry returns 404`.
- The output includes `PASS: disable unknown resources entry returns 404`.
- The output includes `PASS: enable unknown prompts entry returns 404`.
- The output includes `PASS: disable unknown prompts entry returns 404`.
- The output contains no line starting with `FAIL:`.

### Test 4.9: A deleted tool comes back after a refresh

Deleting a discovered entry is not permanent. Disable the entry instead (Test 4.1).

```bash run
DEL=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "${WANAKU_SERVER_URL}/api/v1/tools/get-sum")
GONE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/api/v1/tools/get-sum")
curl -s -o /dev/null -X POST "${WANAKU_SERVER_URL}/api/v1/forwards/${TEST_MCP_FORWARD}/refreshes"
BACK=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/api/v1/tools/get-sum")
[ "${DEL}" = "200" ] && [ "${GONE}" = "404" ] && [ "${BACK}" = "200" ] \
  && echo "PASS: deleted tool rediscovered by refresh" || echo "FAIL: delete ${DEL}, after delete ${GONE}, after refresh ${BACK}"
```

**Expected result:**

- The output includes `PASS: deleted tool rediscovered by refresh`.
- The output contains no line starting with `FAIL:`.

---

## Phase 5: Metrics

`GET /api/v1/metrics` returns in-process counters. Wanaku Server does not export Prometheus or OpenTelemetry
metrics.

### Test 5.1: Metrics sections are present

```bash run
KEYS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics" | jq -r '.data | keys | join(",")')
for SECTION in filters pipeline audit gauges evaluators; do
  echo "${KEYS}" | tr ',' '\n' | grep -qx "${SECTION}" \
    && echo "PASS: metrics section '${SECTION}' present" || echo "FAIL: metrics section '${SECTION}' missing"
done
```

**Expected result:**

- The output includes `PASS: metrics section 'filters' present`.
- The output includes `PASS: metrics section 'pipeline' present`.
- The output includes `PASS: metrics section 'audit' present`.
- The output includes `PASS: metrics section 'gauges' present`.
- The output includes `PASS: metrics section 'evaluators' present`.
- The output contains no line starting with `FAIL:`.

### Test 5.2: The tool call filter counter increases

```bash run
BEFORE=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics" | jq '.data.filters.wanaku_tool_call.duration.count // 0')
java -jar "${CLI_JAR}" mcp tool --uri "${MCP_URI}" --no-auth --name "${REST_TOOL}" --param message=metrics --plain > /dev/null 2>&1
AFTER=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics" | jq '.data.filters.wanaku_tool_call.duration.count // 0')
[ "${AFTER}" -gt "${BEFORE}" ] && echo "PASS: wanaku_tool_call count ${BEFORE} -> ${AFTER}" \
  || echo "FAIL: wanaku_tool_call count did not increase (${BEFORE} -> ${AFTER})"
```

**Expected result:**

- The output includes `PASS: wanaku_tool_call count <BEFORE> -> <AFTER>`.
- The output contains no line starting with `FAIL:`.

---

## Phase 6: Audit Trail

The audit trail records governance decisions for MCP requests. Wanaku uses the HTTP `x-request-id` header as the
correlation ID when the header is present. This phase uses `curl` for the MCP request, because the CLI cannot set the
header.

### Test 6.1: Audit store health and schema

```bash run
HEALTHY=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/health" | jq '.data.healthy')
SCHEMA=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/schema" | jq -r '.data.schema_version')
[ "${HEALTHY}" = "true" ] && echo "PASS: audit store healthy" || echo "FAIL: audit store healthy=${HEALTHY}"
[ "${SCHEMA}" = "1.0" ] && echo "PASS: audit schema 1.0" || echo "FAIL: audit schema ${SCHEMA}"
```

**Expected result:**

- The output includes `PASS: audit store healthy`.
- The output includes `PASS: audit schema 1.0`.
- The output contains no line starting with `FAIL:`.

### Test 6.2: A denied call is audited with its request ID

```bash run
DENIED_RID="${REST_NS}-denied-$$"
curl -s -X POST "${WANAKU_MCP_BASE_URL}/${REST_DENIED_NS}/mcp" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "x-request-id: ${DENIED_RID}" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"${REST_TOOL}\",\"arguments\":{\"message\":\"x\"}}}" \
  | jq -e '.error.code == -32003' > /dev/null \
  && echo "PASS: call in ${REST_DENIED_NS} denied with -32003" || echo "FAIL: call in ${REST_DENIED_NS} was not denied"

EVENT=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${DENIED_RID}" | jq '.data.events[0]')
if echo "${EVENT}" | jq -e '.decision == "block" and .reason_code == "governance_no_match" and .operation == "tools/call"' > /dev/null; then
  echo "PASS: denial audited (event $(echo "${EVENT}" | jq -r '.event_id'))"
else
  echo "FAIL: no matching audit event for ${DENIED_RID}: ${EVENT}"
fi
```

**Expected result:**

- The output includes `PASS: call in <REST_DENIED_NS> denied with -32003`.
- The output includes `PASS: denial audited (event`.
- The output contains no line starting with `FAIL:`.

### Test 6.3: An allowed call is audited

```bash run
ALLOWED_RID="${REST_NS}-allowed-$$"
curl -s -o /dev/null -X POST "${WANAKU_MCP_BASE_URL}/${REST_NS}/mcp" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "x-request-id: ${ALLOWED_RID}" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"${REST_TOOL}\",\"arguments\":{\"message\":\"x\"}}}"
DECISIONS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${ALLOWED_RID}" | jq -r '[.data.events[].decision] | unique | join(",")')
[ "${DECISIONS}" = "allow" ] && echo "PASS: allowed call audited with decision allow" \
  || echo "FAIL: decisions for ${ALLOWED_RID}: '${DECISIONS}'"
```

**Expected result:**

- The output includes `PASS: allowed call audited with decision allow`.
- The output contains no line starting with `FAIL:`.

### Test 6.4: Get one event by ID

```bash run
EVENT_ID=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${DENIED_RID}" | jq -r '.data.events[0].event_id')
GOT=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events/${EVENT_ID}" | jq -r '.data.event_id')
[ "${GOT}" = "${EVENT_ID}" ] && echo "PASS: event ${EVENT_ID} retrieved by ID" || echo "FAIL: got '${GOT}'"
```

**Expected result:**

- The output includes `PASS: event <EVENT_ID> retrieved by ID`.
- The output contains no line starting with `FAIL:`.

### Test 6.5: Filters and pagination

```bash run
PAGE=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?decision=block&namespace=${REST_DENIED_NS}&limit=1&offset=0")
echo "${PAGE}" | jq -e '.data.limit == 1 and (.data.events | length) <= 1 and .data.total >= 1' > /dev/null \
  && echo "PASS: decision/namespace filter with limit=1 works" || echo "FAIL: unexpected page: ${PAGE}"
echo "${PAGE}" | jq -e --arg ns "${REST_DENIED_NS}" '[.data.events[] | select(.namespace != $ns or .decision != "block")] | length == 0' > /dev/null \
  && echo "PASS: page contains only matching events" || echo "FAIL: page contains non-matching events"
```

**Expected result:**

- The output includes `PASS: decision/namespace filter with limit=1 works`.
- The output includes `PASS: page contains only matching events`.
- The output contains no line starting with `FAIL:`.

### Test 6.6: Unknown event ID returns 404 (negative)

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/api/v1/audit/events/no-such-event")
[ "${CODE}" = "404" ] && echo "PASS: unknown audit event returns 404" || echo "FAIL: returned HTTP ${CODE}"
```

**Expected result:**

- The output includes `PASS: unknown audit event returns 404`.
- The output contains no line starting with `FAIL:`.

---

## Phase 7: Removed Endpoints and Other Negative Tests

### Test 7.1: Java router endpoints return 404

```bash run
for ROUTE in /api/v2/tool-calls/history /api/v2/tool-calls/notifications /api/v2/code-execution-engine/execute \
             /q/health/ready /q/openapi /q/metrics /api/v1/capabilities /api/v1/management/discovery /api/v1/data-store; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}${ROUTE}")
  [ "${CODE}" = "404" ] && echo "PASS: ${ROUTE} returns 404" || echo "FAIL: ${ROUTE} returned HTTP ${CODE}"
done
```

**Expected result:**

- The output includes `PASS: /api/v2/tool-calls/history returns 404`.
- The output includes `PASS: /api/v2/tool-calls/notifications returns 404`.
- The output includes `PASS: /api/v2/code-execution-engine/execute returns 404`.
- The output includes `PASS: /q/health/ready returns 404`.
- The output includes `PASS: /q/openapi returns 404`.
- The output includes `PASS: /q/metrics returns 404`.
- The output includes `PASS: /api/v1/capabilities returns 404`.
- The output includes `PASS: /api/v1/management/discovery returns 404`.
- The output includes `PASS: /api/v1/data-store returns 404`.
- The output contains no line starting with `FAIL:`.

### Test 7.2: Forwards cannot be updated in place

There is no `PUT /api/v1/forwards/{name}`. Remove and add the forward instead.

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/forwards/${TEST_MCP_FORWARD}" \
  -H "Content-Type: application/json" -d '{}')
[ "${CODE}" = "404" ] && echo "PASS: PUT on a forward returns 404" || echo "FAIL: returned HTTP ${CODE}"
```

**Expected result:**

- The output includes `PASS: PUT on a forward returns 404`.
- The output contains no line starting with `FAIL:`.

### Test 7.3: Refresh of an unknown forward returns 404

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/forwards/no-such-forward/refreshes")
[ "${CODE}" = "404" ] && echo "PASS: refresh of unknown forward returns 404" || echo "FAIL: returned HTTP ${CODE}"
```

**Expected result:**

- The output includes `PASS: refresh of unknown forward returns 404`.
- The output contains no line starting with `FAIL:`.

### Test 7.4: Bare /mcp path is rejected

```bash run
MESSAGE=$(curl -s -X POST "${WANAKU_MCP_BASE_URL}/mcp" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq -r '.error.message')
echo "${MESSAGE}" | grep -q "use /{namespace}/mcp" && echo "PASS: bare /mcp rejected (${MESSAGE})" \
  || echo "FAIL: unexpected answer '${MESSAGE}'"
```

**Expected result:**

- The output includes `PASS: bare /mcp rejected (<MESSAGE>)`.
- The output contains no line starting with `FAIL:`.

---

## Phase 8: Cleanup

All cleanup steps are idempotent.

### Step 8.1: Remove the test namespaces

```bash run
for NS in "${REST_DENIED_NS}" "${REST_NS}"; do
  java -jar "${CLI_JAR}" namespaces delete "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth 2>/dev/null || true
done
echo "PASS: test namespaces removed (or already absent)"
```

**Expected result:**

- The output includes `PASS: test namespaces removed (or already absent)`.

### Step 8.2: Stop the reference MCP server

Use the [Shutdown](common/start-test-mcp-server.md#shutdown) section of
[common/start-test-mcp-server.md](common/start-test-mcp-server.md). It removes the forward and its discovered entries.

Remove the forward first. Wanaku Server also removes the entries that it discovered from the forward.

The entry plan runs the original shutdown block in its local cleanup unit.

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 8.3: Stop Wanaku Server

Use the [Shutdown](common/start-server.md#shutdown) section of [common/start-server.md](common/start-server.md).

Stop the server with the PID captured at startup. Then remove the data directory of the run.

The entry plan runs the original shutdown block in its local cleanup unit.

Do **not** use `kill -9`. It bypasses the orderly Pingora shutdown and the final registry snapshot. If Pingora worker
processes remain, find them with `lsof -ti :8081` and stop them with `SIGTERM`.

**Expected result:**

- The commands exit with code 0 (inferred).

---

## Test Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | 0.1 | Verify required tools | Critical |
| 1 | 1.1 | Start Wanaku Server | Critical |
| 1 | 1.2 | Start the reference MCP server | Critical |
| 1 | 1.3 | Create the denied namespace | High |
| 1 | 1.4 | The test entries were discovered | Critical |
| 2 | 2.1 | Health endpoints return the envelope | Critical |
| 2 | 2.2 | Server information | High |
| 2 | 2.3 | Statistics match the registry | High |
| 2 | 2.4 | OpenAPI document | High |
| 2 | 2.5 | CORS header on management responses | Medium |
| 3 | 3.1 | A successful GET uses the envelope | High |
| 3 | 3.2 | Unknown entries return 404 | High |
| 3 | 3.3 | Invalid namespace name returns 400 | High |
| 3 | 3.4 | Malformed JSON returns 400 | High |
| 3 | 3.5 | Forward without an address returns 400 | Medium |
| 3 | 3.6 | Duplicate A2A agent returns 409 | Medium |
| 3 | 3.7 | Creating an existing namespace does not duplicate it | Medium |
| 4 | 4.1 | Disable a tool | Critical |
| 4 | 4.2 | The disabled tool stays in the management API | High |
| 4 | 4.3 | MCP clients do not see or call the disabled tool | Critical |
| 4 | 4.4 | The disabled state survives a forward refresh | High |
| 4 | 4.5 | Enable the tool again | Critical |
| 4 | 4.6 | Disable and enable a resource | High |
| 4 | 4.7 | Disable and enable a prompt | High |
| 4 | 4.8 | Unknown entries return 404 (negative) | Medium |
| 4 | 4.9 | A deleted tool comes back after a refresh | Medium |
| 5 | 5.1 | Metrics sections are present | Medium |
| 5 | 5.2 | The tool call filter counter increases | Medium |
| 6 | 6.1 | Audit store health and schema | High |
| 6 | 6.2 | A denied call is audited with its request ID | Critical |
| 6 | 6.3 | An allowed call is audited | High |
| 6 | 6.4 | Get one event by ID | High |
| 6 | 6.5 | Filters and pagination | Medium |
| 6 | 6.6 | Unknown event ID returns 404 (negative) | Medium |
| 7 | 7.1 | Java router endpoints return 404 | High |
| 7 | 7.2 | Forwards cannot be updated in place | Medium |
| 7 | 7.3 | Refresh of an unknown forward returns 404 | Medium |
| 7 | 7.4 | Bare /mcp path is rejected | Medium |
| 8 | 8.1 | Remove the test namespaces | High |
| 8 | 8.2 | Stop the reference MCP server | Critical |
| 8 | 8.3 | Stop Wanaku Server | Critical |
| Setup | S.1 | Set environment variables | Setup |
