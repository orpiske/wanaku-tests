# Test Plan: Barn and Debugger UI Plugins

## Overview

This test plan covers the admin UI pages that do not belong to the Wanaku Server core UI. These pages come from two
UI plugins that this repository builds:

| Plugin | Plugin ID | Source | Pages (host route) | Backend |
|--------|-----------|--------|--------------------|---------|
| Wanaku Barn | `wanaku-barn` | `apps/ui/admin` | Data Stores (`#/wanaku/data-stores`), Service Catalog (`#/wanaku/service-catalog`), Semantic Routers (`#/wanaku/semantic-routers`), Kamelets (`#/wanaku/kamelets`), Change History (`#/wanaku/change-history`) | Barn, through the plugin service `wanaku-barn-api` |
| Deployment Debugger | `wanaku-debugger` | `apps/ui/debugger` | Debugger (`#/wanaku/debugger`, in the Developer section) with the tabs MCP, A2A, Inference and LLM Chat | None. It calls the MCP (`8081`), inference (`8083`) and A2A (`8084`) listeners and the management API directly. |

The core pages of Wanaku Server (Dashboard, Tools, Resources, Prompts, Namespaces, Forwards and others) are tested in
[basic-ui-test.md](basic-ui-test.md). The Java router pages "Tool Call Debugger", "LLM Chat" and "Code Execution"
were removed. The Deployment Debugger plugin replaces the debugger and the chat page. Namespace editing with paths
and the `public` namespace do not exist any more.

The plan runs four test suites from this repository and one integration phase:

| Phase | Suite | Target |
|-------|-------|--------|
| 2 | Unit tests of both plugins (`node --test`) | No server |
| 3 | Playwright, `tests/e2e/ui/playwright.config.ts` | Barn standalone admin UI at `${WANAKU_BARN_URL}/admin/` |
| 4 | Playwright, `tests/e2e/debugger/playwright.config.ts` | Debugger plugin in a stub host on `127.0.0.1:4177` |
| 5 | Playwright, `tests/e2e/ui/playwright.live.config.ts` (optional) | Barn admin UI dev server and a live Barn |
| 6 | HTTP checks and browser steps | Both plugins installed in Wanaku Server |

Most Playwright specs mock the Barn API with `page.route`. Only `smoke.spec.ts` and the optional live spec use a real
Barn. Phase 6 is the only phase that tests the plugins inside the Wanaku Server host. No automated suite covers that
integration yet, so Phase 6 uses HTTP checks and browser steps.

**Components:** Wanaku Barn (Phases 3, 5 and 6) and Wanaku Server (Phase 6).

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `node` | 22+ (the debugger unit tests use `--experimental-strip-types`) | `node --version` |
| `yarn` | 1.22+ | `yarn --version` |
| `npx` | any | `npx --version` |
| `java` | 21+ | `java -version` |
| `curl` | any | `curl --version` |
| `jq` | 1.6+ | `jq --version` |

Playwright downloads Chromium from `cdn.playwright.dev`. The host must be able to reach it.

### Prerequisite check

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `WANAKU_REPO_ROOT` | `.` | Path to this repository (wanaku-barn) |
| `GROUP_PREFIX` | `uix-` | Prefix for the data store that Phase 6 creates |
| `BARN_LIVE_TESTS` | `false` | Set to `true` to run Phase 5. Phase 5 needs a Barn with the native deterministic preview service. |

[common/start-barn.md](common/start-barn.md) sets `WANAKU_BARN_URL`, `CLI_JAR` and `WANAKU_BARN_PID`.
[common/start-server.md](common/start-server.md) sets `WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL`,
`WANAKU_SERVER_PID` and `WANAKU_SERVER_DATA_DIR`. The Playwright suites read the Barn URL from `WANAKU_ROUTER_URL`.
The name is historical. It must point at Barn, not at Wanaku Server.

---

## Phase S: Setup

### Step S.1: Prepare prerequisites

Run this block as a separate `bash` script, not in the shared session.

```bash
for TOOL in node yarn npx java curl jq; do
  command -v "${TOOL}" > /dev/null 2>&1 \
    && echo "PASS: ${TOOL} found" \
    || { echo "FAIL: ${TOOL} not found"; exit 1; }
done
NODE_MAJOR=$(node --version | sed 's/^v\([0-9]*\).*/\1/')
[ "${NODE_MAJOR}" -ge 22 ] && echo "PASS: Node.js ${NODE_MAJOR}" || echo "FAIL: Node.js 22+ required (found ${NODE_MAJOR})"
```

**Expected result:**

- The output includes `PASS: node found`.
- The output includes `PASS: yarn found`.
- The output includes `PASS: npx found`.
- The output includes `PASS: java found`.
- The output includes `PASS: curl found`.
- The output includes `PASS: jq found`.
- The output includes `PASS: Node.js <NODE_MAJOR>`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step S.2: Set environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export GROUP_PREFIX="${GROUP_PREFIX:-uix-}"
export BARN_LIVE_TESTS="${BARN_LIVE_TESTS:-false}"
```

**Expected result:**

- The commands exit with code 0 (inferred).


## Phase 0: Manual Prerequisites

### Step 0.1: Confirm prerequisites

There are no manual prerequisites.

**Expected result:**

- There are no manual prerequisites.


---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Barn

Use [common/start-barn.md](common/start-barn.md) steps 1 to 4. After completion, `WANAKU_BARN_URL`, `CLI_JAR`
and `WANAKU_BARN_PID` must be set. Do not start Wanaku Server yet. Phase 6 starts it with the plugins installed.

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

```bash
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


### Test 1.2: Barn serves its standalone admin UI

Barn still embeds a standalone build of `apps/ui/admin` at `/admin/`. The Playwright suite of Phase 3 uses it.

Run this block as a separate `bash` script, not in the shared session.

```bash
BODY=$(curl -s "${WANAKU_BARN_URL}/admin/")
if echo "${BODY}" | grep -q "<title>Wanaku Console</title>"; then
  echo "PASS: Barn admin UI served at ${WANAKU_BARN_URL}/admin/"
else
  echo "FAIL: Barn admin UI not served"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: Barn admin UI served at <WANAKU_BARN_URL>/admin/`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step 1.3: Install the Node.js dependencies

Run this block as a separate `bash` script, not in the shared session.

```bash
cd "${WANAKU_REPO_ROOT}"
(cd apps/ui/admin && yarn install --frozen-lockfile) \
  && (cd apps/ui/debugger && yarn install --frozen-lockfile) \
  && (cd tests/e2e/ui && yarn install --frozen-lockfile && npx playwright install chromium)
if [ $? -eq 0 ]; then
  echo "PASS: dependencies and Chromium installed"
else
  echo "FAIL: dependency installation failed"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: dependencies and Chromium installed`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step 1.4: Build both plugins

Run this block as a separate `bash` script, not in the shared session.

```bash
cd "${WANAKU_REPO_ROOT}"
(cd apps/ui/admin && yarn build:plugin) && (cd apps/ui/debugger && yarn build:plugin)
for FILE in apps/ui/admin/plugin-dist/plugin.js apps/ui/admin/plugin-dist/plugin.json \
            apps/ui/debugger/plugin-dist/plugin.js apps/ui/debugger/plugin-dist/plugin.json; do
  [ -f "${FILE}" ] && echo "PASS: ${FILE} exists" || { echo "FAIL: ${FILE} not found"; exit 1; }
done
```

**Expected result:**

- The output includes `PASS: <FILE> exists`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 2: Plugin Unit Tests

### Test 2.1: Barn plugin unit tests

These tests cover the response envelope handling of the plugin HTTP client, the pages that the plugin mounts, and
the semantic router publication flow.

```bash run
(cd "${WANAKU_REPO_ROOT}/apps/ui/admin" && yarn test)
[ $? -eq 0 ] && echo "PASS: Barn plugin unit tests passed" || echo "FAIL: Barn plugin unit tests failed"
```

**Expected result:**

- The output includes `PASS: Barn plugin unit tests passed`.
- The output contains no line starting with `FAIL:`.


### Test 2.2: Debugger plugin unit tests

These tests cover credential redaction, governance denial messages, the management envelope and the chat tool
loop.

```bash run
(cd "${WANAKU_REPO_ROOT}/apps/ui/debugger" && yarn test)
[ $? -eq 0 ] && echo "PASS: Debugger plugin unit tests passed" || echo "FAIL: Debugger plugin unit tests failed"
```

**Expected result:**

- The output includes `PASS: Debugger plugin unit tests passed`.
- The output contains no line starting with `FAIL:`.


---

## Phase 3: Barn Pages (Playwright, Barn Standalone UI)

The default configuration runs every spec in `tests/e2e/ui/tests` against `${WANAKU_ROUTER_URL}/admin/`. When the
URL is on `localhost` and does not answer, the configuration starts the Barn jar itself. Test 1.2 makes sure that the
Barn of Phase 1 answers, so Playwright reuses it.

### Test 3.1: Smoke test against the live Barn

`smoke.spec.ts` loads the landing page. The landing page is Data Stores.

```bash run
(cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && WANAKU_ROUTER_URL="${WANAKU_BARN_URL}" \
  npx playwright test tests/smoke.spec.ts --reporter=line)
[ $? -eq 0 ] && echo "PASS: Barn admin UI landing page loads" || echo "FAIL: smoke test failed"
```

**Expected result:**

- The output includes `PASS: Barn admin UI landing page loads`.
- The output contains no line starting with `FAIL:`.


### Test 3.2: Complete Barn page suite

```bash run
(cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && WANAKU_ROUTER_URL="${WANAKU_BARN_URL}" npx playwright test --reporter=line)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: Barn page suite passed"
else
  echo "FAIL: Barn page suite failed (exit code ${EXIT_CODE}) — see tests/e2e/ui/playwright-report"
fi
```

The suite skips `semantic-routers.live.spec.ts` when `BARN_URL` is not set. This skip is expected.

| Page | Spec | Scenarios |
|------|------|-----------|
| Data Stores | `data-store-downloads.spec.ts` | Binary ZIP and UTF-8 JSON downloads, filenames, download errors, labels and audit metadata, search and filter, delete with revision, pagination |
| Kamelets | `kamelets.spec.ts` | List and search, bundled entries protected, upload, rejected files (negative), removal, failure and retry, wizard refresh |
| Semantic Routers | `semantic-routers.spec.ts` | Create, validate, preview, publish, reopen, remove, name validation (negative), drafts, generated files, experts, guards |
| Change History | `change-history.spec.ts` | Newest-first list, filter by resource type, deep link, empty state |
| Landing page | `smoke.spec.ts` | Data Stores loads against the live Barn |

**Expected result:**

- The output includes `PASS: Barn page suite passed`.
- The output contains no line starting with `FAIL:`.


### Test 3.3: Barn pages against the Vite preview build (optional)

`playwright.ui.config.ts` runs the mocked specs against `vite preview` on `127.0.0.1:4173`. It needs no Barn. Build
the standalone UI first.

```bash
(cd "${WANAKU_REPO_ROOT}/apps/ui/admin" && yarn build) \
  && (cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && npx playwright test --config playwright.ui.config.ts --reporter=line)
[ $? -eq 0 ] && echo "PASS: preview suite passed" || echo "FAIL: preview suite failed"
```

**Expected result:**

- The output includes `PASS: preview suite passed`.
- The output contains no line starting with `FAIL:`.


---

## Phase 4: Debugger Page (Playwright, Stub Host)

`tests/e2e/debugger/serve.mjs` serves `apps/ui/debugger/plugin-dist` in a minimal plugin host on
`127.0.0.1:4177`. The specs mock the MCP, A2A, inference and management endpoints. The configuration uses the
Playwright installation of `tests/e2e/ui`.

### Test 4.1: Debugger suite

```bash run
(cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && npx playwright test --config ../debugger/playwright.config.ts --reporter=line)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: Debugger suite passed"
else
  echo "FAIL: Debugger suite failed (exit code ${EXIT_CODE})"
fi
```

| Area | Spec | Scenarios |
|------|------|-----------|
| Registration | `debugger.spec.ts` | The plugin registers and renders without Barn services |
| MCP | `debugger.spec.ts` | Schema-driven invocation, allowed and governance-denied (negative), inspector, custom namespace without discovery |
| A2A | `debugger.spec.ts` | Send a message and follow the returned task |
| Inference | `debugger.spec.ts` | Token forwarding, tool calls shown and not executed |
| LLM Chat | `chat.spec.ts` | Only selected namespaced tools run, credential storage opt-in, stop and clear, refused unselected tools (negative), inert HTML |

**Expected result:**

- The output includes `PASS: Debugger suite passed`.
- The output contains no line starting with `FAIL:`.


---

## Phase 5: Semantic Router Against a Live Barn (Optional)

Run this phase only when `BARN_LIVE_TESTS` is `true`. The spec authors, classifies, publishes and removes a semantic
router through the live Barn API. The Barn must have the native deterministic preview service configured. The
configuration starts the admin UI dev server on `127.0.0.1:4174` with a proxy to `BARN_URL`.

### Test 5.1: Live semantic router flow

```bash run
if [ "${BARN_LIVE_TESTS}" = "true" ]; then
  (cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && BARN_URL="${WANAKU_BARN_URL}" \
    npx playwright test --config playwright.live.config.ts --reporter=line)
  [ $? -eq 0 ] && echo "PASS: live semantic router flow passed" || echo "FAIL: live semantic router flow failed"
else
  echo "SKIP: BARN_LIVE_TESTS is not true"
fi
```

**Expected result:**

- The output includes `PASS: live semantic router flow passed` or `SKIP: BARN_LIVE_TESTS is not true`.
- The output contains no line starting with `FAIL:`.


---

## Phase 6: Plugins Inside Wanaku Server

This phase installs both plugins in a Wanaku Server instance, as the operator and the Plugin Catalog do. Wanaku
Server reads plugins from `<WANAKU_PERSIST_PATH>/plugins` at startup, so the plugins must be in place before the
server starts.

### Step 6.1: Prepare the server data directory

The configuration allows tool calls in the `default` namespace (step 2b of
[common/start-server.md](common/start-server.md)) and maps the plugin service `wanaku-barn-api` to Barn.

```bash
export WANAKU_SERVER_DATA_DIR="${WANAKU_SERVER_DATA_DIR:-$(mktemp -d)}"
mkdir -p "${WANAKU_SERVER_DATA_DIR}/plugins/wanaku-barn" "${WANAKU_SERVER_DATA_DIR}/plugins/wanaku-debugger"
cp -r "${WANAKU_REPO_ROOT}/apps/ui/admin/plugin-dist/." "${WANAKU_SERVER_DATA_DIR}/plugins/wanaku-barn/"
cp -r "${WANAKU_REPO_ROOT}/apps/ui/debugger/plugin-dist/." "${WANAKU_SERVER_DATA_DIR}/plugins/wanaku-debugger/"
echo "PASS: plugins copied to ${WANAKU_SERVER_DATA_DIR}/plugins"
```

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

Run step 2b of [common/start-server.md](common/start-server.md) with `WANAKU_TEST_GOVERNED_NAMESPACES=default`. Then
append the plugin service mapping to the same file. Do not use the `cat >` command of the optional section of
[common/start-barn.md](common/start-barn.md) here, because it replaces the governance configuration.

Run this block as a separate `bash` script, not in the shared session.

```bash
cat >> "${WANAKU_SERVER_CONFIG}" <<EOF
plugins:
  - id: wanaku-barn
    services:
      wanaku-barn-api:
        target: ${WANAKU_BARN_URL}
EOF
grep -q "wanaku-barn-api" "${WANAKU_SERVER_CONFIG}" \
  && echo "PASS: plugin service mapping added" || { echo "FAIL: mapping not added"; exit 1; }
```

**Expected result:**

- The output includes `PASS: test governance configuration written to <WANAKU_SERVER_CONFIG>`.


- The output includes `PASS: plugins copied to <WANAKU_SERVER_DATA_DIR>/plugins`.
- The output includes `PASS: plugin service mapping added`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


### Step 6.2: Start Wanaku Server and the reference MCP server

Use [common/start-server.md](common/start-server.md) steps 1, 2 and 3 to 6. Keep the default ports. The Debugger
derives the MCP, inference and A2A URLs from the page origin with the fixed ports `8081`, `8083` and `8084`.

Then follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md). It registers the forward
`everything` in the `default` namespace.

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

#### Procedure step 1: Start the reference server

Run this block as a separate `bash` script, not in the shared session.

```bash
export TEST_MCP_PACKAGE="${TEST_MCP_PACKAGE:-@modelcontextprotocol/server-everything@1.0.0}"
export TEST_MCP_PORT="${TEST_MCP_PORT:-3001}"
export TEST_MCP_URL="http://localhost:${TEST_MCP_PORT}/mcp"
TEST_MCP_LOG="$(mktemp)"

PORT="${TEST_MCP_PORT}" npx -y "${TEST_MCP_PACKAGE}" streamableHttp > "${TEST_MCP_LOG}" 2>&1 &
TEST_MCP_PID=$!
export TEST_MCP_PID

for i in $(seq 1 30); do
  if grep -q "listening on port ${TEST_MCP_PORT}" "${TEST_MCP_LOG}"; then
    echo "PASS: reference MCP server listens on ${TEST_MCP_URL}"
    break
  fi
  if [ "${i}" -eq 30 ]; then
    echo "FAIL: reference MCP server did not start"
    cat "${TEST_MCP_LOG}"
    exit 1
  fi
  sleep 2
done
```

#### Procedure step 2: Register the forward

Run this block as a separate `bash` script, not in the shared session.

```bash
export TEST_MCP_FORWARD="${TEST_MCP_FORWARD:-everything}"
export TEST_MCP_NAMESPACE="${TEST_MCP_NAMESPACE:-default}"

java -jar "${CLI_JAR}" forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${TEST_MCP_FORWARD}" --service "${TEST_MCP_URL}" --namespace "${TEST_MCP_NAMESPACE}"
[ $? -eq 0 ] && echo "PASS: forward '${TEST_MCP_FORWARD}' registered" \
  || { echo "FAIL: could not register forward '${TEST_MCP_FORWARD}'"; exit 1; }
```

Register the reference server only once per Wanaku Server. Wanaku Server keys tools, resources and prompts by name
across all namespaces. A second forward to the same upstream (or to another upstream with the same tool names) moves
the entries to the namespace of the second forward. Removing that forward then deletes them. To test more than one
namespace, start a second reference server on another port with another forward name, and expect the same collision.
Use `forwards refresh --name "${TEST_MCP_FORWARD}"` to restore the entries after a collision.

#### Procedure step 3: Verify the tools were discovered

Wanaku Server discovers the upstream entries when the forward is created.

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "echo" && echo "PASS: tool 'echo' discovered" \
  || { echo "FAIL: tool 'echo' not discovered"; echo "${OUTPUT}"; exit 1; }
```

**Expected result:**

- The output includes `PASS: <WANAKU_SERVER_BIN> exists`.
- The output includes `PASS: port <WANAKU_SERVER_MGMT_PORT> is free`.
- The output includes `PASS: port 8081 is free`.
- The output includes `PASS: Wanaku Server is healthy`.
- The output includes `PASS: MCP endpoint <WANAKU_MCP_BASE_URL>/default/mcp answers`.
- The output includes `PASS: CLI can connect to Wanaku Server`.


- The output includes `PASS: reference MCP server listens on <TEST_MCP_URL>`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- The output includes `PASS: forward '<TEST_MCP_FORWARD>' registered`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- The output includes `PASS: tool 'echo' discovered`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.


### Test 6.3: Wanaku Server discovered both plugins

```bash run
IDS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/plugins" | jq -r '.data[].id' | sort | tr '\n' ' ')
if [ "${IDS}" = "wanaku-barn wanaku-debugger " ]; then
  echo "PASS: plugins discovered: ${IDS}"
else
  echo "FAIL: unexpected plugin list '${IDS}'"
fi
```

**Expected result:**

- The output includes `PASS: plugins discovered: <IDS>`.
- The output contains no line starting with `FAIL:`.


### Test 6.4: Plugin files are served

```bash run
for PLUGIN in wanaku-barn wanaku-debugger; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/plugins/${PLUGIN}/plugin.js")
  [ "${HTTP_CODE}" = "200" ] && echo "PASS: ${PLUGIN}/plugin.js served" \
    || echo "FAIL: ${PLUGIN}/plugin.js returned HTTP ${HTTP_CODE}"
done
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/plugins/no-such-plugin/plugin.js")
[ "${HTTP_CODE}" = "404" ] && echo "PASS: unknown plugin file returns 404" \
  || echo "FAIL: unknown plugin file returned HTTP ${HTTP_CODE}"
```

**Expected result:**

- The output includes `PASS: wanaku-barn/plugin.js served`.
- The output includes `PASS: wanaku-debugger/plugin.js served`.
- The output includes `PASS: unknown plugin file returns 404`.
- The output contains no line starting with `FAIL:`.


### Test 6.5: The plugin proxy reaches Barn

Create a data store in Barn with the CLI. Then read it through the plugin proxy route that the Barn pages use.

```bash run
DS_NAME="${GROUP_PREFIX}ui-entry"
DS_FILE="$(mktemp)"
echo "ui-extended-pages" > "${DS_FILE}"
java -jar "${CLI_JAR}" data-store add --host "${WANAKU_BARN_URL}" --no-auth \
  --name "${DS_NAME}" --read-from-file "${DS_FILE}"
[ $? -eq 0 ] && echo "PASS: data store ${DS_NAME} created in Barn" || echo "FAIL: data store not created"

COUNT=$(curl -s "${WANAKU_SERVER_URL}/api/plugins/wanaku-barn/wanaku-barn-api/api/v1/data-store" \
  | jq --arg n "${DS_NAME}" '[.data[] | select(.name == $n)] | length')
[ "${COUNT}" = "1" ] && echo "PASS: data store visible through the plugin proxy" \
  || echo "FAIL: data store not visible through the plugin proxy (count ${COUNT})"
```

**Expected result:**

- The output includes `PASS: data store <DS_NAME> created in Barn`.
- The output includes `PASS: data store visible through the plugin proxy`.
- The output contains no line starting with `FAIL:`.


### Test 6.6: Unknown plugin service is rejected (negative)

```bash run
RESPONSE=$(curl -s -w "\n%{http_code}" "${WANAKU_SERVER_URL}/api/plugins/wanaku-barn/no-such-service/api/v1/data-store")
HTTP_CODE=$(echo "${RESPONSE}" | tail -1)
ERROR=$(echo "${RESPONSE}" | head -1 | jq -r '.error')
if [ "${HTTP_CODE}" = "404" ] && echo "${ERROR}" | grep -q "service no-such-service not found"; then
  echo "PASS: unknown plugin service returns 404"
else
  echo "FAIL: expected 404, got HTTP ${HTTP_CODE} (${ERROR})"
fi
```

**Expected result:**

- The output includes `PASS: unknown plugin service returns 404`.
- The output contains no line starting with `FAIL:`.


### Test 6.7: Barn pages in the host (browser)

Open `${WANAKU_SERVER_URL}/admin/` in a browser. Do these steps manually or with a browser automation agent. Record
PASS or FAIL for each row.

| Step | Action | Expected result |
|------|--------|-----------------|
| 1 | Look at the navigation | The entries Data Stores, Service Catalog, Semantic Routers, Kamelets and Change History are present |
| 2 | Select Data Stores | The URL hash is `#/wanaku/data-stores`. The heading is "Data Stores". The row `${GROUP_PREFIX}ui-entry` is present. |
| 3 | Select Service Catalog | The heading is "Service Catalog". The tabs "Service Catalogs" and "Service Templates" are present. |
| 4 | Select the "Service Templates" tab | Template cards are present (Barn seeds the bundled templates, for example `echo-tool`), or the empty-state tile is shown |
| 5 | Select Semantic Routers, Kamelets and Change History | Each page shows its heading. No error notification is shown. |
| 6 | Reload the page on `#/wanaku/kamelets` | The Kamelets page loads again without a "Page not found." message |

**Expected result:**

- The entries Data Stores, Service Catalog, Semantic Routers, Kamelets and Change History are present
- The URL hash is `#/wanaku/data-stores`. The heading is "Data Stores". The row `${GROUP_PREFIX}ui-entry` is present.
- The heading is "Service Catalog". The tabs "Service Catalogs" and "Service Templates" are present.
- Template cards are present (Barn seeds the bundled templates, for example `echo-tool`), or the empty-state tile is shown
- Each page shows its heading. No error notification is shown.
- The Kamelets page loads again without a "Page not found." message


### Test 6.8: Debugger page in the host (browser)

| Step | Action | Expected result |
|------|--------|-----------------|
| 1 | Expand the Developer section of the navigation | The entry "Debugger" is present |
| 2 | Select Debugger | The URL hash is `#/wanaku/debugger`. The tabs MCP, A2A, Inference and LLM Chat are present. |
| 3 | On the MCP tab, select namespace `default` and tool `echo`. Set `message` to `hello`. Run the request. | The response contains `Echo: hello` |
| 4 | Select a namespace that is not in `WANAKU_TEST_GOVERNED_NAMESPACES` (create one with `wanaku namespaces create`) and call a tool there (negative) | The Debugger explains the governance denial (`No governance policy permits this action.`) |

**Expected result:**

- The entry "Debugger" is present
- The URL hash is `#/wanaku/debugger`. The tabs MCP, A2A, Inference and LLM Chat are present.
- The response contains `Echo: hello`
- The Debugger explains the governance denial (`No governance policy permits this action.`)


### Test 6.9: Barn pages report an unreachable Barn (negative)

Stop Barn (see the [Shutdown](common/start-barn.md#shutdown) section of
[common/start-barn.md](common/start-barn.md)). The plugin proxy must return `502`.

```bash
kill "${WANAKU_BARN_PID}" 2>/dev/null || true
wait "${WANAKU_BARN_PID}" 2>/dev/null || true
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  "${WANAKU_SERVER_URL}/api/plugins/wanaku-barn/wanaku-barn-api/api/v1/data-store")
[ "${HTTP_CODE}" = "502" ] && echo "PASS: plugin proxy returns 502 when Barn is down" \
  || echo "FAIL: plugin proxy returned HTTP ${HTTP_CODE}"
```

In the browser, open Data Stores again. Expected result: the page shows an error notification. It does not show an
empty table without an explanation.

**Expected result:**

- The output includes `PASS: plugin proxy returns 502 when Barn is down`.
- The output contains no line starting with `FAIL:`.
- The Data Stores page shows an error notification and does not show an empty table without an explanation.


---

## Phase 7: Cleanup

All cleanup steps are idempotent.

### Step 7.1: Remove the test data store

Barn is stopped after Test 6.9. Its home directory is removed in Step 7.3, so the data store is removed with it.
When Test 6.9 did not run, remove the entry explicitly:

```bash
java -jar "${CLI_JAR}" data-store remove --host "${WANAKU_BARN_URL}" --no-auth \
  --name "${GROUP_PREFIX}ui-entry" 2>/dev/null || true
rm -f "${DS_FILE}" 2>/dev/null || true
echo "PASS: test data store removed (or already absent)"
```

**Expected result:**

- The output includes `PASS: test data store removed (or already absent)`.


### Step 7.2: Stop the reference MCP server and Wanaku Server

Use the Shutdown sections of [common/start-test-mcp-server.md](common/start-test-mcp-server.md#shutdown) and
[common/start-server.md](common/start-server.md#shutdown). The server shutdown removes `WANAKU_SERVER_DATA_DIR`,
including the copied plugins.

Remove the forward first. Wanaku Server also removes the entries that it discovered from the forward.

The entry plan runs the original shutdown block in its local cleanup unit.

Stop the server with the PID captured at startup. Then remove the data directory of the run.

The entry plan runs the original shutdown block in its local cleanup unit.

Do **not** use `kill -9`. It bypasses the orderly Pingora shutdown and the final registry snapshot. If Pingora worker
processes remain, find them with `lsof -ti :8081` and stop them with `SIGTERM`.

**Expected result:**

- The reference MCP server and Wanaku Server are stopped, and the server data directory is removed (inferred).


### Step 7.3: Stop Barn

Use the [Shutdown](common/start-barn.md#shutdown) section of [common/start-barn.md](common/start-barn.md).

```bash
if [ -n "${WANAKU_BARN_PID}" ]; then
  kill "${WANAKU_BARN_PID}" 2>/dev/null || true
  wait "${WANAKU_BARN_PID}" 2>/dev/null || true
  echo "PASS: Wanaku Barn stopped"
fi
rm -rf "${WANAKU_BARN_HOME}"
```

**Expected result:**

- Barn is stopped and its home directory is removed (inferred).


### Step 7.4: Remove the Playwright artifacts (optional)

```bash
cd "${WANAKU_REPO_ROOT}"
rm -rf tests/e2e/ui/playwright-report tests/e2e/ui/test-results tests/e2e/ui/test-results-live \
  tests/e2e/debugger/test-results 2>/dev/null || true
echo "PASS: Playwright artifacts removed"
```

**Expected result:**

- The output includes `PASS: Playwright artifacts removed`.


---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| S | S.1 | Prepare prerequisites | High |
| S | S.2 | Set environment variables | High |
| 0 | 0.1 | Confirm prerequisites | High |
| 1 | 1.1 | Start Wanaku Barn | Critical |
| 1 | 1.2 | Barn serves its standalone admin UI | Critical |
| 1 | 1.3 | Install the Node.js dependencies | Critical |
| 1 | 1.4 | Build both plugins | Critical |
| 2 | 2.1 | Barn plugin unit tests | High |
| 2 | 2.2 | Debugger plugin unit tests | High |
| 3 | 3.1 | Smoke test against the live Barn | Critical |
| 3 | 3.2 | Complete Barn page suite | Critical |
| 3 | 3.3 | Barn pages against the Vite preview build | Low |
| 4 | 4.1 | Debugger suite | Critical |
| 5 | 5.1 | Live semantic router flow | Medium |
| 6 | 6.1 | Prepare the server data directory | Critical |
| 6 | 6.2 | Start Wanaku Server and the reference MCP server | Critical |
| 6 | 6.3 | Wanaku Server discovered both plugins | Critical |
| 6 | 6.4 | Plugin files are served | High |
| 6 | 6.5 | The plugin proxy reaches Barn | Critical |
| 6 | 6.6 | Unknown plugin service is rejected (negative) | Medium |
| 6 | 6.7 | Barn pages in the host (browser) | High |
| 6 | 6.8 | Debugger page in the host (browser) | High |
| 6 | 6.9 | Barn pages report an unreachable Barn (negative) | Medium |
| 7 | 7.1 | Remove the test data store | Medium |
| 7 | 7.2 | Stop the reference MCP server and Wanaku Server | Critical |
| 7 | 7.3 | Stop Barn | Critical |
| 7 | 7.4 | Remove the Playwright artifacts | Low |
