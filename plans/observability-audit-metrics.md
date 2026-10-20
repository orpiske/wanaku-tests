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

```bash
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export CLI_JAR="${CLI_JAR:-${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
# Prefix of the correlation IDs that this run sends
export OBS_RUN_ID="${OBS_RUN_ID:-obs-$(date +%s)}"
# Optional Barn phase
export WANAKU_BARN_OTEL_PORT="${WANAKU_BARN_OTEL_PORT:-8181}"
export OTLP_RECEIVER_PORT="${OTLP_RECEIVER_PORT:-4318}"

wanaku() { java -jar "${CLI_JAR}" "$@"; }
```

The server logs in JSON at the `info` level for this plan. Export these variables **before** you start the server.
The server process inherits them:

```bash
export RUST_LOG="${RUST_LOG:-info}"
export PRAXIS_LOG_FORMAT=json
```

`RUST_LOG` uses the `tracing` filter syntax, for example `info,wanaku_filters=trace`. Without
`PRAXIS_LOG_FORMAT=json`, the server writes text logs with ANSI color codes, also to a file.

---

## Phase 0: Prerequisites

### Test 0.1: Verify tools

```bash
FAIL=0
for CMD in java curl jq npx; do
  command -v "${CMD}" > /dev/null 2>&1 && echo "PASS: ${CMD} found" || { echo "FAIL: ${CMD} is not installed"; FAIL=1; }
done
[ -f "${CLI_JAR}" ] && echo "PASS: ${CLI_JAR} exists" || { echo "FAIL: ${CLI_JAR} not found"; FAIL=1; }
[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || exit 1
```

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server with the default (fail-safe) governance

Follow [common/start-server.md](common/start-server.md). Do **not** do step 2b in this phase. After completion,
`WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID`, `WANAKU_SERVER_DATA_DIR` and `WANAKU_SERVER_LOG` must
be set.

The server reads `wanaku.yaml` from its working directory when `--wanaku-config` is not given. Make sure that the
current directory has no `wanaku.yaml`, or Phase 3 does not get the default posture.

```bash
[ -f wanaku.yaml ] && echo "FAIL: remove ./wanaku.yaml before Phase 1" || echo "PASS: no wanaku.yaml in the working directory"
```

### Step 1.2: Start the upstream test MCP server

Follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md) with the default values. It registers the
forward `everything` in the namespace `default`. After completion, `TEST_MCP_URL` and `TEST_MCP_PID` must be set.

```bash
export MCP_URI="${WANAKU_MCP_BASE_URL}/default/mcp"
```

### Test 1.3: Server name and version

```bash
INFO=$(curl -s "${WANAKU_SERVER_URL}/api/v1/management/info")
echo "${INFO}" | jq -e '.error == null and .data.name == "wanaku-server" and (.data.version | length > 0)' > /dev/null \
  && echo "PASS: info reports $(echo "${INFO}" | jq -r '.data.name + " " + .data.version')" \
  || echo "FAIL: unexpected info response: ${INFO}"
```

### Test 1.4: Registry statistics match the CLI lists

```bash
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

---

## Phase 2: Observability Endpoints — Baseline

These are API routes without CLI commands, so the plan uses `curl`.

### Test 2.1: Audit schema version and store health

```bash
curl -s "${WANAKU_SERVER_URL}/api/v1/audit/schema" | jq -e '.data.schema_version == "1.0"' > /dev/null \
  && echo "PASS: audit schema version 1.0" || echo "FAIL: unexpected audit schema version"

HEALTH=$(curl -s "${WANAKU_SERVER_URL}/api/v1/audit/health")
echo "${HEALTH}" | jq -e '.data.healthy == true and .data.dropped_events == 0 and .data.capacity == 10000 and .data.last_error == null' \
  > /dev/null && echo "PASS: audit store healthy (capacity 10000)" || echo "FAIL: unexpected audit health: ${HEALTH}"
```

### Test 2.2: Metrics snapshot shape

```bash
METRICS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics")
echo "${METRICS}" | jq -e '.data | has("filters") and has("evaluators") and has("evaluator_governance")
  and has("pipeline") and has("gauges") and .audit.storage_failures == 0' > /dev/null \
  && echo "PASS: metrics snapshot has the expected sections" || echo "FAIL: unexpected metrics: ${METRICS}"
```

### Test 2.3: No Prometheus or Quarkus endpoints on Wanaku Server (negative)

```bash
for PATH_SUFFIX in /q/metrics /q/health /metrics; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}${PATH_SUFFIX}")
  [ "${CODE}" = "404" ] && echo "PASS: ${PATH_SUFFIX} returns 404" || echo "FAIL: ${PATH_SUFFIX} returned ${CODE}"
done
```

---

## Phase 3: Fail-Safe Denials Produce Audit Events

### Test 3.1: A tool call is denied by default

The CLI sends the `x-request-id` tool argument. Wanaku adds this argument to every tool schema and removes it before it
forwards a call.

```bash
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo \
  --param "message=hello,x-request-id=${OBS_RUN_ID}-deny" 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "No governance policy permits this action."; then
  echo "PASS: echo denied by the fail-safe posture"
else
  echo "FAIL: echo was not denied (exit code ${EXIT_CODE}): ${OUTPUT}"
fi
```

### Test 3.2: The denial is an audit event with the request ID as correlation ID

```bash
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

### Test 3.3: The JSON-RPC error carries the reason code

This test checks the raw MCP response, so it uses `curl`.

```bash
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

### Test 3.4: List calls are not governed

```bash
wanaku mcp tool list --uri "${MCP_URI}" --no-auth 2>/dev/null | grep -q "^echo" \
  && echo "PASS: tools/list works under the fail-safe posture" || echo "FAIL: tools/list did not return echo"
```

### Test 3.5: The denial is counted in the metrics

```bash
REJECTS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics" | jq '.data.filters.wanaku_action_policy.requests_reject // 0')
[ "${REJECTS}" -ge 2 ] && echo "PASS: wanaku_action_policy requests_reject = ${REJECTS}" \
  || echo "FAIL: wanaku_action_policy requests_reject = ${REJECTS}"
```

A denied call stops before the tool-call filter, so the log has no `tools/call` line for it.

---

## Phase 4: Restart With the Test Governance Configuration

### Step 4.1: Write the configuration

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

### Step 4.2: Restart the server

Follow the **Restart** section of [common/start-server.md](common/start-server.md): stop the server with `SIGTERM`,
then run steps 3 and 4 again with the same `WANAKU_SERVER_DATA_DIR`. `RUST_LOG`, `PRAXIS_LOG_FORMAT` and
`WANAKU_SERVER_CONFIG` must still be exported.

### Test 4.3: Audit events survive the restart

```bash
[ -f "${WANAKU_SERVER_DATA_DIR}/audit-events.json" ] && echo "PASS: audit-events.json exists" \
  || echo "FAIL: audit-events.json not found"
[ -f "${WANAKU_SERVER_DATA_DIR}/action-policy-revisions.json" ] && echo "PASS: action-policy-revisions.json exists" \
  || echo "FAIL: action-policy-revisions.json not found"

curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events/${DENY_EVENT_ID}" \
  | jq -e --arg id "${DENY_EVENT_ID}" '.data.event_id == $id and .data.decision == "block"' > /dev/null \
  && echo "PASS: event ${DENY_EVENT_ID} is available after the restart" || echo "FAIL: event lost after the restart"
```

### Test 4.4: Metrics are in memory only

```bash
REJECTS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/metrics" | jq '.data.filters.wanaku_action_policy.requests_reject // 0')
[ "${REJECTS}" -eq 0 ] && echo "PASS: metrics counters start at zero after the restart" \
  || echo "FAIL: requests_reject = ${REJECTS} after the restart"
```

### Test 4.5: The active policy is the startup revision

```bash
curl -s "${WANAKU_SERVER_URL}/api/v1/action-policies" | jq -e '.data.revision.status == "active"
  and .data.revision.origin == "startup" and (.data.policy.rules | map(.id) | index("obs-deny-get-sum") != null)' \
  > /dev/null && echo "PASS: startup policy revision is active" || echo "FAIL: unexpected action policy"
```

---

## Phase 5: Request Correlation

### Test 5.1: An allowed call is recorded by both governance filters

```bash
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

### Test 5.2: An explicit deny rule is recorded with its rule ID

```bash
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name get-sum \
  --param "a=1,b=2,x-request-id=${OBS_RUN_ID}-rule" 2>&1)
echo "${OUTPUT}" | grep -q "The observability test denies get-sum." \
  && echo "PASS: get-sum denied with the configured message" || echo "FAIL: unexpected output: ${OUTPUT}"

curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events?correlation_id=${OBS_RUN_ID}-rule" | jq -e '.data.total >= 1 and
  (.data.events | all(.decision == "block" and .reason_code == "obs_test_denied" and .policy_revision != null
  and .attributes.deny_rule_ids == ["obs-deny-get-sum"] and .attributes.policy_decision == "explicit_deny"))' \
  > /dev/null && echo "PASS: block event names rule obs-deny-get-sum" || echo "FAIL: unexpected deny events"
```

### Test 5.3: The HTTP header takes precedence over the tool argument

The CLI cannot set HTTP headers, so this test uses `curl`.

```bash
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

---

## Phase 6: Structured Logs

### Test 6.1: The tools/call log line contains the correlation fields

```bash
LINE=$(grep '"message":"tools/call"' "${WANAKU_SERVER_LOG}" | grep "\"x_request_id\":\"${OBS_RUN_ID}-hdr\"" | tail -1)
echo "${LINE}" | jq -e --arg conv "${OBS_RUN_ID}-conv" '.level == "INFO" and .target == "wanaku_filters::tool_call"
  and .fields.tool == "echo" and .fields.namespace == "default" and .fields.conversation_id == $conv' > /dev/null \
  && echo "PASS: JSON log line has tool, namespace, conversation_id and x_request_id" \
  || echo "FAIL: tools/call log line not found or incomplete: ${LINE}"
```

**Expected output (shortened):**
`{"timestamp":"...","level":"INFO","fields":{"message":"tools/call","tool":"echo","namespace":"default","conversation_id":"obs-...-conv","x_request_id":"obs-...-hdr"},"target":"wanaku_filters::tool_call","span":{...,"url.path":"/default/mcp",...}}`

### Test 6.2: Tool argument values are not logged (negative)

```bash
grep -q "obs-secret-value" "${WANAKU_SERVER_LOG}" \
  && echo "FAIL: a tool argument value appears in the server log" \
  || echo "PASS: tool argument values are not logged"
```

### Test 6.3: Each log line is valid JSON

```bash
INVALID=$(grep -v '^{' "${WANAKU_SERVER_LOG}" | grep -c .)
[ "${INVALID}" -eq 0 ] && echo "PASS: all log lines are JSON" || echo "FAIL: ${INVALID} non-JSON log lines"
```

---

## Phase 7: Metrics After Traffic

### Test 7.1: Filter and governance counters

```bash
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

---

## Phase 8: Administrative Audit Events

Evaluator configuration changes, revision activations and namespace bindings are administrative events. Their
correlation ID is the `x-request-id` header of the management request.

### Test 8.1: Bind and unbind a namespace

```bash
curl -s -X PUT "${WANAKU_SERVER_URL}/api/v1/evaluators/namespaces/default" \
  -H "Content-Type: application/json" -H "x-request-id: ${OBS_RUN_ID}-bind" \
  -d "{\"conversation_id\":\"${OBS_RUN_ID}-conversation\"}" | jq -e '.data.namespace == "default"' > /dev/null \
  && echo "PASS: namespace bound" || echo "FAIL: bind failed"
curl -s -X DELETE "${WANAKU_SERVER_URL}/api/v1/evaluators/namespaces/default" \
  -H "x-request-id: ${OBS_RUN_ID}-unbind" | jq -e '.data.unbound == "default"' > /dev/null \
  && echo "PASS: namespace unbound" || echo "FAIL: unbind failed"
```

### Test 8.2: The events are in the administrative category

```bash
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

---

## Phase 9: Audit Query API

### Test 9.1: Filters and pagination

```bash
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

### Test 9.2: Negative cases

```bash
RESP=$(curl -s -w " %{http_code}" "${Q}/wk-doesnotexist")
[ "${RESP}" = '{"data":null,"error":"audit event not found"} 404' ] && echo "PASS: unknown event ID returns 404" \
  || echo "FAIL: unexpected response: ${RESP}"

RESP=$(curl -s -w " %{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/audit/semantic" -H "Content-Type: application/json" -d '{}')
[ "${RESP}" = '{"data":null,"error":"semantic audit ingestion disabled"} 404' ] \
  && echo "PASS: semantic audit ingestion is disabled by default" || echo "FAIL: unexpected response: ${RESP}"

curl -s "${Q}?correlation_id=${OBS_RUN_ID}-never-sent" | jq -e '.data.total == 0 and .data.events == []' > /dev/null \
  && echo "PASS: unknown correlation ID returns an empty page" || echo "FAIL: unknown correlation ID"
```

---

## Phase 10 (Optional): Wanaku Barn Metrics and OpenTelemetry

Skip this phase when the run does not use Barn.

### Step 10.1: Start Barn

Follow [common/start-barn.md](common/start-barn.md). After completion, `WANAKU_BARN_URL`, `BARN_JAR` and
`WANAKU_BARN_HOME` must be set.

### Test 10.2: Prometheus metrics and the log format

```bash
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

### Test 10.3: OpenTelemetry export from Barn

This test starts a second Barn on `WANAKU_BARN_OTEL_PORT` with OpenTelemetry enabled. A small OTLP/HTTP receiver
stores the export requests. No collector container is necessary.

```bash
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

### Step 10.4: Stop the OpenTelemetry Barn and the receiver

```bash
kill "${BARN_OTEL_PID}" 2>/dev/null || true
wait "${BARN_OTEL_PID}" 2>/dev/null || true
kill "${OTLP_PID}" 2>/dev/null || true
rm -rf "${BARN_OTEL_HOME}" "${OTLP_DIR}"
echo "PASS: OpenTelemetry Barn and receiver stopped"
```

---

## Phase 11: Cleanup

1. Stop the test MCP server with the shutdown section of
   [common/start-test-mcp-server.md](common/start-test-mcp-server.md).
2. If Phase 10 ran, stop Barn with the shutdown section of [common/start-barn.md](common/start-barn.md).
3. Stop Wanaku Server and remove its data directory with the shutdown section of
   [common/start-server.md](common/start-server.md). The data directory holds `registry.json`, `audit-events.json`,
   `action-policy-revisions.json`, the generated `wanaku.yaml` and the log.

```bash
unset PRAXIS_LOG_FORMAT WANAKU_SERVER_CONFIG
curl -s -o /dev/null "${WANAKU_SERVER_URL}/health" 2>/dev/null \
  && echo "FAIL: Wanaku Server still answers" || echo "PASS: Wanaku Server stopped"
```

---

## Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
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
