# Test Plan: Wanaku Home and Server Persistence Path Resolution

## Overview

This test plan verifies where Wanaku components store local state, and how the location can be overridden.

Wanaku Barn and the Wanaku CLI resolve a **Wanaku home** directory with `ai.wanaku.core.util.WanakuHome`
(`core/core-util`). The resolution order is:

1. The Java system property `wanaku.home`
2. The environment variable `WANAKU_HOME`
3. The default `${user.home}/.wanaku`

A blank value (empty or only spaces) is ignored, and the next source is used.

| Component | What it stores under the Wanaku home | Override that wins over the home |
|-----------|---------------------------------------|----------------------------------|
| Wanaku Barn | Infinispan store in `<home>/barn` (`wanaku.persistence.infinispan.base-folder`) and, with the `local` profile, the log file `<home>/local/logs/wanaku-router.log` | `-Dwanaku.persistence.infinispan.base-folder` |
| Wanaku CLI | Credentials file `<home>/credentials` (`wanaku auth login`) and cloud target `<home>/cloud.properties` (`wanaku cloud target set`) | `WANAKU_CREDENTIALS` for the credentials file |

Wanaku Server (Rust) does **not** use `WanakuHome` or `WANAKU_HOME`. It stores its registry snapshot `registry.json`
in `WANAKU_PERSIST_PATH`. The default is `$HOME/.wanaku/server`, where `HOME` is the environment variable.
`WANAKU_PERSIST_BACKEND=none` disables persistence. The server writes `registry.json` when the registry changes and
flushes pending changes during an orderly shutdown (`SIGTERM`). It loads the file at startup.

**Components:** Wanaku Barn, the Wanaku CLI and Wanaku Server. Each phase starts its own processes.

The plan never writes to the real `~/.wanaku` directory:

- Barn and the CLI tests that check the default location set `-Duser.home` to a temporary directory.
- The Wanaku Server tests that check the default location set the `HOME` environment variable of the server process
  to a temporary directory.
- Phase 10 compares the real `~/.wanaku` before and after the run.

Tests run locally, with no authentication, and are fully automatable.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java -version` |
| `mvn` | 3.9+ | `mvn -version` |
| `cargo` | 1.96+ (or a prebuilt `wanaku-server` binary) | `cargo --version` |
| `node` / `npx` | 18+ | `node --version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |

### Environment variables

The executable block is in Step S.1 below.

| Variable | Default | Description |
|----------|---------|-------------|
| `WANAKU_REPO_ROOT` | `.` | Path to this repository (wanaku-barn) |
| `WANAKU_SERVER_REPO_ROOT` | `../wanaku` | Path to the `wanaku-ai/wanaku` checkout |
| `HOME_TEST_ROOT` | `$(mktemp -d)` | Parent directory for every home and data directory of this run |
| `HOME_BARN_PORT` | `8180` | HTTP port of the Barn instances of this plan |
| `HOME_BARN_JGROUPS_PORT` | `46700` | JGroups multicast port of the Barn instances (see the note below) |
| `HOME_SERVER_MGMT_PORT` | `8080` | Management port of the Wanaku Server instances of this plan |
| `HOME_SERVER_MCP_PORT`, `HOME_SERVER_INFERENCE_PORT`, `HOME_SERVER_A2A_PORT` | `8081`, `8083`, `8084` | Listener ports of the Wanaku Server instances |
| `HOME_NS` | `home-<epoch>` | Wanaku namespace that Phase 9 creates |

The plan starts and stops Barn and Wanaku Server several times. Do not run another Barn or Wanaku Server on the same
ports. To run next to other instances, set all port variables to free ports.

**Note on JGroups:** Barn starts an Infinispan JGroups channel with UDP multicast. Barn processes on the same host
that use the same multicast port join one cluster. A test instance then exchanges cluster state with other Barn
processes, and its caches can fail to start. The helpers below set `-Djgroups.mcast_port` to
`HOME_BARN_JGROUPS_PORT`, so the test instances form their own cluster.

### Known limitations

- **No Kubernetes:** The operator sets `WANAKU_PERSIST_PATH` and the Barn home in the pods. That is out of scope.
- **Separate JVMs:** `-Dwanaku.home` affects only the JVM that receives it. Barn and the CLI each need their own
  override.
- **Root user:** File permission tests do not work as `root`. The negative tests use a path below a regular file
  instead. This path fails for every user.

---

## Phase S: Setup

### Step S.1: Set environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_SERVER_REPO_ROOT="${WANAKU_SERVER_REPO_ROOT:-../wanaku}"
export HOME_TEST_ROOT="${HOME_TEST_ROOT:-$(mktemp -d)}"
export HOME_BARN_PORT="${HOME_BARN_PORT:-8180}"
export HOME_BARN_JGROUPS_PORT="${HOME_BARN_JGROUPS_PORT:-46700}"
export HOME_SERVER_MGMT_PORT="${HOME_SERVER_MGMT_PORT:-8080}"
export HOME_SERVER_MCP_PORT="${HOME_SERVER_MCP_PORT:-8081}"
export HOME_SERVER_INFERENCE_PORT="${HOME_SERVER_INFERENCE_PORT:-8083}"
export HOME_SERVER_A2A_PORT="${HOME_SERVER_A2A_PORT:-8084}"
export HOME_NS="${HOME_NS:-home-$(date +%s)}"
export HOME_BARN_URL="http://localhost:${HOME_BARN_PORT}"
export WANAKU_SERVER_URL="http://localhost:${HOME_SERVER_MGMT_PORT}"
export WANAKU_MCP_BASE_URL="http://localhost:${HOME_SERVER_MCP_PORT}"
unset WANAKU_HOME WANAKU_CREDENTIALS WANAKU_PERSIST_PATH WANAKU_PERSIST_BACKEND
```

**Expected result:**

- The commands complete without errors (inferred).

## Phase 0: Prerequisites Verification

### Test 0.1: Verify required tools

Run this block as a separate `bash` script, not in the shared session.

```bash
for TOOL in java curl jq npx; do
  command -v "${TOOL}" > /dev/null 2>&1 \
    && echo "PASS: ${TOOL} found" || { echo "FAIL: ${TOOL} not found"; exit 1; }
done
JAVA_VERSION=$(java -version 2>&1 | grep -m1 'version' | sed 's/[^"]*"\([0-9]*\).*/\1/')
[ "${JAVA_VERSION}" -ge 21 ] && echo "PASS: Java ${JAVA_VERSION}" || { echo "FAIL: Java ${JAVA_VERSION} < 21"; exit 1; }
```

**Expected result:**

- The output includes `PASS: java found`.
- The output includes `PASS: curl found`.
- The output includes `PASS: jq found`.
- The output includes `PASS: npx found`.
- The output includes `PASS: Java <JAVA_VERSION>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 0.2: Verify the test root is not the default home

Run this block as a separate `bash` script, not in the shared session.

```bash
case "${HOME_TEST_ROOT}" in
  "${HOME}/.wanaku"*) echo "FAIL: HOME_TEST_ROOT must not be inside ${HOME}/.wanaku"; exit 1 ;;
esac
mkdir -p "${HOME_TEST_ROOT}" && echo "PASS: test root ${HOME_TEST_ROOT}"
```

**Expected result:**

- The output includes `PASS: test root <HOME_TEST_ROOT>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 0.3: Record the state of the real default home

Phase 10 compares this listing with the state after the run.

```bash run
REAL_HOME_SNAPSHOT_BEFORE="${HOME_TEST_ROOT}/real-home-before.txt"
if [ -d "${HOME}/.wanaku" ]; then
  find "${HOME}/.wanaku" -type f -exec ls -l --time-style=+%s {} \; 2>/dev/null | sort > "${REAL_HOME_SNAPSHOT_BEFORE}"
else
  : > "${REAL_HOME_SNAPSHOT_BEFORE}"
fi
echo "PASS: recorded $(wc -l < "${REAL_HOME_SNAPSHOT_BEFORE}" | tr -d ' ') files of ${HOME}/.wanaku"
```

**Expected result:**

- The output includes `PASS: recorded`.

### Test 0.4: Verify the ports are free

Run this block as a separate `bash` script, not in the shared session.

```bash
for PORT in "${HOME_BARN_PORT}" "${HOME_SERVER_MGMT_PORT}" "${HOME_SERVER_MCP_PORT}"; do
  if curl -s -o /dev/null "http://localhost:${PORT}/" 2>/dev/null; then
    echo "FAIL: port ${PORT} is in use"
    exit 1
  fi
  echo "PASS: port ${PORT} is free"
done
```

**Expected result:**

- The output includes `PASS: port <HOME_BARN_PORT> is free`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: port <HOME_SERVER_MGMT_PORT> is free`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: port <HOME_SERVER_MCP_PORT> is free`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

## Phase 1: Build and Helpers

### Step 1.1: Build Barn and the CLI

Use [common/start-barn.md](common/start-barn.md) step 1 only. Do not start Barn. After completion, `CLI_JAR` and
`BARN_JAR` must be set.

```bash run
cd "${WANAKU_REPO_ROOT:-.}"
mvn -DskipTests clean package
```

Run this block as a separate `bash` script, not in the shared session.

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

**Expected result:**

- The output includes `PASS: <CLI_JAR> exists` and `PASS: <BARN_JAR> exists`, using the resolved JAR paths.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step 1.2: Build Wanaku Server

Use [common/start-server.md](common/start-server.md) step 1 only. Do not start the server. After completion,
`WANAKU_SERVER_BIN` must be set.

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

**Expected result:**

- The output includes `PASS: <WANAKU_SERVER_BIN> exists`, using the resolved binary path.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step 1.3: Write the pipeline configuration

The MCP, inference and A2A listener ports come from the pipeline configuration. This step writes a copy of the
embedded default with the ports of this plan. A file passed with `--pipeline-config` replaces the embedded default
completely. The environment overrides of the embedded default (`WANAKU_A2A_LISTEN`, `WANAKU_CORS_ORIGIN` and the
inference upstream) then do not apply. This plan does not need them.

Run this block as a separate `bash` script, not in the shared session.

```bash
HOME_PIPELINE="${HOME_TEST_ROOT}/pipeline.yaml"
sed -e "s/:8081\"/:${HOME_SERVER_MCP_PORT}\"/" \
    -e "s/:8083\"/:${HOME_SERVER_INFERENCE_PORT}\"/" \
    -e "s/:8084/:${HOME_SERVER_A2A_PORT}/g" \
    "${WANAKU_SERVER_REPO_ROOT}/server/src/default.yaml" > "${HOME_PIPELINE}"
grep -q "0.0.0.0:${HOME_SERVER_MCP_PORT}" "${HOME_PIPELINE}" \
  && echo "PASS: pipeline configuration written to ${HOME_PIPELINE}" || { echo "FAIL: MCP port not set"; exit 1; }
```

**Expected result:**

- The output includes `PASS: pipeline configuration written to <HOME_PIPELINE>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step 1.4: Define the helper functions

The helpers poll the health endpoints. They do not use fixed waits. Run the remaining phases in the same shell.

```bash run
# wait_http URL EXPECTED_CODE MAX_SECONDS
wait_http() {
  _i=0
  while [ "${_i}" -lt "$3" ]; do
    _code=$(curl -s -o /dev/null -w "%{http_code}" "$1" 2>/dev/null)
    [ "${_code}" = "$2" ] && return 0
    _i=$((_i + 1))
    sleep 1
  done
  return 1
}

# start_barn LOG_FILE [extra JVM options...]  (uses WANAKU_HOME from the current environment)
start_barn() {
  _log="$1"; shift
  java -Dquarkus.http.port="${HOME_BARN_PORT}" -Djgroups.mcast_port="${HOME_BARN_JGROUPS_PORT}" \
    "$@" -jar "${BARN_JAR}" > "${_log}" 2>&1 &
  BARN_PID=$!
  wait_http "${HOME_BARN_URL}/q/health/live" 200 120
}

stop_barn() {
  if [ -n "${BARN_PID}" ]; then
    kill "${BARN_PID}" 2>/dev/null || true
    wait "${BARN_PID}" 2>/dev/null || true
    BARN_PID=""
  fi
}

# start_server LOG_FILE HOME_DIR  (uses WANAKU_PERSIST_PATH and WANAKU_PERSIST_BACKEND from the current environment)
start_server() {
  env HOME="$2" WANAKU_MGMT_LISTEN="0.0.0.0:${HOME_SERVER_MGMT_PORT}" \
    "${WANAKU_SERVER_BIN}" --pipeline-config "${HOME_PIPELINE}" > "$1" 2>&1 &
  SERVER_PID=$!
  wait_http "${WANAKU_SERVER_URL}/health" 200 30
}

# stop_server: SIGTERM for an orderly shutdown, then wait until the process exits
stop_server() {
  if [ -n "${SERVER_PID}" ]; then
    kill -TERM "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
    SERVER_PID=""
  fi
}
echo "PASS: helper functions defined"
```

The Barn helper waits for `/q/health/live`, not for `/q/health/ready`. Phase 6 explains why.

**Expected result:**

- The blocks define the helper functions without errors (inferred).

### Test 2.1: Start Barn without overrides

```bash run
unset WANAKU_HOME
BARN_USER_HOME="${HOME_TEST_ROOT}/barn-user-home"
mkdir -p "${BARN_USER_HOME}"
start_barn "${HOME_TEST_ROOT}/barn-default.log" -Duser.home="${BARN_USER_HOME}" \
  && echo "PASS: Barn started with the default home" || { echo "FAIL: Barn did not start"; tail -20 "${HOME_TEST_ROOT}/barn-default.log"; }
```

**Expected result:**

- The output includes `PASS: Barn started with the default home`.
- The output contains no line starting with `FAIL:`.

### Test 2.2: Barn is ready

```bash run
wait_http "${HOME_BARN_URL}/q/health/ready" 200 60 \
  && echo "PASS: Barn is ready" || echo "FAIL: Barn is not ready"
```

**Expected result:**

- The output includes `PASS: Barn is ready`.
- The output contains no line starting with `FAIL:`.

### Test 2.3: The store is in the default home

```bash run
[ -d "${BARN_USER_HOME}/.wanaku/barn" ] \
  && echo "PASS: store created at ${BARN_USER_HOME}/.wanaku/barn" \
  || echo "FAIL: store not found at ${BARN_USER_HOME}/.wanaku/barn"
```

**Expected result:**

- The output includes `PASS: store created at <BARN_USER_HOME>/.wanaku/barn`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 2.4: The CLI can connect

```bash run
java -jar "${CLI_JAR}" service catalog list --host "${HOME_BARN_URL}" --no-auth --plain
[ $? -eq 0 ] && echo "PASS: CLI connected to Barn" || echo "FAIL: CLI cannot connect to Barn"
```

**Expected result:**

- The output includes `PASS: CLI connected to Barn`.
- The output contains no line starting with `FAIL:`.

### Step 2.5: Stop Barn

```bash run
STOPPED_PID="${BARN_PID}"
stop_barn
kill -0 "${STOPPED_PID}" 2>/dev/null && echo "FAIL: Barn still running" || echo "PASS: Barn stopped"
```

**Expected result:**

- The output includes `PASS: Barn stopped`.
- The output contains no line starting with `FAIL:`.

## Phase 3: Barn WANAKU_HOME Override

This phase also uses the `local` profile, which writes the log file into the Wanaku home.

### Test 3.1: Start Barn with WANAKU_HOME

```bash run
export WANAKU_HOME="${HOME_TEST_ROOT}/barn-env-home"
BARN_USER_HOME_2="${HOME_TEST_ROOT}/barn-user-home-2"
mkdir -p "${BARN_USER_HOME_2}"
start_barn "${HOME_TEST_ROOT}/barn-env.log" -Duser.home="${BARN_USER_HOME_2}" -Dquarkus.profile=local \
  && echo "PASS: Barn started with WANAKU_HOME=${WANAKU_HOME}" || echo "FAIL: Barn did not start"
wait_http "${HOME_BARN_URL}/q/health/ready" 200 60 && echo "PASS: Barn is ready" || echo "FAIL: Barn is not ready"
```

**Expected result:**

- The output includes `PASS: Barn started with WANAKU_HOME=<WANAKU_HOME>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: Barn is ready`.
- The output contains no line starting with `FAIL:`.

### Test 3.2: The store and the log are in WANAKU_HOME

```bash run
[ -d "${WANAKU_HOME}/barn" ] && echo "PASS: store at ${WANAKU_HOME}/barn" || echo "FAIL: store not at ${WANAKU_HOME}/barn"
LOG_FILE="${WANAKU_HOME}/local/logs/wanaku-router.log"
[ -s "${LOG_FILE}" ] && echo "PASS: log file ${LOG_FILE} has content" || echo "FAIL: log file ${LOG_FILE} missing or empty"
```

**Expected result:**

- The output includes `PASS: store at <WANAKU_HOME>/barn`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: log file <LOG_FILE> has content`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 3.3: The default home was not used

```bash run
[ -e "${BARN_USER_HOME_2}/.wanaku" ] \
  && echo "FAIL: ${BARN_USER_HOME_2}/.wanaku exists — WANAKU_HOME was ignored" \
  || echo "PASS: default home not created"
```

**Expected result:**

- The output includes `PASS: default home not created`.
- The output contains no line starting with `FAIL:`.

### Step 3.4: Stop Barn

```bash run
stop_barn
unset WANAKU_HOME
echo "PASS: Barn stopped"
```

**Expected result:**

- The output includes `PASS: Barn stopped`.

## Phase 4: Barn System Property Override

### Test 4.1: Start Barn with -Dwanaku.home

```bash run
unset WANAKU_HOME
BARN_SYSPROP_HOME="${HOME_TEST_ROOT}/barn-sysprop-home"
start_barn "${HOME_TEST_ROOT}/barn-sysprop.log" -Duser.home="${BARN_USER_HOME_2}" -Dwanaku.home="${BARN_SYSPROP_HOME}" \
  && echo "PASS: Barn started with -Dwanaku.home" || echo "FAIL: Barn did not start"
```

**Expected result:**

- The output includes `PASS: Barn started with -Dwanaku.home`.
- The output contains no line starting with `FAIL:`.

### Test 4.2: The store is under the system property path

```bash run
[ -d "${BARN_SYSPROP_HOME}/barn" ] && echo "PASS: store at ${BARN_SYSPROP_HOME}/barn" \
  || echo "FAIL: store not at ${BARN_SYSPROP_HOME}/barn"
```

**Expected result:**

- The output includes `PASS: store at <BARN_SYSPROP_HOME>/barn`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Step 4.3: Stop Barn

```bash run
stop_barn
echo "PASS: Barn stopped"
```

**Expected result:**

- The output includes `PASS: Barn stopped`.

## Phase 5: Barn Precedence (System Property Wins)

### Test 5.1: Start Barn with both overrides

```bash run
PRECEDENCE_ENV_DIR="${HOME_TEST_ROOT}/precedence-env"
PRECEDENCE_SYSPROP_DIR="${HOME_TEST_ROOT}/precedence-sysprop"
export WANAKU_HOME="${PRECEDENCE_ENV_DIR}"
start_barn "${HOME_TEST_ROOT}/barn-precedence.log" -Duser.home="${BARN_USER_HOME_2}" -Dwanaku.home="${PRECEDENCE_SYSPROP_DIR}" \
  && echo "PASS: Barn started with both overrides" || echo "FAIL: Barn did not start"
```

**Expected result:**

- The output includes `PASS: Barn started with both overrides`.
- The output contains no line starting with `FAIL:`.

### Test 5.2: The system property path wins

```bash run
[ -d "${PRECEDENCE_SYSPROP_DIR}/barn" ] && echo "PASS: store under the system property path" \
  || echo "FAIL: store not under ${PRECEDENCE_SYSPROP_DIR}"
[ -e "${PRECEDENCE_ENV_DIR}/barn" ] && echo "FAIL: store also under the WANAKU_HOME path" \
  || echo "PASS: WANAKU_HOME path not used"
```

**Expected result:**

- The output includes `PASS: store under the system property path`.
- The output includes `PASS: WANAKU_HOME path not used`.
- The output contains no line starting with `FAIL:`.

### Step 5.3: Stop Barn

```bash run
stop_barn
unset WANAKU_HOME
echo "PASS: Barn stopped"
```

**Expected result:**

- The output includes `PASS: Barn stopped`.

## Phase 6: Barn Data Survives a Restart

### Test 6.1: Create a data store in a fresh home

```bash run
export WANAKU_HOME="${HOME_TEST_ROOT}/barn-persist-home"
start_barn "${HOME_TEST_ROOT}/barn-persist-1.log" -Duser.home="${BARN_USER_HOME_2}" || echo "FAIL: Barn did not start"
wait_http "${HOME_BARN_URL}/q/health/ready" 200 60 || echo "FAIL: Barn is not ready"
DS_FILE="${HOME_TEST_ROOT}/home-entry.txt"
echo "wanaku-home-resolution" > "${DS_FILE}"
java -jar "${CLI_JAR}" data-store add --host "${HOME_BARN_URL}" --no-auth \
  --name "${HOME_NS}-entry" --read-from-file "${DS_FILE}"
[ $? -eq 0 ] && echo "PASS: data store ${HOME_NS}-entry created" || echo "FAIL: data store not created"
stop_barn
```

**Expected result:**

- The output includes `PASS: data store <HOME_NS>-entry created`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 6.2: Restart with the same home and read the data store

```bash run
start_barn "${HOME_TEST_ROOT}/barn-persist-2.log" -Duser.home="${BARN_USER_HOME_2}" || echo "FAIL: Barn did not restart"
java -jar "${CLI_JAR}" data-store list --host "${HOME_BARN_URL}" --no-auth --plain 2>/dev/null \
  | awk '{print $2}' | grep -qx "${HOME_NS}-entry" \
  && echo "PASS: data store survived the restart" || echo "FAIL: data store lost after the restart"
```

**Expected result:**

- The output includes `PASS: data store survived the restart`.
- The output contains no line starting with `FAIL:`.

### Test 6.3: Barn is ready after the restart

Expected result: `/q/health/ready` returns `200`.

On 2026-10-18 (Barn `0.3.0-SNAPSHOT`) this test failed. After a restart with an existing store, the readiness check
stays `DOWN` with `"failedCaches": "catalog-version,catalog-version-counter"`. The caches start only when a request
uses them. The data is intact (Test 6.2). In Kubernetes, a readiness probe on `/q/health/ready` would keep the
restarted pod out of service. Report a failure of this test as a Barn defect.

```bash run
if wait_http "${HOME_BARN_URL}/q/health/ready" 200 60; then
  echo "PASS: Barn is ready after the restart"
else
  echo "FAIL: Barn is not ready after the restart: $(curl -s "${HOME_BARN_URL}/q/health/ready" | jq -c '[.checks[] | select(.status == "DOWN")]')"
fi
stop_barn
unset WANAKU_HOME
```

**Expected result:**

- The output includes `PASS: Barn is ready after the restart`.
- The output contains no line starting with `FAIL:`.

## Phase 7: Barn Negative Tests

### Test 7.1: Empty WANAKU_HOME falls back to the default

```bash run
export WANAKU_HOME=""
BARN_USER_HOME_3="${HOME_TEST_ROOT}/barn-user-home-3"
mkdir -p "${BARN_USER_HOME_3}"
start_barn "${HOME_TEST_ROOT}/barn-empty.log" -Duser.home="${BARN_USER_HOME_3}" \
  && echo "PASS: Barn started with an empty WANAKU_HOME" || echo "FAIL: Barn did not start"
[ -d "${BARN_USER_HOME_3}/.wanaku/barn" ] && echo "PASS: empty WANAKU_HOME ignored, default home used" \
  || echo "FAIL: store not at ${BARN_USER_HOME_3}/.wanaku/barn"
stop_barn
unset WANAKU_HOME
```

**Expected result:**

- The output includes `PASS: Barn started with an empty WANAKU_HOME`.
- The output includes `PASS: empty WANAKU_HOME ignored, default home used`.
- The output contains no line starting with `FAIL:`.

### Test 7.2: WANAKU_HOME below a regular file stops Barn

Barn cannot create its store. It must exit with an error. It must not run without a store.

```bash run
NOT_A_DIR="${HOME_TEST_ROOT}/not-a-directory"
: > "${NOT_A_DIR}"
export WANAKU_HOME="${NOT_A_DIR}/home"
java -Dquarkus.http.port="${HOME_BARN_PORT}" -Djgroups.mcast_port="${HOME_BARN_JGROUPS_PORT}" \
  -jar "${BARN_JAR}" > "${HOME_TEST_ROOT}/barn-invalid.log" 2>&1 &
BARN_PID=$!
i=0
while kill -0 "${BARN_PID}" 2>/dev/null && [ "${i}" -lt 60 ]; do i=$((i + 1)); sleep 1; done
if kill -0 "${BARN_PID}" 2>/dev/null; then
  echo "FAIL: Barn still runs with an invalid WANAKU_HOME"
  stop_barn
else
  wait "${BARN_PID}"; EXIT_CODE=$?
  grep -q "Not a directory" "${HOME_TEST_ROOT}/barn-invalid.log" && [ "${EXIT_CODE}" -ne 0 ] \
    && echo "PASS: Barn exited (code ${EXIT_CODE}) and reported 'Not a directory'" \
    || echo "FAIL: Barn exited with code ${EXIT_CODE} without the expected error"
  BARN_PID=""
fi
unset WANAKU_HOME
```

**Expected result:**

- The output includes `PASS: Barn exited`.
- The output contains no line starting with `FAIL:`.

## Phase 8: CLI Home Resolution

The CLI uses the Wanaku home for its credentials file and its cloud target. `wanaku auth login --api-token` stores a
token without a network call. Every CLI command creates the home directory, because the credentials store creates its
parent directory.

### Test 8.1: WANAKU_HOME selects the credentials file

```bash run
CLI_USER_HOME="${HOME_TEST_ROOT}/cli-user-home"
CLI_ENV_HOME="${HOME_TEST_ROOT}/cli-env-home"
mkdir -p "${CLI_USER_HOME}"
WANAKU_HOME="${CLI_ENV_HOME}" java -Duser.home="${CLI_USER_HOME}" -jar "${CLI_JAR}" auth login --api-token=home-token-env
[ $? -eq 0 ] && [ -f "${CLI_ENV_HOME}/credentials" ] \
  && echo "PASS: credentials stored at ${CLI_ENV_HOME}/credentials" || echo "FAIL: credentials not under WANAKU_HOME"
[ -e "${CLI_USER_HOME}/.wanaku" ] && echo "FAIL: default home also created" || echo "PASS: default home not used"
```

**Expected result:**

- The output includes `PASS: credentials stored at <CLI_ENV_HOME>/credentials`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: default home not used`.
- The output contains no line starting with `FAIL:`.

### Test 8.2: auth status reports the resolved file

```bash run
OUTPUT=$(WANAKU_HOME="${CLI_ENV_HOME}" java -jar "${CLI_JAR}" auth status 2>/dev/null)
echo "${OUTPUT}" | grep -q "Credentials File: ${CLI_ENV_HOME}/credentials" \
  && echo "PASS: auth status shows ${CLI_ENV_HOME}/credentials" || echo "FAIL: unexpected status: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS: auth status shows <CLI_ENV_HOME>/credentials`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 8.3: The credentials file is private

```bash run
MODE=$(stat -c '%a' "${CLI_ENV_HOME}/credentials" 2>/dev/null || stat -f '%Lp' "${CLI_ENV_HOME}/credentials")
[ "${MODE}" = "600" ] && echo "PASS: credentials file mode 600" || echo "FAIL: credentials file mode ${MODE}"
```

**Expected result:**

- The output includes `PASS: credentials file mode 600`.
- The output contains no line starting with `FAIL:`.

### Test 8.4: Another home does not see the token (negative)

```bash run
OUTPUT=$(WANAKU_HOME="${HOME_TEST_ROOT}/cli-other-home" java -jar "${CLI_JAR}" auth token --get 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "No valid API token" \
  && echo "PASS: no token in another home (exit ${EXIT_CODE})" || echo "FAIL: token visible from another home"
```

**Expected result:**

- The output includes `PASS: no token in another home`.
- The output contains no line starting with `FAIL:`.

### Test 8.5: The system property wins over WANAKU_HOME

```bash run
CLI_SYSPROP_HOME="${HOME_TEST_ROOT}/cli-sysprop-home"
CLI_ENV_HOME_2="${HOME_TEST_ROOT}/cli-env-home-2"
WANAKU_HOME="${CLI_ENV_HOME_2}" java -Duser.home="${CLI_USER_HOME}" -Dwanaku.home="${CLI_SYSPROP_HOME}" \
  -jar "${CLI_JAR}" auth login --api-token=home-token-sysprop > /dev/null 2>&1
[ -f "${CLI_SYSPROP_HOME}/credentials" ] && [ ! -e "${CLI_ENV_HOME_2}/credentials" ] \
  && echo "PASS: -Dwanaku.home wins" || echo "FAIL: precedence not respected"
```

**Expected result:**

- The output includes `PASS: -Dwanaku.home wins`.
- The output contains no line starting with `FAIL:`.

### Test 8.6: Default and empty WANAKU_HOME use ${user.home}/.wanaku

```bash run
for VALUE in unset empty; do
  CLI_DEFAULT_HOME="${HOME_TEST_ROOT}/cli-default-${VALUE}"
  mkdir -p "${CLI_DEFAULT_HOME}"
  if [ "${VALUE}" = "unset" ]; then
    env -u WANAKU_HOME java -Duser.home="${CLI_DEFAULT_HOME}" -jar "${CLI_JAR}" auth login --api-token=t > /dev/null 2>&1
  else
    WANAKU_HOME="" java -Duser.home="${CLI_DEFAULT_HOME}" -jar "${CLI_JAR}" auth login --api-token=t > /dev/null 2>&1
  fi
  [ -f "${CLI_DEFAULT_HOME}/.wanaku/credentials" ] && echo "PASS: ${VALUE} WANAKU_HOME uses the default home" \
    || echo "FAIL: ${VALUE} WANAKU_HOME did not use ${CLI_DEFAULT_HOME}/.wanaku"
done
```

**Expected result:**

- The output includes `PASS: unset WANAKU_HOME uses the default home`.
- The output includes `PASS: empty WANAKU_HOME uses the default home`.
- The output contains no line starting with `FAIL:`.

### Test 8.7: WANAKU_CREDENTIALS wins over the home

```bash run
CLI_EXPLICIT="${HOME_TEST_ROOT}/cli-explicit/creds"
WANAKU_HOME="${HOME_TEST_ROOT}/cli-env-home-3" WANAKU_CREDENTIALS="${CLI_EXPLICIT}" \
  java -jar "${CLI_JAR}" auth login --api-token=home-token-explicit > /dev/null 2>&1
[ -f "${CLI_EXPLICIT}" ] && [ ! -e "${HOME_TEST_ROOT}/cli-env-home-3/credentials" ] \
  && echo "PASS: WANAKU_CREDENTIALS selects the file" || echo "FAIL: WANAKU_CREDENTIALS ignored"
```

**Expected result:**

- The output includes `PASS: WANAKU_CREDENTIALS selects the file`.
- The output contains no line starting with `FAIL:`.

### Test 8.8: The cloud target is stored in the home

```bash run
WANAKU_HOME="${CLI_ENV_HOME}" java -jar "${CLI_JAR}" cloud target set --context home-ctx -n home-ns --router home-router
grep -q "^router=home-router$" "${CLI_ENV_HOME}/cloud.properties" 2>/dev/null \
  && echo "PASS: cloud target stored in ${CLI_ENV_HOME}/cloud.properties" || echo "FAIL: cloud.properties not written"
OUTPUT=$(WANAKU_HOME="${CLI_ENV_HOME}" java -jar "${CLI_JAR}" cloud target show 2>/dev/null)
echo "${OUTPUT}" | grep -q "router: home-router" && echo "PASS: cloud target show reads it back" \
  || echo "FAIL: unexpected output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS: cloud target stored in <CLI_ENV_HOME>/cloud.properties`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: cloud target show reads it back`.
- The output contains no line starting with `FAIL:`.

### Test 8.9: Logout removes the credentials file

```bash run
WANAKU_HOME="${CLI_ENV_HOME}" java -jar "${CLI_JAR}" auth logout > /dev/null 2>&1
[ ! -e "${CLI_ENV_HOME}/credentials" ] && echo "PASS: credentials removed by logout" || echo "FAIL: credentials still present"
```

**Expected result:**

- The output includes `PASS: credentials removed by logout`.
- The output contains no line starting with `FAIL:`.

## Phase 9: Wanaku Server Persistence Path

Wanaku Server is the replacement of the Java router. It does not read `WANAKU_HOME`. This phase uses its own
server instance (helpers of Step 1.4).

### Test 9.1: Default path is $HOME/.wanaku/server

```bash run
unset WANAKU_PERSIST_PATH WANAKU_PERSIST_BACKEND
SERVER_HOME="${HOME_TEST_ROOT}/server-home"
mkdir -p "${SERVER_HOME}"
start_server "${HOME_TEST_ROOT}/server-default.log" "${SERVER_HOME}" || echo "FAIL: server did not start"
java -jar "${CLI_JAR}" namespaces create "${HOME_NS}-default" --host "${WANAKU_SERVER_URL}" --no-auth
REGISTRY="${SERVER_HOME}/.wanaku/server/registry.json"
i=0; while [ ! -f "${REGISTRY}" ] && [ "${i}" -lt 10 ]; do i=$((i + 1)); sleep 1; done
jq -e --arg ns "${HOME_NS}-default" '[.namespaces[].name] | index($ns) != null' "${REGISTRY}" > /dev/null 2>&1 \
  && echo "PASS: registry.json written to ${REGISTRY}" || echo "FAIL: ${REGISTRY} missing or without the namespace"
stop_server
```

**Expected result:**

- The output includes `PASS: registry.json written to <REGISTRY>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 9.2: The server ignores WANAKU_HOME

```bash run
SERVER_HOME_2="${HOME_TEST_ROOT}/server-home-2"
SERVER_WANAKU_HOME="${HOME_TEST_ROOT}/server-wanaku-home"
mkdir -p "${SERVER_HOME_2}" "${SERVER_WANAKU_HOME}"
export WANAKU_HOME="${SERVER_WANAKU_HOME}"
start_server "${HOME_TEST_ROOT}/server-wanaku-home.log" "${SERVER_HOME_2}" || echo "FAIL: server did not start"
java -jar "${CLI_JAR}" namespaces create "${HOME_NS}-ignored" --host "${WANAKU_SERVER_URL}" --no-auth > /dev/null 2>&1
i=0; while [ ! -f "${SERVER_HOME_2}/.wanaku/server/registry.json" ] && [ "${i}" -lt 10 ]; do i=$((i + 1)); sleep 1; done
stop_server
unset WANAKU_HOME
[ -f "${SERVER_HOME_2}/.wanaku/server/registry.json" ] && [ -z "$(ls -A "${SERVER_WANAKU_HOME}")" ] \
  && echo "PASS: registry under \$HOME/.wanaku/server, WANAKU_HOME untouched" || echo "FAIL: server used WANAKU_HOME"
```

**Expected result:**

- The output includes `PASS: registry under $HOME/.wanaku/server, WANAKU_HOME untouched`.
- The output contains no line starting with `FAIL:`.

### Test 9.3: WANAKU_PERSIST_PATH selects the directory

```bash run
export WANAKU_PERSIST_BACKEND=file
export WANAKU_PERSIST_PATH="${HOME_TEST_ROOT}/server-data"
start_server "${HOME_TEST_ROOT}/server-data-1.log" "${SERVER_HOME}" || echo "FAIL: server did not start"
java -jar "${CLI_JAR}" namespaces create "${HOME_NS}" --host "${WANAKU_SERVER_URL}" --no-auth
[ $? -eq 0 ] && echo "PASS: namespace ${HOME_NS} created" || echo "FAIL: namespace not created"
```

**Expected result:**

- The output includes `PASS: namespace <HOME_NS> created`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Step 9.4: Register a forward

Follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md) with `TEST_MCP_NAMESPACE="${HOME_NS}"`. It
uses `WANAKU_SERVER_URL` of this plan. After completion, `TEST_MCP_URL`, `TEST_MCP_PID` and `TEST_MCP_FORWARD` must be
set.

**Expected result:**

- The invoked procedure completes successfully (inferred).

### Test 9.5: registry.json contains the namespace, the forward and the tools

```bash run
REGISTRY="${WANAKU_PERSIST_PATH}/registry.json"
i=0
until jq -e --arg f "${TEST_MCP_FORWARD}" '[.forwards[].name] | index($f) != null' "${REGISTRY}" > /dev/null 2>&1; do
  i=$((i + 1)); [ "${i}" -ge 10 ] && break; sleep 1
done
jq -e --arg ns "${HOME_NS}" --arg f "${TEST_MCP_FORWARD}" \
  '([.namespaces[].name] | index($ns) != null) and ([.forwards[].name] | index($f) != null) and (.tools | length > 0)' \
  "${REGISTRY}" > /dev/null \
  && echo "PASS: registry.json has the namespace, the forward and $(jq '.tools | length' "${REGISTRY}") tools" \
  || echo "FAIL: registry.json incomplete"
```

**Expected result:**

- The output includes `PASS: registry.json has the namespace, the forward and`.
- The output contains no line starting with `FAIL:`.

### Test 9.6: Change state and stop the server in an orderly way

The enable/disable route has no CLI command, so `curl` is used. The namespace is created immediately before the
shutdown, so the final flush must write it.

```bash run
curl -s -o /dev/null -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/echo/disable"
java -jar "${CLI_JAR}" namespaces create "${HOME_NS}-late" --host "${WANAKU_SERVER_URL}" --no-auth > /dev/null 2>&1
stop_server
grep -q "registry persistence flushed during shutdown" "${HOME_TEST_ROOT}/server-data-1.log" \
  && echo "PASS: registry flushed during the orderly shutdown" || echo "FAIL: no flush message in the log"
```

**Expected result:**

- The output includes `PASS: registry flushed during the orderly shutdown`.
- The output contains no line starting with `FAIL:`.

### Test 9.7: The registry is reloaded after the restart

```bash run
start_server "${HOME_TEST_ROOT}/server-data-2.log" "${SERVER_HOME}" || echo "FAIL: server did not restart"
NAMESPACES=$(java -jar "${CLI_JAR}" namespaces list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null | awk 'NR>1 {print $1}')
for NS in "${HOME_NS}" "${HOME_NS}-late"; do
  echo "${NAMESPACES}" | grep -qx "${NS}" && echo "PASS: namespace ${NS} reloaded" || echo "FAIL: namespace ${NS} lost"
done
java -jar "${CLI_JAR}" forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
  | awk 'NR>1 {print $1}' | grep -qx "${TEST_MCP_FORWARD}" \
  && echo "PASS: forward ${TEST_MCP_FORWARD} reloaded" || echo "FAIL: forward lost"
ENABLED=$(curl -s "${WANAKU_SERVER_URL}/api/v1/tools/echo" | jq '.data.enabled')
[ "${ENABLED}" = "false" ] && echo "PASS: tool 'echo' is still disabled" || echo "FAIL: tool 'echo' enabled=${ENABLED}"
```

**Expected result:**

- The output includes `PASS: namespace <HOME_NS> reloaded`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: namespace <HOME_NS>-late reloaded`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: forward <TEST_MCP_FORWARD> reloaded`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: tool 'echo' is still disabled`.
- The output contains no line starting with `FAIL:`.

### Step 9.8: Stop the reference MCP server and the server

Use the [Shutdown](common/start-test-mcp-server.md#shutdown) section of
[common/start-test-mcp-server.md](common/start-test-mcp-server.md). Then stop the server:

```bash run
stop_server
echo "PASS: server stopped"
```

**Expected result:**

- The output includes `PASS: server stopped`.

### Test 9.9: WANAKU_PERSIST_BACKEND=none writes nothing

```bash run
export WANAKU_PERSIST_BACKEND=none
unset WANAKU_PERSIST_PATH
SERVER_HOME_NONE="${HOME_TEST_ROOT}/server-home-none"
mkdir -p "${SERVER_HOME_NONE}"
start_server "${HOME_TEST_ROOT}/server-none-1.log" "${SERVER_HOME_NONE}" || echo "FAIL: server did not start"
java -jar "${CLI_JAR}" namespaces create "${HOME_NS}-none" --host "${WANAKU_SERVER_URL}" --no-auth > /dev/null 2>&1
stop_server
[ -z "$(ls -A "${SERVER_HOME_NONE}")" ] && echo "PASS: nothing written with persistence disabled" \
  || echo "FAIL: files written: $(find "${SERVER_HOME_NONE}" -type f)"
start_server "${HOME_TEST_ROOT}/server-none-2.log" "${SERVER_HOME_NONE}" || echo "FAIL: server did not restart"
java -jar "${CLI_JAR}" namespaces list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
  | awk 'NR>1 {print $1}' | grep -qx "${HOME_NS}-none" \
  && echo "FAIL: namespace survived without persistence" || echo "PASS: namespace not restored without persistence"
stop_server
unset WANAKU_PERSIST_BACKEND
```

**Expected result:**

- The output includes `PASS: nothing written with persistence disabled`.
- The output includes `PASS: namespace not restored without persistence`.
- The output contains no line starting with `FAIL:`.

### Test 9.10: Unusable WANAKU_PERSIST_PATH (negative)

The server starts, serves requests and logs each failed write. It does not exit.

```bash run
export WANAKU_PERSIST_BACKEND=file
export WANAKU_PERSIST_PATH="${NOT_A_DIR}/server"
start_server "${HOME_TEST_ROOT}/server-invalid.log" "${SERVER_HOME}" \
  && echo "PASS: server started with an unusable persistence path" || echo "FAIL: server did not start"
java -jar "${CLI_JAR}" namespaces create "${HOME_NS}-invalid" --host "${WANAKU_SERVER_URL}" --no-auth > /dev/null 2>&1
i=0; until grep -q "failed to persist registry" "${HOME_TEST_ROOT}/server-invalid.log" || [ "${i}" -ge 10 ]; do i=$((i + 1)); sleep 1; done
grep -q "failed to persist registry" "${HOME_TEST_ROOT}/server-invalid.log" \
  && echo "PASS: write failure logged" || echo "FAIL: no write failure in the log"
stop_server
```

**Expected result:**

- The output includes `PASS: server started with an unusable persistence path`.
- The output includes `PASS: write failure logged`.
- The output contains no line starting with `FAIL:`.

### Test 9.11: Corrupt registry.json (negative)

The server logs the load failure and starts with an empty registry. It does not overwrite the file until the
registry changes.

```bash run
export WANAKU_PERSIST_PATH="${HOME_TEST_ROOT}/server-corrupt"
mkdir -p "${WANAKU_PERSIST_PATH}"
echo '{not json' > "${WANAKU_PERSIST_PATH}/registry.json"
start_server "${HOME_TEST_ROOT}/server-corrupt.log" "${SERVER_HOME}" \
  && echo "PASS: server started with a corrupt registry.json" || echo "FAIL: server did not start"
grep -q "failed to load persisted registry" "${HOME_TEST_ROOT}/server-corrupt.log" \
  && echo "PASS: load failure logged" || echo "FAIL: no load failure in the log"
stop_server
[ "$(cat "${WANAKU_PERSIST_PATH}/registry.json")" = "{not json" ] \
  && echo "PASS: corrupt file kept unchanged" || echo "FAIL: corrupt file was overwritten"
unset WANAKU_PERSIST_PATH WANAKU_PERSIST_BACKEND
```

**Expected result:**

- The output includes `PASS: server started with a corrupt registry.json`.
- The output includes `PASS: load failure logged`.
- The output includes `PASS: corrupt file kept unchanged`.
- The output contains no line starting with `FAIL:`.

## Phase 10: Default Home Isolation

### Test 10.1: The real default home is unchanged

```bash run
REAL_HOME_SNAPSHOT_AFTER="${HOME_TEST_ROOT}/real-home-after.txt"
if [ -d "${HOME}/.wanaku" ]; then
  find "${HOME}/.wanaku" -type f -exec ls -l --time-style=+%s {} \; 2>/dev/null | sort > "${REAL_HOME_SNAPSHOT_AFTER}"
else
  : > "${REAL_HOME_SNAPSHOT_AFTER}"
fi
if cmp -s "${REAL_HOME_SNAPSHOT_BEFORE}" "${REAL_HOME_SNAPSHOT_AFTER}"; then
  echo "PASS: ${HOME}/.wanaku unchanged by the test run"
else
  echo "FAIL: ${HOME}/.wanaku changed:"
  diff "${REAL_HOME_SNAPSHOT_BEFORE}" "${REAL_HOME_SNAPSHOT_AFTER}"
fi
```

The snapshot uses GNU `ls --time-style`. On macOS, use `stat -f '%m %z %N'` instead.

**Expected result:**

- The output includes `PASS: <HOME>/.wanaku unchanged by the test run`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

## Phase 11: Cleanup

All cleanup steps are idempotent.

### Step 11.1: Stop the remaining processes

```bash run
stop_barn 2>/dev/null || true
stop_server 2>/dev/null || true
if [ -n "${TEST_MCP_PID}" ]; then
  kill "${TEST_MCP_PID}" 2>/dev/null || true
  wait "${TEST_MCP_PID}" 2>/dev/null || true
fi
echo "PASS: all test processes stopped (or already absent)"
```

**Expected result:**

- The output includes `PASS: all test processes stopped (or already absent)`.

### Step 11.2: Remove the temporary directories

```bash run
rm -rf "${HOME_TEST_ROOT}" 2>/dev/null || true
echo "PASS: ${HOME_TEST_ROOT} removed"
```

**Expected result:**

- The output includes `PASS: <HOME_TEST_ROOT> removed`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.

### Step 11.3: Unset the test variables

```bash run
unset WANAKU_HOME WANAKU_CREDENTIALS WANAKU_PERSIST_PATH WANAKU_PERSIST_BACKEND HOME_TEST_ROOT
echo "PASS: test environment variables cleared"
```

**Expected result:**

- The output includes `PASS: test environment variables cleared`.

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | 0.1 | Verify required tools | Critical |
| 0 | 0.2 | Test root is not the default home | Critical |
| 0 | 0.3 | Record the state of the real default home | High |
| 0 | 0.4 | Verify the ports are free | Critical |
| 1 | 1.1 | Build Barn and the CLI | Critical |
| 1 | 1.2 | Build Wanaku Server | Critical |
| 1 | 1.3 | Write the pipeline configuration | Critical |
| 1 | 1.4 | Define the helper functions | Critical |
| 2 | 2.1 | Start Barn without overrides | Critical |
| 2 | 2.2 | Barn is ready | Critical |
| 2 | 2.3 | Store in the default home | Critical |
| 2 | 2.4 | CLI can connect | High |
| 2 | 2.5 | Stop Barn | High |
| 3 | 3.1 | Start Barn with WANAKU_HOME | Critical |
| 3 | 3.2 | Store and log in WANAKU_HOME | Critical |
| 3 | 3.3 | Default home not used | High |
| 3 | 3.4 | Stop Barn | High |
| 4 | 4.1 | Start Barn with -Dwanaku.home | Critical |
| 4 | 4.2 | Store under the system property path | Critical |
| 4 | 4.3 | Stop Barn | High |
| 5 | 5.1 | Start Barn with both overrides | Critical |
| 5 | 5.2 | System property path wins | Critical |
| 5 | 5.3 | Stop Barn | High |
| 6 | 6.1 | Create a data store in a fresh home | High |
| 6 | 6.2 | Data store survives a restart | Critical |
| 6 | 6.3 | Barn is ready after the restart | High |
| 7 | 7.1 | Empty WANAKU_HOME falls back to the default | High |
| 7 | 7.2 | WANAKU_HOME below a regular file stops Barn (negative) | High |
| 8 | 8.1 | WANAKU_HOME selects the credentials file | Critical |
| 8 | 8.2 | auth status reports the resolved file | Medium |
| 8 | 8.3 | Credentials file is private | High |
| 8 | 8.4 | Another home does not see the token (negative) | High |
| 8 | 8.5 | System property wins over WANAKU_HOME | High |
| 8 | 8.6 | Default and empty WANAKU_HOME | High |
| 8 | 8.7 | WANAKU_CREDENTIALS wins over the home | Medium |
| 8 | 8.8 | Cloud target stored in the home | Medium |
| 8 | 8.9 | Logout removes the credentials file | Medium |
| 9 | 9.1 | Server default path is $HOME/.wanaku/server | Critical |
| 9 | 9.2 | Server ignores WANAKU_HOME | High |
| 9 | 9.3 | WANAKU_PERSIST_PATH selects the directory | Critical |
| 9 | 9.4 | Register a forward | Critical |
| 9 | 9.5 | registry.json contains namespace, forward and tools | Critical |
| 9 | 9.6 | Change state and stop in an orderly way | Critical |
| 9 | 9.7 | Registry reloaded after the restart | Critical |
| 9 | 9.8 | Stop the reference MCP server and the server | High |
| 9 | 9.9 | WANAKU_PERSIST_BACKEND=none writes nothing | High |
| 9 | 9.10 | Unusable WANAKU_PERSIST_PATH (negative) | High |
| 9 | 9.11 | Corrupt registry.json (negative) | High |
| 10 | 10.1 | Real default home unchanged | High |
| 11 | 11.1 | Stop the remaining processes | Critical |
| 11 | 11.2 | Remove the temporary directories | Critical |
| 11 | 11.3 | Unset the test variables | Medium |
| S | S.1 | Set environment variables | Setup |
