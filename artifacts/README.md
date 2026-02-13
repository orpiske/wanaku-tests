# Artifacts Directory

This directory contains pre-built JAR files required for running the integration tests.

## Required Artifacts

Place the following files in this directory before running tests:

- `wanaku-router-*.jar` - The Wanaku Router JAR (runner/uber-jar format)
- `wanaku-tool-service-http-*.jar` - The HTTP Tool Service JAR (runner/uber-jar format)

## Obtaining Artifacts

### Option 1: Build from Source

```bash
# Clone Wanaku repository
git clone https://github.com/wanaku-ai/wanaku.git
cd wanaku

# Build with Maven
mvn clean package -DskipTests

# Copy artifacts
cp router/target/*-runner.jar ../wanaku-tests/artifacts/
cp tool-services/http/target/*-runner.jar ../wanaku-tests/artifacts/
```

### Option 2: Download from Releases

Download the latest release artifacts from the Wanaku releases page and place them here.

## Optional CLI Binary

If you want to run CLI tests, also place the Wanaku CLI binary:

- `wanaku` (Linux/macOS) or `wanaku.exe` (Windows)

Alternatively, set the `wanaku.test.cli.path` system property to point to the CLI binary location.

## File Naming

The test framework auto-detects artifacts using glob patterns:
- Router: `wanaku-router-*.jar` or `wanaku-router-*-runner.jar`
- HTTP Tool Service: `wanaku-tool-service-http-*.jar` or `wanaku-tool-service-http-*-runner.jar`

You can also specify exact paths via system properties:
- `wanaku.test.router.jar=/path/to/router.jar`
- `wanaku.test.http-service.jar=/path/to/http-service.jar`
