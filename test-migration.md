# Migration Plan: wanaku-tests for wanaku-praxis

## Context

Wanaku's MCP routing engine is being replaced: Java/Quarkus router -> Rust `wanaku-praxis`. The test framework must adapt to:

- **Rust binary** instead of Java JAR
- **Two ports**: management (8080) + MCP (8081) instead of one
- **No auto-registration**: capabilities no longer self-register; tests must manually register services via REST
- **No built-in auth**: external goauth_proxy; CLI needs `--no-auth` for non-auth tests
- **No DataStore / ServiceCatalog**: not native in praxis
- **x-request-id enrichment**: tools get extra required arg in schema
- **Dynamic namespaces**: CRUD via REST, path-based MCP routing (`/{ns}/mcp`)
- **Different health endpoint**: `/healthz` vs `/q/health/ready`
- **Different deregistration**: `DELETE /api/v1/services/{name}` vs `DELETE /api/v1/management/discovery`

Capability providers (HTTP tool, file provider, CIC) remain Java-based but now act as passive gRPC servers -- praxis calls them, they don't call praxis.

### New Test Lifecycle

Old: start capability -> capability auto-registers with router -> poll until registered -> test
New: start praxis -> start capability (gRPC server only) -> **manually register service** via `POST /api/v1/services` -> register tools via `POST /api/v1/tools` -> test

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

- Remove `routerGrpcPort` (praxis has no gRPC server)
- Remove `oidcCredentials` (capabilities don't register, no OIDC needed)
- Simplify to: `(routerHost, routerHttpPort)` -- or just pass the management URL directly

### 1d. `SharedInfrastructure` changes

**File:** `test-common/.../base/SharedInfrastructure.java`

- Create `PraxisManager` instead of `RouterManager` when praxis binary is available
- **Drop Keycloak** for praxis mode (capabilities don't need OIDC, praxis doesn't enforce auth)
- Keep Keycloak support conditional for auth-specific tests
- Expose both mgmt and MCP base URLs

### 1e. `WanakuTestConstants` updates

**File:** `test-common/.../WanakuTestConstants.java`

- `ROUTER_HEALTH_PATH`: `/q/health/ready` -> `/healthz` (or use `isPraxisMode()` to pick)
- Add `PROP_PRAXIS_BINARY`
- Add `ROUTER_SERVICES_PATH = "/api/v1/services"`
- Remove: `ROUTER_MANAGEMENT_DISCOVERY_PATH`, `ROUTER_MANAGEMENT_INFO_PATH`, `ROUTER_DATA_STORE_PATH`, `ROUTER_SERVICE_CATALOG_PATH`

---

## Phase 2: Registration & Client Changes

### 2a. New `ServicesClient`

**New file:** `test-common/.../client/ServicesClient.java`

- CRUD on `/api/v1/services`
- `register(name, address, serviceType)`: POST with `{name, address, serviceType}` JSON
- `list()`, `get(name)`, `delete(name)`
- Critical for the new manual registration flow

### 2b. `BaseIntegrationTest` overhaul

**File:** `test-common/.../base/BaseIntegrationTest.java`

Replace field `RouterManager routerManager` -> `PraxisManager praxisManager`

**New `@BeforeEach` flow:**
1. Create `RouterClient` with **mgmt** base URL (no auth token needed)
2. Create `McpTestClient` with **MCP** base URL
3. Start HTTP capability (gRPC server only -- no registration config)
4. **Manually register service**: `servicesClient.register("http", "localhost:{grpcPort}", "tool-invoker")`
5. Poll `routerClient.isCapabilityRegistered("http")` (same as before -- response format is compatible)

**New `@AfterEach` flow:**
1. Clear all tools
2. **Deregister service**: `servicesClient.delete("http")`
3. Stop HTTP capability
4. Disconnect MCP

### 2c. `HttpCapabilityManager` / `ResourceProviderManager` simplification

**Files:** `test-common/.../managers/HttpCapabilityManager.java`, `ResourceProviderManager.java`

Remove from `prepare()`:
- `wanaku.service.registration.uri` (no auto-registration)
- `wanaku.router.host/port` (no gRPC connection to router)
- `quarkus.oidc-client.*` (no OIDC)
- `wanaku.service.registration.delay-seconds` (no delay)

Keep:
- Own gRPC port allocation
- Quarkus HTTP port (set to 0)
- Health check (wait for gRPC port)

`prepare()` signature simplifies: no longer needs `TargetConfiguration`. Just allocate a gRPC port.

### 2d. `CamelCapabilityManager` simplification

**File:** `test-common/.../managers/CamelCapabilityManager.java`

Remove from CLI args:
- `--registration-url`
- `--registration-announce-address`
- `--token-endpoint`, `--client-id`, `--client-secret`

Keep:
- `--name`, `--grpc-port`, `--routes-ref`, `--rules-ref`, `--dependencies`

After starting CIC, the test must manually register the service and tools with praxis.

### 2e. `RouterClient` updates

**File:** `test-common/.../client/RouterClient.java`

- `deregisterCapability()`: rewrite from `DELETE /api/v1/management/discovery` -> `DELETE /api/v1/services/{name}`
- Remove mandatory auth token (constructor accepts null, no token sent)
- `isCapabilityRegistered()`: **no change** -- `GET /api/v1/capabilities` returns same `serviceName` field

### 2f. `McpTestClient` updates

**File:** `test-common/.../client/McpTestClient.java`

- Constructor takes `mcpBaseUrl` (MCP port URL) instead of management URL
- MCP path stays `mcp/`
- Auth: keep optional Bearer token (for goauth_proxy scenarios)
- Add namespace-aware constructor/method: path becomes `{namespace}/mcp` instead of `mcp/`

### 2g. `ManagementClient` updates

**File:** `test-common/.../client/ManagementClient.java`

- Remove `getInfo()` (endpoint gone in praxis)
- `getStatistics()`: same path, update expected response fields

### 2h. `DataStoreClient` and `ServiceCatalogClient`

Keep classes but skip all tests that use them in praxis mode.

### 2i. `CLIExecutor` updates

**File:** `test-common/.../client/CLIExecutor.java`

- Add `--no-auth` flag support for praxis mode
- Append the flag to all CLI commands when no auth is configured

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
| `ServiceDiscoveryITCase` | **Rework** | Use new `ServicesClient` for registration/deregistration |
| `DataStoreCrudITCase` | **Skip** | Not in praxis |
| `DataStoreCliITCase` | **Skip** | Not in praxis |
| `ConcurrentOperationsITCase` | **Partial** | Keep concurrent tool reg + list. Skip DataStore tests |
| `CapabilityResilienceITCase` | **Rework** | Use `ServicesClient.delete()` for deregistration |

### 3b. `http-capability-tests`

| Test Class | Action | Details |
|---|---|---|
| `HttpToolRegistrationITCase` | **Keep** | Same API |
| `HttpToolCliITCase` | **Keep** | Add `--no-auth` |
| `HttpToolCliExtendedITCase` | **Keep** | Add `--no-auth` |
| `HttpToolErrorHandlingITCase` | **Keep** | Same MCP protocol |
| `PublicApiITCase` | **Adjust** | `tools/list` assertions must account for `x-request-id` in tool schemas |

### 3c. `resources-tests`

All test classes keep working. Add `--no-auth` to CLI tests.

### 3d. `camel-integration-capability-tests`

| Test Class | Action | Details |
|---|---|---|
| `CamelBasicToolITCase` | **Partial** | Skip DataStore-loading tests. Keep direct fixture tests |
| `CamelMultiInstanceITCase` | **Keep** | Update registration flow |
| `CamelFileResourceITCase` | **Partial** | Skip DataStore-loading tests |
| `CamelPostgresToolITCase` | **Partial** | Skip DataStore-loading tests |

`CamelCapabilityTestBase.startCapability()` needs updating:
- Remove registration config from CIC CLI args
- After CIC starts, manually call `servicesClient.register()` and `routerClient.registerTool()`
- `waitForCapabilityReady()` stays the same (polls capabilities list + tools list)

### 3e. `mcp-forwarding-tests`

`McpForwardingTestBase`: second router also becomes a `PraxisManager` instance. Update the full setup flow for manual service registration against both routers.

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

New `ServicesCrudITCase`: test `POST/GET/DELETE /api/v1/services`. This is a new praxis-specific API.

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

1. **SDK startup without registration config**: Will the Java SDK capability providers (HTTP tool, file provider) start cleanly without `wanaku.service.registration.uri`? Or do they need a flag to disable auto-registration? If the SDK hasn't been updated yet, we may need to point registration at a dummy URL or add a "skip registration" config.

2. **CIC startup without registration args**: Same question for CIC -- will it start without `--registration-url` and `--token-endpoint` args, or will it error?

3. **Tool registration for capabilities**: When capabilities no longer auto-register tools, does the test framework need to register individual tools via `POST /api/v1/tools` after registering the service? Or does registering the service automatically make tools available? (I believe tools must be registered separately.)
