# Test Plan: Wanaku Server Admin UI (Core Pages)

## Overview

This test plan runs the Playwright end-to-end suite of the Wanaku Server admin UI. The admin UI is embedded in
Wanaku Server and is served at `${WANAKU_SERVER_URL}/admin/`. The suite and the UI source are in the
[wanaku-ai/wanaku](https://github.com/wanaku-ai/wanaku) repository, not in this repository:

| Item | Location in the `wanaku-ai/wanaku` checkout |
|------|---------------------------------------------|
| Admin UI source | `ui/admin` |
| Playwright suite | `tests/e2e/ui` (`playwright.config.ts`, `pages/`, `helpers/`, `tests/`) |
| Configuration that the suite expects | `wanaku.yaml` at the repository root |

The suite covers the core pages: Dashboard, Tools, Resources, Prompts, Namespaces, Forwards, A2A Agents, Credential
Bindings, Evaluators, Action Policies, Policy Simulation, Audit, Plugin Catalog and CLI Downloads. It also verifies
that pages which moved to plugins (Tool Call Debugger, LLM Chat) are not in the core navigation.

The pages that come from Wanaku Barn (Data Stores, Service Catalog, Kamelets, Semantic Routers, Change History) and
from the Deployment Debugger plugin are tested in [ui-extended-pages.md](ui-extended-pages.md).

**Components:** Wanaku Server only. Wanaku Barn is not necessary.

Keep these differences from the Java router UI in mind:

- Tools, Resources and Prompts do not have Add, Edit or Delete buttons. Each row has an Enable/Disable toggle. The
  entries come from forwards. The specs mock the registry list for the toggle tests (`helpers/registry-mock.ts`), so
  no upstream MCP server is necessary.
- Namespaces have only a name (a DNS label). There are no paths, no `public` namespace and no `ns-N` slots.
- The suite changes server state. It creates and deletes namespaces, forwards and A2A agents, and it replaces the
  evaluator configuration. **Run it only against a dedicated Wanaku Server instance.** Never run it against a shared
  instance.

Every step is automatable.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `node` | 18+ | `node --version` |
| `yarn` | 1.22+ | `yarn --version` |
| `npx` | any | `npx --version` |
| `cargo` | 1.96+ | `cargo --version` |
| `cargo-component` | 0.21.1 | `cargo component --version` |
| `java` | 21+ | `java -version` |
| `curl` | any | `curl --version` |
| `jq` | 1.6+ | `jq --version` |

`cargo-component` and the `wasm32-wasip1` target build the evaluator action that the repository `wanaku.yaml`
loads at startup. Install them with:

Playwright downloads Chromium from `cdn.playwright.dev`. The host must be able to reach it.

### Prerequisite check

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `WANAKU_SERVER_REPO_ROOT` | `../wanaku` | Path to the `wanaku-ai/wanaku` checkout |
| `WANAKU_E2E_DIR` | `${WANAKU_SERVER_REPO_ROOT}/tests/e2e/ui` | Directory of the Playwright suite |
| `CLI_JAR` | `apps/wanaku-cli/target/quarkus-app/quarkus-run.jar` | Wanaku CLI jar (used for the residue check) |

[common/start-server.md](common/start-server.md) sets `WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL`,
`WANAKU_SERVER_PID` and `WANAKU_SERVER_DATA_DIR`. The suite reads the server URL from `WANAKU_ROUTER_URL`. Phase 4
sets it from `WANAKU_SERVER_URL`.

---

## Phase S: Setup

### Step S.1: Install evaluator build tools

```bash run
rustup target add wasm32-wasip1
cargo install cargo-component --version 0.21.1 --locked
```

**Expected result:**

- The commands exit with code 0 (inferred).


### Step S.2: Prepare prerequisites

Run this block as a separate `bash` script, not in the shared session.

```bash
for TOOL in node yarn npx cargo java curl jq; do
  command -v "${TOOL}" > /dev/null 2>&1 \
    && echo "PASS: ${TOOL} found" \
    || { echo "FAIL: ${TOOL} not found"; exit 1; }
done
cargo component --version > /dev/null 2>&1 \
  && echo "PASS: cargo-component found" \
  || { echo "FAIL: cargo-component not found"; exit 1; }
```

**Expected result:**

- The output includes `PASS: node found`.
- The output includes `PASS: yarn found`.
- The output includes `PASS: npx found`.
- The output includes `PASS: cargo found`.
- The output includes `PASS: java found`.
- The output includes `PASS: curl found`.
- The output includes `PASS: jq found`.
- The output includes `PASS: cargo-component found`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step S.3: Set environment variables

```bash run
export WANAKU_SERVER_REPO_ROOT="${WANAKU_SERVER_REPO_ROOT:-../wanaku}"
export WANAKU_E2E_DIR="${WANAKU_E2E_DIR:-${WANAKU_SERVER_REPO_ROOT}/tests/e2e/ui}"
export CLI_JAR="${CLI_JAR:-apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
```

**Expected result:**

- The commands exit with code 0 (inferred).


## Phase 0: Manual Prerequisites

### Step 0.1: Confirm prerequisites

There are no manual prerequisites. The plan starts its own Wanaku Server.

**Expected result:**

- There are no manual prerequisites.


---

## Phase 1: Environment Setup

### Step 1.1: Build the evaluator action

The repository `wanaku.yaml` configures the `safety-gate` evaluator with a WASM processor. Wanaku Server does not
start if the WASM file is missing (`fatal: evaluator startup failed: configuration_invalid`). This step follows the
`build-evaluator-actions` CI action of the `wanaku-ai/wanaku` repository.

Run this block as a separate `bash` script, not in the shared session.

```bash
mkdir -p "${WANAKU_SERVER_REPO_ROOT}/actions/dist"
(cd "${WANAKU_SERVER_REPO_ROOT}/actions/safety-review" && cargo component build --release --locked) \
  && cp "${WANAKU_SERVER_REPO_ROOT}/actions/safety-review/target/wasm32-wasip1/release/safety_review_action.wasm" \
       "${WANAKU_SERVER_REPO_ROOT}/actions/dist/"
if [ -f "${WANAKU_SERVER_REPO_ROOT}/actions/dist/safety_review_action.wasm" ]; then
  echo "PASS: evaluator action built"
else
  echo "FAIL: evaluator action not built"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: evaluator action built`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step 1.2: Prepare the server configuration

The Evaluators spec expects the LLM connections from the repository `wanaku.yaml` (for example `local-llama`). The
processor path in that file is relative to the current directory. Write a copy with an absolute path, so the server
can start from any directory.

Run this block as a separate `bash` script, not in the shared session.

```bash
export WANAKU_SERVER_DATA_DIR="${WANAKU_SERVER_DATA_DIR:-$(mktemp -d)}"
WANAKU_ACTIONS_DIR="$(cd "${WANAKU_SERVER_REPO_ROOT}/actions/dist" && pwd)"
export WANAKU_SERVER_CONFIG="${WANAKU_SERVER_DATA_DIR}/wanaku-e2e.yaml"
sed "s#path: \"actions/dist/#path: \"${WANAKU_ACTIONS_DIR}/#" \
  "${WANAKU_SERVER_REPO_ROOT}/wanaku.yaml" > "${WANAKU_SERVER_CONFIG}"
if grep -q "path: \"${WANAKU_ACTIONS_DIR}/safety_review_action.wasm\"" "${WANAKU_SERVER_CONFIG}"; then
  echo "PASS: server configuration written to ${WANAKU_SERVER_CONFIG}"
else
  echo "FAIL: processor path not rewritten"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: server configuration written to <WANAKU_SERVER_CONFIG>`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step 1.3: Start Wanaku Server

Use [common/start-server.md](common/start-server.md) steps 1 to 6. Do **not** run step 2b: the configuration of
step 1.2 already contains a `governance` section, and the suite does not call tools. Step 3 passes
`WANAKU_SERVER_CONFIG` with `--wanaku-config`.

Step 1 must produce a binary with the embedded admin UI. `cargo build --release -p wanaku-server` embeds the UI by
default (cargo feature `ui`, which runs `yarn build` in `ui/admin`). `make build` gives the same result. A build with
`--no-default-features` is headless and fails Test 2.2.

After completion, `WANAKU_SERVER_URL`, `WANAKU_SERVER_PID` and `WANAKU_SERVER_DATA_DIR` must be set.

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


---

## Phase 2: Verify Server Connectivity

`curl` is used in this phase to test HTTP-level reachability. The admin UI has no CLI equivalent.

### Test 2.1: Server health check

Run this block as a separate `bash` script, not in the shared session.

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health")
if [ "${HTTP_STATUS}" = "200" ]; then
  echo "PASS: Wanaku Server healthy at ${WANAKU_SERVER_URL}"
else
  echo "FAIL: Wanaku Server not reachable (HTTP ${HTTP_STATUS})"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: Wanaku Server healthy at <WANAKU_SERVER_URL>`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Test 2.2: Admin UI is embedded and served at /admin/

Run this block as a separate `bash` script, not in the shared session.

```bash
BODY=$(curl -s "${WANAKU_SERVER_URL}/admin/")
if echo "${BODY}" | grep -q "<title>Wanaku Console</title>"; then
  echo "PASS: admin UI served at ${WANAKU_SERVER_URL}/admin/"
else
  echo "FAIL: admin UI not served — the binary is probably a headless build"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: admin UI served at <WANAKU_SERVER_URL>/admin/`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Test 2.3: Root path redirects to the admin UI

```bash run
LOCATION=$(curl -s -D - -o /dev/null "${WANAKU_SERVER_URL}/" | tr -d '\r' | awk 'tolower($1)=="location:" {print $2}')
if [ "${LOCATION}" = "/admin/" ]; then
  echo "PASS: / redirects to /admin/"
else
  echo "FAIL: unexpected redirect target '${LOCATION}'"
fi
```

**Expected result:**

- The output includes `PASS: / redirects to /admin/`.
- The output contains no line starting with `FAIL:`.


### Test 2.4: Missing UI asset returns 404 (negative)

Unknown hash routes (for example `#/tool-calls`) are handled by the single-page application. Unknown static files
must return `404`.

```bash run
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/admin/assets/does-not-exist.js")
if [ "${HTTP_STATUS}" = "404" ]; then
  echo "PASS: missing asset returns 404"
else
  echo "FAIL: missing asset returned HTTP ${HTTP_STATUS}"
fi
```

**Expected result:**

- The output includes `PASS: missing asset returns 404`.
- The output contains no line starting with `FAIL:`.


### Test 2.5: Server loaded the e2e configuration

Run this block as a separate `bash` script, not in the shared session.

```bash
CONNECTIONS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/evaluators/llm-connections" | jq -r '.data[]')
if echo "${CONNECTIONS}" | grep -qx "local-llama"; then
  echo "PASS: LLM connection 'local-llama' is configured"
else
  echo "FAIL: LLM connection 'local-llama' not found (got: ${CONNECTIONS})"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: LLM connection 'local-llama' is configured`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 3: Install Dependencies

### Step 3.1: Install Node.js dependencies

Run this block as a separate `bash` script, not in the shared session.

```bash
(cd "${WANAKU_E2E_DIR}" && yarn install --frozen-lockfile)
if [ $? -eq 0 ]; then
  echo "PASS: dependencies installed"
else
  echo "FAIL: yarn install failed"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: dependencies installed`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step 3.2: Install the Playwright browser

Add `--with-deps` on a fresh Linux host to install the system libraries of Chromium.

Run this block as a separate `bash` script, not in the shared session.

```bash
(cd "${WANAKU_E2E_DIR}" && npx playwright install chromium)
if [ $? -eq 0 ]; then
  echo "PASS: Chromium installed"
else
  echo "FAIL: Playwright browser install failed"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: Chromium installed`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Test 3.3: The suite is discovered

`--list` does not need a browser or a server. The suite has 108 tests in 16 files at the time of writing. Use the
count as a sanity check, not as a fixed value.

Run this block as a separate `bash` script, not in the shared session.

```bash
LIST=$(cd "${WANAKU_E2E_DIR}" && WANAKU_ROUTER_URL="${WANAKU_SERVER_URL}" npx playwright test --list 2>&1)
TOTAL=$(echo "${LIST}" | awk '/^Total:/ {print $2}')
if [ -n "${TOTAL}" ] && [ "${TOTAL}" -gt 0 ]; then
  echo "PASS: ${TOTAL} tests discovered"
else
  echo "FAIL: no tests discovered"
  echo "${LIST}" | tail -5
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: <TOTAL> tests discovered`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 4: Run the Test Suite

The Playwright configuration has a `webServer` entry that runs `cargo run` from the repository root. It sets
`reuseExistingServer: true`, so Playwright uses the server of Phase 1 when `WANAKU_ROUTER_URL` answers. Phase 2 makes
sure that it answers. Otherwise Playwright would start a second server on the default ports.

### Test 4.1: Core registry pages

These specs cover the Tools, Resources and Prompts toggles, the Dashboard tiles (Tools, Resources, Prompts,
Forwards), Namespaces (create, invalid name, delete, MCP address column) and Forwards (add, address validation,
delete, details, status).

```bash run
(cd "${WANAKU_E2E_DIR}" && WANAKU_ROUTER_URL="${WANAKU_SERVER_URL}" npx playwright test \
  tests/dashboard.spec.ts tests/tools.spec.ts tests/resources.spec.ts tests/prompts.spec.ts \
  tests/namespaces.spec.ts tests/forwards.spec.ts --reporter=line)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: core registry page tests passed"
else
  echo "FAIL: core registry page tests failed (exit code ${EXIT_CODE})"
fi
```

**Expected result:**

- The output includes `PASS: core registry page tests passed`.
- The output contains no line starting with `FAIL:`.


### Test 4.2: Complete suite

```bash run
(cd "${WANAKU_E2E_DIR}" && WANAKU_ROUTER_URL="${WANAKU_SERVER_URL}" npx playwright test --reporter=line)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: all Playwright tests passed"
else
  echo "FAIL: Playwright tests failed (exit code ${EXIT_CODE})"
  echo "Run 'npx playwright show-report' in ${WANAKU_E2E_DIR} for details"
fi
```

**Expected result:**

- The output includes `PASS: all Playwright tests passed`.
- The output contains no line starting with `FAIL:`.


### Test 4.3: Headed mode for visual verification (optional)

```bash
(cd "${WANAKU_E2E_DIR}" && WANAKU_ROUTER_URL="${WANAKU_SERVER_URL}" npx playwright test --headed tests/tools.spec.ts)
```

**Expected result:**

- The headed Playwright command exits with code 0 (inferred).


---

## Phase 5: Verify Results

### Test 5.1: Test report was generated

```bash run
if [ -f "${WANAKU_E2E_DIR}/playwright-report/index.html" ]; then
  echo "PASS: test report generated"
else
  echo "FAIL: no test report found"
fi
```

**Expected result:**

- The output includes `PASS: test report generated`.
- The output contains no line starting with `FAIL:`.


### Test 5.2: The suite removed its namespaces and forwards

The specs create entries with the `e2e-` prefix and delete them in `afterEach`. A leftover entry shows a cleanup
defect in the suite.

```bash run
LEFTOVER_NS=$(java -jar "${CLI_JAR}" namespaces list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
  | awk 'NR>1 {print $1}' | grep -c '^e2e-')
LEFTOVER_FW=$(java -jar "${CLI_JAR}" forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
  | awk 'NR>1 {print $1}' | grep -c '^e2e-')
if [ "${LEFTOVER_NS}" -eq 0 ] && [ "${LEFTOVER_FW}" -eq 0 ]; then
  echo "PASS: no e2e- namespaces or forwards remain"
else
  echo "FAIL: ${LEFTOVER_NS} e2e- namespace(s) and ${LEFTOVER_FW} e2e- forward(s) remain"
fi
```

**Expected result:**

- The output includes `PASS: no e2e- namespaces or forwards remain`.
- The output contains no line starting with `FAIL:`.


### Test 5.3: View the HTML report (optional)

```bash
(cd "${WANAKU_E2E_DIR}" && npx playwright show-report)
```

**Expected result:**

- The HTML report viewer starts (inferred).


---

## Phase 6: Cleanup

All cleanup steps are idempotent.

### Step 6.1: Stop Wanaku Server

Use the [Shutdown](common/start-server.md#shutdown) section of [common/start-server.md](common/start-server.md).
It stops the server and removes `WANAKU_SERVER_DATA_DIR`, which also contains `wanaku-e2e.yaml`.

Stop the server with the PID captured at startup. Then remove the data directory of the run.

The entry plan runs the original shutdown block in its local cleanup unit.

Do **not** use `kill -9`. It bypasses the orderly Pingora shutdown and the final registry snapshot. If Pingora worker
processes remain, find them with `lsof -ti :8081` and stop them with `SIGTERM`.

**Expected result:**

- The server is stopped and its data directory is removed (inferred).


### Step 6.2: Remove the test artifacts (optional)

```bash
rm -rf "${WANAKU_E2E_DIR}/playwright-report" "${WANAKU_E2E_DIR}/test-results" 2>/dev/null || true
echo "PASS: Playwright artifacts removed"
```

**Expected result:**

- The output includes `PASS: Playwright artifacts removed`.


---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| S | S.1 | Install evaluator build tools | High |
| S | S.2 | Prepare prerequisites | High |
| S | S.3 | Set environment variables | High |
| 0 | 0.1 | Confirm prerequisites | High |
| 1 | 1.1 | Build the evaluator action | Critical |
| 1 | 1.2 | Prepare the server configuration | Critical |
| 1 | 1.3 | Start Wanaku Server | Critical |
| 2 | 2.1 | Server health check | Critical |
| 2 | 2.2 | Admin UI is embedded and served | Critical |
| 2 | 2.3 | Root path redirects to the admin UI | Medium |
| 2 | 2.4 | Missing UI asset returns 404 (negative) | Medium |
| 2 | 2.5 | Server loaded the e2e configuration | High |
| 3 | 3.1 | Install Node.js dependencies | Critical |
| 3 | 3.2 | Install Playwright browser | Critical |
| 3 | 3.3 | The suite is discovered | High |
| 4 | 4.1 | Core registry pages | Critical |
| 4 | 4.2 | Complete suite | Critical |
| 4 | 4.3 | Headed mode visual verification | Low |
| 5 | 5.1 | Test report generated | Medium |
| 5 | 5.2 | The suite removed its namespaces and forwards | High |
| 5 | 5.3 | View HTML report | Low |
| 6 | 6.1 | Stop Wanaku Server | Critical |
| 6 | 6.2 | Remove the test artifacts | Low |

### Covered UI scenarios (via Playwright)

| Page | Spec | Scenario | Priority |
|------|------|----------|----------|
| Dashboard | `dashboard.spec.ts` | Title and description, statistics tiles (Tools, Resources, Prompts, Forwards), refresh | High |
| Tools | `tools.spec.ts` | Title, disable and enable a tool with the toggle | Critical |
| Resources | `resources.spec.ts` | Title, disable and enable a resource with the toggle | Critical |
| Prompts | `prompts.spec.ts` | Title, disable and enable a prompt with the toggle | Critical |
| Namespaces | `namespaces.spec.ts` | Title, columns, `default` present, create, invalid name rejected, delete, MCP address | Critical |
| Forwards | `forwards.spec.ts` | Title, add, URL validation, delete, details, server info, status, credential bindings | Critical |
| A2A Agents | `a2a-agents.spec.ts` | Add, edit, card URL handling, validation, delete, invalid upstream rejected | High |
| Credential Bindings | `bindings.spec.ts` | List, read-only details, empty state | Medium |
| Evaluators | `evaluators.spec.ts` | Title, LLM connections, delete, TypeSafe and OPA engines | High |
| Action Policies | `action-policies.spec.ts` | Active policy and revision history | High |
| Policy Simulation | `policy-simulation.spec.ts` | Validation, simulation, replay, YAML candidates | High |
| Audit | `audit.spec.ts` | Event list and details, filters, degraded health, expired event | High |
| Plugin Catalog | `plugin-catalog.spec.ts` | Catalog, install, service targets, errors | Medium |
| CLI Downloads | `downloads.spec.ts` | Title, Developer menu entry, package links | Low |
| Navigation | `llm-chat.spec.ts`, `tool-calls.spec.ts` | LLM Chat and Tool Call Debugger are not in the core UI | Medium |
