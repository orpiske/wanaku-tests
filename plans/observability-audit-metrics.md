# Test Plan: Observability — Audit Trail, Request Correlation, Metrics and Logs

## Overview

This plan verifies the observability features of Wanaku Server, and the OpenTelemetry support that remains in Wanaku
Barn.

The earlier version of this plan tested OpenTelemetry traces, Micrometer/Prometheus metrics and the
`x-wanaku-request-id` header of the Java router. That router is gone. Wanaku Server (Rust) is not built with
OpenTelemetry export and has no Prometheus endpoint. It gives these equivalents, which this plan tests:

| Need | Java router (removed) | Wanaku Server |
|------|-----------------------|---------------|
| Who did what, and why was it allowed or denied | Traces, logs | Governance audit trail: `GET /api/v1/audit/events` |
| Correlate one request across records | `traceparent`, `x-wanaku-request-id` | `x-request-id` HTTP header, `x-request-id` tool argument or JSON-RPC `id` → `correlation_id` of the audit events and fields of the `tools/call` log line |
| Counters | `/q/metrics` (Prometheus) | `GET /api/v1/metrics` (JSON snapshot, in memory) and `GET /api/v1/management/statistics` (registry counts) |
| Structured logs | `traceId`/`requestId` MDC keys | `RUST_LOG` level filter, `PRAXIS_LOG_FORMAT=json` |
| Version | `/q/info` | `GET /api/v1/management/info` |

Wanaku Barn still contains the Quarkus OpenTelemetry and Micrometer extensions. Barn serves only REST APIs (service
catalogs, data stores, ...), so its traces cover Barn HTTP requests only. Phase 10 tests this Barn-only support.

Wanaku Server is fail-safe: it denies every `tools/call`, `resources/read` and `prompts/get` that no governance rule
allows. Each denial is an audit event. The plan uses this behavior first (Phase 3), then restarts the server with a test
governance configuration (Phase 4).

**Components under test:**

| Component | Needed | Role in this plan |
|-----------|--------|-------------------|
| Wanaku Server | Yes | Audit trail, correlation, metrics, logs |
| Upstream test MCP server | Yes | Supplies the `echo` and `get-sum` tools through the forward `everything` |
| Wanaku Barn | Only Phase 10 | Prometheus metrics and optional OpenTelemetry export |

No Kubernetes cluster is necessary. Every step is fully automatable.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java -version` |
| `curl` | any | `curl --version` |
| `jq` | 1.6+ | `jq --version` |
| `npx` (Node.js) | 18+ | `npx --version` |
| `python3` | 3.8+ | `python3 --version` (Phase 10.3 only) |

### Environment variables

The server logs in JSON at the `info` level for this plan. Export these variables **before** you start the server.
The server process inherits them:

`RUST_LOG` uses the `tracing` filter syntax, for example `info,wanaku_filters=trace`. Without
`PRAXIS_LOG_FORMAT=json`, the server writes text logs with ANSI color codes, also to a file.

---

## Phase S: Setup

### Step S.1: Set environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export CLI_JAR="${CLI_JAR:-${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
# Prefix of the correlation IDs that this run sends
export OBS_RUN_ID="${OBS_RUN_ID:-obs-$(date +%s)}"
# Optional Barn phase
export WANAKU_BARN_OTEL_PORT="${WANAKU_BARN_OTEL_PORT:-8181}"
export OTLP_RECEIVER_PORT="${OTLP_RECEIVER_PORT:-4318}"

wanaku() { java -jar "${CLI_JAR}" "$@"; }
```

**Expected result:**

- The commands exit with code 0 (inferred).


### Step S.2: Set environment variables

```bash run
export RUST_LOG="${RUST_LOG:-info}"
export PRAXIS_LOG_FORMAT=json
```

**Expected result:**

- The commands exit with code 0 (inferred).


## Phase 0: Prerequisites

### Test 0.1: Verify tools

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
for CMD in java curl jq npx; do
  command -v "${CMD}" > /dev/null 2>&1 && echo "PASS: ${CMD} found" || { echo "FAIL: ${CMD} is not installed"; FAIL=1; }
done
[ -f "${CLI_JAR}" ] && echo "PASS: ${CLI_JAR} exists" || { echo "FAIL: ${CLI_JAR} not found"; FAIL=1; }
[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || exit 1
```

**Expected result:**

- The output includes `PASS: java found`.
- The output includes `PASS: curl found`.
- The output includes `PASS: jq found`.
- The output includes `PASS: npx found`.
- The output includes `PASS: <CLI_JAR> exists`.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server with the default (fail-safe) governance

Use [common/start-server.md](common/start-server.md). Do **not** do step 2b in this phase. After completion,
`WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID`, `WANAKU_SERVER_DATA_DIR` and `WANAKU_SERVER_LOG` must
be set.

The server reads `wanaku.yaml` from its working directory when `--wanaku-config` is not given. Make sure that the
current directory has no `wanaku.yaml`, or Phase 3 does not get the default posture.

```bash
[ -f wanaku.yaml ] && echo "FAIL: remove ./wanaku.yaml before Phase 1" || echo "PASS: no wanaku.yaml in the working directory"
```

#### Procedure step 1: Build Wanaku Server

Skip this step when `WANAKU_SERVER_BIN` points to a prebuilt binary.

The default build embeds the admin UI. It builds the UI with Node.js and Yarn. To build without the admin UI (headless),
add `--no-default-features`. The headless build is sufficient for CLI and MCP test plans.

Run this block as a separate `bash` script, not in the shared session.

```bash
export WANAKU_SERVER_REPO_ROOT="${WANAKU_SERVER_REPO_ROOT:-../wanaku}"

if [ -z "${WANAKU_SERVER_BIN}" ]; then
  (cd "${WANAKU_SERVER_REPO_ROOT}" && cargo build --release -p wanaku-server)
  WANAKU_SERVER_BIN="${WANAKU_SERVER_REPO_ROOT}/target/release/wanaku-server"
fi

if [ -x "${WANAKU_SERVER_BIN}" ]; then
  echo "PASS: ${WANAKU_SERVER_BIN} exists"
else
  echo "FAIL: ${WANAKU_SERVER_BIN} not found or not executable"
  exit 1
fi
export WANAKU_SERVER_BIN
```

#### Procedure step 2: Verify the ports are free

A server left over from an earlier run makes the test results unreliable.

Run this block as a separate `bash` script, not in the shared session.

```bash
export WANAKU_SERVER_MGMT_PORT="${WANAKU_SERVER_MGMT_PORT:-8080}"
for PORT in "${WANAKU_SERVER_MGMT_PORT}" 8081; do
  if curl -s -o /dev/null "http://localhost:${PORT}/" 2>/dev/null; then
    echo "FAIL: port ${PORT} is already in use — stop the process that uses it"
    exit 1
  fi
  echo "PASS: port ${PORT} is free"
done
```

#### Procedure step 3: Start Wanaku Server

Each run uses a new data directory. This keeps the run independent of `$HOME/.wanaku/server` and of earlier runs.
Authentication is disabled by default in Wanaku Server, so no extra configuration is necessary.

The server process inherits the exported environment. Export other variables before this step when the plan needs
them, for example `RUST_LOG`, `PRAXIS_LOG_FORMAT=json` or `WANAKU_AUTH_ISSUER`.

```bash
export WANAKU_SERVER_DATA_DIR="${WANAKU_SERVER_DATA_DIR:-$(mktemp -d)}"
export WANAKU_SERVER_LOG="${WANAKU_SERVER_LOG:-${WANAKU_SERVER_DATA_DIR}/wanaku-server.log}"
export WANAKU_SERVER_URL="http://localhost:${WANAKU_SERVER_MGMT_PORT}"
export WANAKU_MCP_BASE_URL="http://localhost:8081"

WANAKU_MGMT_LISTEN="0.0.0.0:${WANAKU_SERVER_MGMT_PORT}" \
WANAKU_PERSIST_BACKEND=file \
WANAKU_PERSIST_PATH="${WANAKU_SERVER_DATA_DIR}" \
  "${WANAKU_SERVER_BIN}" ${WANAKU_SERVER_CONFIG:+--wanaku-config "${WANAKU_SERVER_CONFIG}"} \
  > "${WANAKU_SERVER_LOG}" 2>&1 &
WANAKU_SERVER_PID=$!
export WANAKU_SERVER_PID
echo "Wanaku Server started with PID ${WANAKU_SERVER_PID} (data: ${WANAKU_SERVER_DATA_DIR})"
```

#### Procedure step 4: Wait for health

`curl` is used here to test HTTP-level readiness. `GET /health` returns the standard response envelope.

Run this block as a separate `bash` script, not in the shared session.

```bash
MAX_RETRIES=30
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: Wanaku Server is healthy (attempt ${i})"
    break
  fi
  if [ "${i}" -eq "${MAX_RETRIES}" ]; then
    echo "FAIL: Wanaku Server not healthy after ${MAX_RETRIES} attempts (last HTTP ${HTTP_CODE})"
    tail -20 "${WANAKU_SERVER_LOG}"
    kill "${WANAKU_SERVER_PID}" 2>/dev/null || true
    exit 1
  fi
  sleep 1
done
```

#### Procedure step 5: Verify the MCP listener

The MCP listener accepts only `/<namespace>/mcp`. The `default` namespace always exists.

Run this block as a separate `bash` script, not in the shared session.

```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_MCP_BASE_URL}/default/mcp" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
if [ "${HTTP_CODE}" = "200" ]; then
  echo "PASS: MCP endpoint ${WANAKU_MCP_BASE_URL}/default/mcp answers"
else
  echo "FAIL: MCP endpoint returned HTTP ${HTTP_CODE}"
  exit 1
fi
```

#### Procedure step 6: Verify the CLI can connect

Use the CLI jar from this repository (see
[contributing-test-plans.md](../../../docs/contributing-test-plans.md#use-the-wanaku-cli-not-raw-api-clients)).
Wanaku Server has no built-in authentication, so pass `--no-auth`.

Run this block as a separate `bash` script, not in the shared session.

```bash
CLI_JAR="${CLI_JAR:-apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: CLI can connect to Wanaku Server"
else
  echo "FAIL: CLI cannot connect to Wanaku Server (exit code ${EXIT_CODE})"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: <WANAKU_SERVER_BIN> exists`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- The output includes `PASS: port <WANAKU_SERVER_MGMT_PORT> is free`.
- The output includes `PASS: port 8081 is free`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- The commands exit with code 0 (inferred).
- The output includes `PASS: Wanaku Server is healthy`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- The output includes `PASS: MCP endpoint <WANAKU_MCP_BASE_URL>/default/mcp answers`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- The output includes `PASS: CLI can connect to Wanaku Server`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- The output includes `PASS: no wanaku.yaml in the working directory`.


### Step 1.2: Start the upstream test MCP server

Follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md) with the default values. It registers the
forward `everything` in the namespace `default`. After completion, `TEST_MCP_URL` and `TEST_MCP_PID` must be set.

```bash run
export MCP_URI="${WANAKU_MCP_BASE_URL}/default/mcp"
```

**Expected result:**

- The commands exit with code 0 (inferred).


### Test 1.3: Server name and version

```bash run
INFO=$(curl -s "${WANAKU_SERVER_URL}/api/v1/management/info")
echo "${INFO}" | jq -e '.error == null and .data.name == "wanaku-server" and (.data.version | length > 0)' > /dev/null \
  && echo "PASS: info reports $(echo "${INFO}" | jq -r '.data.name + " " + .data.version')" \
  || echo "FAIL: unexpected info response: ${INFO}"
```

**Expected result:**

- The output includes `PASS: info reports`.
- The output contains no line starting with `FAIL:`.


### Test 1.4: Registry statistics match the CLI lists

```bash run
STATS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/management/statistics")
TOOLS_CLI=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null | tail -n +2 | grep -c .)
FORWARDS_CLI=$(wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null | tail -n +2 | grep -c .)

[ "$(echo "${STATS}" | jq '.data.toolsCount')" = "${TOOLS_CLI}" ] \
  && echo "PASS: toolsCount equals the CLI tool count (${TOOLS_CLI})" || echo "FAIL: toolsCount mismatch: ${STATS}"
[ "$(echo "${STATS}" | jq '.data.forwardsCount')" = "${FORWARDS_CLI}" ] \
  && echo "PASS: forwardsCount equals the CLI forward count (${FORWARDS_CLI})" || echo "FAIL: forwardsCount mismatch"
echo "${STATS}" | jq -e '.data | has("resourcesCount") and has("promptsCount") and .dataStoresCount == 0' > /dev/null \
  && echo "PASS: resource, prompt and data store counts present (data stores are Barn-only, so 0)" \
  || echo "FAIL: statistics fields missing"
```

**Expected result:**

- The output includes `PASS: toolsCount equals the CLI tool count (<TOOLS_CLI>)`.
- The output includes `PASS: forwardsCount equals the CLI forward count (<FORWARDS_CLI>)`.
- The output includes `PASS: resource, prompt and data store counts present (data stores are Barn-only, so 0)`.
- The output contains no line starting with `FAIL:`.


---

## Phase 2: Observability Endpoints — Baseline

These are API routes without CLI commands, so the plan uses `curl`.

### Test 2.1: Audit schema version and store health

```bash run
curl -s "${WANAKU_SERVER_URL}/api/v1/audit/schema" | jq -e '.data.schema_version == "1.0"' > /dev/null \
  && echo "PASS: audit schema version 1.0" || echo "FAIL: unexpected audit schema version"

HEALTH=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/health")
echo "${HEALTH}" | jq -e '.data.healthy == true and .data.dropped_events == 0 and .data.capacity == 10000 and .data.last_error == null' \
  > /dev/null && echo "PASS: audit store healthy (capacity 10000)" || echo "FAIL: unexpected audit health: ${HEALTH}"
```

**Expected result:**

- The output includes `PASS: audit schema version 1.0`.
- The output includes `PASS: audit store healthy (capacity 10000)`.
- The output contains no line starting with `FAIL:`.


### Test 2.2: Metrics snapshot shape

```bash run
METRICS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics")
echo "${METRICS}" | jq -e '.data | has("filters") and has("evaluators") and has("evaluator_governance")
  and has("pipeline") and has("gauges") and .audit.storage_failures == 0' > /dev/null \
  && echo "PASS: metrics snapshot has the expected sections" || echo "FAIL: unexpected metrics: ${METRICS}"
```

**Expected result:**

- The output includes `PASS: metrics snapshot has the expected sections`.
- The output contains no line starting with `FAIL:`.


### Test 2.3: No Prometheus or Quarkus endpoints on Wanaku Server (negative)

```bash run
for PATH_SUFFIX in /q/metrics /q/health /metrics; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}${PATH_SUFFIX}")
  [ "${CODE}" = "404" ] && echo "PASS: ${PATH_SUFFIX} returns 404" || echo "FAIL: ${PATH_SUFFIX} returned ${CODE}"
done
```

**Expected result:**

- The output includes `PASS: /q/metrics returns 404`.
- The output includes `PASS: /q/health returns 404`.
- The output includes `PASS: /metrics returns 404`.
- The output contains no line starting with `FAIL:`.


---

## Phase 3: Fail-Safe Denials Produce Audit Events

### Test 3.1: A tool call is denied by default

The CLI sends the `x-request-id` tool argument. Wanaku adds this argument to every tool schema and removes it before it
forwards a call.

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo \
  --param "message=hello,x-request-id=${OBS_RUN_ID}-deny" 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "No governance policy permits this action."; then
  echo "PASS: echo denied by the fail-safe posture"
else
  echo "FAIL: echo was not denied (exit code ${EXIT_CODE}): ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: echo denied by the fail-safe posture`.
- The output contains no line starting with `FAIL:`.


### Test 3.2: The denial is an audit event with the request ID as correlation ID

```bash run
EVENTS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${OBS_RUN_ID}-deny")
echo "${EVENTS}" | jq -e --arg id "${OBS_RUN_ID}-deny" '.data.total >= 1 and (.data.events | all(
  .category == "decision" and .decision == "block" and .reason_code == "governance_no_match"
  and .filter == "wanaku_action_policy" and .operation == "tools/call" and .target == "echo"
  and .target_type == "tool" and .namespace == "default" and .correlation_id == $id and .request_id == $id
  and .schema_version == "1.0"))' > /dev/null \
  && echo "PASS: denial recorded as a block event correlated by x-request-id" \
  || echo "FAIL: unexpected events: ${EVENTS}"

export DENY_EVENT_ID=$(echo "${EVENTS}" | jq -r '.data.events[0].event_id')
echo "${DENY_EVENT_ID}" | grep -q "^wk-" && echo "PASS: event ID ${DENY_EVENT_ID}" || echo "FAIL: no event ID"
```

**Expected result:**

- The output includes `PASS: denial recorded as a block event correlated by x-request-id`.
- The output includes `PASS: event ID <DENY_EVENT_ID>`.
- The output contains no line starting with `FAIL:`.


### Test 3.3: The JSON-RPC error carries the reason code

This test checks the raw MCP response, so it uses `curl`.

```bash run
RESPONSE=$(curl -s -X POST "${MCP_URI}" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"echo","arguments":{"message":"x"}}}')
echo "${RESPONSE}" | jq -e '.id == 31 and .error.code == -32003 and .error.data.reason_code == "governance_no_match"' \
  > /dev/null && echo "PASS: JSON-RPC error -32003 with reason_code governance_no_match" \
  || echo "FAIL: unexpected response: ${RESPONSE}"

# Without an x-request-id, the JSON-RPC id is the correlation ID
curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=31" | jq -e '.data.total >= 1' > /dev/null \
  && echo "PASS: JSON-RPC id 31 used as correlation ID" || echo "FAIL: no event with correlation ID 31"
```

**Expected result:**

- The output includes `PASS: JSON-RPC error -32003 with reason_code governance_no_match`.
- The output includes `PASS: JSON-RPC id 31 used as correlation ID`.
- The output contains no line starting with `FAIL:`.


### Test 3.4: List calls are not governed

```bash run
wanaku mcp tool list --uri "${MCP_URI}" --no-auth 2>/dev/null | grep -q "^echo" \
  && echo "PASS: tools/list works under the fail-safe posture" || echo "FAIL: tools/list did not return echo"
```

**Expected result:**

- The output includes `PASS: tools/list works under the fail-safe posture`.
- The output contains no line starting with `FAIL:`.


### Test 3.5: The denial is counted in the metrics

```bash run
REJECTS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics" | jq '.data.filters.wanaku_action_policy.requests_reject // 0')
[ "${REJECTS}" -ge 2 ] && echo "PASS: wanaku_action_policy requests_reject = ${REJECTS}" \
  || echo "FAIL: wanaku_action_policy requests_reject = ${REJECTS}"
```

A denied call stops before the tool-call filter, so the log has no `tools/call` line for it.

**Expected result:**

- The output includes `PASS: wanaku_action_policy requests_reject = <REJECTS>`.
- The output contains no line starting with `FAIL:`.


---

## Phase 4: Restart With the Test Governance Configuration

### Step 4.1: Write the configuration

#### Procedure step 2b: (Optional) Prepare the test governance configuration

Wanaku Server is fail-safe by default. It denies every `tools/call`, `resources/read` and `prompts/get` request that
no action policy and no evaluator explicitly allows. The error is `No governance policy permits this action.`
(`governance_no_match`) from the action-policy filter, or `evaluator: no_match` from the evaluator filter. Listing
requests (`tools/list`, `resources/list`, `prompts/list`) are not governed.

Plans that call tools, read resources or get prompts must start the server with a test configuration. Keep the
scope narrow: allow only the namespaces that the plan creates for the test. Do not use this configuration outside a
local test instance.

```bash
export WANAKU_SERVER_DATA_DIR="${WANAKU_SERVER_DATA_DIR:-$(mktemp -d)}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${WANAKU_TEST_GOVERNED_NAMESPACES:-default}"
WANAKU_SERVER_CONFIG="${WANAKU_SERVER_DATA_DIR}/wanaku.yaml"

{
  echo "governance:"
  echo "  default:"
  echo "    mode: enforce"
  echo "    no_match: deny"
  echo "    on_failure: deny"
  echo "  namespaces:"
  for NS in $(echo "${WANAKU_TEST_GOVERNED_NAMESPACES}" | tr ',' ' '); do
    echo "    ${NS}:"
    echo "      no_match: allow"
  done
} > "${WANAKU_SERVER_CONFIG}"
export WANAKU_SERVER_CONFIG
echo "PASS: test governance configuration written to ${WANAKU_SERVER_CONFIG}"
```

Explicit deny rules still apply in these namespaces. All other namespaces keep the fail-safe defaults, so a plan can
also verify the default denial (for example in a namespace that is not in `WANAKU_TEST_GOVERNED_NAMESPACES`).

Without `--wanaku-config`, the server reads `./wanaku.yaml` from the current directory if that file exists. A stray
file there changes the governance and bootstrap configuration silently. Run the server from a directory without
`wanaku.yaml`, or always set `WANAKU_SERVER_CONFIG`.

When `WANAKU_SERVER_CONFIG` is set, step 3 passes it with `--wanaku-config`. A plan can append more sections (for
example `forwards`, `plugins` or `action_policy`) to the same file before step 3.

Do step 2b of [common/start-server.md](common/start-server.md) with `WANAKU_TEST_GOVERNED_NAMESPACES=default` and the
same `WANAKU_SERVER_DATA_DIR`. Then append an explicit deny rule for `get-sum`:

```bash
cat >> "${WANAKU_SERVER_CONFIG}" <<'EOF'
action_policy:
  rules:
    - id: obs-deny-get-sum
      effect: deny
      selectors:
        namespace: default
        operation: tools/call
        target_type: tool
        target_name: { matcher: exact, value: get-sum }
      reason_code: obs_test_denied
      message: The observability test denies get-sum.
EOF
grep -q "obs-deny-get-sum" "${WANAKU_SERVER_CONFIG}" && echo "PASS: deny rule appended" || echo "FAIL: deny rule missing"
```

**Expected result:**

- The output includes `PASS: test governance configuration written to <WANAKU_SERVER_CONFIG>`.


- The output includes `PASS: deny rule appended`.
- The output contains no line starting with `FAIL:`.


### Step 4.2: Restart the server

Use the **Restart** section of [common/start-server.md](common/start-server.md): stop the server with `SIGTERM`,
then run steps 3 and 4 again with the same `WANAKU_SERVER_DATA_DIR`. `RUST_LOG`, `PRAXIS_LOG_FORMAT` and
`WANAKU_SERVER_CONFIG` must still be exported.

#### Procedure step 3: Start Wanaku Server

Each run uses a new data directory. This keeps the run independent of `$HOME/.wanaku/server` and of earlier runs.
Authentication is disabled by default in Wanaku Server, so no extra configuration is necessary.

The server process inherits the exported environment. Export other variables before this step when the plan needs
them, for example `RUST_LOG`, `PRAXIS_LOG_FORMAT=json` or `WANAKU_AUTH_ISSUER`.

```bash
export WANAKU_SERVER_DATA_DIR="${WANAKU_SERVER_DATA_DIR:-$(mktemp -d)}"
export WANAKU_SERVER_LOG="${WANAKU_SERVER_LOG:-${WANAKU_SERVER_DATA_DIR}/wanaku-server.log}"
export WANAKU_SERVER_URL="http://localhost:${WANAKU_SERVER_MGMT_PORT}"
export WANAKU_MCP_BASE_URL="http://localhost:8081"

WANAKU_MGMT_LISTEN="0.0.0.0:${WANAKU_SERVER_MGMT_PORT}" \
WANAKU_PERSIST_BACKEND=file \
WANAKU_PERSIST_PATH="${WANAKU_SERVER_DATA_DIR}" \
  "${WANAKU_SERVER_BIN}" ${WANAKU_SERVER_CONFIG:+--wanaku-config "${WANAKU_SERVER_CONFIG}"} \
  > "${WANAKU_SERVER_LOG}" 2>&1 &
WANAKU_SERVER_PID=$!
export WANAKU_SERVER_PID
echo "Wanaku Server started with PID ${WANAKU_SERVER_PID} (data: ${WANAKU_SERVER_DATA_DIR})"
```

#### Procedure step 4: Wait for health

`curl` is used here to test HTTP-level readiness. `GET /health` returns the standard response envelope.

Run this block as a separate `bash` script, not in the shared session.

```bash
MAX_RETRIES=30
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: Wanaku Server is healthy (attempt ${i})"
    break
  fi
  if [ "${i}" -eq "${MAX_RETRIES}" ]; then
    echo "FAIL: Wanaku Server not healthy after ${MAX_RETRIES} attempts (last HTTP ${HTTP_CODE})"
    tail -20 "${WANAKU_SERVER_LOG}"
    kill "${WANAKU_SERVER_PID}" 2>/dev/null || true
    exit 1
  fi
  sleep 1
done
```

**Expected result:**

- The commands exit with code 0 (inferred).
- The output includes `PASS: Wanaku Server is healthy`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.


### Test 4.3: Audit events survive the restart

```bash run
[ -f "${WANAKU_SERVER_DATA_DIR}/audit-events.json" ] && echo "PASS: audit-events.json exists" \
  || echo "FAIL: audit-events.json not found"
[ -f "${WANAKU_SERVER_DATA_DIR}/action-policy-revisions.json" ] && echo "PASS: action-policy-revisions.json exists" \
  || echo "FAIL: action-policy-revisions.json not found"

curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events/${DENY_EVENT_ID}" \
  | jq -e --arg id "${DENY_EVENT_ID}" '.data.event_id == $id and .data.decision == "block"' > /dev/null \
  && echo "PASS: event ${DENY_EVENT_ID} is available after the restart" || echo "FAIL: event lost after the restart"
```

**Expected result:**

- The output includes `PASS: audit-events.json exists`.
- The output includes `PASS: action-policy-revisions.json exists`.
- The output includes `PASS: event <DENY_EVENT_ID> is available after the restart`.
- The output contains no line starting with `FAIL:`.


### Test 4.4: Metrics are in memory only

```bash run
REJECTS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics" | jq '.data.filters.wanaku_action_policy.requests_reject // 0')
[ "${REJECTS}" -eq 0 ] && echo "PASS: metrics counters start at zero after the restart" \
  || echo "FAIL: requests_reject = ${REJECTS} after the restart"
```

**Expected result:**

- The output includes `PASS: metrics counters start at zero after the restart`.
- The output contains no line starting with `FAIL:`.


### Test 4.5: The active policy is the startup revision

```bash run
curl -s "${WANAKU_SERVER_URL}/api/v1/action-policies" | jq -e '.data.revision.status == "active"
  and .data.revision.origin == "startup" and (.data.policy.rules | map(.id) | index("obs-deny-get-sum") != null)' \
  > /dev/null && echo "PASS: startup policy revision is active" || echo "FAIL: unexpected action policy"
```

**Expected result:**

- The output includes `PASS: startup policy revision is active`.
- The output contains no line starting with `FAIL:`.


---

## Phase 5: Request Correlation

### Test 5.1: An allowed call is recorded by both governance filters

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo \
  --param "message=hello,x-request-id=${OBS_RUN_ID}-allow" 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && [ "${OUTPUT}" = "Echo: hello" ] \
  && echo "PASS: echo allowed; the upstream did not receive x-request-id" \
  || echo "FAIL: unexpected echo result (exit code ${EXIT_CODE}): ${OUTPUT}"

EVENTS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${OBS_RUN_ID}-allow")
echo "${EVENTS}" | jq -e --arg id "${OBS_RUN_ID}-allow" '
  (.data.events | map(.filter) | sort) == ["wanaku_action_policy", "wanaku_evaluator"]
  and (.data.events | all(.decision == "allow" and .conversation_id == $id and .stream_id == $id))' > /dev/null \
  && echo "PASS: allow events from wanaku_action_policy and wanaku_evaluator share the correlation ID" \
  || echo "FAIL: unexpected events: ${EVENTS}"
```

The namespace override `no_match: allow` allows the call. Both events have the reason code of the no-match decision
(`governance_no_match`, `evaluator_no_match`) and the decision `allow`. The events are listed newest first.

**Expected result:**

- The output includes `PASS: echo allowed; the upstream did not receive x-request-id`.
- The output includes `PASS: allow events from wanaku_action_policy and wanaku_evaluator share the correlation ID`.
- The output contains no line starting with `FAIL:`.


### Test 5.2: An explicit deny rule is recorded with its rule ID

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name get-sum \
  --param "a=1,b=2,x-request-id=${OBS_RUN_ID}-rule" 2>&1)
echo "${OUTPUT}" | grep -q "The observability test denies get-sum." \
  && echo "PASS: get-sum denied with the configured message" || echo "FAIL: unexpected output: ${OUTPUT}"

curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${OBS_RUN_ID}-rule" | jq -e '.data.total >= 1 and
  (.data.events | all(.decision == "block" and .reason_code == "obs_test_denied" and .policy_revision != null
  and .attributes.deny_rule_ids == ["obs-deny-get-sum"] and .attributes.policy_decision == "explicit_deny"))' \
  > /dev/null && echo "PASS: block event names rule obs-deny-get-sum" || echo "FAIL: unexpected deny events"
```

**Expected result:**

- The output includes `PASS: get-sum denied with the configured message`.
- The output includes `PASS: block event names rule obs-deny-get-sum`.
- The output contains no line starting with `FAIL:`.


### Test 5.3: The HTTP header takes precedence over the tool argument

The CLI cannot set HTTP headers, so this test uses `curl`.

```bash run
RESPONSE=$(curl -s -X POST "${MCP_URI}" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "x-request-id: ${OBS_RUN_ID}-hdr" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":53,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"message\":\"obs-secret-value\",\"x-request-id\":\"${OBS_RUN_ID}-conv\"}}}")
echo "${RESPONSE}" | jq -e '.result.content[0].text == "Echo: obs-secret-value"' > /dev/null \
  && echo "PASS: call succeeded" || echo "FAIL: unexpected response: ${RESPONSE}"

curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${OBS_RUN_ID}-hdr" | jq -e \
  --arg hdr "${OBS_RUN_ID}-hdr" --arg conv "${OBS_RUN_ID}-conv" \
  '.data.total == 2 and (.data.events | all(.request_id == $hdr and .conversation_id == $conv))' > /dev/null \
  && echo "PASS: header is the request/correlation ID; argument is the conversation ID" \
  || echo "FAIL: header correlation not applied"
```

| Source | Used as |
|--------|---------|
| `x-request-id` HTTP header | `correlation_id`, `request_id`, `stream_id` |
| `x-request-id` tool argument | `conversation_id`; also the correlation ID when the header is absent |
| JSON-RPC `id` | Correlation ID when both are absent |

**Expected result:**

- The output includes `PASS: call succeeded`.
- The output includes `PASS: header is the request/correlation ID; argument is the conversation ID`.
- The output contains no line starting with `FAIL:`.


---

## Phase 6: Structured Logs

### Test 6.1: The tools/call log line contains the correlation fields

```bash run
LINE=$(grep '"message":"tools/call"' "${WANAKU_SERVER_LOG}" | grep "\"x_request_id\":\"${OBS_RUN_ID}-hdr\"" | tail -1)
echo "${LINE}" | jq -e --arg conv "${OBS_RUN_ID}-conv" '.level == "INFO" and .target == "wanaku_filters::tool_call"
  and .fields.tool == "echo" and .fields.namespace == "default" and .fields.conversation_id == $conv' > /dev/null \
  && echo "PASS: JSON log line has tool, namespace, conversation_id and x_request_id" \
  || echo "FAIL: tools/call log line not found or incomplete: ${LINE}"
```

**Expected output (shortened):**
`{"timestamp":"...","level":"INFO","fields":{"message":"tools/call","tool":"echo","namespace":"default","conversation_id":"obs-...-conv","x_request_id":"obs-...-hdr"},"target":"wanaku_filters::tool_call","span":{...,"url.path":"/default/mcp",...}}`

**Expected result:**

- The output includes `PASS: JSON log line has tool, namespace, conversation_id and x_request_id`.
- The output contains no line starting with `FAIL:`.


### Test 6.2: Tool argument values are not logged (negative)

```bash run
grep -q "obs-secret-value" "${WANAKU_SERVER_LOG}" \
  && echo "FAIL: a tool argument value appears in the server log" \
  || echo "PASS: tool argument values are not logged"
```

**Expected result:**

- The output includes `PASS: tool argument values are not logged`.
- The output contains no line starting with `FAIL:`.


### Test 6.3: Each log line is valid JSON

```bash run
INVALID=$(grep -v '^{' "${WANAKU_SERVER_LOG}" | grep -c .)
[ "${INVALID}" -eq 0 ] && echo "PASS: all log lines are JSON" || echo "FAIL: ${INVALID} non-JSON log lines"
```

**Expected result:**

- The output includes `PASS: all log lines are JSON`.
- The output contains no line starting with `FAIL:`.


---

## Phase 7: Metrics After Traffic

### Test 7.1: Filter and governance counters

```bash run
METRICS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics")
echo "${METRICS}" | jq -e '.data.filters | has("wanaku_action_policy") and has("wanaku_evaluator") and has("wanaku_tool_call")' \
  > /dev/null && echo "PASS: per-filter counters present" || echo "FAIL: filter counters missing"
echo "${METRICS}" | jq -e '.data.filters.wanaku_action_policy.requests_continue >= 2
  and .data.filters.wanaku_action_policy.requests_reject >= 1
  and .data.filters.wanaku_action_policy.duration.count >= 3' > /dev/null \
  && echo "PASS: action-policy continue, reject and duration counters increased" || echo "FAIL: unexpected counters"
echo "${METRICS}" | jq -e '[.data.evaluator_governance[] | select(.mode == "enforce" and .outcome == "no_match_allow") | .count] | add >= 2' \
  > /dev/null && echo "PASS: evaluator_governance counts enforce/no_match_allow" || echo "FAIL: governance counter missing"
```

The counters have no namespace, tool or request ID dimensions.

**Expected result:**

- The output includes `PASS: per-filter counters present`.
- The output includes `PASS: action-policy continue, reject and duration counters increased`.
- The output includes `PASS: evaluator_governance counts enforce/no_match_allow`.
- The output contains no line starting with `FAIL:`.


---

## Phase 8: Administrative Audit Events

Evaluator configuration changes, revision activations and namespace bindings are administrative events. Their
correlation ID is the `x-request-id` header of the management request.

### Test 8.1: Bind and unbind a namespace

```bash run
curl -s -X PUT "${WANAKU_SERVER_URL}/api/v1/evaluators/namespaces/default" \
  -H "Content-Type: application/json" -H "x-request-id: ${OBS_RUN_ID}-bind" \
  -d "{\"conversation_id\":\"${OBS_RUN_ID}-conversation\"}" | jq -e '.data.namespace == "default"' > /dev/null \
  && echo "PASS: namespace bound" || echo "FAIL: bind failed"
curl -s -X DELETE "${WANAKU_SERVER_URL}/api/v1/evaluators/namespaces/default" \
  -H "x-request-id: ${OBS_RUN_ID}-unbind" | jq -e '.data.unbound == "default"' > /dev/null \
  && echo "PASS: namespace unbound" || echo "FAIL: unbind failed"
```

**Expected result:**

- The output includes `PASS: namespace bound`.
- The output includes `PASS: namespace unbound`.
- The output contains no line starting with `FAIL:`.


### Test 8.2: The events are in the administrative category

```bash run
ADMIN=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?category=administrative")
for PAIR in "bind:evaluator.namespace.bind" "unbind:evaluator.namespace.unbind"; do
  SUFFIX="${PAIR%%:*}"; OPERATION="${PAIR#*:}"
  echo "${ADMIN}" | jq -e --arg id "${OBS_RUN_ID}-${SUFFIX}" --arg op "${OPERATION}" '.data.events | any(
    .correlation_id == $id and .operation == $op and .protocol == "http" and .decision == "allow"
    and .reason_code == "configuration_changed" and .response_status == 200)' > /dev/null \
    && echo "PASS: ${OPERATION} recorded with correlation ID ${OBS_RUN_ID}-${SUFFIX}" \
    || echo "FAIL: ${OPERATION} event not found"
done
```

**Expected result:**

- The output includes `PASS: evaluator.namespace.bind recorded with correlation ID <OBS_RUN_ID>-bind`.
- The output includes `PASS: evaluator.namespace.unbind recorded with correlation ID <OBS_RUN_ID>-unbind`.
- The output contains no line starting with `FAIL:`.


---

## Phase 9: Audit Query API

### Test 9.1: Filters and pagination

```bash run
Q="${WANAKU_SERVER_URL}/api/v1/audit/events"
curl -s "${Q}?decision=block&namespace=default&operation=tools/call" | jq -e '.data.total >= 2 and (.data.events | all(.decision == "block"))' \
  > /dev/null && echo "PASS: decision/namespace/operation filters" || echo "FAIL: filter result"
curl -s "${Q}?reason_code=obs_test_denied&target=get-sum" | jq -e '.data.total >= 1 and (.data.events | all(.target == "get-sum"))' \
  > /dev/null && echo "PASS: reason_code/target filters" || echo "FAIL: reason_code/target filter result"

PAGE=$(curl -s "${Q}?limit=2&offset=1")
echo "${PAGE}" | jq -e '.data.limit == 2 and .data.offset == 1 and (.data.events | length) == 2 and .data.total >= 3' > /dev/null \
  && echo "PASS: limit and offset applied; total counts all matches" || echo "FAIL: pagination: ${PAGE}"
```

The default `limit` is `100`. Unknown filter values (for example `decision=bogus`) are ignored. They do not cause an
error.

**Expected result:**

- The output includes `PASS: decision/namespace/operation filters`.
- The output includes `PASS: reason_code/target filters`.
- The output includes `PASS: limit and offset applied; total counts all matches`.
- The output contains no line starting with `FAIL:`.


### Test 9.2: Negative cases

```bash run
RESP=$(curl -s -w " %{http_code}" "${Q}/wk-doesnotexist")
[ "${RESP}" = '{"data":null,"error":"audit event not found"} 404' ] && echo "PASS: unknown event ID returns 404" \
  || echo "FAIL: unexpected response: ${RESP}"

RESP=$(curl -s -w " %{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/audit/semantic" -H "Content-Type: application/json" -d '{}')
[ "${RESP}" = '{"data":null,"error":"semantic audit ingestion disabled"} 404' ] \
  && echo "PASS: semantic audit ingestion is disabled by default" || echo "FAIL: unexpected response: ${RESP}"

curl -s "${Q}?correlation_id=${OBS_RUN_ID}-never-sent" | jq -e '.data.total == 0 and .data.events == []' > /dev/null \
  && echo "PASS: unknown correlation ID returns an empty page" || echo "FAIL: unknown correlation ID"
```

**Expected result:**

- The output includes `PASS: unknown event ID returns 404`.
- The output includes `PASS: semantic audit ingestion is disabled by default`.
- The output includes `PASS: unknown correlation ID returns an empty page`.
- The output contains no line starting with `FAIL:`.


---

## Phase 10: Wanaku Barn Metrics and OpenTelemetry (Optional)

Skip this phase when the run does not use Barn.

### Step 10.1: Start Barn

Use [common/start-barn.md](common/start-barn.md), steps 1 to 4. After completion, `WANAKU_BARN_URL`, `BARN_JAR` and
`WANAKU_BARN_HOME` must be set.



#### Procedure step 1: Build the project

```bash
cd "${WANAKU_REPO_ROOT:-.}"
mvn -DskipTests clean package
```

**Build verification:**


```bash
VERSION=$(cat core/core-util/target/classes/version.txt)
CLI_JAR="apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
BARN_JAR="apps/wanaku-barn-backend/target/quarkus-app/quarkus-run.jar"

for FILE in "${CLI_JAR}" "${BARN_JAR}"; do
  if [ ! -f "${FILE}" ]; then
    echo "FAIL: ${FILE} not found"
    exit 1
  fi
  echo "PASS: ${FILE} exists"
done
```



#### Procedure step 2: Start Barn

Each run uses a new Wanaku home directory, so the Infinispan store does not contain data from earlier runs.

```bash run
export WANAKU_BARN_PORT="${WANAKU_BARN_PORT:-8180}"
export WANAKU_BARN_URL="http://localhost:${WANAKU_BARN_PORT}"
export WANAKU_BARN_HOME="${WANAKU_BARN_HOME:-$(mktemp -d)}"

java -Dquarkus.http.port="${WANAKU_BARN_PORT}" \
  -Dwanaku.home="${WANAKU_BARN_HOME}" \
  -Djgroups.mcast_port="${WANAKU_BARN_MCAST_PORT:-46655}" \
  -jar "${BARN_JAR}" > "${WANAKU_BARN_HOME}/barn.log" 2>&1 &
WANAKU_BARN_PID=$!
export WANAKU_BARN_PID
echo "Wanaku Barn started with PID ${WANAKU_BARN_PID} (home: ${WANAKU_BARN_HOME})"
```



#### Procedure step 3: Wait for health

`curl` is used here to test HTTP-level readiness.

Run this block as a separate `bash` script, not in the shared session.

```bash
MAX_RETRIES=30
RETRY_INTERVAL=2
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_BARN_URL}/q/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: Wanaku Barn is healthy (attempt ${i})"
    break
  fi
  if [ "${i}" -eq "${MAX_RETRIES}" ]; then
    echo "FAIL: Wanaku Barn not healthy after ${MAX_RETRIES} attempts (last HTTP ${HTTP_CODE})"
    tail -20 "${WANAKU_BARN_HOME}/barn.log"
    kill "${WANAKU_BARN_PID}" 2>/dev/null || true
    exit 1
  fi
  sleep ${RETRY_INTERVAL}
done
```



#### Procedure step 4: Verify the CLI can connect

Run this block as a separate `bash` script, not in the shared session.

```bash
java -jar "${CLI_JAR}" service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: CLI can connect to Wanaku Barn"
else
  echo "FAIL: CLI cannot connect to Wanaku Barn (exit code ${EXIT_CODE})"
  exit 1
fi
```



**Expected result:**

- The verification output includes `PASS: <FILE> exists` for `CLI_JAR` and `BARN_JAR`.
- The verification output contains no line starting with `FAIL:`.
- The commands exit with code 0.
- The output includes `Wanaku Barn started with PID` (inferred).
- The output includes `PASS: Wanaku Barn is healthy (attempt <i>)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.
- The output includes `PASS: CLI can connect to Wanaku Barn`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Test 10.2: Prometheus metrics and the log format

```bash run
wanaku service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain > /dev/null 2>&1
METRICS=$(curl -s "${WANAKU_BARN_URL}/q/metrics")
echo "${METRICS}" | grep -q "^jvm_memory_used_bytes" && echo "PASS: JVM metrics present" || echo "FAIL: JVM metrics missing"
echo "${METRICS}" | grep -q '^http_server_requests_seconds_count{.*uri="/api/v1/service-catalog"' \
  && echo "PASS: HTTP server metrics for /api/v1/service-catalog" || echo "FAIL: HTTP server metrics missing"

grep -q '\[traceId=, requestId=\] .*"GET /api/v1/service-catalog' "${WANAKU_BARN_HOME}/barn.log" \
  && echo "PASS: access log has empty traceId and requestId when OpenTelemetry is disabled (default)" \
  || echo "FAIL: unexpected access log format"
```

Barn never sets the `requestId` MDC key. The field stays empty.

**Expected result:**

- The output includes `PASS: JVM metrics present`.
- The output includes `PASS: HTTP server metrics for /api/v1/service-catalog`.
- The output includes `PASS: access log has empty traceId and requestId when OpenTelemetry is disabled (default)`.
- The output contains no line starting with `FAIL:`.


### Test 10.3: OpenTelemetry export from Barn

This test starts a second Barn on `WANAKU_BARN_OTEL_PORT` with OpenTelemetry enabled. A small OTLP/HTTP receiver
stores the export requests. No collector container is necessary.

```bash run
OTLP_DIR=$(mktemp -d)
cat > "${OTLP_DIR}/receiver.py" <<'EOF'
import http.server, os, sys
OUT = sys.argv[2]
class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with open(os.path.join(OUT, "requests.log"), "a") as f:
            f.write(f"{self.path} {len(body)}\n")
        with open(os.path.join(OUT, "payloads.bin"), "ab") as f:
            f.write(body)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()
    def log_message(self, *args):
        pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
EOF
python3 -I "${OTLP_DIR}/receiver.py" "${OTLP_RECEIVER_PORT}" "${OTLP_DIR}" &
OTLP_PID=$!

BARN_OTEL_HOME=$(mktemp -d)
java -Dquarkus.http.port="${WANAKU_BARN_OTEL_PORT}" -Dwanaku.home="${BARN_OTEL_HOME}" \
  -Dquarkus.otel.sdk.disabled=false \
  -Dquarkus.otel.exporter.otlp.endpoint="http://localhost:${OTLP_RECEIVER_PORT}" \
  -Dquarkus.otel.exporter.otlp.traces.protocol=http/protobuf \
  -jar "${BARN_JAR}" > "${BARN_OTEL_HOME}/barn.log" 2>&1 &
BARN_OTEL_PID=$!

for i in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${WANAKU_BARN_OTEL_PORT}/q/health/ready")" = "200" ] && break
  sleep 2
done
wanaku service catalog list --host "http://localhost:${WANAKU_BARN_OTEL_PORT}" --no-auth --plain > /dev/null 2>&1

# The batch span processor exports within a few seconds
for i in $(seq 1 15); do
  grep -q "^/v1/traces " "${OTLP_DIR}/requests.log" 2>/dev/null && break
  sleep 2
done
grep -q "^/v1/traces " "${OTLP_DIR}/requests.log" 2>/dev/null \
  && echo "PASS: Barn exported traces to /v1/traces" || echo "FAIL: no trace export received"
grep -aq "wanaku-router" "${OTLP_DIR}/payloads.bin" 2>/dev/null \
  && echo "PASS: exported resource has service.name wanaku-router" || echo "FAIL: service.name not found in export"
grep -Eq '\[traceId=[0-9a-f]{32}, requestId=\] .*"GET /api/v1/service-catalog' "${BARN_OTEL_HOME}/barn.log" \
  && echo "PASS: access log contains the trace ID" || echo "FAIL: trace ID missing in access log"
```

`application.properties` of Barn sets `service.name=wanaku-router` for historical reasons. The default exporter
protocol is gRPC. The test selects `http/protobuf` so that the receiver can be a plain HTTP server.

**Expected result:**

- The output includes `PASS: Barn exported traces to /v1/traces`.
- The output includes `PASS: exported resource has service.name wanaku-router`.
- The output includes `PASS: access log contains the trace ID`.
- The output contains no line starting with `FAIL:`.


### Step 10.4: Stop the OpenTelemetry Barn and the receiver

```bash run
kill "${BARN_OTEL_PID}" 2>/dev/null || true
wait "${BARN_OTEL_PID}" 2>/dev/null || true
kill "${OTLP_PID}" 2>/dev/null || true
rm -rf "${BARN_OTEL_HOME}" "${OTLP_DIR}"
echo "PASS: OpenTelemetry Barn and receiver stopped"
```

**Expected result:**

- The output includes `PASS: OpenTelemetry Barn and receiver stopped`.


---

## Phase 11: Cleanup

### Step 11.1: Stop processes and remove test data

1. Stop the test MCP server with the shutdown section of
   [common/start-test-mcp-server.md](common/start-test-mcp-server.md).
2. If Phase 10 ran, stop Barn with the shutdown section of [common/start-barn.md](common/start-barn.md).
3. Stop Wanaku Server and remove its data directory with the shutdown section of
   [common/start-server.md](common/start-server.md). The data directory holds `registry.json`, `audit-events.json`,
   `action-policy-revisions.json`, the generated `wanaku.yaml` and the log.


Remove the forward first. Wanaku Server also removes the entries that it discovered from the forward.

The entry plan runs the original shutdown block in its local cleanup unit.

If Phase 10 ran, execute this block.

```bash
if [ -n "${WANAKU_BARN_PID}" ]; then
  kill "${WANAKU_BARN_PID}" 2>/dev/null || true
  wait "${WANAKU_BARN_PID}" 2>/dev/null || true
  echo "PASS: Wanaku Barn stopped"
fi
rm -rf "${WANAKU_BARN_HOME}"
```

Stop the server with the PID captured at startup. Then remove the data directory of the run.

The entry plan runs the original shutdown block in its local cleanup unit.

Do **not** use `kill -9`. It bypasses the orderly Pingora shutdown and the final registry snapshot. If Pingora worker
processes remain, find them with `lsof -ti :8081` and stop them with `SIGTERM`.

```bash run
unset PRAXIS_LOG_FORMAT WANAKU_SERVER_CONFIG
curl -s -o /dev/null "${WANAKU_SERVER_URL}/health" 2>/dev/null \
  && echo "FAIL: Wanaku Server still answers" || echo "PASS: Wanaku Server stopped"
```

**Expected result:**

- The output includes `PASS: Wanaku Server stopped`.
- The output contains no line starting with `FAIL:`.


---

## Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| S | S.1 | Set environment variables | High |
| S | S.2 | Set environment variables | High |
| 11 | 11.1 | Stop processes and remove test data | High |
| 0 | 0.1 | Tools and CLI jar | High |
| 1 | 1.1–1.4 | Server (fail-safe), test MCP server, info, statistics vs CLI | Critical |
| 2 | 2.1–2.3 | Audit schema and health, metrics shape, no Prometheus endpoint | High |
| 3 | 3.1–3.5 | Fail-safe denial: CLI error, block audit event, JSON-RPC reason code, list not governed, reject counter | Critical |
| 4 | 4.1–4.5 | Restart with test governance: audit persistence, in-memory metrics, startup policy revision | Critical |
| 5 | 5.1–5.3 | Correlation: allow events of both filters, explicit deny rule, header vs argument vs JSON-RPC id | Critical |
| 6 | 6.1–6.3 | JSON logs: tools/call fields, no argument values, valid JSON | High |
| 7 | 7.1 | Filter and governance counters after traffic | High |
| 8 | 8.1–8.2 | Administrative audit events with x-request-id | High |
| 9 | 9.1–9.2 | Audit query filters, pagination, negative cases | High |
| 10 | 10.1–10.4 | Barn Prometheus metrics, log format, OpenTelemetry export (optional) | Medium |
| 11 | — | Cleanup | Critical |
