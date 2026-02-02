<!--
Sync Impact Report
==================
Version change: 1.0.0 -> 1.1.0
Modified principles: None renamed
Added sections:
  - VI. Layered Isolation (new principle)
Removed sections: None
Templates requiring updates:
  - .specify/templates/plan-template.md: ✅ compatible (no changes needed)
  - .specify/templates/spec-template.md: ✅ compatible (no changes needed)
  - .specify/templates/tasks-template.md: ✅ compatible (no changes needed)
Follow-up TODOs: None
-->

# Wanaku Test Framework Constitution

## Core Principles

### I. Hybrid Execution Model

The framework MUST use a hybrid execution model:
- Infrastructure components (Keycloak, PostgreSQL, etc.) MUST run via Testcontainers
- System under test (Wanaku Router, capabilities) MUST run as local Java processes
- All ports MUST be dynamically allocated to avoid conflicts
- Container-to-process communication MUST use localhost with mapped ports

**Rationale**: This model enables testing the actual runtime behavior of Wanaku components while leveraging Testcontainers for reproducible infrastructure setup.

### II. Test Isolation (NON-NEGOTIABLE)

Every test MUST be fully isolated:
- Each test MUST use its own isolated data directory (never ~/.wanaku)
- State MUST be cleaned between tests via @AfterEach hooks
- Tests MUST NOT depend on execution order
- Per-test infrastructure (databases, capabilities) MUST start fresh for each test
- Shared infrastructure (Keycloak, Router) MUST be reset to clean state between tests

**Rationale**: Isolation prevents flaky tests and ensures reproducible results regardless of execution order or parallelization.

### III. Fail-Fast with Clear Errors

The framework MUST fail fast and provide actionable error messages:
- Missing prerequisites (JARs, containers) MUST cause immediate failure with clear instructions
- Health checks MUST verify component readiness before test execution
- Timeouts MUST be explicit and configurable
- Process failures MUST capture and expose stdout/stderr for debugging
- All logs MUST be preserved in target/ directory with consistent naming

**Rationale**: Clear error messages reduce debugging time and enable faster iteration.

### IV. Configuration Flexibility

The framework MUST support multiple test scenarios without code changes:
- Test fixtures (routes, rules, dependencies) MUST be loaded from src/test/resources/
- Configuration MUST be injectable via system properties
- New test scenarios MUST only require adding resource files and test classes
- Dynamic configuration MUST support different capability combinations

**Rationale**: Wanaku supports diverse deployment scenarios; the test framework must validate all of them with minimal friction.

### V. Performance-Aware Resource Management

The framework MUST optimize resource usage:
- Shared infrastructure (Keycloak, Router) MUST be reused across tests in a module
- Per-test containers MUST use Testcontainers reuse feature where appropriate
- Process lifecycle MUST use graceful shutdown (SIGTERM) before forced termination
- Port allocation MUST include retry logic for race conditions
- Container images SHOULD be pulled once and cached

**Rationale**: Efficient resource management enables running the full test suite in reasonable time.

### VI. Layered Isolation

The framework MUST apply layered isolation with tiered infrastructure lifecycles:

**Core Infrastructure Layer (Suite-Scoped)**:
- Keycloak and other authentication/authorization infrastructure MUST remain online for the entire test suite execution
- Core infrastructure MUST NOT be restarted between tests
- Core infrastructure starts in @BeforeAll and stops in @AfterAll

**Router Layer (Configurable Scope)**:
- Router MUST remain online by default across all tests in a module
- Router MUST support an optional per-test restart mode for tests requiring fresh router state
- Tests requiring router restart MUST declare this requirement explicitly (e.g., via annotation or configuration)
- Router restart mode MUST clean all registered tools/resources and reset internal state

**Per-Test Infrastructure Layer (Test-Scoped)**:
- Capabilities MUST start fresh for each test
- Test-specific containers (PostgreSQL, etc.) MUST be created per test
- All per-test infrastructure MUST be torn down in @AfterEach

**Rationale**: Layered isolation balances test independence with execution efficiency. Core infrastructure startup is expensive and rarely affects test isolation, while router restart capability supports edge cases requiring completely fresh state.

## Technical Standards

### Language and Framework
- Java with JUnit 5 as the test framework
- Testcontainers for infrastructure management
- Maven as the build system
- ProcessBuilder for local process management

### Project Structure
- Multi-module Maven project with test-common for shared utilities
- Tests grouped by capability type (HTTP, Camel tools, Camel resources, etc.)
- Integration/E2E tests in separate modules
- Resource files organized by test scenario

### Testing Conventions
- @BeforeAll: Start core infrastructure (Keycloak) and Router (default mode)
- @BeforeEach: Start test-specific infrastructure and capabilities; optionally restart Router
- @Test: Execute test scenario with assertions
- @AfterEach: Clean test state, stop per-test processes and capabilities
- @AfterAll: Stop Router and core infrastructure, cleanup

### Logging Standards
- Log file format: `{test-name}-{component}-{timestamp}.log`
- All process output redirected to target/ directory
- Logs preserved for post-mortem debugging

## Development Workflow

### Adding New Tests
1. Create resource files in src/test/resources/{scenario-name}/
2. Create test class extending base test infrastructure
3. Configure capability with test fixtures
4. Write test methods with clear assertions
5. Verify isolation by running tests in any order
6. If router restart is needed, annotate test appropriately

### Code Review Gates
- All tests MUST pass before merge
- New tests MUST include positive and negative cases
- Resource cleanup MUST be verified
- Flaky tests MUST NOT be merged
- Router restart usage MUST be justified in test comments

### Build Verification
- `mvn test` MUST run complete suite without manual steps
- Tests MUST work on fresh checkout with only Docker available
- CI pipeline MUST mirror local execution

## Governance

This constitution establishes the non-negotiable principles for the Wanaku Test Framework. All contributions MUST comply with these principles.

### Amendment Process
1. Propose changes via pull request to constitution.md
2. Document rationale for changes
3. Update version following semantic versioning:
   - MAJOR: Principle removal or incompatible redefinition
   - MINOR: New principle or material expansion
   - PATCH: Clarifications and wording improvements
4. Update dependent templates if principles change

### Compliance
- All PRs MUST pass Constitution Check in plan.md
- Violations MUST be justified in Complexity Tracking section
- Regular review of test suite health against principles

**Version**: 1.1.0 | **Ratified**: 2026-02-02 | **Last Amended**: 2026-02-02
