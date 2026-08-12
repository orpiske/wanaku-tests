# Migration Plan: wanaku-tests for wanaku-praxis

## Context

Wanaku's MCP routing engine is being replaced: Java/Quarkus router -> Rust `wanaku-praxis`. The test framework must adapt to:

- **Rust binary** instead of Java JAR
- **Two ports**: management (8080) + MCP (8081) instead of one
- **No gRPC bridge**: capabilities are now MCP servers; praxis uses MCP forwarding to invoke tools (see [wanaku-barn#7](https://github.com/wanaku-ai/wanaku-barn/pull/7))
- **No auto-registration**: capabilities don't self-register; tests register them as **MCP forwards**
- **No built-in auth**: external goauth_proxy; CLI needs `--no-auth` for non-auth tests
- **No DataStore / ServiceCatalog**: not native in praxis
- **x-request-id enrichment**: tools get extra required arg in schema
- **Dynamic namespaces**: CRUD via REST, path-based MCP routing (`/{ns}/mcp`)
- **Different health endpoint**: `/healthz` vs `/q/health/ready`

Capability providers (HTTP tool, file provider, CIC) remain Java-based but are now **MCP servers** -- praxis discovers and invokes their tools via MCP forwarding, not gRPC.

### New Test Lifecycle

```
Old (Java router):
  start capability -> capability auto-registers via gRPC -> poll until registered -> test

New (praxis):
  start praxis -> start capability (MCP server on HTTP port) ->
  register as MCP forward via POST /api/v1/forwards ->
  praxis auto-discovers tools from MCP endpoint -> test
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
- Add `findPraxisBinary(artifactsDir)` -- looks for `wanaku-praxis` binary in artifacts
- `isPraxisAvailable()` -> binary exists and is executable

### 1c. `TargetConfiguration` changes

**File:** `test-common/.../config/TargetConfiguration.java`

- Remove `routerGrpcPort` entirely (no gRPC anywhere -- capabilities use MCP, praxis has no gRPC server)
- Remove `oidcCredentials` (capabilities don't register, no OIDC needed)
- Simplify to: `(routerHost, routerHttpPort)` -- or just pass the management URL directly

### 1d. `SharedInfrastructure` changes

**File:** `test-common/.../base/SharedInfrastructure.java`

- Create `PraxisManager` instead of `RouterManager` when praxis binary is available
- **Drop Keycloak** for praxis mode (capabilities don't need OIDC, praxis doesn't enforce auth)
- Keep Keycloak support conditional for auth-specific tests
- Expose both mgmt and MCP base URLs
- Remove `getGrpcPort()` method

### 1e. `WanakuTestConstants` updates

**File:** `test-common/.../WanakuTestConstants.java`

- `ROUTER_HEALTH_PATH`: `/q/health/ready` -> `/healthz` (or use `isPraxisMode()` to pick)
- Add `PROP_PRAXIS_BINARY`
- Remove: `ROUTER_MANAGEMENT_DISCOVERY_PATH`, `ROUTER_MANAGEMENT_INFO_PATH`, `ROUTER_DATA_STORE_PATH`, `ROUTER_SERVICE_CATALOG_PATH`

---

## Phase 2: gRPC Removal & MCP Forwarding

The gRPC bridge has been removed from the capability SDK entirely ([wanaku-barn#7](https://github.com/wanaku-ai/wanaku-barn/pull/7)). Capability providers no longer expose gRPC servers. They are now **MCP servers** that expose tools via the MCP protocol over HTTP. Praxis invokes them via **MCP forwarding**.

### 2a. Capability Managers: gRPC -> HTTP/MCP

All three capability managers need the same change: replace gRPC port with HTTP port.

**`HttpCapabilityManager`** (`test-common/.../managers/HttpCapabilityManager.java`):
- `prepareStandalone()`: allocate **HTTP port** via `PortUtils.findAvailablePort()`, set `quarkus.http.port={httpPort}`
- Remove all `quarkus.grpc.server.*` system properties
- Health check: `HealthCheckUtils.waitForPort("localhost", httpPort, ...)` (or HTTP GET to `/q/health/ready`)
- Rename `getGrpcPort()` -> `getHttpPort()`, add `getMcpUrl()` returning `http://localhost:{httpPort}/mcp`
- `prepare(TargetConfiguration)` (router mode): remove `wanaku.router.host/port` gRPC properties, remove `quarkus.grpc.server.*`

**`ResourceProviderManager`** (`test-common/.../managers/ResourceProviderManager.java`):
- Same changes as HttpCapabilityManager
- `getGrpcPort()` -> `getHttpPort()`, add `getMcpUrl()`

**`CamelCapabilityManager`** (`test-common/.../managers/CamelCapabilityManager.java`):
- `prepareStandalone()`: allocate HTTP port, remove `--grpc-port` CLI arg
- Add HTTP port configuration (depends on CIC startup args -- may be `--http-port` or env var)
- Health check on HTTP port instead of gRPC
- `getGrpcPort()` -> `getHttpPort()`, add `getMcpUrl()`

### 2b. Registration Model: Services -> Forwards

Capabilities are no longer registered as gRPC services. They are registered as **MCP forwards**.

**`BaseIntegrationTest`** (`test-common/.../base/BaseIntegrationTest.java`):

New `@BeforeEach` flow (praxis mode):
1. Create `RouterClient` with mgmt base URL (no auth token)
2. Create `McpTestClient` with MCP base URL
3. Start HTTP capability (MCP server on allocated HTTP port)
4. Create `ForwardsClient` for the praxis management API
5. **Register as forward**: `forwardsClient.add("http-capability", capabilityManager.getMcpUrl(), namespace)`
6. Praxis auto-discovers tools from the MCP endpoint
7. Wait for tools to appear: `Awaitility.await().until(() -> !routerClient.listTools().isEmpty())`

New `@AfterEach` flow (praxis mode):
1. `forwardsClient.remove("http-capability")` (also removes discovered tools)
2. Stop HTTP capability
3. Disconnect MCP client

**`ResourceTestBase`** (`resources-tests/.../ResourceTestBase.java`):
- Replace `svcClient.register("file", "localhost:{grpcPort}", "resource-provider")` -> `forwardsClient.add("file-provider", resourceProviderManager.getMcpUrl(), namespace)`
- Cleanup: `forwardsClient.remove("file-provider")`

**`CamelCapabilityTestBase`** (`camel-integration-capability-tests/.../CamelCapabilityTestBase.java`):
- Replace `serviceClient.register(serviceName, "localhost:{grpcPort}", "tool-invoker")` -> `forwardsClient.add(serviceName, manager.getMcpUrl(), namespace)`
- Cleanup: `forwardsClient.remove(serviceName)`

### 2c. Remove gRPC from Router-side code

- **`RouterManager`**: remove `grpcPort` field, `getGrpcPort()`, `quarkus.grpc.server.*` system properties (router mode may still need these until Java router is fully deprecated -- keep behind `isPraxisMode()` guard if needed)
- **`PraxisManager`**: remove `getGrpcPort()` (returns -1 currently)
- **`SharedInfrastructure`**: remove `getGrpcPort()` method
- **`BaseIntegrationTest`**: remove `getServerGrpcPort()` helper
- **`TargetConfiguration`**: remove `routerGrpcPort` field entirely
- **`ServiceClient`**: keep for Services CRUD tests, but no longer used for capability registration

### 2d. `McpTestClient` updates

**File:** `test-common/.../client/McpTestClient.java`

- Constructor takes `mcpBaseUrl` (MCP port URL) instead of management URL
- MCP path stays `mcp/`
- Auth: keep optional Bearer token (for goauth_proxy scenarios)
- Add namespace-aware constructor/method: path becomes `{namespace}/mcp` instead of `mcp/`

### 2e. `RouterClient` updates

**File:** `test-common/.../client/RouterClient.java`

- `deregisterCapability()`: no longer needed for praxis mode (capabilities are forwards, not services)
- Remove mandatory auth token (constructor accepts null, no token sent)
- `isCapabilityRegistered()`: may not be used in praxis mode (capabilities registered as forwards, not services). Use `forwardsClient.exists()` or check tools list instead.

### 2f. `ManagementClient` updates

**File:** `test-common/.../client/ManagementClient.java`

- Remove `getInfo()` (endpoint gone in praxis)
- `getStatistics()`: same path, update expected response fields

### 2g. `CLIExecutor` updates

**File:** `test-common/.../client/CLIExecutor.java`

- Add `--no-auth` flag support for praxis mode
- Append the flag to all CLI commands when no auth is configured

### 2h. `DataStoreClient` and `ServiceCatalogClient`

Keep classes but skip all tests that use them in praxis mode.

---

## Phase 3: Test Module Updates

### 3a. `router-tests`

| Test Class | Action | Details |
|---|---|---|
| `NamespaceCrudITCase` | **Keep** | Same API |
| `NamespaceCliITCase` | **Keep** | Add `--no-auth` to CLI commands |
| `ForwardsCrudITCase` | **Keep** | May remove `@KnownLimitation` if auth issue resolved |
| `ForwardsCliITCase` | **Keep** | Add `--no-auth` |
| `PromptsCrudITCase` | **Keep** | Same API |
| `RouterInfoITCase` | **Rework** | Remove `shouldReturnInfo()`. Update `shouldReturnStatistics()` field assertions. Change health path to `/healthz` |
| `AuthenticationITCase` | **Skip** | No built-in auth in praxis |
| `ServiceDiscoveryITCase` | **Rework** | Capabilities are forwards now, not gRPC services. Rework to test forward-based discovery |
| `DataStoreCrudITCase` | **Skip** | Not in praxis |
| `DataStoreCliITCase` | **Skip** | Not in praxis |
| `ConcurrentOperationsITCase` | **Partial** | Keep concurrent tool reg + list. Skip DataStore tests |
| `CapabilityResilienceITCase` | **Rework** | Use `forwardsClient.remove()` instead of gRPC-based deregistration |

### 3b. `http-capability-tests`

| Test Class | Action | Details |
|---|---|---|
| `HttpToolRegistrationITCase` | **Keep** | Same API |
| `HttpToolCliITCase` | **Keep** | Add `--no-auth` |
| `HttpToolCliExtendedITCase` | **Keep** | Add `--no-auth` |
| `HttpToolErrorHandlingITCase` | **Keep** | Same MCP protocol |
| `PublicApiITCase` | **Adjust** | `tools/list` assertions must account for `x-request-id` in tool schemas |

### 3c. `resources-tests`

All test classes keep working. Update registration to use MCP forwards. Add `--no-auth` to CLI tests.

### 3d. `camel-integration-capability-tests`

| Test Class | Action | Details |
|---|---|---|
| `CamelBasicToolITCase` | **Partial** | Skip DataStore-loading tests. Keep direct fixture tests |
| `CamelMultiInstanceITCase` | **Keep** | Update registration to MCP forwards |
| `CamelFileResourceITCase` | **Partial** | Skip DataStore-loading tests |
| `CamelPostgresToolITCase` | **Partial** | Skip DataStore-loading tests |

`CamelCapabilityTestBase.startCapability()` needs updating:
- Remove gRPC port and registration config from CIC CLI args
- After CIC starts, register as MCP forward via `forwardsClient.add()`
- `waitForCapabilityReady()`: wait for forwarded tools to appear (praxis auto-discovers)

### 3e. `mcp-forwarding-tests`

`McpForwardingTestBase`: the target "router" also becomes a PraxisManager instance. The forwarding setup uses the target's MCP URL directly, which aligns well with the new model.

---

## Phase 4: CI & Artifacts

### 4a. `artifacts/download.sh`

- Add praxis binary download from `wanaku-ai/wanaku-praxis` GitHub releases (platform-specific: linux-x86_64, darwin-aarch64)
- Remove or flag the old Java router ZIP download

### 4b. `.github/scripts/collect-from-source-artifacts.sh`

- Add: clone wanaku-praxis, `cargo build --release`, copy `target/release/wanaku-praxis` to `artifacts/`
- Rust toolchain needed in CI

### 4c. CI Workflows

- `full-integration-test.yml`: add `praxis_repo`/`praxis_branch` inputs, add Rust build step
- `integration-tests.yml`: download praxis binary from releases
- Both: pass `-Dwanaku.test.praxis.binary=artifacts/wanaku-praxis` to Maven

---

## Phase 5: New Tests

### 5a. x-request-id Enrichment

New test in `router-tests`: register a tool, verify `tools/list` via MCP includes `x-request-id` in `inputSchema.required`. Call the tool with `x-request-id` -- verify the capability provider does NOT receive it.

### 5b. Namespace-scoped MCP Routing

New test: register tool in namespace "finance", connect MCP client to `/finance/mcp`, verify tool appears. Connect to `/mcp` (default) -- verify it does NOT appear.

### 5c. Services CRUD

New `ServicesCrudITCase`: test `POST/GET/DELETE /api/v1/services`. This is a praxis-specific management API.

---

## Verification

1. `mvn -DskipTests package`
2. Place praxis binary + capability JARs in `artifacts/`
3. `mvn verify -Dwanaku.test.praxis.binary=artifacts/wanaku-praxis`
4. Non-DataStore, non-auth tests pass
5. DataStore tests skip cleanly
6. Skip threshold stays under 30%
7. CI: `gh workflow run full-integration-test.yml` with praxis repo/branch

---

## Open Questions

1. **Capability MCP endpoint path**: Do the Java SDK capability providers expose MCP at `/mcp` or a different path? This determines the URL passed to `forwardsClient.add()`.

2. **CIC as MCP server**: Does CIC now expose an MCP endpoint? What CLI args does it need (no `--grpc-port`, but maybe `--http-port` or similar)? How are CIC tools discovered via MCP?

3. **Capability health check**: With gRPC removed, should capability health checks use the Quarkus HTTP health endpoint (`/q/health/ready`) or just wait for the HTTP port to be listening?
