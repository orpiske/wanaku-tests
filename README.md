# Wanaku Integration Tests

Integration test framework for Wanaku components.

## Quick Start

```bash
# Run all tests
mvn clean test

# Run with debug logging
mvn test -Dwanaku.log.level=DEBUG

# Run specific test class
mvn test -pl http-capability-tests -Dtest=HttpToolRegistrationITCase

# Run single test
mvn test -pl http-capability-tests -Dtest=HttpToolRegistrationITCase#shouldRegisterAndListTool
```

## Project Structure

```
wanaku-tests/
├── artifacts/             # Wanaku JARs (not in git)
│   ├── wanaku-router/quarkus-run.jar
│   └── wanaku-tool-service-http/quarkus-run.jar
├── http-capability-tests/ # HTTP capability test cases
│   └── src/test/java/
│       └── ai/wanaku/test/http/
│           ├── HttpToolCliITCase.java
│           ├── HttpToolRegistrationITCase.java
│           └── PublicApiITCase.java
└── test-common/           # Shared test infrastructure
    └── src/main/java/
        └── ai/wanaku/test/
            ├── base/      # BaseIntegrationTest
            ├── client/    # RouterClient, McpTestClient, CLIExecutor
            ├── managers/  # KeycloakManager, RouterManager, HttpCapabilityManager
            └── utils/     # HealthCheckUtils, PortUtils, LogUtils
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

## Requirements

- Java 21+
- Maven 3.9+
- Docker (for Keycloak testcontainer)
- Wanaku JARs in `artifacts/` directory
- `wanaku` CLI in PATH (for CLI tests)

## See Also

- [HTTP Capability Tests](http-capability-tests/README.md) - detailed test documentation
- [Usage Guide](USAGE.md) - advanced usage and configuration
