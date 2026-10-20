# Common: Run the Camel Integration Capability Locally

Reusable steps for running a local [Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability)
(CIC) runtime that serves a Wanaku Barn service catalog as MCP tools, and for registering it with Wanaku Server.

Wanaku Server does not run Camel routes. A deployed service catalog becomes available to MCP clients in three steps:

1. Barn stores the catalog (`wanaku service deploy` or `wanaku service template instantiate`).
2. A CIC runtime downloads the catalog from Barn and serves the tagged routes as MCP tools.
3. The CIC MCP endpoint is registered with Wanaku Server as a forward. Wanaku Server then discovers the tools.

These steps use the CIC container image with the same environment contract that the operator uses for a
`WanakuIntegration` resource (see `IntegrationResourceFactory` in `apps/wanaku-operator`). This keeps local runs and
OpenShift runs equivalent.

## Prerequisites

- Wanaku Server started (see [start-server.md](start-server.md)). `WANAKU_SERVER_URL` must be set.
- Wanaku Barn started (see [start-barn.md](start-barn.md)). `WANAKU_BARN_URL` must be set.
- The service catalog is deployed to Barn.
- A container runtime (see [container-runtime.md](container-runtime.md)). `CONTAINER_RUNTIME` must be set.
- `CLI_JAR` set

The steps use host networking, so the container reaches Barn and Wanaku Server on `localhost`. Host networking works
on Linux. On macOS and Windows, set `CIC_BARN_URL` and `CIC_SERVER_URL` to `http://host.containers.internal:<port>`
(Podman) or `http://host.docker.internal:<port>` (Docker), and publish the MCP port with `-p` instead.

## Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CIC_IMAGE` | CIC container image | `quay.io/wanaku/camel-integration-capability:latest` |
| `CIC_NAME` | Container name, and forward name in Wanaku Server | `cic-test` |
| `CIC_PORT` | MCP port of the runtime | `9190` |
| `CIC_CATALOG` | Name of the service catalog in Barn | _(required)_ |
| `CIC_CATALOG_SYSTEM` | System (service) inside the catalog to serve | _(required)_ |
| `CIC_NAMESPACE` | Wanaku namespace for the forward | `default` |
| `CIC_SELF_REGISTER` | `true` lets the runtime register itself with Wanaku Server. `false` registers it with `wanaku forwards add`. | `false` |
| `CIC_BARN_URL` | Barn URL as the container sees it | `${WANAKU_BARN_URL}` |
| `CIC_SERVER_URL` | Wanaku Server management URL as the container sees it | `${WANAKU_SERVER_URL}` |
| `CIC_EXTRA_ENV` | Extra `-e NAME=value` arguments, for example API keys that the routes read | _(empty)_ |

## Output variables

| Variable | Description |
|----------|-------------|
| `CIC_MCP_URL` | MCP endpoint of the runtime (`http://localhost:9190/mcp`) |

## Steps

### 1. Start the runtime

```bash
export CIC_IMAGE="${CIC_IMAGE:-quay.io/wanaku/camel-integration-capability:latest}"
export CIC_NAME="${CIC_NAME:-cic-test}"
export CIC_PORT="${CIC_PORT:-9190}"
export CIC_NAMESPACE="${CIC_NAMESPACE:-default}"
export CIC_SELF_REGISTER="${CIC_SELF_REGISTER:-false}"
export CIC_BARN_URL="${CIC_BARN_URL:-${WANAKU_BARN_URL}}"
export CIC_SERVER_URL="${CIC_SERVER_URL:-${WANAKU_SERVER_URL}}"
export CIC_MCP_URL="http://localhost:${CIC_PORT}/mcp"

if [ -z "${CIC_CATALOG}" ] || [ -z "${CIC_CATALOG_SYSTEM}" ]; then
  echo "FAIL: CIC_CATALOG and CIC_CATALOG_SYSTEM must be set"
  exit 1
fi

# CIC_EXTRA_ENV is not quoted on purpose: it holds zero or more "-e NAME=value" arguments
${CONTAINER_RUNTIME} run -d --name "${CIC_NAME}" --network host \
  -e REGISTRATION_URL="${CIC_BARN_URL}" \
  -e SERVICE_NAME="${CIC_NAME}" \
  -e SERVICE_CATALOG="${CIC_CATALOG}" \
  -e SERVICE_CATALOG_SYSTEM="${CIC_CATALOG_SYSTEM}" \
  -e MCP_PORT="${CIC_PORT}" \
  -e DATA_DIR=/tmp/cic-data \
  -e WANAKU_REGISTER="${CIC_SELF_REGISTER}" \
  -e WANAKU_URL="${CIC_SERVER_URL}" \
  -e WANAKU_NAMESPACE="${CIC_NAMESPACE}" \
  -e MCP_ADDRESS="${CIC_MCP_URL}" \
  ${CIC_EXTRA_ENV} \
  "${CIC_IMAGE}"
[ $? -eq 0 ] && echo "PASS: CIC container '${CIC_NAME}' started" \
  || { echo "FAIL: could not start the CIC container"; exit 1; }
```

### 2. Wait for the runtime to become ready

The runtime downloads the catalog and its dependencies before it is ready. The first start can take some minutes.

```bash
for i in $(seq 1 60); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${CIC_PORT}/observe/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: CIC runtime is ready (attempt ${i})"
    break
  fi
  if [ "${i}" -eq 60 ]; then
    echo "FAIL: CIC runtime not ready (last HTTP ${HTTP_CODE})"
    ${CONTAINER_RUNTIME} logs --tail 30 "${CIC_NAME}"
    exit 1
  fi
  sleep 3
done
```

### 3. Register the runtime with Wanaku Server

```bash
if [ "${CIC_SELF_REGISTER}" = "true" ]; then
  for i in $(seq 1 20); do
    java -jar "${CLI_JAR}" forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
      | grep -q "${CIC_NAME}" && break
    sleep 3
  done
else
  java -jar "${CLI_JAR}" forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
    --name "${CIC_NAME}" --service "${CIC_MCP_URL}" --namespace "${CIC_NAMESPACE}"
fi

java -jar "${CLI_JAR}" forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null \
  | grep -q "${CIC_NAME}" && echo "PASS: forward '${CIC_NAME}' is registered" \
  || { echo "FAIL: forward '${CIC_NAME}' is not registered"; exit 1; }
```

Wanaku Server keys tools by name across all namespaces and forwards. If another forward serves a tool with the same
name, the latest discovery replaces the entry. Use tool names that are unique on the server.

After this step, the catalog tools are in `wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth` and MCP clients
can call them at `${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp`.

## Shutdown

```bash
java -jar "${CLI_JAR}" forwards remove --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${CIC_NAME:-cic-test}" 2>/dev/null || true
${CONTAINER_RUNTIME} stop "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
${CONTAINER_RUNTIME} rm "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
echo "PASS: CIC runtime stopped and its forward removed"
```
