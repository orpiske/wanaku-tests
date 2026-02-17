# Wanaku Suite 

The job is to develop a JUnit based test suite for the Wanaku project.

The files will available in the `artifacts` directory. The layout should look like this:

wanaku-test/ 
   |- /artifacts
        |- wanaku-router-0.1.0-SNAPSHOT.tar.gz 
        |- camel-integration-capability-0.1.0-SNAPSHOT.tar.gz 
 

The suite has to be able to perform the following steps:

0. Run keycloak
1. Configure credentials (using the script)
2. Run Wanaku 
2. Setup the tools/resources for Wanaku
3. Run

---

## Future: CLI Installation Testing

Currently, CLI tests use a local JAR from the build (`cli-*.jar`). In the future, we may want to add tests for different CLI installation methods:

### Installation Methods to Test

| Method | Command | Use Case |
|--------|---------|----------|
| **Local JAR** | `java -jar cli-*.jar` | Development, CI pipeline (current) |
| **JBang** | `jbang app install wanaku@wanaku-ai/wanaku` | User installation via JBang |
| **GitHub Release Binary** | Download from https://github.com/wanaku-ai/wanaku/releases | User installation via binary |

### Considerations

1. **Local JAR**: Tests current code before release. Primary method for development.
2. **JBang**: Tests *published* version from GitHub, not current branch. Useful for release verification.
3. **GitHub Binary**: Tests actual release artifacts. Smoke testing after release.

### Proposed Structure

```
wanaku-tests/
├── http-capability-tests/              # HTTP capability (current, 17 tests)
├── resources-tests/                    # Resource providers (planned)
├── camel-integration-capability-tests/ # Apache Camel capabilities (planned)
├── integration-tests/                  # Cross-module tests (planned)
└── installation-tests/                 # CLI installation methods (future)
    ├── JBangInstallationTest.java
    └── ReleaseBinaryTest.java
```

### When to Run

- **Local JAR tests**: Every PR, CI pipeline
- **JBang/Release tests**: Release pipeline, periodic verification

---

## Future: Integration Tests Module

Cross-module tests that require multiple capabilities to be available.

### Labels Test (Router Feature)

Labels allow grouping and batch deletion of tools/resources. This is a Router feature that works across all capability types.

**Test Scenario:**

1. Create HTTP tool with label `team-a`
2. Create Resource with label `team-a`
3. Create Camel capability with label `team-b`
4. Create HTTP tool without label
5. Delete by label `team-a` → verify HTTP tool + Resource deleted
6. Delete by label `team-b` → verify Camel capability deleted
7. Verify only the no-label HTTP tool remains

**Why in integration-tests:**
- Labels are Router feature, not capability-specific
- Test must verify labels work across ALL capability types (HTTP, Resources, Camel)
- Requires all three capability modules to be implemented first

**Prerequisites:**
- `http-capability-tests` ✅ (done)
- `resources-tests` (planned)
- `camel-integration-capability-tests` (planned)

### Other Integration Test Candidates

- **Cross-capability tool chaining**: Output of one tool as input to another
- **Authentication flows**: OAuth tokens across capabilities
- **Rate limiting**: Router-level rate limits affecting multiple capabilities