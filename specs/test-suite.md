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
├── http-capability-tests/     # Current: uses local JAR
└── installation-tests/        # Future: tests JBang and release binaries
    ├── JBangInstallationTest.java
    └── ReleaseBinaryTest.java
```

### When to Run

- **Local JAR tests**: Every PR, CI pipeline
- **JBang/Release tests**: Release pipeline, periodic verification