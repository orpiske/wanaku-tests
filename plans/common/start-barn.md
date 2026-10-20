# Procedure: Start Wanaku Barn Locally

Reusable steps for building and starting the Wanaku Barn backend locally, next to Wanaku Server.

Wanaku Barn is optional. Wanaku Server handles MCP routing, namespaces, forwards and the discovered tools, resources
and prompts. Barn adds the management APIs that Wanaku Server does not have: service catalogs, service templates, data
stores, kamelets, semantic router definitions, the Barn audit trail and backup/restore. Start Barn only when a plan
uses these APIs (for example `wanaku service ...` or `wanaku data-store ...`).

Barn does not enforce authentication and does not serve MCP. There is no OIDC re-augmentation step.

## Prerequisites

- Java 21+
- Maven 3.9+
- This repository checked out, with the current directory at the repository root
- Wanaku Server started (see [start-server.md](start-server.md)) when the plan uses the Barn admin UI plugin or
  registers deployed catalogs with the server

## Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `WANAKU_REPO_ROOT` | Path to this repository (wanaku-barn) | `.` |
| `WANAKU_BARN_PORT` | HTTP port of the Barn backend | `8180` |
| `WANAKU_BARN_MCAST_PORT` | JGroups multicast port. Use a different value for each Barn on the same host, or the instances join one Infinispan cluster. | `46655` |
| `WANAKU_BARN_HOME` | Wanaku home directory of Barn for this run (the store is in `${WANAKU_BARN_HOME}/barn`) | `$(mktemp -d)` |

## Output variables

| Variable | Description |
|----------|-------------|
| `VERSION` | Wanaku Barn version string from the build |
| `CLI_JAR` | Path to the CLI JAR |
| `BARN_JAR` | Path to the Barn backend runner JAR |
| `WANAKU_BARN_URL` | Barn base URL (`http://localhost:8180`). Use it as `--host` for `service`, `data-store` and `backup` commands. |
| `WANAKU_BARN_PID` | PID of the background Barn process |

## Phase 1: Start Wanaku Barn Locally

### Step 1: Build the project

```bash run
cd "${WANAKU_REPO_ROOT:-.}"
mvn -DskipTests clean package
```

**Expected result:**

- The verification output includes `PASS: <FILE> exists` for `CLI_JAR` and `BARN_JAR`.
- The verification output contains no line starting with `FAIL:`.
- The commands exit with code 0.

**Verification:**

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

### Step 2: Start Barn

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

**Expected result:**

- The output includes `Wanaku Barn started with PID` (inferred).

### Step 3: Wait for health

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

**Expected result:**

- The output includes `PASS: Wanaku Barn is healthy (attempt <i>)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step 4: Verify the CLI can connect

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

- The output includes `PASS: CLI can connect to Wanaku Barn`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

## Phase O: Optional: connect Barn to Wanaku Server

### Step O.1: Optional: connect Barn to Wanaku Server

The admin UI of Wanaku Server reaches Barn through the `wanaku-barn` plugin service. The operator writes the same
configuration (see `RouterResourceFactory.serverConfig`). Plans that use the Barn pages in the admin UI must start
Wanaku Server with this `wanaku.yaml` and install the Barn UI plugin (`apps/ui`) in the server plugins directory
(`${WANAKU_SERVER_DATA_DIR}/plugins`).

Append the section to the configuration file. Do not overwrite it, because the file can already contain the test
governance configuration from [start-server.md](start-server.md), step 2b:

```bash
export WANAKU_SERVER_CONFIG="${WANAKU_SERVER_CONFIG:-${WANAKU_SERVER_DATA_DIR}/wanaku.yaml}"
cat >> "${WANAKU_SERVER_CONFIG}" <<EOF
plugins:
  - id: wanaku-barn
    services:
      wanaku-barn-api:
        target: ${WANAKU_BARN_URL}
EOF
```

Then start the server (see [start-server.md](start-server.md), step 3). Step 3 passes `WANAKU_SERVER_CONFIG` with
`--wanaku-config`. Verify the proxy route:

```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  "${WANAKU_SERVER_URL}/api/plugins/wanaku-barn/wanaku-barn-api/api/v1/service-catalog")
[ "${HTTP_CODE}" = "200" ] && echo "PASS: Wanaku Server proxies requests to Barn" \
  || echo "FAIL: plugin proxy returned HTTP ${HTTP_CODE}"
```

**Expected result:**

- When the plan uses the Barn admin UI plugin, the output includes `PASS: Wanaku Server proxies requests to Barn`; otherwise this optional connection is not required.
- The output contains no line starting with `FAIL:`.

## Restart

A Barn that restarts on an existing store keeps its data, but `/q/health/ready` can stay `DOWN` (caches
`catalog-version` and `catalog-version-counter`). This is a known defect. Plans that restart Barn must report it
instead of waiting for readiness indefinitely.

## Shutdown

The entry plan runs the original shutdown block in its local cleanup unit.

Do **not** use `kill -9`. It bypasses shutdown hooks and can leave the Infinispan store in an inconsistent state.
