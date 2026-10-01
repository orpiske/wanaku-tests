# Infrastructure Auto-Remediation MCP Server

A mock MCP (Model Context Protocol) server for testing LLM intent-extraction and decision-making. Built with Quarkus and the `quarkus-mcp-server-http` extension.

## What it exposes

### Resources

| URI | Description |
|---|---|
| `config://policies/safety-limits` | Static JSON with `max_db_replicas` and `blocked_services` |
| `logs://{server_id}/syslog` | Dynamic template returning mock syslog entries for a given server |

### Tools

| Tool | Parameters | Behavior |
|---|---|---|
| `restartService` | `serverId`, `service` | Returns a simulated restart confirmation |
| `scaleDeployment` | `target`, `replicas` | Returns success if replicas <= 5; returns a policy-block error otherwise |
| `escalateTicket` | `reason`, `urgency` (low/medium/high) | Returns a mock ticket ID |

## Build

```shell
./mvnw clean install -B
```

Requires Java 21+.

## Run via HTTP transport

```shell
java -jar target/quarkus-app/quarkus-run.jar
```

The server starts on `http://localhost:8181` and exposes the MCP endpoint at `/mcp`.

## Container

Run these commands from `fixtures/test-mcp-server`:

```shell
./mvnw package -DskipTests
podman build -f src/main/docker/Dockerfile.jvm -t test-mcp-server:local .
podman run --rm -p 8181:8181 test-mcp-server:local
```

Docker can be used in place of Podman. The container listens on port 8181.

The `fixture-container.yml` GitHub Actions workflow builds the container for pull
requests and publishes `quay.io/wanaku/test-mcp-server:latest` from pushes to
`main` in `wanaku-ai/wanaku-tests`. It can also be triggered manually:

```shell
gh workflow run fixture-container.yml --ref main -R wanaku-ai/wanaku-tests
```

Each published build also has an immutable `sha-<full Git commit SHA>` tag. Manual
runs on other branches and runs in forks build the image without publishing it.

Publishing requires repository secrets `QUAY_USERNAME` and `QUAY_PASSWORD` for an
account with write access to `quay.io/wanaku/test-mcp-server`.

## OpenShift

Select the target project with `oc project <project>`, then deploy from
`fixtures/test-mcp-server`:

```shell
oc apply -f deploy/openshift.yaml
oc rollout status deployment/test-mcp-server
oc get route test-mcp-server -o jsonpath='https://{.spec.host}/mcp{"\n"}'
```

The Deployment uses `quay.io/wanaku/test-mcp-server:latest`. To use a specific
image tag or a different registry, change the `image` field in
`deploy/openshift.yaml` before applying it. For an existing deployment:

```shell
oc set image deployment/test-mcp-server test-mcp-server=<registry>/test-mcp-server:<tag>
oc rollout status deployment/test-mcp-server
```

The Service exposes port 8181 within the project, and the Route exposes `/mcp`
over HTTPS. OpenShift assigns the container's user ID. TCP probes check that the
HTTP listener is available because this fixture has no health endpoint.

When a new image is published under the same tag, restart the deployment to
pull it:

```shell
oc rollout restart deployment/test-mcp-server
oc rollout status deployment/test-mcp-server
```

### Claude Desktop configuration

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "infra-remediation": {
      "type": "streamable-http",
      "url": "http://localhost:8181/mcp"
    }
  }
}
```

### Claude Code configuration

Add to `.claude/settings.json`:

```json
{
  "mcpServers": {
    "infra-remediation": {
      "type": "streamable-http",
      "url": "http://localhost:8181/mcp"
    }
  }
}
```
