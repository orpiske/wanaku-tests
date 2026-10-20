# Test Plan: Wanaku MCP CLI Commands

## Overview

This test plan verifies the `wanaku mcp` CLI commands. These commands talk to an MCP endpoint directly. The plan points
them at the MCP endpoint of **Wanaku Server** (`${WANAKU_MCP_BASE_URL}/<namespace>/mcp`). The tools, resources and
prompts come from the supplied infra-remediation MCP test fixture, which is registered with Wanaku Server as a forward. Thus the
`list`, `call`, `read` and `get` operations return real data.

The plan has two parts:

- **Part 1 — Builtin or existing instance:** help output, option validation, tool/resource/prompt operations, namespace isolation,
  governance, enable/disable and error handling against a builtin or existing Wanaku Server.
- **Part 2 — OpenShift:** the operator deploys Wanaku Server with oauth2-proxy authentication. The plan registers an
  in-cluster MCP server as a forward and uses the `wanaku mcp` commands through the MCP Route with a bearer token.

**Components:**

| Component                                     | Part 1                                        | Part 2                                                        |
|-----------------------------------------------|-----------------------------------------------|---------------------------------------------------------------|
| Wanaku Server                                 | Required (builtin or existing instance)       | Required (operator, `spec.server`)                            |
| Wanaku Barn                                   | Not used                                      | Not used (`spec.barn` is not enabled)                         |
| MCP test fixture (`fixtures/test-mcp-server`) | Required (reachable from the selected server) | Required (in-cluster Deployment, forward `infra-remediation`) |
| Keycloak and oauth2-proxy                     | Not used                                      | Required (`spec.auth.enabled: true`)                          |

Every step in Part 1 is automatable. In Part 2, only the OpenShift login is manual.

## Prerequisites

### Required tools

| Tool            | Minimum version                                  | Verify command             |
|-----------------|--------------------------------------------------|----------------------------|
| `wanaku` CLI    | installed executable or local build              | `wanaku --version`         |
| `java`          | 21+, only for direct jar invocation              | `java -version`            |
| `wanaku-server` | installed in the worker image; builtin mode only | `command -v wanaku-server` |
| `curl`          | any                                              | `curl --version`           |
| `timeout`       | GNU coreutils; required in the worker image      | `timeout --version`        |
| `jq`            | 1.6+                                             | `jq --version`             |

### CLI invocation

The plan calls the CLI as `wanaku`. Step S.1 prefers an installed executable on `PATH`, including the
`/usr/local/bin/wanaku` launcher supplied by the Saci test image. Its Linux x86_64 native CLI does not require
Java; on other architectures the image launcher provides its JVM invocation. Set `CLI_JAR` explicitly to
use a particular jar instead. If no installed executable is available, the plan falls back to the local build jar.
Run the steps in the same shell so the selected invocation remains available.

Do not put a full command in a variable (for example `WANAKU_CLI="java -jar ..."`); `zsh` does not split it into words.

### How to read MCP command output

- The `wanaku mcp` commands write results to stdout. Capture stdout and stderr separately. When the CLI talks to an
  upstream server that sends SSE frames, the MCP client library can write `WARN` log lines with Java stack traces to
  stderr. These lines are not errors of the command.
- `wanaku mcp ... list` prints one line per entry: `<name> <description>` for tools and prompts, and
  `<name> <uri> <mimeType>` for resources. An empty list prints `No tools found` (or `No resources found`,
  `No prompts found`) and exits with `0`.
- A failed call prints the error message and exits with `1`. Missing picocli-required options (for example `--uri`
  on `list` subcommands) exit with `2`.
- The CLI sends every `--param` and `--arg` value as a JSON **string**. The supplied Quarkus fixture converts
  numeric strings for integer tool arguments, so `scaleDeployment --param replicas=2` succeeds. A non-numeric
  value must be rejected; do not assume every upstream schema handles strings the same way.

### Known limitations

- Wanaku Server is fail-safe. It denies `tools/call`, `resources/read` and `prompts/get` in a namespace unless the
  governance configuration or an action policy allows them. The denial message is
  `No governance policy permits this action.` (action-policy filter, `-32003`) or `evaluator: no_match` (evaluator
  filter). Listing requests are not governed. For a builtin server, Part 1 prepares the test governance configuration of
  [common/start-server.md](common/start-server.md) (step 2b).
- The operator has no field to supply the governance configuration. Part 2 therefore verifies the fail-safe denial
  for calls. The success path of calls on OpenShift is blocked until the operator supports it (see Test 11.2).
- Tool, resource and prompt names are unique in the whole Wanaku Server registry, not per namespace. Do not register
  the same upstream server twice in one server. The second discovery moves the entries to the namespace of the second
  forward.

---
---

## Part 1: Builtin or Existing Instance

### Environment variables

Set variables before Step S.2 to override these defaults. Keep exports and ownership variables in the shared shell.

| Variable                                                                | Purpose                                                                       | Default                                                  |
|-------------------------------------------------------------------------|-------------------------------------------------------------------------------|----------------------------------------------------------|
| `CLI_JAR`                                                               | Explicit jar override; otherwise PATH CLI or local build fallback             | Unset                                                    |
| `WANAKU_SERVER_MODE`                                                    | `builtin` launches the installed server binary; `existing` reuses an instance | `builtin`                                                |
| `WANAKU_SERVER_MGMT_PORT`                                               | Builtin management listener port                                              | `8080`                                                   |
| `WANAKU_SERVER_URL`                                                     | Management API base URL                                                       | Builtin: `http://localhost:8080`; required for existing  |
| `WANAKU_MCP_BASE_URL`                                                   | MCP listener base URL, without namespace/path                                 | Builtin: `http://localhost:8081`; required for existing  |
| `MCP_RUN_ID`                                                            | Unique run suffix                                                             | Timestamp and shell PID                                  |
| `MCP_NAMESPACE` / `MCP_ISOLATED_NAMESPACE` / `MCP_UNGOVERNED_NAMESPACE` | New, distinct test namespaces                                                 | Run-specific DNS labels                                  |
| `TEST_MCP_FORWARD`                                                      | Name of this run's forward                                                    | `infra-remediation-${MCP_RUN_ID}`                               |
| `TEST_MCP_URL`                                                          | Running infra-remediation MCP fixture URL reachable from Wanaku Server                | Required; `TEST_MCP_ADVERTISED_URL` accepted as fallback |

Example before setup: `export TEST_MCP_URL=http://fixture:8181/mcp` (replace `fixture` with the reachable fixture
service name). When the fixture runs beside the builtin server in the same worker container, use its localhost URL.
The plan registers this fixture; it does not launch or stop it.

The test fixture forward goes into `MCP_NAMESPACE`. `MCP_ISOLATED_NAMESPACE` is governed but has no forward.
`MCP_UNGOVERNED_NAMESPACE` has the fail-safe defaults.

Minimal existing-instance configuration before Phase S (replace the example service names; this example is not an
additional step to run in builtin mode):

```bash
export WANAKU_SERVER_MODE=existing
export WANAKU_SERVER_URL=http://wanaku-test:8080
export WANAKU_MCP_BASE_URL=http://wanaku-test:8081
export TEST_MCP_URL=http://fixture:8181/mcp
```

`TEST_MCP_URL` is the upstream fixture endpoint registered with the server. `TEST_MCP_FORWARD` is only a forward
**name**, generated per run by default; do not put a URL in it.

## Phase S: Setup

### Step S.1: Define the CLI invocation

```bash run
# An explicit CLI_JAR overrides PATH; otherwise prefer the installed launcher.
WANAKU_CLI_EXEC="$(unset -f wanaku 2>/dev/null; command -v wanaku 2>/dev/null || true)"
if [ -n "${CLI_JAR:-}" ]; then
  WANAKU_CLI_MODE=jar
elif [ -n "${WANAKU_CLI_EXEC}" ] && [ -x "${WANAKU_CLI_EXEC}" ]; then
  WANAKU_CLI_MODE=executable
else
  CLI_JAR=apps/wanaku-cli/target/quarkus-app/quarkus-run.jar
  WANAKU_CLI_MODE=jar
fi
if [ "${WANAKU_CLI_MODE}" = jar ]; then
  [ -r "${CLI_JAR}" ] \
    || { echo "FAIL [S.1]: selected jar missing or unreadable: ${CLI_JAR}. Install wanaku or set CLI_JAR to a built jar."; exit 1; }
  command -v java > /dev/null 2>&1 \
    || { echo "FAIL [S.1]: jar invocation requires Java 21+ on PATH"; exit 1; }
  wanaku() { java -jar "${CLI_JAR}" "$@"; }
else
  wanaku() { "${WANAKU_CLI_EXEC}" "$@"; }
fi
wanaku --version \
  && echo "PASS [S.1]: selected ${WANAKU_CLI_MODE} CLI runs" \
  || { echo "FAIL [S.1]: selected ${WANAKU_CLI_MODE} CLI failed --version; inspect the error above (launcher, permissions or Java compatibility)."; exit 1; }
```

**Expected result:** The selected CLI prints its version and `PASS [S.1]`; setup stops with a useful diagnostic
if the executable/jar cannot run. Java is required only when invoking a jar directly.

### Step S.2: Select the server mode and test environment

```bash run run
# builtin: launch wanaku-server in this worker; existing: use an already running server.
export WANAKU_SERVER_MODE="${WANAKU_SERVER_MODE:-builtin}"
export MCP_RUN_ID="${MCP_RUN_ID:-$(date +%s)-$$}"
export MCP_NAMESPACE="${MCP_NAMESPACE:-mcp-${MCP_RUN_ID}}"
export MCP_ISOLATED_NAMESPACE="${MCP_ISOLATED_NAMESPACE:-mcp-isolated-${MCP_RUN_ID}}"
export MCP_UNGOVERNED_NAMESPACE="${MCP_UNGOVERNED_NAMESPACE:-mcp-ungoverned-${MCP_RUN_ID}}"
export TEST_MCP_FORWARD="${TEST_MCP_FORWARD:-infra-remediation-${MCP_RUN_ID}}"
export WANAKU_SERVER_MGMT_PORT="${WANAKU_SERVER_MGMT_PORT:-8080}"
export MCP_UNREACHABLE_URI="${MCP_UNREACHABLE_URI:-http://localhost:59999/default/mcp}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${MCP_NAMESPACE},${MCP_ISOLATED_NAMESPACE}"
# Ownership is recorded only after successful creation, for partial-run cleanup.
MCP_CREATED_NAMESPACES=""
MCP_CREATED_FORWARD=false
MCP_CREATED_SERVER=false
MCP_CREATED_SERVER_DATA_DIR=""
WANAKU_SERVER_PID=""
export TEST_MCP_URL="${TEST_MCP_URL:-${TEST_MCP_ADVERTISED_URL:-}}"
# Accept the supplied fixture origin as shorthand, preserving explicit endpoint paths.
TEST_MCP_URL="${TEST_MCP_URL%/}"
case "${TEST_MCP_URL}" in
  http://*|https://*)
    case "${TEST_MCP_URL#*://}" in
      */*) ;;
      *) TEST_MCP_URL="${TEST_MCP_URL}/mcp" ;;
    esac
    ;;
esac
```

**Expected result:**

- The commands exit with code 0 (inferred).

## Phase 0: Prerequisites

### Test 0.1: Verify the required tools

```bash run
for CMD in curl jq timeout; do
  command -v "${CMD}" > /dev/null 2>&1 && echo "PASS [0.1]: ${CMD} available" || echo "FAIL [0.1]: ${CMD} not found"
done
wanaku --version > /dev/null 2>&1 && echo "PASS [0.1]: wanaku CLI available" || echo "FAIL [0.1]: wanaku CLI not available"
```

**Expected result:**

- The output includes `PASS [0.1]: curl available`.
- The output includes `PASS [0.1]: jq available`.
- The output includes `PASS [0.1]: timeout available`.
- The output includes `PASS [0.1]: wanaku CLI available`.
- The output contains no line starting with `FAIL`.

---

## Phase 1: Environment Setup

### Step 1.1: Launch the builtin server OR connect to an existing instance

Run setup and cleanup in the same shell so ownership variables survive. Stop on any failed setup check.
Part 1 uses unauthenticated, trusted test endpoints (`--no-auth`); authenticated deployments use Part 2.
Namespace overrides must be distinct DNS labels of at most 63 characters and absent before this run.

**Builtin mode (`WANAKU_SERVER_MODE=builtin`, default):** Use the `wanaku-server` binary already installed in
the worker image. It runs directly in the same worker container as the CLI. No nested container runtime or image
pull is needed. Management and MCP URLs are set automatically. Both listener ports must be free; never terminate an
unrelated listener to free a port. The MCP port is fixed at `8081` by the embedded server pipeline.

The following block prepares the namespace-scoped governance configuration described in
[common/start-server.md](common/start-server.md#step-2b-optional-prepare-the-test-governance-configuration) and starts
the installed binary. Run this block directly; no other startup procedure is needed.

```bash run
case "${WANAKU_SERVER_MODE}" in
  builtin)
    WANAKU_SERVER_BIN=$(command -v wanaku-server) \
      || { echo "FAIL [1.1]: builtin mode requires wanaku-server on PATH"; exit 1; }
    for PORT in "${WANAKU_SERVER_MGMT_PORT}" 8081; do
      if curl --connect-timeout 1 --max-time 2 -s -o /dev/null "http://localhost:${PORT}/"; then
        echo "FAIL [1.1]: builtin listener port already in use"; exit 1
      fi
    done
    export WANAKU_SERVER_DATA_DIR="$(mktemp -d)"
    MCP_CREATED_SERVER_DATA_DIR="${WANAKU_SERVER_DATA_DIR}"
    export WANAKU_SERVER_CONFIG="${WANAKU_SERVER_DATA_DIR}/wanaku.yaml"
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
    export WANAKU_SERVER_URL="http://localhost:${WANAKU_SERVER_MGMT_PORT}"
    export WANAKU_MCP_BASE_URL="http://localhost:8081"
    export WANAKU_SERVER_LOG="${WANAKU_SERVER_DATA_DIR}/wanaku-server.log"
    WANAKU_MGMT_LISTEN="0.0.0.0:${WANAKU_SERVER_MGMT_PORT}" \
    WANAKU_PERSIST_BACKEND=file WANAKU_PERSIST_PATH="${WANAKU_SERVER_DATA_DIR}" \
      "${WANAKU_SERVER_BIN}" --wanaku-config "${WANAKU_SERVER_CONFIG}" \
      < /dev/null > "${WANAKU_SERVER_LOG}" 2>&1 &
    WANAKU_SERVER_PID=$!
    MCP_CREATED_SERVER=true
    echo "PASS [1.1]: builtin server process launched"
    ;;
  existing) ;;
  *) echo "FAIL [1.1]: mode must be builtin or existing"; exit 1 ;;
esac
```

The background process uses `/dev/null` for stdin and a file for stdout/stderr, so it does not retain the worker's
captured input/output streams. Readiness below is bounded to about 30 seconds; each registry query has a 10-second
limit plus a 2-second termination grace period. Step 1.1 should complete within about 85 seconds, including port
checks. Run Phase 9 cleanup after any failure.

**Existing mode (`WANAKU_SERVER_MODE=existing`):** Set both `WANAKU_SERVER_URL` (management API base URL)
and `WANAKU_MCP_BASE_URL` (MCP listener base URL, without a namespace or `/mcp`). These may have different hosts,
ports or schemes. No local server binary or server PID is required. Do not start, restart or reconfigure
the instance from this plan. Its owner must provision governance allowing calls in `MCP_NAMESPACE` and
`MCP_ISOLATED_NAMESPACE`, with fail-safe denial in `MCP_UNGOVERNED_NAMESPACE`, before running the tests.
If unavailable, mark the affected governance/call tests BLOCKED, never PASS.

The tests modify global registry entries (including disabling `restartService`). Use a dedicated empty test instance for the
run. Unique namespaces alone cannot prevent the global name collisions described above. Inspect the lists below
and stop if any tools, resources, prompts or forwards exist. Do not remove other users' entries to satisfy this
prerequisite.

```bash run
case "${WANAKU_SERVER_MODE}" in
  builtin) ;;
  existing)
    : "${WANAKU_SERVER_URL:?Set management API base URL}"
    : "${WANAKU_MCP_BASE_URL:?Set MCP listener base URL}"
    echo "PASS [1.1]: existing server selected (${WANAKU_SERVER_URL})"
    ;;
  *) echo "FAIL [1.1]: mode must be builtin or existing"; exit 1 ;;
esac
for ATTEMPT in $(seq 1 15); do
  if [ "${MCP_CREATED_SERVER:-false}" = true ] && ! kill -0 "${WANAKU_SERVER_PID}" 2>/dev/null; then
    echo "FAIL [1.1]: builtin server exited during startup"
    tail -n 20 "${WANAKU_SERVER_LOG}"
    exit 1
  fi
  if curl --connect-timeout 1 --max-time 1 -fsS "${WANAKU_SERVER_URL}/healthz" > /dev/null 2>&1; then
    echo "PASS [1.1]: management endpoint ready"
    break
  fi
  if [ "${ATTEMPT}" -eq 15 ]; then
    echo "FAIL [1.1]: management endpoint unavailable after bounded readiness wait"
    [ "${MCP_CREATED_SERVER:-false}" != true ] || tail -n 20 "${WANAKU_SERVER_LOG}"
    exit 1
  fi
  sleep 1
done
for KIND in tools resources prompts forwards; do
  # timeout needs an executable, so invoke the route selected in S.1 directly.
  if [ "${WANAKU_CLI_MODE}" = jar ]; then
    timeout --kill-after=2s 10s java -jar "${CLI_JAR}" \
      "${KIND}" list --host "${WANAKU_SERVER_URL}" --no-auth --plain
  else
    timeout --kill-after=2s 10s "${WANAKU_CLI_EXEC}" \
      "${KIND}" list --host "${WANAKU_SERVER_URL}" --no-auth --plain
  fi
  RESULT=$?
  [ "${RESULT}" -eq 0 ] \
    || { echo "FAIL [1.1]: ${KIND} registry inspection failed or timed out (exit ${RESULT})"; exit 1; }
done
echo "PASS [1.1]: server setup complete (mode ${WANAKU_SERVER_MODE})"
```

Use the recorded outputs of the `run` blocks for assessment. Do not rerun startup or wait for the background server
to exit; it must remain running for later steps. After comparing the recorded evidence with the criteria below,
submit the step assessment.

**Expected result:**

- Both `run` blocks exit with code 0.
- The output includes `PASS [1.1]: management endpoint ready`.
- The output includes `PASS [1.1]: server setup complete (mode <WANAKU_SERVER_MODE>)` for the selected mode.
- The output contains no line starting with `FAIL`.

### Step 1.2: Create the test namespaces

```bash run
for NS in "${MCP_NAMESPACE}" "${MCP_ISOLATED_NAMESPACE}" "${MCP_UNGOVERNED_NAMESPACE}"; do
  wanaku namespaces create "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null \
    || { echo "FAIL [1.2]: namespace creation failed; stop before registering the forward"; exit 1; }
  MCP_CREATED_NAMESPACES="${MCP_CREATED_NAMESPACES} ${NS}"
  echo "PASS [1.2]: test namespace created"
done
```

**Expected result:** Three new namespaces are created; none are reused.

### Step 1.3: Register the running MCP fixture as a forward

Provide `TEST_MCP_URL` before setup. Use the supplied `fixtures/test-mcp-server` fixture, implemented by
`InfraRemediationServer`. Its tools include `restartService`, `scaleDeployment` and `escalateTicket`; its static
resource is `safetyLimits` at `config://policies/safety-limits`; its prompts are `summarizeIncident` and
`draftEscalation`. These are simulated remediation operations in the test fixture.
It must use Streamable HTTP and be reachable **from Wanaku Server**, not just from the CLI worker.
Supply the MCP endpoint (for example `http://host.docker.internal:38181/mcp`). Step S.2 also accepts a bare
HTTP(S) origin and appends the fixture's `/mcp` path; an explicit endpoint path is preserved.
For builtin mode a fixture in the same worker can use localhost. For existing mode, localhost refers to the existing
server's machine/container; use a reachable fixture service URL instead. A fixture connection failure is a setup
failure, not a tool-list test failure. The plan registers the fixture without launching or stopping it.

```bash run
: "${TEST_MCP_URL:?Set the running infra-remediation MCP fixture URL reachable from Wanaku Server}"
case "${TEST_MCP_URL}" in
  http://*|https://*) ;;
  *) echo "FAIL [1.3]: TEST_MCP_URL must use http:// or https://"; exit 1 ;;
esac
export TEST_MCP_NAMESPACE="${MCP_NAMESPACE}"
```

Register and verify discovery below using the CLI selected in S.1, before any MCP CLI tests.
Record ownership immediately after successful registration, so cleanup also works if discovery fails.

```bash run
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${TEST_MCP_FORWARD}" --service "${TEST_MCP_URL}" --namespace "${TEST_MCP_NAMESPACE}" \
  || { echo "FAIL [1.3]: forward registration failed"; exit 1; }
MCP_CREATED_FORWARD=true
for ATTEMPT in $(seq 1 30); do
  OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain) \
    || { echo "FAIL [1.3]: discovery query failed"; exit 1; }
  if echo "${OUTPUT}" | grep -Eq '^restartService([[:space:]]|$)'; then
    echo "PASS [1.3]: restartService discovered"
    break
  fi
  [ "${ATTEMPT}" -lt 30 ] || { echo "FAIL [1.3]: fixture restartService not discovered; check server-to-fixture reachability"; exit 1; }
  wanaku forwards refresh --host "${WANAKU_SERVER_URL}" --no-auth --name "${TEST_MCP_FORWARD}" > /dev/null 2>&1 || true
  sleep 1
done
```

Do not register another copy if discovery fails; fix connectivity and refresh this forward.

**Expected result:** The test fixture is reachable from the selected instance, the run's forward is created,
and `restartService` is discovered. Step 1.4 also verifies the MCP listener through the CLI.

### Step 1.4: Set the MCP endpoint

```bash run
export MCP_URI="${WANAKU_MCP_BASE_URL}/${MCP_NAMESPACE}/mcp"
for ATTEMPT in $(seq 1 30); do
  OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain) \
    || { echo "FAIL [1.4]: MCP listener unavailable"; exit 1; }
  if echo "${OUTPUT}" | grep -Eq '^restartService([[:space:]]|$)'; then
    echo "PASS [1.4]: MCP listener lists fixture restartService"
    break
  fi
  [ "${ATTEMPT}" -lt 30 ] || { echo "FAIL [1.4]: fixture restartService missing from test namespace; stop before MCP tests"; exit 1; }
  sleep 1
done
```

**Expected result:**

- The output includes `PASS [1.4]: MCP listener lists fixture restartService`; no FAIL is printed.

---

## Phase 2: Help and Usage

### Test 2.1: `wanaku mcp` shows the subcommands

```bash run
OUTPUT=$(wanaku mcp 2>&1)
for SUB in tool resource prompt; do
  echo "${OUTPUT}" | grep -q "^  ${SUB} " && echo "PASS [2.1]: '${SUB}' listed" || echo "FAIL [2.1]: '${SUB}' not listed"
done
```

**Expected result:**

- The output includes `PASS [2.1]: 'tool' listed`.
- The output includes `PASS [2.1]: 'resource' listed`.
- The output includes `PASS [2.1]: 'prompt' listed`.
- The output contains no line starting with `FAIL`.

### Test 2.2: Subcommand help shows the options

```bash run
check_help() {
  HELP=$(wanaku mcp "$1" --help 2>&1)
  shift
  for OPT in "$@"; do
    echo "${HELP}" | grep -q -- "${OPT}" && echo "PASS [2.2]: ${OPT} listed" || echo "FAIL [2.2]: ${OPT} missing"
  done
  echo "${HELP}" | grep -q "^  list " && echo "PASS [2.2]: 'list' listed" || echo "FAIL [2.2]: 'list' missing"
}
check_help tool --uri --name --param
check_help resource --uri --resource-uri
check_help prompt --uri --name --arg
```

**Expected result:**

- The output includes `PASS [2.2]: --uri listed`.
- The output includes `PASS [2.2]: --name listed`.
- The output includes `PASS [2.2]: --param listed`.
- The output includes `PASS [2.2]: --resource-uri listed`.
- The output includes `PASS [2.2]: --arg listed`.
- The output includes `PASS [2.2]: 'list' listed`.
- The output contains no line starting with `FAIL`.

---

## Phase 3: Required Option Validation

The call commands check `--uri`, `--name` and `--resource-uri` themselves and exit with `1`. The `list` subcommands
declare `--uri` as a required picocli option and exit with `2`.

### Test 3.1: Missing options are rejected

```bash run
expect_exit() {
  EXPECTED="$1"; MESSAGE="$2"; shift 2
  OUTPUT=$("$@" 2>&1)
  EXIT_CODE=$?
  if [ "${EXIT_CODE}" -eq "${EXPECTED}" ] && echo "${OUTPUT}" | grep -q -- "${MESSAGE}"; then
    echo "PASS [3.1]: '$*' exits ${EXIT_CODE} with '${MESSAGE}'"
  else
    echo "FAIL [3.1]: '$*' exit ${EXIT_CODE}, expected ${EXPECTED} with '${MESSAGE}'"
    echo "${OUTPUT}"
  fi
}
expect_exit 1 "Missing required option: --uri"          wanaku mcp tool --no-auth --name restartService
expect_exit 1 "Missing required option: --name"         wanaku mcp tool --no-auth --uri "${MCP_URI}"
expect_exit 1 "Missing required option: --resource-uri" wanaku mcp resource --no-auth --uri "${MCP_URI}"
expect_exit 1 "Missing required option: --name"         wanaku mcp prompt --no-auth --uri "${MCP_URI}"
expect_exit 2 "Missing required option"                 wanaku mcp tool list --no-auth
expect_exit 2 "Missing required option"                 wanaku mcp resource list --no-auth
expect_exit 2 "Missing required option"                 wanaku mcp prompt list --no-auth
```

**Expected result:**

- The output includes `PASS [3.1]: 'wanaku mcp tool --no-auth --name restartService' exits 1 with 'Missing required option: --uri'`.
- The output includes `PASS [3.1]: 'wanaku mcp tool --no-auth --uri <MCP_URI>' exits 1 with 'Missing required option: --name'`.
- The output includes `PASS [3.1]: 'wanaku mcp resource --no-auth --uri <MCP_URI>' exits 1 with 'Missing required option: --resource-uri'`.
- The output includes `PASS [3.1]: 'wanaku mcp prompt --no-auth --uri <MCP_URI>' exits 1 with 'Missing required option: --name'`.
- The output includes `PASS [3.1]: 'wanaku mcp tool list --no-auth' exits 2 with 'Missing required option'`.
- The output includes `PASS [3.1]: 'wanaku mcp resource list --no-auth' exits 2 with 'Missing required option'`.
- The output includes `PASS [3.1]: 'wanaku mcp prompt list --no-auth' exits 2 with 'Missing required option'`.
- The output contains no line starting with `FAIL`.

---

## Phase 4: Tool Operations

### Test 4.1: List the tools of the namespace

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS [4.1]: tool list exit 0" || echo "FAIL [4.1]: tool list exit ${EXIT_CODE}"
for TOOL in restartService scaleDeployment escalateTicket; do
  echo "${OUTPUT}" | grep -q "^${TOOL} " && echo "PASS [4.1]: '${TOOL}' listed" || echo "FAIL [4.1]: '${TOOL}' not listed"
done
```

**Expected result:**

- The output includes `PASS [4.1]: tool list exit 0`.
- The output includes `PASS [4.1]: 'restartService' listed`.
- The output includes `PASS [4.1]: 'scaleDeployment' listed`.
- The output includes `PASS [4.1]: 'escalateTicket' listed`.
- The output contains no line starting with `FAIL`.

### Test 4.2: Call a tool with its required string parameters

The fixture has no tool with a successful one-parameter call independent of forwarded headers. Use both
required parameters of `restartService` to verify successful invocation.

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && [ "${OUTPUT}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ]; then
  echo "PASS [4.2]: restartService returned the restart confirmation"
else
  echo "FAIL [4.2]: exit ${EXIT_CODE}, output '${OUTPUT}'"
fi
```

**Expected result:**

- The output includes `PASS [4.2]: restartService returned the restart confirmation`.
- The output contains no line starting with `FAIL`.

### Test 4.3: Call a tool with repeated and comma-separated parameters

The CLI accepts `--param` more than once, and it splits one value at commas. Both forms must supply
the two required fixture parameters and produce the same restart confirmation. Also verify the fixture
accepts its string-valued urgency enum; the ticket number increments, so assert its format rather than a fixed ID.

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>/dev/null)
[ "${OUTPUT}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ] && echo "PASS [4.3]: --param serverId=test-server --param service=nginx" \
  || echo "FAIL [4.3]: --param serverId=test-server --param service=nginx returned '${OUTPUT}'"
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server,service=nginx --plain 2>/dev/null)
[ "${OUTPUT}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ] && echo "PASS [4.3]: --param serverId=test-server,service=nginx" \
  || echo "FAIL [4.3]: --param serverId=test-server,service=nginx returned '${OUTPUT}'"
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name escalateTicket \
  --param reason=memory-pressure --param urgency=high --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -Eq '^Ticket INC-[0-9]+ created with urgency=high: memory-pressure$'; then
  echo "PASS [4.3]: escalateTicket accepts urgency=high"
else
  echo "FAIL [4.3]: exit ${EXIT_CODE}, output '${OUTPUT}'"
fi
```

**Expected result:**

- The output includes `PASS [4.3]: --param serverId=test-server --param service=nginx`.
- The output includes `PASS [4.3]: --param serverId=test-server,service=nginx`.
- The output includes `PASS [4.3]: escalateTicket accepts urgency=high`.
- The output contains no line starting with `FAIL`.

### Test 4.4: Numeric tool arguments are converted or rejected appropriately

The CLI sends `replicas=2` as a string; the supplied Quarkus fixture converts it to an integer. Verify that
valid numeric input succeeds and non-numeric input fails, without depending on validation-library wording.

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name scaleDeployment \
  --param target=test-deployment --param replicas=2 --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && [ "${OUTPUT}" = "SUCCESS: Deployment 'test-deployment' scaled to 2 replica(s)." ]; then
  echo "PASS [4.4]: numeric string converted to integer"
else
  echo "FAIL [4.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name scaleDeployment \
  --param target=test-deployment --param replicas=not-a-number --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -qiE 'replicas|not-a-number|integer|number'; then
  echo "PASS [4.4]: non-numeric replicas rejected"
else
  echo "FAIL [4.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.4]: numeric string converted to integer`.
- The output includes `PASS [4.4]: non-numeric replicas rejected`.
- The output contains no line starting with `FAIL`.

### Test 4.5: Call a tool without its required argument

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "Missing required argument: serverId"; then
  echo "PASS [4.5]: missing 'serverId' rejected"
else
  echo "FAIL [4.5]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.5]: missing 'serverId' rejected`.
- The output contains no line starting with `FAIL`.

### Test 4.6: Call an unknown tool

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name non-existent-tool-12345 --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "tool not found: non-existent-tool-12345"; then
  echo "PASS [4.6]: unknown tool rejected"
else
  echo "FAIL [4.6]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.6]: unknown tool rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 5: Resource Operations

### Test 5.1: List the resources of the namespace

```bash run
OUTPUT=$(wanaku mcp resource list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "^safetyLimits .*config://policies/safety-limits"; then
  echo "PASS [5.1]: safetyLimits listed with its URI"
else
  echo "FAIL [5.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.1]: safetyLimits listed with its URI`.
- The output contains no line starting with `FAIL`.

### Test 5.2: Read a resource

```bash run
RESOURCE_URI="${TEST_RESOURCE_URI:-config://policies/safety-limits}"
OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "${RESOURCE_URI}" --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "max_db_replicas" \
  && echo "${OUTPUT}" | grep -q "blocked_services" && echo "${OUTPUT}" | grep -q "payment-gateway"; then
  echo "PASS [5.2]: resource content returned"
else
  echo "FAIL [5.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.2]: resource content returned`.
- The output contains no line starting with `FAIL`.

### Test 5.3: Read an unknown resource

Wanaku Server looks up the URI in its registry before it forwards the request.

```bash run
OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "demo://non-existent-12345" --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "resource not found: demo://non-existent-12345"; then
  echo "PASS [5.3]: unknown resource rejected"
else
  echo "FAIL [5.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.3]: unknown resource rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 6: Prompt Operations

### Test 6.1: List the prompts of the namespace

```bash run
OUTPUT=$(wanaku mcp prompt list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
for PROMPT in summarizeIncident draftEscalation; do
  echo "${OUTPUT}" | grep -q "^${PROMPT} " && echo "PASS [6.1]: '${PROMPT}' listed" || echo "FAIL [6.1]: '${PROMPT}' not listed"
done
```

**Expected result:**

- The output includes `PASS [6.1]: 'summarizeIncident' listed`.
- The output includes `PASS [6.1]: 'draftEscalation' listed`.
- The output contains no line starting with `FAIL`.

### Test 6.2: Get a prompt without arguments

Both fixture prompts require arguments; neither is a zero-argument prompt. Verify that an argument-free
request is rejected, then verify successful rendering in Test 6.3.

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name summarizeIncident --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q -- "-32602"; then
  echo "PASS [6.2]: required prompt arguments rejected when absent"
else
  echo "FAIL [6.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [6.2]: required prompt arguments rejected when absent`.
- The output contains no line starting with `FAIL`.

### Test 6.3: Get prompts with arguments

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name summarizeIncident \
  --arg serverId=test-server --arg incident=memory-pressure --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "server test-server" \
  && echo "${OUTPUT}" | grep -q "memory-pressure"; then
  echo "PASS [6.3]: summarizeIncident rendered with both arguments"
else
  echo "FAIL [6.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name draftEscalation \
  --arg urgency=high --arg reason=memory-pressure --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "urgency=high" \
  && echo "${OUTPUT}" | grep -q "memory-pressure"; then
  echo "PASS [6.3]: draftEscalation rendered with both arguments"
else
  echo "FAIL [6.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [6.3]: summarizeIncident rendered with both arguments`.
- The output includes `PASS [6.3]: draftEscalation rendered with both arguments`.
- The output contains no line starting with `FAIL`.

### Test 6.4: Get a prompt without one required argument

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name summarizeIncident \
  --arg serverId=test-server --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q -- "-32602"; then
  echo "PASS [6.4]: missing 'incident' rejected"
else
  echo "FAIL [6.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [6.4]: missing 'incident' rejected`.
- The output contains no line starting with `FAIL`.

### Test 6.5: Get an unknown prompt

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name non-existent-prompt-12345 --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "prompt not found: non-existent-prompt-12345"; then
  echo "PASS [6.5]: unknown prompt rejected"
else
  echo "FAIL [6.5]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [6.5]: unknown prompt rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 7: Namespaces and Governance

### Test 7.1: A namespace shows only its own entries

`MCP_ISOLATED_NAMESPACE` is governed, but it has no forward.

```bash run
ISO_URI="${WANAKU_MCP_BASE_URL}/${MCP_ISOLATED_NAMESPACE}/mcp"
for KIND in tool resource prompt; do
  OUTPUT=$(wanaku mcp "${KIND}" list --uri "${ISO_URI}" --no-auth --plain 2>/dev/null)
  echo "${OUTPUT}" | grep -q "^No ${KIND}s found" \
    && echo "PASS [7.1]: no ${KIND}s in ${MCP_ISOLATED_NAMESPACE}" || echo "FAIL [7.1]: ${KIND}s leaked: ${OUTPUT}"
done
```

**Expected result:**

- The output includes `PASS [7.1]: no tools in <MCP_ISOLATED_NAMESPACE>`.
- The output includes `PASS [7.1]: no resources in <MCP_ISOLATED_NAMESPACE>`.
- The output includes `PASS [7.1]: no prompts in <MCP_ISOLATED_NAMESPACE>`.
- The output contains no line starting with `FAIL`.

### Test 7.2: A tool of another namespace cannot be called

```bash run
OUTPUT=$(wanaku mcp tool --uri "${ISO_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "tool not found: restartService"; then
  echo "PASS [7.2]: 'restartService' is not callable from ${MCP_ISOLATED_NAMESPACE}"
else
  echo "FAIL [7.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [7.2]: 'restartService' is not callable from <MCP_ISOLATED_NAMESPACE>`.
- The output contains no line starting with `FAIL`.

### Test 7.3: The fail-safe default denies calls in a namespace that is not governed

The governance check runs before the tool lookup. Listing is not governed.

```bash run
UNGOV_URI="${WANAKU_MCP_BASE_URL}/${MCP_UNGOVERNED_NAMESPACE}/mcp"
wanaku mcp tool list --uri "${UNGOV_URI}" --no-auth --plain > /dev/null 2>&1 \
  && echo "PASS [7.3]: listing is allowed in ${MCP_UNGOVERNED_NAMESPACE}" || echo "FAIL [7.3]: listing failed"

OUTPUT=$(wanaku mcp tool --uri "${UNGOV_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "No governance policy permits this action."; then
  echo "PASS [7.3]: tools/call denied by the fail-safe default"
else
  echo "FAIL [7.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [7.3]: listing is allowed in <MCP_UNGOVERNED_NAMESPACE>`.
- The output includes `PASS [7.3]: tools/call denied by the fail-safe default`.
- The output contains no line starting with `FAIL`.

### Test 7.4: A disabled tool is hidden from MCP clients

The enable and disable routes have no CLI command, so this test uses `curl`.

```bash run
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/restartService/disable")
[ "${HTTP_CODE}" = "200" ] && echo "PASS [7.4]: restartService disabled" || echo "FAIL [7.4]: disable returned HTTP ${HTTP_CODE}"

wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null | grep -q "^restartService " \
  && echo "FAIL [7.4]: disabled tool still listed" || echo "PASS [7.4]: disabled tool not listed"

OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
echo "${OUTPUT}" | grep -q "tool not found: restartService" \
  && echo "PASS [7.4]: disabled tool cannot be called" || echo "FAIL [7.4]: output: ${OUTPUT}"

# The management API still lists the entry
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^restartService" \
  && echo "PASS [7.4]: management API still lists restartService" || echo "FAIL [7.4]: restartService missing from management API"
```

**Expected result:**

- The output includes `PASS [7.4]: restartService disabled`.
- The output includes `PASS [7.4]: disabled tool not listed`.
- The output includes `PASS [7.4]: disabled tool cannot be called`.
- The output includes `PASS [7.4]: management API still lists restartService`.
- The output contains no line starting with `FAIL`.

### Test 7.5: An enabled tool is visible again

```bash run
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/restartService/enable")
[ "${HTTP_CODE}" = "200" ] && echo "PASS [7.5]: restartService enabled" || echo "FAIL [7.5]: enable returned HTTP ${HTTP_CODE}"

OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>/dev/null)
[ "${OUTPUT}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ] && echo "PASS [7.5]: restartService callable again" || echo "FAIL [7.5]: output '${OUTPUT}'"
```

**Expected result:**

- The output includes `PASS [7.5]: restartService enabled`.
- The output includes `PASS [7.5]: restartService callable again`.
- The output contains no line starting with `FAIL`.

---

## Phase 8: Endpoint and Connection Errors

### Test 8.1: The bare `/mcp` path is rejected

Wanaku Server returns the JSON-RPC error `-32600` for an MCP request without a namespace.

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/mcp" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "invalid MCP endpoint path: use /{namespace}/mcp"; then
  echo "PASS [8.1]: bare /mcp rejected"
else
  echo "FAIL [8.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [8.1]: bare /mcp rejected`.
- The output contains no line starting with `FAIL`.

### Test 8.2: A legacy SSE endpoint is rejected by the CLI

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}/sse" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "Legacy SSE MCP endpoints are no longer supported"; then
  echo "PASS [8.2]: SSE endpoint rejected with a migration hint"
else
  echo "FAIL [8.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [8.2]: SSE endpoint rejected with a migration hint`.
- The output contains no line starting with `FAIL`.

### Test 8.3: An unreachable server fails without a stack trace

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${MCP_UNREACHABLE_URI}" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && ! echo "${OUTPUT}" | grep -q "at .*(.*\.java:"; then
  echo "PASS [8.3]: unreachable server fails gracefully"
else
  echo "FAIL [8.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
echo "${OUTPUT}" | grep -q "Connection refused" \
  || echo "WARN [8.3]: message is not the friendly 'Connection refused' text: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [8.3]: unreachable server fails gracefully`.
- The output contains no line starting with `FAIL`.
- The output contains `Connection refused`, or includes `WARN [8.3]: message is not the friendly`.

### Test 8.4: An invalid URI fails gracefully

```bash run
OUTPUT=$(wanaku mcp tool list --uri "not-a-valid-uri" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "Invalid URI: 'not-a-valid-uri'"; then
  echo "PASS [8.4]: invalid URI rejected"
else
  echo "FAIL [8.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [8.4]: invalid URI rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 9: Cleanup (Part 1)

### Step 9.1: Cleanup (Part 1)

Remove only resources recorded as created by this run, including after partial setup. Never stop an existing
Wanaku Server or delete its persistence/configuration. Do not use port-wide process killing on a shared machine.

```bash run
if [ "${MCP_CREATED_FORWARD:-false}" = true ]; then
  wanaku forwards remove --host "${WANAKU_SERVER_URL}" --no-auth \
    --name "${TEST_MCP_FORWARD}" --plain > /dev/null 2>&1 || true
fi
for NS in $(printf '%s' "${MCP_CREATED_NAMESPACES}"); do
  wanaku namespaces delete "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null 2>&1 || true
done
if [ "${WANAKU_SERVER_MODE}" = builtin ]; then
  if [ "${MCP_CREATED_SERVER:-false}" = true ]; then
    kill "${WANAKU_SERVER_PID}" 2>/dev/null || true
    wait "${WANAKU_SERVER_PID}" 2>/dev/null || true
  fi
  if [ -n "${MCP_CREATED_SERVER_DATA_DIR:-}" ]; then
    rm -rf "${MCP_CREATED_SERVER_DATA_DIR}"
  fi
fi
```

**Expected result:** The run's forward, discovered entries and three namespaces are absent (verify with the CLI
list commands). The supplied fixture remains running. In builtin mode the owned server process and fresh
configuration/data directory are removed. In existing mode the server remains reachable and its unrelated configuration/data is preserved.
Cleanup failures must be investigated; ignored missing-resource errors alone do not establish PASS.

## Part 1 Summary

| Phase | Test ID | Test Name                                                                             | Priority |
|-------|---------|---------------------------------------------------------------------------------------|----------|
| S     | S.1-S.2 | CLI invocation and server mode                                                        | High     |
| 0     | 0.1     | Prerequisites                                                                         | High     |
| 1     | 1.1-1.4 | Server with test governance, fixture forward, namespaces                              | Critical |
| 2     | 2.1-2.2 | Help and usage output                                                                 | Medium   |
| 3     | 3.1     | Required option validation (exit codes 1 and 2)                                       | High     |
| 4     | 4.1-4.6 | Tool list, call, multi-param, numeric conversion/rejection, missing arg, unknown tool | Critical |
| 5     | 5.1-5.3 | Resource list, read, unknown resource                                                 | Critical |
| 6     | 6.1-6.5 | Prompt list, get, get with args, missing arg, unknown prompt                          | Critical |
| 7     | 7.1-7.5 | Namespace isolation, fail-safe governance, disable/enable                             | Critical |
| 8     | 8.1-8.4 | Bare `/mcp`, legacy SSE, unreachable server, invalid URI                              | High     |
| 9     | 9.1     | Cleanup                                                                               | Critical |

---
---

## Part 2: OpenShift with the Operator and oauth2-proxy

## Part 2 overview

The operator deploys Wanaku Server from a `WanakuRouter` resource (`spec.server`) with authentication enabled
(`spec.auth`). The operator creates:

- the `<router>-server` Deployment and the `server-<router>` Service (ports 8080 management, 8081 MCP, 8083, 8084)
- the `<router>-oauth2-proxy` Deployment (4180 MCP proxy, 4181 management proxy)
- the Route `<router>` for the management API and the Route `<router>-mcp` for the MCP listener (edge TLS)

The plan deploys the MCP test fixture in the cluster and registers it as a forward with the CLI. This replaces
the removed `WanakuCapability` resource. The operator also offers `WanakuIntegration` for the Camel Integration
Capability; this plan does not need it. Barn is not deployed.

## Part 2 prerequisites

### Required tools

| Tool                    | Minimum version                     | Verify command                    |
|-------------------------|-------------------------------------|-----------------------------------|
| `oc`                    | 4.12+                               | `oc version --client`             |
| `helm`                  | 3.x                                 | `helm version --short`            |
| `wanaku` CLI            | installed executable or local build | `wanaku --version`                |
| `wanaku-keycloak-admin` | installed executable or local build | `wanaku-keycloak-admin --version` |
| `openssl`               | any                                 | `openssl version`                 |
| `curl`, `jq`            | any / 1.6+                          | `jq --version`                    |

### Environment variables

Step 11.1 sets a unique `WANAKU_NAMESPACE` using the namespace setup procedure.

The environment variables are set in Step S.3 after Step 11.1.

The cluster must be able to pull `TEST_MCP_IMAGE`, the supplied infra-remediation fixture image.

Step S.4 defines `wait_for_deletion` from [common/wait-for-deletion.md](common/wait-for-deletion.md).

---

## Phase 10: OpenShift Login (MANUAL)

### Step 10.1: Log in to OpenShift (MANUAL)

Follow [common/openshift-login.md](common/openshift-login.md).

**Expected result:**

- All procedure steps passed, and `oc whoami` shows the service account.

---

## Phase 11: Deploy the Wanaku Stack

### Step 11.1: Create the namespace

Follow [common/namespace-setup.md](common/namespace-setup.md).

**Expected result:**

- The current namespace is the value of `WANAKU_NAMESPACE`.

## Phase T: Setup for OpenShift

### Step S.3: Set OpenShift environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-mcp-test}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_OAUTH2_PROXY_IMAGE="${WANAKU_OAUTH2_PROXY_IMAGE:-}"   # empty: operator default
export TEST_MCP_IMAGE="${TEST_MCP_IMAGE:-quay.io/wanaku/test-mcp-server:latest}"
export MCP_E2E_NAMESPACE="${MCP_E2E_NAMESPACE:-mcp-e2e}"
# Set to "--insecure" when the cluster Route certificates are not trusted
export WANAKU_TLS_FLAG="${WANAKU_TLS_FLAG:-}"
# Isolate CLI credentials per test run (see #1697)
export WANAKU_CREDENTIALS="${WANAKU_CREDENTIALS:-/tmp/wanaku-creds-${WANAKU_NAMESPACE}}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step S.4: Define the deletion helper

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the function without errors (inferred).

## Phase 11b: Deploy the Wanaku Stack (continued)

### Step 11.2: Deploy Keycloak

Follow [common/keycloak-setup.md](common/keycloak-setup.md).

For this plan, run the procedure's blocks that set output variables in the shared shell, including the block
that exports `WANAKU_CLIENT_SECRET`, even where the procedure says to use a separate script. The variables must
remain available for the checks below and for the oauth2-proxy Secret.

After completion, verify the variables:

Run this block as a separate `bash` script, not in the shared session.

```bash run
for VAR_NAME in KEYCLOAK_URL WANAKU_CLIENT_SECRET WANAKU_TEST_USER WANAKU_TEST_PASS; do
  eval "VAL=\${${VAR_NAME}}"
  [ -n "${VAL}" ] && echo "PASS [11.2]: ${VAR_NAME} is set" || { echo "FAIL [11.2]: ${VAR_NAME} is not set"; exit 1; }
done
```

**Expected result:**

- The output includes `PASS [11.2]: KEYCLOAK_URL is set`.
- The output includes `PASS [11.2]: WANAKU_CLIENT_SECRET is set`.
- The output includes `PASS [11.2]: WANAKU_TEST_USER is set`.
- The output includes `PASS [11.2]: WANAKU_TEST_PASS is set`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.3: Install the operator

Follow [common/operator-deployment.md](common/operator-deployment.md).

**Expected result:**

- The operator is available and the procedure checks pass.

### Step 11.4: Create the oauth2-proxy Secret

The proxies use the `wanaku-mcp-router` client. The Secret needs the keys `client-secret` and `cookie-secret`.

Run this block as a separate `bash` script, not in the shared session.

```bash run
oc create secret generic "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="$(openssl rand -hex 16)" 2>/dev/null \
  || oc get secret "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" > /dev/null
[ $? -eq 0 ] && echo "PASS [11.4]: oauth2-proxy Secret exists" || { echo "FAIL [11.4]: Secret not created"; exit 1; }
```

**Expected result:**

- The output includes `PASS [11.4]: oauth2-proxy Secret exists`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.5: Create the WanakuRouter

Run this block as a separate `bash` script, not in the shared session.

```bash run
{
cat <<EOF
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  server:
    image: ${WANAKU_SERVER_IMAGE}
    imagePullPolicy: Always
  auth:
    enabled: true
    issuerUrl: ${KEYCLOAK_URL}/realms/wanaku
    internalIssuerUrl: http://keycloak:8080/realms/wanaku
    clientId: wanaku-mcp-router
    secretName: ${WANAKU_ROUTER_NAME}-oidc
EOF
[ -n "${WANAKU_OAUTH2_PROXY_IMAGE}" ] && echo "    image: ${WANAKU_OAUTH2_PROXY_IMAGE}"
} | oc apply -n "${WANAKU_NAMESPACE}" -f -

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=300s -n "${WANAKU_NAMESPACE}" \
  && echo "PASS [11.5]: WanakuRouter is Ready" || { echo "FAIL [11.5]: WanakuRouter not Ready"; exit 1; }
```

**Expected result:**

- The output includes `PASS [11.5]: WanakuRouter is Ready`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.6: Verify the generated resources

```bash run
R="${WANAKU_ROUTER_NAME}"
for DEP in "${R}-server" "${R}-oauth2-proxy"; do
  oc rollout status "deployment/${DEP}" --timeout=180s -n "${WANAKU_NAMESPACE}" > /dev/null \
    && echo "PASS [11.6]: deployment ${DEP} available" || echo "FAIL [11.6]: deployment ${DEP} not available"
done

PORTS=$(oc get service "server-${R}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[*].port}')
for PORT in 8080 8081; do
  echo " ${PORTS} " | grep -q " ${PORT} " && echo "PASS [11.6]: server-${R} exposes ${PORT}" \
    || echo "FAIL [11.6]: server-${R} ports are '${PORTS}'"
done

for ROUTE in "${R}" "${R}-mcp"; do
  oc get route "${ROUTE}" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
    && echo "PASS [11.6]: route ${ROUTE} exists" || echo "FAIL [11.6]: route ${ROUTE} missing"
done

oc get deployment "${R}-barn" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
  && echo "FAIL [11.6]: Barn deployed although spec.barn is not enabled" || echo "PASS [11.6]: Barn not deployed"

REASON=$(oc get wanakurouter "${R}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="AuthConfigured")].reason}')
[ "${REASON}" = "Configured" ] && echo "PASS [11.6]: AuthConfigured=Configured" \
  || echo "FAIL [11.6]: AuthConfigured reason is '${REASON}'"
```

**Expected result:**

- The output includes `PASS [11.6]: deployment <R>-server available`.
- The output includes `PASS [11.6]: deployment <R>-oauth2-proxy available`.
- The output includes `PASS [11.6]: server-<R> exposes 8080`.
- The output includes `PASS [11.6]: server-<R> exposes 8081`.
- The output includes `PASS [11.6]: route <R> exists`.
- The output includes `PASS [11.6]: route <R>-mcp exists`.
- The output includes `PASS [11.6]: Barn not deployed`.
- The output includes `PASS [11.6]: AuthConfigured=Configured`.
- The output contains no line starting with `FAIL`.

### Step 11.7: Set the URL variables

```bash run
export WANAKU_SERVER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_MCP_BASE_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
# common/oidc-login-verification.md uses WANAKU_ROUTER_URL
export WANAKU_ROUTER_URL="${WANAKU_SERVER_URL}"
export MCP_URI="${WANAKU_MCP_BASE_URL}/default/mcp"

STATUS_MCP=$(oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.endpoints.mcp}')
[ "${STATUS_MCP}" = "${WANAKU_MCP_BASE_URL}/mcp" ] && echo "PASS [11.7]: status.endpoints.mcp is ${STATUS_MCP}" \
  || echo "FAIL [11.7]: status.endpoints.mcp is '${STATUS_MCP}', expected ${WANAKU_MCP_BASE_URL}/mcp"
```

**Expected result:**

- The output includes `PASS [11.7]: status.endpoints.mcp is <STATUS_MCP>`.
- The output contains no line starting with `FAIL`.

### Step 11.8: Wait for health

`/health` does not require a token. `curl` is used to test HTTP-level readiness. Add `-k` to the `curl` commands of
this part when `WANAKU_TLS_FLAG` is `--insecure`.

Run this block as a separate `bash` script, not in the shared session.

```bash run
for i in $(seq 1 30); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health" 2>/dev/null || echo "000")
  [ "${HTTP_CODE}" = "200" ] && { echo "PASS [11.8]: Wanaku Server healthy (attempt ${i})"; break; }
  [ "${i}" -eq 30 ] && { echo "FAIL [11.8]: health returned HTTP ${HTTP_CODE}"; exit 1; }
  sleep 5
done
```

**Expected result:**

- The output includes `PASS [11.8]: Wanaku Server healthy`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.9: Verify that the proxies require a token

```bash run
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/api/v1/tools")
[ "${HTTP_CODE}" = "401" ] && echo "PASS [11.9]: management API returns 401 without a token" \
  || echo "FAIL [11.9]: management API returned HTTP ${HTTP_CODE}"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${MCP_URI}" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
[ "${HTTP_CODE}" = "401" ] && echo "PASS [11.9]: MCP endpoint returns 401 without a token" \
  || echo "FAIL [11.9]: MCP endpoint returned HTTP ${HTTP_CODE}"

# The OAuth protected-resource metadata is public. The operator sets WANAKU_AUTH_ISSUER.
META=$(curl -s "${WANAKU_MCP_BASE_URL}/.well-known/oauth-protected-resource/default/mcp")
echo "${META}" | grep -q "${KEYCLOAK_URL}/realms/wanaku" \
  && echo "PASS [11.9]: protected-resource metadata names the issuer" || echo "FAIL [11.9]: metadata: ${META}"
```

**Expected result:**

- The output includes `PASS [11.9]: management API returns 401 without a token`.
- The output includes `PASS [11.9]: MCP endpoint returns 401 without a token`.
- The output includes `PASS [11.9]: protected-resource metadata names the issuer`.
- The output contains no line starting with `FAIL`.

### Step 11.10: Log in

Follow [common/oidc-login-verification.md](common/oidc-login-verification.md).

Run its login and fallback blocks in the shared shell, overriding its separate-script instructions, so
`LOGIN_FAILED`, any updated `WANAKU_CLIENT_SECRET` and the selected `wanaku` function remain available.

Then read the access token for the
`wanaku mcp` commands. These commands do not read the stored credentials, so they need `--token`.

Run this block in the shared shell so the selected `wanaku` invocation is available and `MCP_TOKEN` survives
for the later MCP commands. Do not print the token.

```bash run
MCP_TOKEN=$(wanaku auth token --get --unmask 2>/dev/null)
[ -n "${MCP_TOKEN}" ] && echo "PASS [11.10]: access token available" || { echo "FAIL [11.10]: no access token"; exit 1; }
```

**Expected result:**

- The output includes `PASS [11.10]: access token available`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

---

## Phase 12: Register an MCP Server as a Forward

### Step 12.1: Deploy the infra-remediation MCP fixture

Run this block as a separate `bash` script, not in the shared session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-mcp
spec:
  replicas: 1
  selector:
    matchLabels:
      app: test-mcp
  template:
    metadata:
      labels:
        app: test-mcp
    spec:
      containers:
        - name: test-mcp-server
          image: ${TEST_MCP_IMAGE}
          env:
            - name: QUARKUS_HTTP_PORT
              value: "8181"
            - name: QUARKUS_HTTP_HOST
              value: "0.0.0.0"
          ports:
            - containerPort: 8181
          readinessProbe:
            tcpSocket:
              port: 8181
            periodSeconds: 5
            failureThreshold: 60
---
apiVersion: v1
kind: Service
metadata:
  name: test-mcp
spec:
  selector:
    app: test-mcp
  ports:
    - port: 8181
      targetPort: 8181
EOF
oc rollout status deployment/test-mcp --timeout=300s -n "${WANAKU_NAMESPACE}" \
  && echo "PASS [12.1]: infra-remediation MCP fixture running" || { echo "FAIL [12.1]: infra-remediation MCP fixture not ready"; exit 1; }
```

**Expected result:**

- The output includes `PASS [12.1]: infra-remediation MCP fixture running`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 12.2: Register the forward

The CLI uses the stored token from step 11.10. Do not pass `--no-auth`.

```bash run
wanaku forwards add --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} \
  --name infra-remediation --service "http://test-mcp:8181/mcp" --namespace default --plain
[ $? -eq 0 ] && echo "PASS [12.2]: forward 'infra-remediation' registered" || echo "FAIL [12.2]: forward not registered"
```

**Expected result:**

- The output includes `PASS [12.2]: forward 'infra-remediation' registered`.
- The output contains no line starting with `FAIL`.

### Step 12.3: Verify the discovery

```bash run
OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain 2>&1)
echo "${OUTPUT}" | grep -q "^restartService" && echo "PASS [12.3]: tool 'restartService' discovered" \
  || echo "FAIL [12.3]: 'restartService' not discovered: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [12.3]: tool 'restartService' discovered`.
- The output contains no line starting with `FAIL`.

### Step 12.4: Create a second namespace

```bash run
wanaku namespaces create "${MCP_E2E_NAMESPACE}" --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain > /dev/null \
  && echo "PASS [12.4]: namespace ${MCP_E2E_NAMESPACE} created" || echo "FAIL [12.4]: namespace not created"
```

**Expected result:**

- The output includes `PASS [12.4]: namespace <MCP_E2E_NAMESPACE> created`.
- The output contains no line starting with `FAIL`.

---

## Phase 13: MCP CLI Through the MCP Route

### Test 13.1: List tools, resources and prompts with a token

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^restartService " && echo "PASS [13.1]: restartService listed through the Route" || echo "FAIL [13.1]: ${OUTPUT}"

OUTPUT=$(wanaku mcp resource list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^safetyLimits " && echo "PASS [13.1]: safetyLimits listed" || echo "FAIL [13.1]: ${OUTPUT}"

OUTPUT=$(wanaku mcp prompt list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^summarizeIncident " && echo "PASS [13.1]: summarizeIncident listed" || echo "FAIL [13.1]: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.1]: restartService listed through the Route`.
- The output includes `PASS [13.1]: safetyLimits listed`.
- The output includes `PASS [13.1]: summarizeIncident listed`.
- The output contains no line starting with `FAIL`.

### Test 13.2: The MCP Route rejects a request without a token

```bash run
wanaku mcp tool list --uri "${MCP_URI}" ${WANAKU_TLS_FLAG} --plain > /dev/null 2>&1
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "PASS [13.2]: request without token rejected (exit ${EXIT_CODE})" \
  || echo "FAIL [13.2]: request without token succeeded"
```

**Expected result:**

- The output includes `PASS [13.2]: request without token rejected`.
- The output contains no line starting with `FAIL`.

### Test 13.3: Namespace isolation through the Route

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/${MCP_E2E_NAMESPACE}/mcp" \
  --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^No tools found" && echo "PASS [13.3]: ${MCP_E2E_NAMESPACE} has no tools" \
  || echo "FAIL [13.3]: tools leaked: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.3]: <MCP_E2E_NAMESPACE> has no tools`.
- The output contains no line starting with `FAIL`.

### Test 13.4: The bare `/mcp` path is rejected

The MCP Route sends all paths to the MCP proxy. Wanaku Server rejects `/mcp` without a namespace.

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/mcp" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>&1)
echo "${OUTPUT}" | grep -q "invalid MCP endpoint path" && echo "PASS [13.4]: bare /mcp rejected" \
  || echo "FAIL [13.4]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.4]: bare /mcp rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 14: Governed Calls on OpenShift

### Test 14.1: Calls get the fail-safe denial

The operator starts Wanaku Server without a governance configuration. The default posture denies `tools/call`,
`resources/read` and `prompts/get`.

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -Eq "No governance policy permits this action.|evaluator: no_match"; then
  echo "PASS [14.1]: tools/call denied by the fail-safe default"
else
  echo "FAIL [14.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi

OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --resource-uri config://policies/safety-limits --plain 2>&1)
echo "${OUTPUT}" | grep -Eq "No governance policy permits this action.|evaluator: no_match" \
  && echo "PASS [14.1]: resources/read denied" || echo "FAIL [14.1]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [14.1]: tools/call denied by the fail-safe default`.
- The output includes `PASS [14.1]: resources/read denied`.
- The output contains no line starting with `FAIL`.

### Test 14.2: Calls succeed when governance allows them (BLOCKED)

This test is blocked. The `WanakuRouter` resource has no field for the Wanaku Server governance configuration, and
the operator owns the generated `wanaku.yaml`. Run the test only when the deployment allows calls in the `default`
namespace. Set `WANAKU_E2E_GOVERNANCE_ALLOWS_DEFAULT=true` in that case.

```bash run
if [ "${WANAKU_E2E_GOVERNANCE_ALLOWS_DEFAULT:-false}" = "true" ]; then
  OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
    --name restartService --param serverId=test-server --param service=nginx --plain 2>/dev/null)
  [ "${OUTPUT}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ] && echo "PASS [14.2]: restartService called through the Route" || echo "FAIL [14.2]: '${OUTPUT}'"
  OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
    --name draftEscalation --arg urgency=high --arg reason=memory-pressure --plain 2>/dev/null)
  echo "${OUTPUT}" | grep -q "urgency=high" && echo "PASS [14.2]: draftEscalation rendered" || echo "FAIL [14.2]: '${OUTPUT}'"
else
  echo "SKIP [14.2]: governance configuration is not available through the operator"
fi
```

**Expected result:**

- Either `PASS [14.2]: restartService called through the Route` and `PASS [14.2]: draftEscalation rendered` appear, or `SKIP [14.2]: governance configuration is not available through the operator` appears.
- The output contains no line starting with `FAIL`.

---

## Phase 15: Cleanup (Part 2)

### Step 15.1: Remove the test data and workloads

```bash run
wanaku forwards remove --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --name infra-remediation --plain > /dev/null 2>&1 || true
wanaku namespaces delete "${MCP_E2E_NAMESPACE}" --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain > /dev/null 2>&1 || true
oc delete deployment test-mcp -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete service test-mcp -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-server" "${WANAKU_NAMESPACE}" 120 || true
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-oauth2-proxy" "${WANAKU_NAMESPACE}" 120 || true
oc delete secret "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
wanaku auth logout > /dev/null 2>&1 || true
rm -rf "${WANAKU_CREDENTIALS}" 2>/dev/null || true
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 15.2: Full cleanup

Clean up Keycloak, the operator and the namespace.

Follow [common/cleanup.md](common/cleanup.md).

**Expected result:**

- All cleanup procedure steps satisfy their expected results.

---

## Full Test Summary Matrix

| Phase | Test ID | Test Name | Priority | Part |
|-------|---------|-----------|----------|------|
| S | S.1-S.2 | CLI invocation and server mode | High | 1 |
| T | S.3-S.4 | OpenShift environment and deletion helper | High | 2 |
| 10 | 10.1 | OpenShift login (MANUAL) | Critical | 2 |
| 0 | 0.1 | Prerequisites | High | 1 |
| 1 | 1.1-1.4 | Server with test governance, fixture forward, namespaces | Critical | 1 |
| 2 | 2.1-2.2 | Help and usage output | Medium | 1 |
| 3 | 3.1 | Required option validation | High | 1 |
| 4 | 4.1-4.6 | Tool operations | Critical | 1 |
| 5 | 5.1-5.3 | Resource operations | Critical | 1 |
| 6 | 6.1-6.5 | Prompt operations | Critical | 1 |
| 7 | 7.1-7.5 | Namespace isolation, governance, disable/enable | Critical | 1 |
| 8 | 8.1-8.4 | Endpoint and connection errors | High | 1 |
| 9 | 9.1 | Cleanup | Critical | 1 |
| 10 | — | OpenShift login (MANUAL) | Critical | 2 |
| 11 | 11.1-11.10 | Operator, Keycloak, WanakuRouter with oauth2-proxy, Routes, 401 checks, login | Critical | 2 |
| 12 | 12.1-12.4 | In-cluster MCP server registered as a forward | Critical | 2 |
| 13 | 13.1-13.4 | MCP list through the Route, token required, isolation, bare `/mcp` | Critical | 2 |
| 14 | 14.1-14.2 | Fail-safe denial; governed success path (BLOCKED) | High | 2 |
| 15 | 15.1-15.2 | Cleanup | Critical | 2 |
