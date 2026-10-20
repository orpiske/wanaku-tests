# Test Plan: Wanaku CLI Forwards and Data Store Commands

## Overview

This test plan verifies two CLI command groups that belong to different components:

- `wanaku forwards` against **Wanaku Server**. Forwards are the only way to add tools, resources and prompts to
  Wanaku Server. The plan covers the forward lifecycle, upstream discovery, refresh, the behavior for unreachable and
  re-registered forwards, and the persistence of forwards across a restart.
- `wanaku data-store` against **Wanaku Barn**. The plan covers the data store lifecycle, labels and label expressions.

**Components:**

| Component | Needed | Used for |
|-----------|--------|----------|
| Wanaku Server | Yes | `forwards`, `namespaces`, `tools`, `resources`, `prompts` (`--host "${WANAKU_SERVER_URL}"`) |
| Wanaku Barn | Yes | `data-store` (`--host "${WANAKU_BARN_URL}"`) |
| MCP reference server | Yes | Upstream for the forward `everything` (discovery) |

The plan does not call tools, so it does not need the test governance configuration of Wanaku Server. Every step is
automatable.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `wanaku` CLI | build from source | `wanaku --version` |
| `java` | 21+ | `java -version` |
| `mvn` | 3.9+ | `mvn -version` |
| `node` / `npx` | 18+ | `npx --version` |
| `curl`, `jq` | any / 1.6+ | `jq --version` |

### CLI invocation

The plan calls the CLI as `wanaku`. To use the CLI jar from a local build, define a shell function:

```bash
CLI_JAR="${CLI_JAR:-apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
wanaku() { java -jar "${CLI_JAR}" "$@"; }
```

### Output formats

- `wanaku forwards add` prints nothing on success and exits with `0`.
- The `--plain` tables use tab separators and CRLF line ends. Do not anchor a pattern at the line end (`$`). Strip
  `\r` (`tr -d '\r'`) before you compare the last column.
- `wanaku forwards list --plain` prints a tab-separated table with the columns `name` and `address`.
- `wanaku tools list --plain` prints the columns `name`, `namespace`, `type`, `uri` and `labels`. An empty registry
  prints nothing.
- `wanaku data-store list --plain` prints the columns `id`, `name`, `dataTruncated` and `labels`. Barn can contain
  data stores from other sources (for example service templates). Always filter by name or label.

### Environment variables

```bash
export FWD_NAMESPACE="${FWD_NAMESPACE:-default}"
export FWD_AUTO_NAMESPACE="${FWD_AUTO_NAMESPACE:-fwd-auto-ns}"
export FWD_SECOND_NAMESPACE="${FWD_SECOND_NAMESPACE:-fwd-second}"
export FWD_UNREACHABLE_URL="${FWD_UNREACHABLE_URL:-http://localhost:59998/mcp}"
export DS_PREFIX="${DS_PREFIX:-cli-ds}"
export DS_WORK_DIR="${DS_WORK_DIR:-$(mktemp -d)}"
# The --plain tables use tab separators
TAB=$(printf '\t')
```

---

## Phase 0: Prerequisites

### Test 0.1: Verify the required tools

```bash
for CMD in java mvn npx curl jq; do
  command -v "${CMD}" > /dev/null 2>&1 && echo "PASS [0.1]: ${CMD} available" || echo "FAIL [0.1]: ${CMD} not found"
done
```

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server

Follow [common/start-server.md](common/start-server.md). The optional step 2b is not necessary. After completion,
`WANAKU_SERVER_URL`, `WANAKU_SERVER_PID` and `WANAKU_SERVER_DATA_DIR` must be set.

### Step 1.2: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md). After completion, `WANAKU_BARN_URL`, `WANAKU_BARN_PID` and
`CLI_JAR` must be set.

### Step 1.3: Start the MCP reference server

Follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md) with `TEST_MCP_NAMESPACE="${FWD_NAMESPACE}"`.
After completion, `TEST_MCP_URL` must be set and the forward `everything` must exist.

---

## Phase 2: Forward Lifecycle (Wanaku Server)

### Test 2.1: List the forwards

```bash
OUTPUT=$(wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "^everything${TAB}${TEST_MCP_URL}"; then
  echo "PASS [2.1]: forward 'everything' listed with its address"
else
  echo "FAIL [2.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

### Test 2.2: Add a forward to an unreachable address

Wanaku Server stores a forward even when discovery fails. The forward is then unavailable, and `statusMessage`
contains the error. The CLI does not show these fields, so the test reads them with `curl`.

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name fwd-unreachable --service "${FWD_UNREACHABLE_URL}" --namespace "${FWD_NAMESPACE}" --plain
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS [2.2]: add exits 0" || echo "FAIL [2.2]: add exit ${EXIT_CODE}"

wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^fwd-unreachable${TAB}" \
  && echo "PASS [2.2]: fwd-unreachable listed" || echo "FAIL [2.2]: fwd-unreachable not listed"

FWD_JSON=$(curl -s "${WANAKU_SERVER_URL}/api/v1/forwards/fwd-unreachable")
[ "$(echo "${FWD_JSON}" | jq -r '.data.available')" = "false" ] \
  && echo "PASS [2.2]: forward is unavailable" || echo "FAIL [2.2]: ${FWD_JSON}"
echo "${FWD_JSON}" | jq -r '.data.statusMessage' | grep -q "failed to connect" \
  && echo "PASS [2.2]: statusMessage reports the connection error" || echo "FAIL [2.2]: ${FWD_JSON}"
```

### Test 2.3: Re-register a forward with the same name

Wanaku Server has no update route for forwards. A second `add` with the same name replaces the entry. It does not
return a conflict.

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name fwd-unreachable --service "http://localhost:59997/mcp" --namespace "${FWD_NAMESPACE}" --plain
[ $? -eq 0 ] && echo "PASS [2.3]: re-registration exits 0" || echo "FAIL [2.3]: re-registration failed"

OUTPUT=$(wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain)
COUNT=$(echo "${OUTPUT}" | grep -c "^fwd-unreachable${TAB}")
if [ "${COUNT}" -eq 1 ] && echo "${OUTPUT}" | grep -q "^fwd-unreachable${TAB}http://localhost:59997/mcp"; then
  echo "PASS [2.3]: one entry with the new address"
else
  echo "FAIL [2.3]: ${COUNT} entries: ${OUTPUT}"
fi
```

### Test 2.4: A forward creates its namespace

Wanaku Server lists a namespace when a forward refers to it. A separate `namespaces create` is not necessary.

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name fwd-auto-ns --service "${FWD_UNREACHABLE_URL}" --namespace "${FWD_AUTO_NAMESPACE}" --plain
wanaku namespaces show "${FWD_AUTO_NAMESPACE}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null 2>&1 \
  && echo "PASS [2.4]: namespace ${FWD_AUTO_NAMESPACE} exists" || echo "FAIL [2.4]: namespace ${FWD_AUTO_NAMESPACE} missing"
```

### Test 2.5: Remove the forwards

```bash
for FWD in fwd-unreachable fwd-auto-ns; do
  OUTPUT=$(wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name "${FWD}" --plain 2>&1)
  echo "${OUTPUT}" | grep -q "Successfully removed forward reference '${FWD}'" \
    && echo "PASS [2.5]: ${FWD} removed" || echo "FAIL [2.5]: ${OUTPUT}"
done
OUTPUT=$(wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain)
echo "${OUTPUT}" | grep -q "^fwd-" && echo "FAIL [2.5]: test forwards still listed" || echo "PASS [2.5]: test forwards gone"
echo "${OUTPUT}" | grep -q "^everything${TAB}" && echo "PASS [2.5]: 'everything' not affected" || echo "FAIL [2.5]: 'everything' missing"
```

---

## Phase 3: Discovery and Refresh (Wanaku Server)

### Test 3.1: The forward supplies tools, resources and prompts

```bash
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^echo${TAB}${FWD_NAMESPACE}${TAB}mcp-forward${TAB}${TEST_MCP_URL}" \
  && echo "PASS [3.1]: tool echo discovered in ${FWD_NAMESPACE}" || echo "FAIL [3.1]: tool echo missing"
wanaku resources list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^architecture.md${TAB}" \
  && echo "PASS [3.1]: resource architecture.md discovered" || echo "FAIL [3.1]: resource architecture.md missing"
wanaku prompts list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^simple-prompt${TAB}${FWD_NAMESPACE}${TAB}" \
  && echo "PASS [3.1]: prompt simple-prompt discovered" || echo "FAIL [3.1]: prompt simple-prompt missing"
```

### Test 3.2: Refresh a forward

```bash
OUTPUT=$(wanaku forwards refresh --host "${WANAKU_SERVER_URL}" --no-auth --name everything --plain 2>&1)
echo "${OUTPUT}" | grep -q "Successfully refreshed forward reference 'everything'" \
  && echo "PASS [3.2]: refresh succeeded" || echo "FAIL [3.2]: ${OUTPUT}"
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^echo${TAB}" \
  && echo "PASS [3.2]: echo still present" || echo "FAIL [3.2]: echo missing after refresh"
```

### Test 3.3: A refresh keeps a disabled tool disabled

The enable and disable routes have no CLI command, so this test uses `curl`.

```bash
curl -s -o /dev/null -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/echo/disable"
wanaku forwards refresh --host "${WANAKU_SERVER_URL}" --no-auth --name everything --plain > /dev/null
ENABLED=$(curl -s "${WANAKU_SERVER_URL}/api/v1/tools/echo" | jq -r '.data.enabled')
[ "${ENABLED}" = "false" ] && echo "PASS [3.3]: echo stays disabled after refresh" || echo "FAIL [3.3]: enabled=${ENABLED}"
curl -s -o /dev/null -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/echo/enable"
ENABLED=$(curl -s "${WANAKU_SERVER_URL}/api/v1/tools/echo" | jq -r '.data.enabled')
[ "${ENABLED}" = "true" ] && echo "PASS [3.3]: echo enabled again" || echo "FAIL [3.3]: enabled=${ENABLED}"
```

### Test 3.4: Refresh an unreachable forward

The refresh of an unreachable forward succeeds with zero discovered entries. The CLI reports success.

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name fwd-refresh-down --service "${FWD_UNREACHABLE_URL}" --namespace "${FWD_NAMESPACE}" --plain
OUTPUT=$(wanaku forwards refresh --host "${WANAKU_SERVER_URL}" --no-auth --name fwd-refresh-down --plain 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS [3.4]: refresh exits 0 (${OUTPUT})" || echo "FAIL [3.4]: exit ${EXIT_CODE}: ${OUTPUT}"
wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^fwd-refresh-down${TAB}" \
  && echo "PASS [3.4]: forward still present after refresh" || echo "FAIL [3.4]: forward disappeared"
wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name fwd-refresh-down --plain > /dev/null 2>&1 || true
```

### Test 3.5: Removing a forward removes its discovered entries

```bash
wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name everything --plain > /dev/null
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^echo${TAB}" \
  && echo "FAIL [3.5]: echo still listed" || echo "PASS [3.5]: tools removed with the forward"
wanaku resources list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^architecture.md${TAB}" \
  && echo "FAIL [3.5]: architecture.md still listed" || echo "PASS [3.5]: resources removed with the forward"
wanaku prompts list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^simple-prompt${TAB}" \
  && echo "FAIL [3.5]: simple-prompt still listed" || echo "PASS [3.5]: prompts removed with the forward"
```

### Test 3.6: Adding the forward again restores the entries

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name everything --service "${TEST_MCP_URL}" --namespace "${FWD_NAMESPACE}" --plain
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^echo${TAB}" \
  && echo "PASS [3.6]: echo discovered again" || echo "FAIL [3.6]: echo not discovered"
```

### Test 3.7: The same upstream registered in two namespaces

Tool names are unique in the whole registry. When a second forward discovers a tool with the same name, the entry
moves to the namespace of the second forward. This test records the current behavior and restores the state.

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name fwd-second --service "${TEST_MCP_URL}" --namespace "${FWD_SECOND_NAMESPACE}" --plain
NS=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | awk -F'\t' '$1=="echo"{print $2}')
if [ "${NS}" = "${FWD_NAMESPACE}" ]; then
  echo "PASS [3.7]: echo stays in ${FWD_NAMESPACE}"
else
  echo "WARN [3.7]: known limitation: echo moved to namespace '${NS}'"
fi

# Restore: remove the second forward, then rediscover the first one
wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name fwd-second --plain > /dev/null
wanaku namespaces delete "${FWD_SECOND_NAMESPACE}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null 2>&1 || true
wanaku forwards refresh --host "${WANAKU_SERVER_URL}" --no-auth --name everything --plain > /dev/null
NS=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | awk -F'\t' '$1=="echo"{print $2}')
[ "${NS}" = "${FWD_NAMESPACE}" ] && echo "PASS [3.7]: echo restored in ${FWD_NAMESPACE}" || echo "FAIL [3.7]: echo namespace '${NS}'"
```

### Test 3.8: Forwards survive a restart

Follow the **Restart** section of [common/start-server.md](common/start-server.md) (stop with `SIGTERM`, then steps 3
and 4 with the same `WANAKU_SERVER_DATA_DIR`).

```bash
[ -f "${WANAKU_SERVER_DATA_DIR}/registry.json" ] && echo "PASS [3.8]: registry.json written" \
  || echo "FAIL [3.8]: registry.json missing"
wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^everything${TAB}${TEST_MCP_URL}" \
  && echo "PASS [3.8]: forward restored after restart" || echo "FAIL [3.8]: forward lost after restart"
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^echo${TAB}" \
  && echo "PASS [3.8]: tools restored after restart" || echo "FAIL [3.8]: tools lost after restart"
```

---

## Phase 4: Data Store — Add (Wanaku Barn)

### Test 4.1: Create the test file

```bash
cat > "${DS_WORK_DIR}/test-datastore.yaml" << 'YAMLEOF'
name: test-config
version: 1
settings:
  key1: value1
  key2: value2
YAMLEOF
[ -s "${DS_WORK_DIR}/test-datastore.yaml" ] && echo "PASS [4.1]: test file created" || echo "FAIL [4.1]: test file missing"
```

### Test 4.2: Add a data store from the file

```bash
OUTPUT=$(wanaku data-store add --host "${WANAKU_BARN_URL}" --no-auth \
  --read-from-file "${DS_WORK_DIR}/test-datastore.yaml" --name "${DS_PREFIX}-main" --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Successfully added data store '${DS_PREFIX}-main' with ID: "; then
  echo "PASS [4.2]: data store added"
else
  echo "FAIL [4.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

---

## Phase 5: Data Store — List and Get (Wanaku Barn)

### Test 5.1: The data store is listed

```bash
DS_ID=$(wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth --plain | awk -F'\t' -v n="${DS_PREFIX}-main" '$2==n{print $1}')
[ -n "${DS_ID}" ] && echo "PASS [5.1]: ${DS_PREFIX}-main listed with ID ${DS_ID}" || echo "FAIL [5.1]: ${DS_PREFIX}-main not listed"
```

### Test 5.2: Get the data store by name and by ID

```bash
for SELECTOR in "--name ${DS_PREFIX}-main" "--id ${DS_ID}"; do
  rm -f "${DS_WORK_DIR}/retrieved.yaml"
  # shellcheck disable=SC2086 # word splitting of SELECTOR is intended
  OUTPUT=$(wanaku data-store get --host "${WANAKU_BARN_URL}" --no-auth ${SELECTOR} \
    --output-file "${DS_WORK_DIR}/retrieved.yaml" --plain 2>&1)
  if echo "${OUTPUT}" | grep -q "Successfully retrieved data store" \
     && diff "${DS_WORK_DIR}/test-datastore.yaml" "${DS_WORK_DIR}/retrieved.yaml" > /dev/null; then
    echo "PASS [5.2]: ${SELECTOR} returns the original content"
  else
    echo "FAIL [5.2]: ${SELECTOR}: ${OUTPUT}"
  fi
done
```

---

## Phase 6: Data Store — Labels (Wanaku Barn)

### Test 6.1: Add labels

```bash
OUTPUT=$(wanaku data-store label add --host "${WANAKU_BARN_URL}" --no-auth --id "${DS_ID}" \
  --label env=test --label type=yaml --plain 2>&1)
echo "${OUTPUT}" | grep -q "(2 added, 0 updated)" && echo "PASS [6.1]: two labels added" || echo "FAIL [6.1]: ${OUTPUT}"
```

### Test 6.2: The labels are listed

```bash
LABELS=$(wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth --plain | awk -F'\t' -v n="${DS_PREFIX}-main" '$2==n{print $4}' | tr -d '\r')
for L in env=test type=yaml; do
  echo "${LABELS}" | grep -q "${L}" && echo "PASS [6.2]: label ${L} listed" || echo "FAIL [6.2]: labels are ${LABELS}"
done
```

### Test 6.3: Remove a label

```bash
OUTPUT=$(wanaku data-store label remove --host "${WANAKU_BARN_URL}" --no-auth --id "${DS_ID}" --label type --plain 2>&1)
echo "${OUTPUT}" | grep -q "Removed label 'type' (was: 'yaml')" && echo "PASS [6.3]: label removed" || echo "FAIL [6.3]: ${OUTPUT}"
LABELS=$(wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth --plain | awk -F'\t' -v n="${DS_PREFIX}-main" '$2==n{print $4}' | tr -d '\r')
[ "${LABELS}" = "{env=test}" ] && echo "PASS [6.3]: only env=test remains" || echo "FAIL [6.3]: labels are ${LABELS}"
```

---

## Phase 7: Data Store — Remove (Wanaku Barn)

### Test 7.1: Remove the data store by name

```bash
OUTPUT=$(wanaku data-store remove --host "${WANAKU_BARN_URL}" --no-auth --name "${DS_PREFIX}-main" --plain 2>&1)
echo "${OUTPUT}" | grep -q "Successfully removed data store '${DS_PREFIX}-main'" \
  && echo "PASS [7.1]: data store removed" || echo "FAIL [7.1]: ${OUTPUT}"
wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth --plain | awk -F'\t' '{print $2}' | grep -qx "${DS_PREFIX}-main" \
  && echo "FAIL [7.1]: data store still listed" || echo "PASS [7.1]: data store no longer listed"
```

---

## Phase 8: Data Store — Label Expressions (Wanaku Barn)

### Test 8.1: Add three labeled data stores

```bash
for i in 1 2 3; do
  echo "batch_item: ${i}" > "${DS_WORK_DIR}/batch-${i}.yaml"
  wanaku data-store add --host "${WANAKU_BARN_URL}" --no-auth \
    --read-from-file "${DS_WORK_DIR}/batch-${i}.yaml" --name "${DS_PREFIX}-batch-${i}" --plain > /dev/null
  ID=$(wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth --plain | awk -F'\t' -v n="${DS_PREFIX}-batch-${i}" '$2==n{print $1}')
  wanaku data-store label add --host "${WANAKU_BARN_URL}" --no-auth --id "${ID}" --label "${DS_PREFIX}-batch=true" --plain > /dev/null
  [ $? -eq 0 ] && echo "PASS [8.1]: ${DS_PREFIX}-batch-${i} added and labeled" || echo "FAIL [8.1]: ${DS_PREFIX}-batch-${i}"
done
```

### Test 8.2: Filter the list by label expression

```bash
COUNT=$(wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth -e "${DS_PREFIX}-batch=true" --plain | grep -c "${TAB}${DS_PREFIX}-batch-")
[ "${COUNT}" -eq 3 ] && echo "PASS [8.2]: label expression returns 3 data stores" || echo "FAIL [8.2]: found ${COUNT}"
```

### Test 8.3: Add a label to every match of an expression

```bash
OUTPUT=$(wanaku data-store label add --host "${WANAKU_BARN_URL}" --no-auth -e "${DS_PREFIX}-batch=true" --label stage=two --plain 2>&1)
echo "${OUTPUT}" | grep -q "Label update complete: 3 succeeded, 0 failed" \
  && echo "PASS [8.3]: label added to 3 data stores" || echo "FAIL [8.3]: ${OUTPUT}"
COUNT=$(wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth -e "${DS_PREFIX}-batch=true & stage=two" --plain | grep -c "${TAB}${DS_PREFIX}-batch-")
[ "${COUNT}" -eq 3 ] && echo "PASS [8.3]: combined expression returns 3" || echo "FAIL [8.3]: found ${COUNT}"
```

### Test 8.4: Remove the batch data stores

`data-store remove` has no label expression option. Remove each entry by name.

```bash
for i in 1 2 3; do
  wanaku data-store remove --host "${WANAKU_BARN_URL}" --no-auth --name "${DS_PREFIX}-batch-${i}" --plain > /dev/null 2>&1 || true
done
wanaku data-store list --host "${WANAKU_BARN_URL}" --no-auth --plain | grep -q "${TAB}${DS_PREFIX}-batch-" \
  && echo "FAIL [8.4]: batch data stores still listed" || echo "PASS [8.4]: batch data stores removed"
```

---

## Phase 9: Negative Tests

Picocli rejects a missing required option with exit code `2`. Command-level validation and API errors exit with `1`.
[cli-negative-tests.md](cli-negative-tests.md) covers more error paths.

### Test 9.1: Missing forward options are rejected

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --service "${FWD_UNREACHABLE_URL}" --namespace "${FWD_NAMESPACE}" > /dev/null 2>&1
[ $? -eq 2 ] && echo "PASS [9.1]: missing --name rejected" || echo "FAIL [9.1]: missing --name not rejected"
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-fwd --namespace "${FWD_NAMESPACE}" > /dev/null 2>&1
[ $? -eq 2 ] && echo "PASS [9.1]: missing --service rejected" || echo "FAIL [9.1]: missing --service not rejected"
OUTPUT=$(wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-fwd --service "${FWD_UNREACHABLE_URL}" 2>&1)
[ $? -eq 2 ] && echo "${OUTPUT}" | grep -q "Missing required argument(s): -N=<namespace>" \
  && echo "PASS [9.1]: missing --namespace rejected" || echo "FAIL [9.1]: ${OUTPUT}"
```

### Test 9.2: Remove and refresh of an unknown forward fail

```bash
for ACTION in remove refresh; do
  OUTPUT=$(wanaku forwards "${ACTION}" --host "${WANAKU_SERVER_URL}" --no-auth --name does-not-exist-12345 --plain 2>&1)
  EXIT_CODE=$?
  if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "Forward 'does-not-exist-12345' not found"; then
    echo "PASS [9.2]: ${ACTION} of an unknown forward rejected"
  else
    echo "FAIL [9.2]: ${ACTION}: exit ${EXIT_CODE}, output: ${OUTPUT}"
  fi
done
```

### Test 9.3: Data store errors

```bash
OUTPUT=$(wanaku data-store add --host "${WANAKU_BARN_URL}" --no-auth --read-from-file "${DS_WORK_DIR}/missing.yaml" --name neg-ds --plain 2>&1)
[ $? -eq 1 ] && echo "${OUTPUT}" | grep -q "Failed to read file" && echo "PASS [9.3]: missing file rejected" || echo "FAIL [9.3]: ${OUTPUT}"

OUTPUT=$(wanaku data-store get --host "${WANAKU_BARN_URL}" --no-auth --name does-not-exist-12345 --output-file "${DS_WORK_DIR}/neg.yaml" --plain 2>&1)
[ $? -eq 1 ] && echo "PASS [9.3]: get of unknown name rejected (${OUTPUT})" || echo "FAIL [9.3]: ${OUTPUT}"

for CMD in get remove; do
  OUTPUT=$(wanaku data-store "${CMD}" --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
  [ $? -eq 1 ] && echo "${OUTPUT}" | grep -q "Either --id or --name must be provided" \
    && echo "PASS [9.3]: ${CMD} without --id or --name rejected" || echo "FAIL [9.3]: ${CMD}: ${OUTPUT}"
done

OUTPUT=$(wanaku data-store remove --host "${WANAKU_BARN_URL}" --no-auth --name does-not-exist-12345 --plain 2>&1)
[ $? -eq 1 ] && echo "${OUTPUT}" | grep -q "Data store 'does-not-exist-12345' not found" \
  && echo "PASS [9.3]: remove of unknown name rejected" || echo "FAIL [9.3]: ${OUTPUT}"

OUTPUT=$(wanaku data-store label add --host "${WANAKU_BARN_URL}" --no-auth --id x --label novalue --plain 2>&1)
[ $? -eq 1 ] && echo "${OUTPUT}" | grep -q "Invalid label format: 'novalue'" \
  && echo "PASS [9.3]: label without value rejected" || echo "FAIL [9.3]: ${OUTPUT}"
```

### Test 9.4: Each command group fails against the wrong component

Wanaku Server has no data store API. Barn has no forward API.

```bash
wanaku data-store list --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null 2>&1
[ $? -eq 1 ] && echo "PASS [9.4]: data-store against Wanaku Server fails" || echo "FAIL [9.4]: data-store against Wanaku Server"
wanaku forwards list --host "${WANAKU_BARN_URL}" --no-auth --plain > /dev/null 2>&1
[ $? -eq 1 ] && echo "PASS [9.4]: forwards against Barn fails" || echo "FAIL [9.4]: forwards against Barn"
```

---

## Phase 10: Cleanup

### Step 10.1: Remove the test data

```bash
for FWD in fwd-unreachable fwd-auto-ns fwd-refresh-down fwd-second; do
  wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name "${FWD}" --plain > /dev/null 2>&1 || true
done
for NS in "${FWD_AUTO_NAMESPACE}" "${FWD_SECOND_NAMESPACE}"; do
  wanaku namespaces delete "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null 2>&1 || true
done
for DS in main batch-1 batch-2 batch-3; do
  wanaku data-store remove --host "${WANAKU_BARN_URL}" --no-auth --name "${DS_PREFIX}-${DS}" --plain > /dev/null 2>&1 || true
done
rm -rf "${DS_WORK_DIR}" 2>/dev/null || true
echo "PASS [10.1]: test data removed"
```

### Step 10.2: Stop the processes

Follow the **Shutdown** sections of [common/start-test-mcp-server.md](common/start-test-mcp-server.md),
[common/start-barn.md](common/start-barn.md) and [common/start-server.md](common/start-server.md), in this order.

---

## Test Summary Matrix

| Phase | Test ID | Test Name | Component | Priority |
|-------|---------|-----------|-----------|----------|
| 0 | 0.1 | Prerequisites | — | High |
| 1 | 1.1-1.3 | Start Server, Barn and the reference forward | Both | Critical |
| 2 | 2.1-2.5 | Forward list, unreachable add, re-registration, implicit namespace, remove | Server | Critical |
| 3 | 3.1-3.8 | Discovery, refresh, disabled state, removal cleanup, name collision, restart | Server | Critical |
| 4 | 4.1-4.2 | Data store add from file | Barn | Critical |
| 5 | 5.1-5.2 | Data store list, get by name and ID | Barn | Critical |
| 6 | 6.1-6.3 | Data store labels | Barn | High |
| 7 | 7.1 | Data store remove | Barn | Critical |
| 8 | 8.1-8.4 | Label expressions | Barn | High |
| 9 | 9.1-9.4 | Negative tests | Both | High |
| 10 | 10.1-10.2 | Cleanup | Both | Critical |
