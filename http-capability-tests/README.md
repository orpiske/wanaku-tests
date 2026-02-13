# HTTP Capability Tests

Integration tests for Wanaku HTTP Tool capability.

## Quick Start

```bash
# Run all tests
mvn test -pl http-capability-tests

# Run specific test class
mvn test -pl http-capability-tests -Dtest=HttpToolCliITCase

# Run single test
mvn test -pl http-capability-tests -Dtest=HttpToolCliITCase#shouldRegisterHttpToolViaCli
```

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `wanaku.test.artifacts.dir` | `artifacts` | Path to Wanaku JARs |

## Test Classes

| Class | Tests | Description |
|-------|-------|-------------|
| `HttpToolCliITCase` | 2 | CLI tool registration and listing |
| `HttpToolRegistrationITCase` | 9 | REST API tool CRUD operations |
| `PublicApiITCase` | 6 | External API invocations (httpbin, jsonplaceholder, meowfacts) |

**Total: 17 tests**

## Architecture

```
┌─────────────┐     ┌──────────┐     ┌───────────────────┐
│  Keycloak   │────▶│  Router  │◀────│  HTTP Tool Service│
│  (Auth)     │     │  (API)   │     │  (Capability)     │
└─────────────┘     └──────────┘     └───────────────────┘
       ▲                 ▲                    ▲
       │                 │                    │
       └─────────────────┴────────────────────┘
                         │
                    Test Framework
                    (BaseIntegrationTest)
```

**Lifecycle:**
- Suite-scoped: Keycloak, Router (shared across tests in a class)
- Test-scoped: HTTP Capability (fresh per test)

## Log Structure

```
logs/
├── test-framework.log
├── router/
│   ├── wanaku-router-HttpToolCliITCase-2026-02-05_15-35-09.log
│   └── wanaku-router-HttpToolRegistrationITCase-2026-02-05_15-36-01.log
└── http-capability/
    ├── HttpToolCliITCase/
    │   ├── shouldRegisterHttpToolViaCli-2026-02-05_15-35-12.log
    │   └── shouldListToolsViaCli-2026-02-05_15-35-18.log
    └── HttpToolRegistrationITCase/
        └── shouldRegisterHttpToolViaRestApi-2026-02-05_15-36-05.log
```

## Known Limitations

- **CLI stdout capture**: JLine requires TTY. Tests verify CLI results via REST API instead of stdout.

## Artifacts

Tests expect Wanaku JARs in `../artifacts/`:
```
artifacts/
├── wanaku-router/quarkus-run.jar
├── wanaku-tool-service-http/quarkus-run.jar
└── wanaku-cli/quarkus-run.jar
```
