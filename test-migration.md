# Migration Plan: wanaku-tests for wanaku-praxis

## Context

Wanaku's MCP routing engine is being replaced: Java/Quarkus router -> Rust `wanaku-praxis`. The test framework must adapt to:

- **Rust binary** instead of Java JAR
- **Two ports**: management (8080) + MCP (8081) instead of one
- **No gRPC bridge**: gRPC removed entirely ([wanaku-barn#7](https://github.com/wanaku-ai/wanaku-barn/pull/7)); praxis uses MCP forwarding
- **Unified capability model**: no separate HTTP tool service / file provider processes. All capabilities run through **CIC** (Camel Integration Capability, a.k.a. KAMO) with different Camel route files
- **No auto-registration**: tests register CIC instances as **MCP forwards** with praxis
- **No built-in auth**: external goauth_proxy; CLI needs `--no-auth` for non-auth tests
- **No DataStore / ServiceCatalog**: not native in praxis
- **x-request-id enrichment**: tools get extra required arg in schema
- **Dynamic namespaces**: CRUD via REST, path-based MCP routing (`/{ns}/mcp`)
- **Different health endpoint**: `/healthz` vs `/q/health/ready`

### Capability Model Change

Old: three separate Java processes (HTTP tool service, file provider, CIC), each with its own JAR and gRPC server.

New: **CIC is the only capability provider**. Different "capabilities" are CIC instances launched with different Camel route files. Routes come from the barn catalog or are referenced as local files.

Example -- an echo tool capability:
```
java -Decho.separator=- \
  -jar camel-integration-capability-main-0.3.0-SNAPSHOT-jar-with-dependencies.jar \
  --routes-ref file:///path/to/wanaku-echo-tool.camel.yaml
```

CIC exposes an MCP endpoint; praxis registers it as a forward and auto-discovers its tools.

### New Test Lifecycle

```
Old (Java router):
  start HTTP tool service -> auto-registers via gRPC -> poll -> test
  start file provider    -> auto-registers via gRPC -> poll -> test

New (praxis):
  start praxis ->
  start CIC with route file (MCP server on HTTP port) ->
  forwardsClient.add("name", cicInstance.getMcpUrl(), namespace) ->
  praxis auto-discovers tools from CIC's MCP endpoint -> test
```

---

## Phase 1: Core Infrastructure

### 1a. New `PraxisManager`

**New file:** `test-common/.../managers/PraxisManager.java`

- Runs native binary directly (not `java -jar`) -- override `start()` like `CamelCapabilityManager` does
- **Two dynamic ports**: mgmt + MCP, both via `PortUtils.findAvailablePort()`
- Mgmt port: env var `WANAKU_MGMT_LISTEN=0.0.0.0:{mgmtPort}`
- MCP port: generate temp YAML config from embedded `default.yaml` template with port substituted
- Binary args: `--praxis-config {tempYaml}`
- Health check: `GET http://localhost:{mgmtPort}/healthz`
- Exposes: `getMgmtPort()`, `getMcpPort()`, `getMgmtBaseUrl()`, `getMcpBaseUrl()`
- No gRPC port, no JVM args, no system properties

### 1b. `TestConfiguration` updates

**File:** `test-common/.../config/TestConfiguration.java`

- Add `praxisBinaryPath` field + `PROP_PRAXIS_BINARY = "wanaku.test.praxis.binary"`
- Remove `httpToolServiceJarPath` and `fileProviderJarPath` (no longer separate processes)
- Keep `camelCapabilityJarPath` -- this is the one JAR for all capabilities
- `isPraxisMode()` -> praxis binary exists and is executable

### 1c. `TargetConfiguration` simplification

**File:** `test-common/.../config/TargetConfiguration.java`

- Remove `routerGrpcPort` entirely (no gRPC anywhere)
- Remove `oidcCredentials` (capabilities don't register, no OIDC needed)
- Simplify to: `(routerHost, routerHttpPort)` -- or just pass the management URL directly

### 1d. `SharedInfrastructure` changes

**File:** `test-common/.../base/SharedInfrastructure.java`

- Create `PraxisManager` instead of `RouterManager` when praxis binary is available
- **Drop Keycloak** for praxis mode
- Remove `getGrpcPort()` method
- Expose both mgmt and MCP base URLs

### 1e. `WanakuTestConstants` updates

**File:** `test-common/.../WanakuTestConstants.java`

- `ROUTER_HEALTH_PATH`: `/q/health/ready` -> `/healthz` (or use `isPraxisMode()` to pick)
- Add `PROP_PRAXIS_BINARY`
- Remove: `ROUTER_MANAGEMENT_DISCOVERY_PATH`, `ROUTER_MANAGEMENT_INFO_PATH`, `ROUTER_DATA_STORE_PATH`, `ROUTER_SERVICE_CATALOG_PATH`
- Remove: `PROP_HTTP_SERVICE_JAR`, `PROP_FILE_PROVIDER_JAR`

---

## Phase 2: Unified CIC Capability Manager

### 2a. Remove `HttpCapabilityManager` and `ResourceProviderManager`

**Delete:** `test-common/.../managers/HttpCapabilityManager.java`
**Delete:** `test-common/.../managers/ResourceProviderManager.java`

These are replaced by CIC instances with appropriate route files. No separate Java processes needed.

### 2b. Rework `CamelCapabilityManager` as the single capability manager

**File:** `test-common/.../managers/CamelCapabilityManager.java`

This becomes the **only** capability manager. Key changes:

- **HTTP port instead of gRPC port**: allocate via `PortUtils.findAvailablePort()`, pass to CIC (system property or CLI arg for `quarkus.http.port` or CIC-specific arg)
- **Remove gRPC args**: no `--grpc-port`
- **Remove registration args**: no `--registration-url`, `--registration-announce-address`, `--token-endpoint`, `--client-id`, `--client-secret`
- **Keep route args**: `--routes-ref`, `--rules-ref`, `--dependencies`, `--name`
- **System properties for service config**: e.g., `-Decho.separator=-` passed via `addSystemProperty()`
- **Health check on HTTP port** instead of gRPC port
- Add `getMcpUrl()` returning `http://localhost:{httpPort}/mcp` (or whatever path CIC exposes)
- Add `getHttpPort()`, remove `getGrpcPort()`

Route references can be:
- `file:///absolute/path/to/route.camel.yaml` -- local file
- Barn catalog reference (if CIC supports it)

### 2c. Registration via MCP Forwards

**`BaseIntegrationTest`** (`test-common/.../base/BaseIntegrationTest.java`):

Major simplification -- remove the `httpCapabilityManager` field entirely. In praxis mode, capabilities are started explicitly per test (or suite) using `CamelCapabilityManager` with appropriate route files.

New `@BeforeEach` flow (praxis mode):
1. Create `RouterClient` with mgmt base URL (no auth token)
2. Create `McpTestClient` with MCP base URL
3. Create `ForwardsClient` for the praxis management API
4. (Capability startup moved to individual test bases or tests -- different routes per test module)

New `@AfterEach` flow (praxis mode):
1. `forwardsClient.remove(name)` (removes forward + discovered tools)
2. Stop CIC instance
3. Disconnect MCP client

**Registration helper** (add to `BaseIntegrationTest` or a utility):
```java
protected void registerCapabilityAsForward(String name, CamelCapabilityManager manager) {
    forwardsClient.add(name, manager.getMcpUrl(), namespace);
    Awaitility.await()
        .pollInterval(DEFAULT_REGISTRATION_POLL_INTERVAL)
        .until(() -> forwardsClient.exists(name));
}
```

### 2d. Test base classes per module

**`HttpCapabilityTestBase`** -> rework:
- Instead of starting `HttpCapabilityManager`, start `CamelCapabilityManager` with an HTTP tool route file (e.g., `wanaku-http-tool.camel.yaml` from barn service templates)
- Register as MCP forward
- Same test assertions -- tools are invoked the same way via MCP

**`ResourceTestBase`** -> rework:
- Instead of starting `ResourceProviderManager`, start `CamelCapabilityManager` with a file resource route
- Register as MCP forward
- Same test assertions

**`CamelCapabilityTestBase`** -> simplify:
- Already uses `CamelCapabilityManager` -- just remove gRPC port/registration logic
- Register each CIC instance as an MCP forward after startup
- `waitForCapabilityReady()`: wait for forwarded tools to appear

### 2e. Route files as test fixtures

Test route files (Camel YAML) need to be available for each capability type. Options:
- Bundle barn service templates in `src/test/resources/routes/` of each test module
- Reference them from a checked-out barn repo via `file://` URIs
- Download them as part of the artifacts setup

Recommended: include the needed route files in `src/test/resources/routes/` as test fixtures, just like current fixture YAML files in `camel-integration-capability-tests`.

### 2f. Remove gRPC from remaining code

- **`RouterManager`**: remove `grpcPort`, `getGrpcPort()`, `quarkus.grpc.server.*` (keep for Java router backward compat if needed)
- **`PraxisManager`**: remove `getGrpcPort()`
- **`SharedInfrastructure`**: remove `getGrpcPort()`
- **`BaseIntegrationTest`**: remove `getServerGrpcPort()`, remove `httpCapabilityManager` field
- **`TargetConfiguration`**: remove `routerGrpcPort`
- **`ServiceClient`**: keep for Services CRUD tests, no longer used for capability registration

### 2g. Other client updates

**`McpTestClient`**: constructor takes `mcpBaseUrl` (MCP port URL). Add namespace-aware path support.

**`RouterClient`**: remove `deregisterCapability()` (praxis uses forwards). Remove mandatory auth token. `isCapabilityRegistered()` may not apply -- use `forwardsClient.exists()` or check tools list.

**`ManagementClient`**: remove `getInfo()`. Update `getStatistics()` assertions.

**`CLIExecutor`**: add `--no-auth` flag for praxis mode.

**`DataStoreClient` / `ServiceCatalogClient`**: keep, skip tests in praxis mode.

---

## Phase 3: Test Module Updates

### 3a. `http-capability-tests`

These tests no longer start a separate HTTP tool service JAR. Instead:

| Test Class | Action | Details |
|---|---|---|
| `HttpToolRegistrationITCase` | **Keep** | Tools registered via REST API -- same in praxis |
| `HttpToolCliITCase` | **Keep** | Add `--no-auth` |
| `HttpToolCliExtendedITCase` | **Keep** | Add `--no-auth` |
| `HttpToolErrorHandlingITCase` | **Rework** | Start CIC with HTTP tool route. Error scenarios may differ (CIC handles HTTP calls, not a dedicated HTTP service) |
| `PublicApiITCase` | **Rework** | Start CIC with HTTP tool route. Assertions must account for `x-request-id` in tool schemas |

**`HttpCapabilityTestBase`**: start CIC with HTTP tool route file, register as forward. Replace `isHttpToolServiceAvailable()` with `isCicAvailable()`.

### 3b. `resources-tests`

| Test Class | Action | Details |
|---|---|---|
| `RestApiResourceITCase` | **Keep** | Same REST API for resource management |
| `McpResourceITCase` | **Rework** | Start CIC with file resource route |
| `CliResourceITCase` | **Keep** | Add `--no-auth` |
| `ResourceCliExtendedITCase` | **Keep** | Add `--no-auth` |

**`ResourceTestBase`**: start CIC with file resource route, register as forward. Replace `isFileProviderAvailable()` with `isCicAvailable()`.

### 3c. `router-tests`

| Test Class | Action | Details |
|---|---|---|
| `NamespaceCrudITCase` | **Keep** | Same API |
| `NamespaceCliITCase` | **Keep** | Add `--no-auth` |
| `ForwardsCrudITCase` | **Keep** | May remove `@KnownLimitation` |
| `ForwardsCliITCase` | **Keep** | Add `--no-auth` |
| `PromptsCrudITCase` | **Keep** | Same API |
| `RouterInfoITCase` | **Rework** | Remove info test. Update statistics. Change health to `/healthz` |
| `AuthenticationITCase` | **Skip** | No built-in auth in praxis |
| `ServiceDiscoveryITCase` | **Rework** | Test forward-based discovery instead of gRPC service discovery |
| `DataStoreCrudITCase` | **Skip** | Not in praxis |
| `DataStoreCliITCase` | **Skip** | Not in praxis |
| `ConcurrentOperationsITCase` | **Partial** | Keep concurrent tool reg + list. Skip DataStore |
| `CapabilityResilienceITCase` | **Rework** | Use `forwardsClient.remove()` for deregistration |

### 3d. `camel-integration-capability-tests`

| Test Class | Action | Details |
|---|---|---|
| `CamelBasicToolITCase` | **Partial** | Skip DataStore-loading tests |
| `CamelMultiInstanceITCase` | **Keep** | Multiple CIC instances with different routes, each registered as forward |
| `CamelFileResourceITCase` | **Partial** | Skip DataStore-loading tests |
| `CamelPostgresToolITCase` | **Partial** | Skip DataStore-loading tests |

`CamelCapabilityTestBase.startCapability()`:
- Remove gRPC and registration args
- After CIC starts, register as MCP forward
- `waitForCapabilityReady()`: wait for forwarded tools to appear

### 3e. `mcp-forwarding-tests`

`McpForwardingTestBase`: the target becomes a praxis instance. The forwarding setup aligns naturally -- just register the target's MCP URL as a forward on the primary praxis.

### 3f. Module consolidation (future consideration)

With all capabilities running through CIC, `http-capability-tests` and `resources-tests` are essentially CIC tests with different routes. They could potentially be folded into `camel-integration-capability-tests`. Not required for the migration, but worth considering to reduce module sprawl.

---

## Phase 4: CI & Artifacts

### 4a. `artifacts/download.sh`

- Add praxis binary download from `wanaku-ai/wanaku-praxis` releases (platform-specific)
- **Remove HTTP tool service and file provider downloads** (no longer separate JARs)
- Keep CIC JAR download (the single capability JAR)
- Add route file downloads from barn catalog (or bundle in test resources)

### 4b. `.github/scripts/collect-from-source-artifacts.sh`

- Add: clone wanaku-praxis, `cargo build --release`, copy binary to `artifacts/`
- Remove: HTTP tool service and file provider artifact collection
- Keep: CIC JAR collection
- Rust toolchain needed in CI

### 4c. CI Workflows

- `full-integration-test.yml`: add `praxis_repo`/`praxis_branch` inputs, add Rust build step
- `integration-tests.yml`: download praxis binary from releases
- Both: pass `-Dwanaku.test.praxis.binary=artifacts/wanaku-praxis` to Maven
- Remove: wanaku-examples repo inputs (file provider was there)

---

## Phase 5: New Tests

### 5a. x-request-id Enrichment

New test: register a tool via forward, verify `tools/list` via MCP includes `x-request-id` in `inputSchema.required`. Call the tool with `x-request-id` -- verify CIC does NOT receive it.

### 5b. Namespace-scoped MCP Routing

New test: register tool in namespace "finance" via forward, connect MCP client to `/finance/mcp`, verify tool appears. Connect to `/mcp` (default) -- verify it does NOT appear.

### 5c. Services CRUD

New `ServicesCrudITCase`: test `POST/GET/DELETE /api/v1/services`.

### 5d. Multi-route CIC

New test: verify that a single CIC instance can serve multiple tools from a single route file, and all are discovered via forward registration.

---

## Verification

1. `mvn -DskipTests package`
2. Place praxis binary + CIC JAR in `artifacts/`
3. `mvn verify -Dwanaku.test.praxis.binary=artifacts/wanaku-praxis`
4. Non-DataStore, non-auth tests pass
5. DataStore tests skip cleanly
6. Skip threshold stays under 30%
7. CI: `gh workflow run full-integration-test.yml` with praxis repo/branch

---

## Open Questions

1. **CIC HTTP port configuration**: How does CIC accept a custom HTTP port? Is it `quarkus.http.port` system property, a CLI arg like `--http-port`, or something else?

2. **CIC MCP endpoint path**: What path does CIC expose its MCP endpoint on? `/mcp`, `/mcp/sse`, or something else? This determines the forward URL.

3. **Route files for HTTP tool / file provider**: Which barn service template route files correspond to the old HTTP tool service and file resource provider? We need to identify the right route YAML files to use as test fixtures.

4. **CIC health check**: With gRPC removed, how should we health-check a CIC instance? Quarkus health endpoint (`/q/health/ready`) or just HTTP port listening?
