# Wanaku Integration Tests

Integration test framework for [Wanaku](https://github.com/wanaku-ai/wanaku) — an MCP Router that connects AI-enabled applications via the Model Context Protocol.

## What This Tests

This framework tests Wanaku capabilities:

- **HTTP Capability** — register HTTP endpoints as tools, invoke via MCP *(current)*
- **Resources** — resource providers *(planned)*
- **Camel Integration** — Apache Camel-based capabilities *(planned)*

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for Keycloak testcontainer)

## Setup

### Step 1: Get Wanaku JARs

Tests require Router and HTTP Capability JARs. Choose one option:

**Option A: Copy from local Wanaku build**
```bash
WANAKU_DIR=/path/to/wanaku

cp -r $WANAKU_DIR/wanaku/wanaku-router/target/quarkus-app artifacts/wanaku-router
cp -r $WANAKU_DIR/wanaku/capabilities/tools/wanaku-tool-service-http/target/quarkus-app artifacts/wanaku-tool-service-http
```

**Option B: Download from GitHub releases**
```bash
./artifacts/download.sh
```

After setup:
```
artifacts/
├── wanaku-router/
│   ├── quarkus-run.jar
│   └── lib/
└── wanaku-tool-service-http/
    ├── quarkus-run.jar
    └── lib/
```

### Step 2: Install CLI (for CLI tests)

CLI tests require `wanaku` command. Choose one option:

**Option A: Install via jbang (recommended)**
```bash
jbang app install wanaku@wanaku-ai/wanaku

# Verify installation
wanaku --version
```

**Option B: Use CLI JAR from build**
```bash
cp -r $WANAKU_DIR/wanaku/cli/target/quarkus-app artifacts/wanaku-cli
```

> **Note:** CLI tests are skipped if CLI is not available.

## Run Tests

```bash
# Build and run all tests
mvn clean install

# Run single test
mvn clean install -pl http-capability-tests -Dtest=HttpToolRegistrationITCase#shouldRegisterHttpToolViaRestApi

# Run with debug logging
mvn clean install -Dwanaku.log.level=DEBUG

# Run with CLI JAR instead of system CLI
mvn clean install -Dwanaku.test.cli.path=../artifacts/wanaku-cli/quarkus-run.jar
```

## Project Structure

```
wanaku-tests/
├── artifacts/             # Wanaku JARs (not in git)
├── http-capability-tests/ # HTTP capability tests (17 tests)
│   └── src/test/java/ai/wanaku/test/http/
│       ├── HttpToolCliITCase.java          # CLI tool management
│       ├── HttpToolRegistrationITCase.java # Register, list, remove tools via REST API
│       └── PublicApiITCase.java            # External API invocations
└── test-common/           # Shared infrastructure
    └── src/main/java/ai/wanaku/test/
        ├── base/      # BaseIntegrationTest
        ├── client/    # RouterClient, McpTestClient, CLIExecutor
        └── managers/  # KeycloakManager, RouterManager, HttpCapabilityManager
```

## Logs

After test run, logs are in `http-capability-tests/target/logs/`:

```
target/logs/
├── test-framework.log           # Test framework output
├── router/                      # Router process logs (per test class)
│   └── wanaku-router-HttpToolCliITCase-2026-02-13_16-31-33.log
└── http-capability/             # HTTP Capability logs (per test)
    └── HttpToolCliITCase/
        └── shouldRegisterHttpToolViaCli-2026-02-13_16-31-38.log
```

## Architecture

```
┌─────────────┐     ┌──────────┐     ┌───────────────────┐
│  Keycloak   │────▶│  Router  │◀────│  HTTP Capability  │
│  (Auth)     │     │  (MCP)   │     │  (Tool Service)   │
└─────────────┘     └──────────┘     └───────────────────┘
       ▲                 ▲                    ▲
       └─────────────────┴────────────────────┘
                    Test Framework
```

**Lifecycle:**
- Suite-scoped: Keycloak, Router (shared across tests in a class)
- Test-scoped: HTTP Capability (fresh per test)

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Skipping infrastructure setup` | Copy JARs to `artifacts/` |
| `wanaku: command not found` | Install CLI: `jbang app install wanaku@wanaku-ai/wanaku` |
| `Port already in use` | Kill orphan processes: `pkill -f quarkus-run.jar` |
| `Keycloak connection refused` | Ensure Docker is running |

## Modules

- [HTTP Capability Tests](http-capability-tests/README.md) — HTTP tool registration and invocation
- *Resources Tests* — planned
- *Camel Integration Capability Tests* — planned
- *Integration Tests* — cross-capability tests, e2e flows (planned)
