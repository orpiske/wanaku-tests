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

```bash
for TOOL in node yarn npx java curl jq; do
  command -v "${TOOL}" > /dev/null 2>&1 \
    && echo "PASS: ${TOOL} found" \
    || { echo "FAIL: ${TOOL} not found"; exit 1; }
done
NODE_MAJOR=$(node --version | sed 's/^v\([0-9]*\).*/\1/')
[ "${NODE_MAJOR}" -ge 22 ] && echo "PASS: Node.js ${NODE_MAJOR}" || echo "FAIL: Node.js 22+ required (found ${NODE_MAJOR})"
```

### Environment variables

```bash
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export GROUP_PREFIX="${GROUP_PREFIX:-uix-}"
export BARN_LIVE_TESTS="${BARN_LIVE_TESTS:-false}"
```

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

## Phase 0: Manual Prerequisites

There are no manual prerequisites.

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md) steps 1 to 4. After completion, `WANAKU_BARN_URL`, `CLI_JAR`
and `WANAKU_BARN_PID` must be set. Do not start Wanaku Server yet. Phase 6 starts it with the plugins installed.

### Test 1.2: Barn serves its standalone admin UI

Barn still embeds a standalone build of `apps/ui/admin` at `/admin/`. The Playwright suite of Phase 3 uses it.

```bash
BODY=$(curl -s "${WANAKU_BARN_URL}/admin/")
if echo "${BODY}" | grep -q "<title>Wanaku Console</title>"; then
  echo "PASS: Barn admin UI served at ${WANAKU_BARN_URL}/admin/"
else
  echo "FAIL: Barn admin UI not served"
  exit 1
fi
```

### Step 1.3: Install the Node.js dependencies

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

### Step 1.4: Build both plugins

```bash
cd "${WANAKU_REPO_ROOT}"
(cd apps/ui/admin && yarn build:plugin) && (cd apps/ui/debugger && yarn build:plugin)
for FILE in apps/ui/admin/plugin-dist/plugin.js apps/ui/admin/plugin-dist/plugin.json \
            apps/ui/debugger/plugin-dist/plugin.js apps/ui/debugger/plugin-dist/plugin.json; do
  [ -f "${FILE}" ] && echo "PASS: ${FILE} exists" || { echo "FAIL: ${FILE} not found"; exit 1; }
done
```

---

## Phase 2: Plugin Unit Tests

### Test 2.1: Barn plugin unit tests

These tests cover the response envelope handling of the plugin HTTP client, the pages that the plugin mounts, and
the semantic router publication flow.

```bash
(cd "${WANAKU_REPO_ROOT}/apps/ui/admin" && yarn test)
[ $? -eq 0 ] && echo "PASS: Barn plugin unit tests passed" || echo "FAIL: Barn plugin unit tests failed"
```

### Test 2.2: Debugger plugin unit tests

These tests cover credential redaction, governance denial messages, the management envelope and the chat tool
loop.

```bash
(cd "${WANAKU_REPO_ROOT}/apps/ui/debugger" && yarn test)
[ $? -eq 0 ] && echo "PASS: Debugger plugin unit tests passed" || echo "FAIL: Debugger plugin unit tests failed"
```

---

## Phase 3: Barn Pages (Playwright, Barn Standalone UI)

The default configuration runs every spec in `tests/e2e/ui/tests` against `${WANAKU_ROUTER_URL}/admin/`. When the
URL is on `localhost` and does not answer, the configuration starts the Barn jar itself. Test 1.2 makes sure that the
Barn of Phase 1 answers, so Playwright reuses it.

### Test 3.1: Smoke test against the live Barn

`smoke.spec.ts` loads the landing page. The landing page is Data Stores.

```bash
(cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && WANAKU_ROUTER_URL="${WANAKU_BARN_URL}" \
  npx playwright test tests/smoke.spec.ts --reporter=line)
[ $? -eq 0 ] && echo "PASS: Barn admin UI landing page loads" || echo "FAIL: smoke test failed"
```

### Test 3.2: Complete Barn page suite

```bash
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

### Test 3.3 (optional): Barn pages against the Vite preview build

`playwright.ui.config.ts` runs the mocked specs against `vite preview` on `127.0.0.1:4173`. It needs no Barn. Build
the standalone UI first.

```bash
(cd "${WANAKU_REPO_ROOT}/apps/ui/admin" && yarn build) \
  && (cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && npx playwright test --config playwright.ui.config.ts --reporter=line)
[ $? -eq 0 ] && echo "PASS: preview suite passed" || echo "FAIL: preview suite failed"
```

---

## Phase 4: Debugger Page (Playwright, Stub Host)

`tests/e2e/debugger/serve.mjs` serves `apps/ui/debugger/plugin-dist` in a minimal plugin host on
`127.0.0.1:4177`. The specs mock the MCP, A2A, inference and management endpoints. The configuration uses the
Playwright installation of `tests/e2e/ui`.

### Test 4.1: Debugger suite

```bash
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

---

## Phase 5 (Optional): Semantic Router Against a Live Barn

Run this phase only when `BARN_LIVE_TESTS` is `true`. The spec authors, classifies, publishes and removes a semantic
router through the live Barn API. The Barn must have the native deterministic preview service configured. The
configuration starts the admin UI dev server on `127.0.0.1:4174` with a proxy to `BARN_URL`.

### Test 5.1: Live semantic router flow

```bash
if [ "${BARN_LIVE_TESTS}" = "true" ]; then
  (cd "${WANAKU_REPO_ROOT}/tests/e2e/ui" && BARN_URL="${WANAKU_BARN_URL}" \
    npx playwright test --config playwright.live.config.ts --reporter=line)
  [ $? -eq 0 ] && echo "PASS: live semantic router flow passed" || echo "FAIL: live semantic router flow failed"
else
  echo "SKIP: BARN_LIVE_TESTS is not true"
fi
```

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

Run step 2b of [common/start-server.md](common/start-server.md) with `WANAKU_TEST_GOVERNED_NAMESPACES=default`. Then
append the plugin service mapping to the same file. Do not use the `cat >` command of the optional section of
[common/start-barn.md](common/start-barn.md) here, because it replaces the governance configuration.

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

### Step 6.2: Start Wanaku Server and the reference MCP server

Follow [common/start-server.md](common/start-server.md) steps 1, 2 and 3 to 6. Keep the default ports. The Debugger
derives the MCP, inference and A2A URLs from the page origin with the fixed ports `8081`, `8083` and `8084`.

Then follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md). It registers the forward
`everything` in the `default` namespace.

### Test 6.3: Wanaku Server discovered both plugins

```bash
IDS=$(curl -s "${WANAKU_SERVER_URL}/api/v1/plugins" | jq -r '.data[].id' | sort | tr '\n' ' ')
if [ "${IDS}" = "wanaku-barn wanaku-debugger " ]; then
  echo "PASS: plugins discovered: ${IDS}"
else
  echo "FAIL: unexpected plugin list '${IDS}'"
fi
```

### Test 6.4: Plugin files are served

```bash
for PLUGIN in wanaku-barn wanaku-debugger; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/plugins/${PLUGIN}/plugin.js")
  [ "${HTTP_CODE}" = "200" ] && echo "PASS: ${PLUGIN}/plugin.js served" \
    || echo "FAIL: ${PLUGIN}/plugin.js returned HTTP ${HTTP_CODE}"
done
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/plugins/no-such-plugin/plugin.js")
[ "${HTTP_CODE}" = "404" ] && echo "PASS: unknown plugin file returns 404" \
  || echo "FAIL: unknown plugin file returned HTTP ${HTTP_CODE}"
```

### Test 6.5: The plugin proxy reaches Barn

Create a data store in Barn with the CLI. Then read it through the plugin proxy route that the Barn pages use.

```bash
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

### Test 6.6: Unknown plugin service is rejected (negative)

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" "${WANAKU_SERVER_URL}/api/plugins/wanaku-barn/no-such-service/api/v1/data-store")
HTTP_CODE=$(echo "${RESPONSE}" | tail -1)
ERROR=$(echo "${RESPONSE}" | head -1 | jq -r '.error')
if [ "${HTTP_CODE}" = "404" ] && echo "${ERROR}" | grep -q "service no-such-service not found"; then
  echo "PASS: unknown plugin service returns 404"
else
  echo "FAIL: expected 404, got HTTP ${HTTP_CODE} (${ERROR})"
fi
```

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

### Test 6.8: Debugger page in the host (browser)

| Step | Action | Expected result |
|------|--------|-----------------|
| 1 | Expand the Developer section of the navigation | The entry "Debugger" is present |
| 2 | Select Debugger | The URL hash is `#/wanaku/debugger`. The tabs MCP, A2A, Inference and LLM Chat are present. |
| 3 | On the MCP tab, select namespace `default` and tool `echo`. Set `message` to `hello`. Run the request. | The response contains `Echo: hello` |
| 4 | Select a namespace that is not in `WANAKU_TEST_GOVERNED_NAMESPACES` (create one with `wanaku namespaces create`) and call a tool there (negative) | The Debugger explains the governance denial (`No governance policy permits this action.`) |

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

### Step 7.2: Stop the reference MCP server and Wanaku Server

Follow the Shutdown sections of [common/start-test-mcp-server.md](common/start-test-mcp-server.md#shutdown) and
[common/start-server.md](common/start-server.md#shutdown). The server shutdown removes `WANAKU_SERVER_DATA_DIR`,
including the copied plugins.

### Step 7.3: Stop Barn

Follow the [Shutdown](common/start-barn.md#shutdown) section of [common/start-barn.md](common/start-barn.md).

### Step 7.4: Remove the Playwright artifacts (optional)

```bash
cd "${WANAKU_REPO_ROOT}"
rm -rf tests/e2e/ui/playwright-report tests/e2e/ui/test-results tests/e2e/ui/test-results-live \
  tests/e2e/debugger/test-results 2>/dev/null || true
echo "PASS: Playwright artifacts removed"
```

---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
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
