# Usage Guide

## Running Tests

### All Tests

```bash
mvn clean test
```

### Specific Module

```bash
mvn test -pl http-capability-tests
```

### Specific Test Class

```bash
mvn test -pl http-capability-tests -Dtest=HttpToolRegistrationITCase
mvn test -pl http-capability-tests -Dtest=HttpToolCliITCase
mvn test -pl http-capability-tests -Dtest=PublicApiITCase
```

### Single Test Method

```bash
mvn test -pl http-capability-tests -Dtest=HttpToolRegistrationITCase#shouldRegisterAndListTool
```

## Debug Logging

Enable verbose output with `-Dwanaku.log.level=DEBUG`:

```bash
mvn test -Dwanaku.log.level=DEBUG
```

This shows:
- Port allocations
- Health check timing
- MCP client connections
- Full HTTP request/response details
- Process start commands

## Configuration

### System Properties

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.test.artifacts.dir` | `artifacts` | Path to Wanaku JARs |
| `wanaku.log.level` | `INFO` | Logging level (DEBUG, INFO, WARN) |

### Artifacts Directory

Tests expect Wanaku JARs in `artifacts/`:

```
artifacts/
├── wanaku-router/
│   ├── quarkus-run.jar
│   └── lib/
└── wanaku-tool-service-http/
    ├── quarkus-run.jar
    └── lib/
```

Copy from Wanaku build:
```bash
cp -r /path/to/wanaku/router/target/quarkus-app artifacts/wanaku-router
cp -r /path/to/wanaku/tool-service-http/target/quarkus-app artifacts/wanaku-tool-service-http
```

## Test Classes

| Class | Tests | Description |
|-------|-------|-------------|
| `HttpToolCliITCase` | 2 | CLI tool registration and listing |
| `HttpToolRegistrationITCase` | 9 | REST API tool CRUD operations |
| `PublicApiITCase` | 6 | External API invocations (httpbin, jsonplaceholder, meowfacts) |

**Total: 17 tests**

## Log Files

### Location

```
http-capability-tests/target/logs/
```

### Structure

```
target/logs/
├── test-framework.log              # All test framework logs
├── router/                         # Router process stdout/stderr
│   └── wanaku-router-{TestClass}-{timestamp}.log
└── http-capability/                # HTTP Capability stdout/stderr
    └── {TestClass}/
        └── {testMethod}-{timestamp}.log
```

### Viewing Logs

```bash
# Test framework log
cat http-capability-tests/target/logs/test-framework.log

# Latest router log
ls -t http-capability-tests/target/logs/router/*.log | head -1 | xargs cat

# Follow test framework log during run
tail -f http-capability-tests/target/logs/test-framework.log
```

## Troubleshooting

### Tests Skip (No JARs)

```
Skipping infrastructure setup (no JARs available)
```

Solution: Copy Wanaku JARs to `artifacts/` directory.

### Port Already in Use

```
Failed to allocate port after 5 retries
```

Solution: Check for orphan Java processes:
```bash
ps aux | grep quarkus-run.jar
```

### Keycloak Connection Refused

```
Keycloak startup failed
```

Solution: Ensure Docker is running:
```bash
docker ps
```

### After Modifying test-common

Always rebuild after changes:
```bash
mvn clean install -pl test-common
mvn test -pl http-capability-tests
```
