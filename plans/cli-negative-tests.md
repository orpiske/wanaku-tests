# Test Plan: CLI Negative / Error Path Tests

## Overview

This test plan exercises the negative and error paths of the major Wanaku CLI command groups. Each command points at
the component that owns its API:

| Command group | Component | Target |
|---------------|-----------|--------|
| `tools`, `resources`, `prompts`, `forwards`, `namespaces` | Wanaku Server | `--host "${WANAKU_SERVER_URL}"` |
| `mcp` | Wanaku Server MCP listener | `--uri "${WANAKU_MCP_BASE_URL}/<namespace>/mcp"` |
| `data-store`, `service` | Wanaku Barn | `--host "${WANAKU_BARN_URL}"` |

**Components:** Wanaku Server (with the test governance configuration), Wanaku Barn and the MCP reference server
(forward `everything`).

Every step verifies that the command **fails with the expected exit code**, prints a **meaningful message** and
prints **no Java stack trace**. Some steps record Wanaku Server behavior that is not an error (for example a
re-registered forward). These steps say so explicitly.

All steps are idempotent, POSIX-compatible and automatable.

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

The executable block is in Step S.1 below.

### Exit codes

| Exit code | Meaning |
|-----------|---------|
| `0` | Success |
| `1` | Command validation failed, the API returned an error, or the connection failed |
| `2` | Picocli usage error: missing required option or parameter, unknown option, unknown command |

### Environment variables

The executable block is in Step S.2 below.

### Helper: assert failure

`assert_failure <test-id> <expected-exit> <expected-message> <command...>` runs the command. It checks the exit code
(a number, or `nonzero`), checks that the output contains the message (an empty message skips this check) and checks
that the output contains no Java stack trace.

The executable block is in Step S.3 below.

---

## Phase S: Setup

### Step S.1: Define CLI invocation

```bash run
CLI_JAR="${CLI_JAR:-apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
wanaku() { java -jar "${CLI_JAR}" "$@"; }
```

**Expected result:**

- The commands complete without errors (inferred).

### Step S.2: Set environment variables

```bash run
export WANAKU_UNREACHABLE_HOST="${WANAKU_UNREACHABLE_HOST:-http://localhost:59999}"
export NEG_GOVERNED_NAMESPACE="${NEG_GOVERNED_NAMESPACE:-default}"
export NEG_UNGOVERNED_NAMESPACE="${NEG_UNGOVERNED_NAMESPACE:-neg-ungoverned}"
export NEG_WORK_DIR="${NEG_WORK_DIR:-$(mktemp -d)}"
# Governance allows calls only in this namespace (see common/start-server.md, step 2b)
export WANAKU_TEST_GOVERNED_NAMESPACES="${NEG_GOVERNED_NAMESPACE}"
TAB=$(printf '\t')
```

**Expected result:**

- The commands complete without errors (inferred).

### Step S.3: Check prerequisites

```bash run
assert_failure() {
  AF_ID="$1"; AF_EXIT="$2"; AF_MSG="$3"; shift 3
  OUTPUT=$("$@" 2>&1)
  EXIT_CODE=$?
  AF_OK=true
  if [ "${AF_EXIT}" = "nonzero" ]; then
    [ "${EXIT_CODE}" -ne 0 ] || AF_OK=false
  else
    [ "${EXIT_CODE}" -eq "${AF_EXIT}" ] || AF_OK=false
  fi
  if [ -n "${AF_MSG}" ] && ! echo "${OUTPUT}" | grep -qF -- "${AF_MSG}"; then
    AF_OK=false
  fi
  if echo "${OUTPUT}" | grep -q "at .*(.*\.java:"; then
    echo "FAIL [${AF_ID}]: stack trace in output"
    AF_OK=false
  fi
  if [ "${AF_OK}" = "true" ]; then
    echo "PASS [${AF_ID}]: exit ${EXIT_CODE}${AF_MSG:+, '${AF_MSG}'}"
  else
    echo "FAIL [${AF_ID}]: exit ${EXIT_CODE} (expected ${AF_EXIT}${AF_MSG:+ with '${AF_MSG}'})"
    echo "  Output: ${OUTPUT}"
  fi
}
```

**Expected result:**

- The block defines the `assert_failure` function without errors (inferred).

## Phase 0: Prerequisites

### Test 0.1: Verify the required tools

```bash run
for CMD in java mvn npx curl jq; do
  command -v "${CMD}" > /dev/null 2>&1 && echo "PASS [0.1]: ${CMD} available" || echo "FAIL [0.1]: ${CMD} not found"
done
```

**Expected result:**

- The output includes `PASS [0.1]: java available`.
- The output includes `PASS [0.1]: mvn available`.
- The output includes `PASS [0.1]: npx available`.
- The output includes `PASS [0.1]: curl available`.
- The output includes `PASS [0.1]: jq available`.
- The output contains no line starting with `FAIL:` or `FAIL [`.

## Phase 1: Setup

### Step 1.1: Start Wanaku Server with the test governance configuration

Follow [common/start-server.md](common/start-server.md).

 Run the optional step 2b with the
`WANAKU_TEST_GOVERNED_NAMESPACES` value from the environment variables above. After completion,
`WANAKU_SERVER_URL` and `WANAKU_MCP_BASE_URL` must be set.

**Expected result:**

- The invoked procedure completes successfully (inferred).

### Step 1.2: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md).

After completion, `WANAKU_BARN_URL` and `CLI_JAR` must be set.

**Expected result:**

- The invoked procedure completes successfully (inferred).

### Step 1.3: Start the MCP reference server

Follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md) with
`TEST_MCP_NAMESPACE="${NEG_GOVERNED_NAMESPACE}"`.

**Expected result:**

- The invoked procedure completes successfully (inferred).

### Step 1.4: Verify health

`curl` is used here to test HTTP-level readiness.

```bash run
[ "$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health")" = "200" ] \
  && echo "PASS [1.4]: Wanaku Server healthy" || echo "FAIL [1.4]: Wanaku Server not healthy"
[ "$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_BARN_URL}/q/health/ready")" = "200" ] \
  && echo "PASS [1.4]: Wanaku Barn healthy" || echo "FAIL [1.4]: Wanaku Barn not healthy"
export NEG_MCP_URI="${WANAKU_MCP_BASE_URL}/${NEG_GOVERNED_NAMESPACE}/mcp"
```

**Expected result:**

- The output includes `PASS [1.4]: Wanaku Server healthy`.
- The output includes `PASS [1.4]: Wanaku Barn healthy`.
- The output contains no line starting with `FAIL:` or `FAIL [`.

## Phase 2: Tools, Resources and Prompts — Error Cases (Wanaku Server)

### Test 2.1: Show unknown entries

```bash run
assert_failure "2.1a" 1 "tool not found: nonexistent-tool-12345" \
  wanaku tools show --host "${WANAKU_SERVER_URL}" --no-auth nonexistent-tool-12345
assert_failure "2.1b" 1 "resource not found: nonexistent-resource-12345" \
  wanaku resources show --host "${WANAKU_SERVER_URL}" --no-auth nonexistent-resource-12345
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [2.1a]: exit 1, 'tool not found: nonexistent-tool-12345'`.
- The output includes `PASS [2.1b]: exit 1, 'resource not found: nonexistent-resource-12345'`.

### Test 2.2: Show without a name

```bash run
assert_failure "2.2a" 2 "Missing required parameter" wanaku tools show --host "${WANAKU_SERVER_URL}" --no-auth
assert_failure "2.2b" 2 "Missing required parameter" wanaku resources show --host "${WANAKU_SERVER_URL}" --no-auth
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [2.2a]: exit 2, 'Missing required parameter'`.
- The output includes `PASS [2.2b]: exit 2, 'Missing required parameter'`.

### Test 2.3: Enable or disable an unknown entry

These routes have no CLI command, so the test uses `curl` and checks the HTTP status and the error envelope.

```bash run
for KIND in tools resources prompts; do
  BODY=$(curl -s -w "\n%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/${KIND}/nonexistent-12345/disable")
  CODE=$(echo "${BODY}" | tail -1)
  ERR=$(echo "${BODY}" | head -1 | jq -r '.error')
  if [ "${CODE}" = "404" ] && echo "${ERR}" | grep -q "not found: nonexistent-12345"; then
    echo "PASS [2.3]: ${KIND} disable of an unknown entry returns 404 (${ERR})"
  else
    echo "FAIL [2.3]: ${KIND}: HTTP ${CODE}, body ${BODY}"
  fi
done
```

**Expected result:**

- The output includes `PASS [2.3]: tools disable of an unknown entry returns 404 (<ERR>)`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS [2.3]: resources disable of an unknown entry returns 404 (<ERR>)`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS [2.3]: prompts disable of an unknown entry returns 404 (<ERR>)`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:` or `FAIL [`.

## Phase 3: Forwards — Error Cases (Wanaku Server)

### Test 3.1: Missing required options

```bash run
assert_failure "3.1a" 2 "Missing required option: '--name" \
  wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --service "http://example.com:8080/mcp" --namespace default
assert_failure "3.1b" 2 "Missing required option: '--service" \
  wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-no-service --namespace default
assert_failure "3.1c" 2 "Missing required argument(s): -N=<namespace>" \
  wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-no-ns --service "http://example.com:8080/mcp"
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [3.1a]: exit 2, 'Missing required option: '--name'`.
- The output includes `PASS [3.1b]: exit 2, 'Missing required option: '--service'`.
- The output includes `PASS [3.1c]: exit 2, 'Missing required argument(s): -N=<namespace>'`.

### Test 3.2: The removed `--namespace-id` option is rejected

Namespaces are identified by name only.

```bash run
assert_failure "3.2" 2 "Unknown options: '--namespace-id'" \
  wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-ns-id \
    --service "http://example.com:8080/mcp" --namespace-id some-uuid-value
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [3.2]: exit 2, 'Unknown options: '--namespace-id''`.

### Test 3.3: Remove and refresh an unknown forward

```bash run
assert_failure "3.3a" 1 "Forward 'nonexistent-forward-12345' not found" \
  wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name nonexistent-forward-12345
assert_failure "3.3b" 1 "Forward 'nonexistent-forward-12345' not found" \
  wanaku forwards refresh --host "${WANAKU_SERVER_URL}" --no-auth --name nonexistent-forward-12345
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [3.3a]: exit 1, 'Forward 'nonexistent-forward-12345' not found'`.
- The output includes `PASS [3.3b]: exit 1, 'Forward 'nonexistent-forward-12345' not found'`.

### Test 3.4: A duplicate forward name replaces the entry (not an error)

Wanaku Server has no update route for forwards. A second `add` with the same name replaces the address. It does not
return `409`.

```bash run
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-dup-forward \
  --service "${WANAKU_UNREACHABLE_HOST}/first/mcp" --namespace default --plain > /dev/null 2>&1
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-dup-forward \
  --service "${WANAKU_UNREACHABLE_HOST}/second/mcp" --namespace default --plain > /dev/null 2>&1
EXIT_CODE=$?
ROWS=$(wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep "^neg-dup-forward${TAB}")
if [ "${EXIT_CODE}" -eq 0 ] && [ "$(echo "${ROWS}" | wc -l)" -eq 1 ] && echo "${ROWS}" | grep -q "/second/mcp"; then
  echo "PASS [3.4]: duplicate name replaced the entry"
else
  echo "FAIL [3.4]: exit ${EXIT_CODE}, rows: ${ROWS}"
fi
wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name neg-dup-forward > /dev/null 2>&1 || true
```

**Expected result:**

- The output includes `PASS [3.4]: duplicate name replaced the entry`.
- The output contains no line starting with `FAIL:` or `FAIL [`.

### Test 3.5: An unreachable forward address is stored as unavailable (not an error)

`forwards add` exits with `0`. The forward has `available: false` and no discovered entries.

```bash run
OUTPUT=$(wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-unreachable \
  --service "${WANAKU_UNREACHABLE_HOST}/mcp" --namespace default --plain 2>&1)
EXIT_CODE=$?
AVAILABLE=$(curl -s "${WANAKU_SERVER_URL}/api/v1/forwards/neg-unreachable" | jq -r '.data.available')
if [ "${EXIT_CODE}" -eq 0 ] && [ "${AVAILABLE}" = "false" ]; then
  echo "PASS [3.5]: unreachable forward stored as unavailable"
else
  echo "FAIL [3.5]: exit ${EXIT_CODE}, available=${AVAILABLE}, output: ${OUTPUT}"
fi
wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name neg-unreachable > /dev/null 2>&1 || true
```

**Expected result:**

- The output includes `PASS [3.5]: unreachable forward stored as unavailable`.
- The output contains no line starting with `FAIL:` or `FAIL [`.

### Test 3.6: A forward with an invalid namespace name

The CLI and Wanaku Server do not validate the namespace name of a forward. This test reports the current behavior as
a warning.

```bash run
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth --name neg-bad-ns \
  --service "${WANAKU_UNREACHABLE_HOST}/mcp" --namespace Bad_NS --plain > /dev/null 2>&1
if [ $? -ne 0 ]; then
  echo "PASS [3.6]: invalid namespace name rejected"
else
  echo "WARN [3.6]: forward with invalid namespace name 'Bad_NS' accepted"
fi
wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name neg-bad-ns > /dev/null 2>&1 || true
```

**Expected result:**

- The output includes `PASS [3.6]: invalid namespace name rejected`.

## Phase 4: Namespaces — Error Cases (Wanaku Server)

Namespace names are DNS labels: lowercase letters, digits and hyphens, 1 to 63 characters, no hyphen at the start or
the end.

### Test 4.1: The CLI rejects invalid names

A name that starts with a hyphen must follow `--`. Otherwise picocli reads it as an option.

```bash run
LONG_NAME=$(printf 'a%.0s' $(seq 1 64))
assert_failure "4.1a" 1 "must contain only lowercase alphanumeric characters and hyphens" \
  wanaku namespaces create --host "${WANAKU_SERVER_URL}" --no-auth Bad_NS
assert_failure "4.1b" 1 "must contain only lowercase alphanumeric characters and hyphens" \
  wanaku namespaces create --host "${WANAKU_SERVER_URL}" --no-auth "neg ns"
assert_failure "4.1c" 1 "must not start or end with a hyphen" \
  wanaku namespaces create --host "${WANAKU_SERVER_URL}" --no-auth neg-trailing-
assert_failure "4.1d" 1 "must not start or end with a hyphen" \
  wanaku namespaces create --host "${WANAKU_SERVER_URL}" --no-auth -- -neg-leading
assert_failure "4.1e" 1 "must be at most 63 characters, got 64" \
  wanaku namespaces create --host "${WANAKU_SERVER_URL}" --no-auth "${LONG_NAME}"
assert_failure "4.1f" 1 "must not be empty" \
  wanaku namespaces create --host "${WANAKU_SERVER_URL}" --no-auth ""
assert_failure "4.1g" 2 "Missing required parameter" \
  wanaku namespaces create --host "${WANAKU_SERVER_URL}" --no-auth
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [4.1a]: exit 1, 'must contain only lowercase alphanumeric characters and hyphens'`.
- The output includes `PASS [4.1b]: exit 1, 'must contain only lowercase alphanumeric characters and hyphens'`.
- The output includes `PASS [4.1c]: exit 1, 'must not start or end with a hyphen'`.
- The output includes `PASS [4.1d]: exit 1, 'must not start or end with a hyphen'`.
- The output includes `PASS [4.1e]: exit 1, 'must be at most 63 characters, got 64'`.
- The output includes `PASS [4.1f]: exit 1, 'must not be empty'`.
- The output includes `PASS [4.1g]: exit 2, 'Missing required parameter'`.

### Test 4.2: Wanaku Server also rejects invalid names

The CLI validates the name before it calls the API. This test calls the API directly to verify the server-side
validation.

```bash run
for BODY in '{"name":"Bad_NS"}' '{"name":"-neg"}' '{"name":"neg"'; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_SERVER_URL}/api/v1/namespaces" \
    -H "Content-Type: application/json" -d "${BODY}")
  [ "${CODE}" = "400" ] && echo "PASS [4.2]: ${BODY} returns 400" || echo "FAIL [4.2]: ${BODY} returns ${CODE}"
done
```

**Expected result:**

- The output includes `PASS [4.2]: {"name":"Bad_NS"} returns 400`.
- The output includes `PASS [4.2]: {"name":"-neg"} returns 400`.
- The output includes `PASS [4.2]: {"name":"neg" returns 400`.
- The output contains no line starting with `FAIL:` or `FAIL [`.

### Test 4.3: Show and delete an unknown namespace

```bash run
assert_failure "4.3a" 1 "namespace not found: nonexistent-namespace-12345" \
  wanaku namespaces show --host "${WANAKU_SERVER_URL}" --no-auth nonexistent-namespace-12345
assert_failure "4.3b" 1 "Namespace 'nonexistent-namespace-12345' not found" \
  wanaku namespaces delete --host "${WANAKU_SERVER_URL}" --no-auth nonexistent-namespace-12345
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [4.3a]: exit 1, 'namespace not found: nonexistent-namespace-12345'`.
- The output includes `PASS [4.3b]: exit 1, 'Namespace 'nonexistent-namespace-12345' not found'`.

## Phase 5: Data Store — Error Cases (Wanaku Barn)

### Test 5.1: Validation errors

```bash run
assert_failure "5.1a" 2 "Missing required option: '--read-from-file" \
  wanaku data-store add --host "${WANAKU_BARN_URL}" --no-auth
assert_failure "5.1b" 1 "Failed to read file" \
  wanaku data-store add --host "${WANAKU_BARN_URL}" --no-auth --read-from-file "${NEG_WORK_DIR}/nonexistent.bin"
assert_failure "5.1c" 1 "Either --id or --name must be provided" \
  wanaku data-store get --host "${WANAKU_BARN_URL}" --no-auth
assert_failure "5.1d" 1 "Either --id or --name must be provided" \
  wanaku data-store remove --host "${WANAKU_BARN_URL}" --no-auth
assert_failure "5.1e" 1 "Invalid label format: 'novalue'" \
  wanaku data-store label add --host "${WANAKU_BARN_URL}" --no-auth --id any --label novalue
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [5.1a]: exit 2, 'Missing required option: '--read-from-file'`.
- The output includes `PASS [5.1b]: exit 1, 'Failed to read file'`.
- The output includes `PASS [5.1c]: exit 1, 'Either --id or --name must be provided'`.
- The output includes `PASS [5.1d]: exit 1, 'Either --id or --name must be provided'`.
- The output includes `PASS [5.1e]: exit 1, 'Invalid label format: 'novalue''`.

### Test 5.2: Unknown entries

```bash run
assert_failure "5.2a" 1 "not found" \
  wanaku data-store get --host "${WANAKU_BARN_URL}" --no-auth --name nonexistent-datastore-12345 \
    --output-file "${NEG_WORK_DIR}/out.bin"
assert_failure "5.2b" 1 "Data store 'nonexistent-datastore-12345' not found" \
  wanaku data-store remove --host "${WANAKU_BARN_URL}" --no-auth --name nonexistent-datastore-12345
assert_failure "5.2c" 1 "Data store '00000000-0000-0000-0000-000000000000' not found" \
  wanaku data-store label add --host "${WANAKU_BARN_URL}" --no-auth --id 00000000-0000-0000-0000-000000000000 --label a=b
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [5.2a]: exit 1, 'not found'`.
- The output includes `PASS [5.2b]: exit 1, 'Data store 'nonexistent-datastore-12345' not found'`.
- The output includes `PASS [5.2c]: exit 1, 'Data store '00000000-0000-0000-0000-000000000000' not found'`.

### Test 5.3: The old command name is rejected

The command is `data-store`. Earlier plans used `datastores`, which does not exist.

```bash run
assert_failure "5.3" 2 "Unmatched arguments" wanaku datastores list --host "${WANAKU_BARN_URL}" --no-auth
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [5.3]: exit 2, 'Unmatched arguments'`.

## Phase 6: Service — Error Cases (Wanaku Barn)

### Test 6.1: `service init` validation

```bash run
assert_failure "6.1a" 2 "Missing required option: '--name" wanaku service init --services system1
assert_failure "6.1b" 2 "Missing required option: '--services" wanaku service init --name neg-test-init
mkdir -p "${NEG_WORK_DIR}/neg-existing"
assert_failure "6.1c" 1 "already exists" \
  wanaku service init --name "${NEG_WORK_DIR}/neg-existing" --services sys1
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [6.1a]: exit 2, 'Missing required option: '--name'`.
- The output includes `PASS [6.1b]: exit 2, 'Missing required option: '--services'`.
- The output includes `PASS [6.1c]: exit 1, 'already exists'`.

### Test 6.2: Commands on a path without `index.properties`

```bash run
for CMD in expose package; do
  assert_failure "6.2-${CMD}" 1 "index.properties not found" \
    wanaku service "${CMD}" --path "${NEG_WORK_DIR}/nonexistent-catalog"
done
assert_failure "6.2-deploy" 1 "index.properties not found" \
  wanaku service deploy --path "${NEG_WORK_DIR}/nonexistent-catalog" --host "${WANAKU_BARN_URL}" --no-auth
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [6.2-expose]: exit 1, 'index.properties not found'`.
- The output includes `PASS [6.2-package]: exit 1, 'index.properties not found'`.
- The output includes `PASS [6.2-deploy]: exit 1, 'index.properties not found'`.

### Test 6.3: Deploy to an unreachable host

```bash run
wanaku service init --name "${NEG_WORK_DIR}/neg-svc" --services testsys > /dev/null 2>&1
wanaku service expose --path "${NEG_WORK_DIR}/neg-svc" > /dev/null 2>&1
if [ -f "${NEG_WORK_DIR}/neg-svc/index.properties" ]; then
  assert_failure "6.3" 1 "Incorrect --host URL" \
    wanaku service deploy --path "${NEG_WORK_DIR}/neg-svc" --host "${WANAKU_UNREACHABLE_HOST}" --no-auth
else
  echo "FAIL [6.3]: service init did not create the catalog"
fi
```

**Expected result:**

- The output includes `PASS [6.3]: exit 1, 'Incorrect --host URL'`.
- The output contains no line starting with `FAIL:` or `FAIL [`.

## Phase 7: MCP — Error Cases (Wanaku Server MCP Listener)

### Test 7.1: Missing options

```bash run
assert_failure "7.1a" 1 "Missing required option: --uri" wanaku mcp tool --no-auth --name echo
assert_failure "7.1b" 1 "Missing required option: --resource-uri" wanaku mcp resource --no-auth --uri "${NEG_MCP_URI}"
assert_failure "7.1c" 2 "Missing required option" wanaku mcp tool list --no-auth
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [7.1a]: exit 1, 'Missing required option: --uri'`.
- The output includes `PASS [7.1b]: exit 1, 'Missing required option: --resource-uri'`.
- The output includes `PASS [7.1c]: exit 2, 'Missing required option'`.

### Test 7.2: Invalid endpoints

```bash run
assert_failure "7.2a" 1 "invalid MCP endpoint path: use /{namespace}/mcp" \
  wanaku mcp tool list --no-auth --uri "${WANAKU_MCP_BASE_URL}/mcp"
assert_failure "7.2b" 1 "Legacy SSE MCP endpoints are no longer supported" \
  wanaku mcp tool list --no-auth --uri "${NEG_MCP_URI}/sse"
assert_failure "7.2c" 1 "Invalid URI: 'not-a-valid-uri'" \
  wanaku mcp tool list --no-auth --uri not-a-valid-uri
assert_failure "7.2d" 1 "" \
  wanaku mcp tool list --no-auth --uri "${WANAKU_UNREACHABLE_HOST}/default/mcp"
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [7.2a]: exit 1, 'invalid MCP endpoint path: use /{namespace}/mcp'`.
- The output includes `PASS [7.2b]: exit 1, 'Legacy SSE MCP endpoints are no longer supported'`.
- The output includes `PASS [7.2c]: exit 1, 'Invalid URI: 'not-a-valid-uri''`.
- The output includes `PASS [7.2d]: exit 1`.

### Test 7.3: Unknown tool, resource and prompt

```bash run
assert_failure "7.3a" 1 "tool not found: nonexistent-tool-12345" \
  wanaku mcp tool --no-auth --uri "${NEG_MCP_URI}" --name nonexistent-tool-12345
assert_failure "7.3b" 1 "resource not found: demo://nonexistent-12345" \
  wanaku mcp resource --no-auth --uri "${NEG_MCP_URI}" --resource-uri demo://nonexistent-12345
assert_failure "7.3c" 1 "prompt not found: nonexistent-prompt-12345" \
  wanaku mcp prompt --no-auth --uri "${NEG_MCP_URI}" --name nonexistent-prompt-12345
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [7.3a]: exit 1, 'tool not found: nonexistent-tool-12345'`.
- The output includes `PASS [7.3b]: exit 1, 'resource not found: demo://nonexistent-12345'`.
- The output includes `PASS [7.3c]: exit 1, 'prompt not found: nonexistent-prompt-12345'`.

### Test 7.4: Invalid tool arguments

```bash run
assert_failure "7.4a" 1 "-32602" wanaku mcp tool --no-auth --uri "${NEG_MCP_URI}" --name echo
assert_failure "7.4b" 1 "expected number, received string" \
  wanaku mcp tool --no-auth --uri "${NEG_MCP_URI}" --name get-sum --param a=1 --param b=2
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [7.4a]: exit 1, '-32602'`.
- The output includes `PASS [7.4b]: exit 1, 'expected number, received string'`.

### Test 7.5: Unknown namespace

An MCP endpoint of a namespace that does not exist answers. It lists no tools, and the fail-safe governance denies
calls before the tool lookup.

```bash run
UNKNOWN_URI="${WANAKU_MCP_BASE_URL}/nonexistent-namespace-12345/mcp"
OUTPUT=$(wanaku mcp tool list --no-auth --uri "${UNKNOWN_URI}" --plain 2>&1)
echo "${OUTPUT}" | grep -q "No tools found" && echo "PASS [7.5a]: unknown namespace lists no tools" \
  || echo "FAIL [7.5a]: output: ${OUTPUT}"
assert_failure "7.5b" 1 "No governance policy permits this action." \
  wanaku mcp tool --no-auth --uri "${UNKNOWN_URI}" --name echo --param message=hello
```

**Expected result:**

- The output includes `PASS [7.5a]: unknown namespace lists no tools`.
- The output includes `PASS [7.5b]: exit 1, 'No governance policy permits this action.'`.
- The output contains no line starting with `FAIL:` or `FAIL [`.

### Test 7.6: Calls in a namespace that governance does not allow

```bash run
wanaku namespaces create "${NEG_UNGOVERNED_NAMESPACE}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null
UNGOV_URI="${WANAKU_MCP_BASE_URL}/${NEG_UNGOVERNED_NAMESPACE}/mcp"
assert_failure "7.6a" 1 "No governance policy permits this action." \
  wanaku mcp tool --no-auth --uri "${UNGOV_URI}" --name echo --param message=hello
assert_failure "7.6b" 1 "No governance policy permits this action." \
  wanaku mcp prompt --no-auth --uri "${UNGOV_URI}" --name simple-prompt
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [7.6a]: exit 1, 'No governance policy permits this action.'`.
- The output includes `PASS [7.6b]: exit 1, 'No governance policy permits this action.'`.

## Phase 8: Commands Against the Wrong Component

### Test 8.0: Commands against the wrong component

Wanaku Server has no data store or service catalog API. Barn has no tool, forward or namespace API. These calls get
`404` and the CLI exits with `1`.

```bash run
assert_failure "8.1" 1 "The requested resource was not found" \
  wanaku data-store list --host "${WANAKU_SERVER_URL}" --no-auth --plain
assert_failure "8.2" 1 "The requested resource was not found" \
  wanaku service catalog list --host "${WANAKU_SERVER_URL}" --no-auth --plain
assert_failure "8.3" 1 "The requested resource was not found" \
  wanaku tools list --host "${WANAKU_BARN_URL}" --no-auth --plain
assert_failure "8.4" 1 "The requested resource was not found" \
  wanaku forwards list --host "${WANAKU_BARN_URL}" --no-auth --plain
assert_failure "8.5" 1 "The requested resource was not found" \
  wanaku namespaces list --host "${WANAKU_BARN_URL}" --no-auth --plain
```

**Expected result:**

- The output contains no line starting with `FAIL [` (including stack trace failures).
- The output includes `PASS [8.1]: exit 1, 'The requested resource was not found'`.
- The output includes `PASS [8.2]: exit 1, 'The requested resource was not found'`.
- The output includes `PASS [8.3]: exit 1, 'The requested resource was not found'`.
- The output includes `PASS [8.4]: exit 1, 'The requested resource was not found'`.
- The output includes `PASS [8.5]: exit 1, 'The requested resource was not found'`.

## Phase 9: Connection Errors

### Test 9.0: Connection errors

All commands that point at an unreachable host fail with a clear message and no stack trace.

```bash run
for CMD in "tools list" "resources list" "prompts list" "forwards list" "namespaces list" \
           "data-store list" "service catalog list"; do
  # shellcheck disable=SC2086 # word splitting of CMD is intended
  assert_failure "9-${CMD}" 1 "Unable to connect to Wanaku service" \
    wanaku ${CMD} --host "${WANAKU_UNREACHABLE_HOST}" --no-auth --plain
done
```

**Expected result:**

- The output includes `PASS [9-<CMD>]: exit 1, 'Unable to connect to Wanaku service'`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.

## Phase 10: Cleanup

### Step 10.1: Remove the test data

```bash run
for FWD in neg-dup-forward neg-unreachable neg-bad-ns neg-no-service neg-no-ns neg-ns-id; do
  wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name "${FWD}" > /dev/null 2>&1 || true
done
wanaku namespaces delete "${NEG_UNGOVERNED_NAMESPACE}" --host "${WANAKU_SERVER_URL}" --no-auth > /dev/null 2>&1 || true
rm -rf "${NEG_WORK_DIR}" 2>/dev/null || true
echo "PASS [10.1]: test data removed"
```

**Expected result:**

- The output includes `PASS [10.1]: test data removed`.

### Step 10.2: Stop the processes

Use the **Shutdown** sections of [common/start-test-mcp-server.md](common/start-test-mcp-server.md),
[common/start-barn.md](common/start-barn.md) and [common/start-server.md](common/start-server.md), in this order.

```bash run
if [ -n "${WANAKU_BARN_PID}" ]; then
  kill "${WANAKU_BARN_PID}" 2>/dev/null || true
  wait "${WANAKU_BARN_PID}" 2>/dev/null || true
  echo "PASS: Wanaku Barn stopped"
fi
rm -rf "${WANAKU_BARN_HOME}"
```

**Expected result:**

- If `WANAKU_BARN_PID` is set, the output includes `PASS: Wanaku Barn stopped`.

## Summary Matrix

| Phase | Test ID | Test Name | Component | Priority |
|-------|---------|-----------|-----------|----------|
| 0 | 0.1 | Prerequisites | — | Critical |
| 1 | 1.1-1.4 | Server (test governance), Barn, reference forward, health | Both | Critical |
| 2 | 2.1-2.3 | Unknown tool/resource, missing name, enable/disable unknown entry | Server | High |
| 3 | 3.1-3.6 | Forward options, removed `--namespace-id`, unknown forward, duplicate name, unreachable address, invalid namespace name | Server | High |
| 4 | 4.1-4.3 | DNS-label namespace names (CLI and server), unknown namespace | Server | High |
| 5 | 5.1-5.3 | Data store validation, unknown entries, old command name | Barn | High |
| 6 | 6.1-6.3 | Service init/expose/package/deploy errors | Barn | High |
| 7 | 7.1-7.6 | MCP options, bare `/mcp`, SSE, invalid URI, unknown entries, invalid arguments, unknown namespace, fail-safe governance | Server MCP | Critical |
| 8 | 8.1-8.5 | Commands against the wrong component | Both | High |
| 9 | 9.x | Connection errors | — | Critical |
| 10 | 10.1-10.2 | Cleanup | Both | Critical |
| S | S.1 | Define CLI invocation | Setup |
| S | S.2 | Set environment variables | Setup |
| S | S.3 | Check prerequisites | Setup |
| 8 | 8.0 | Commands against the wrong component | Setup |
| 9 | 9.0 | Connection errors | Setup |
