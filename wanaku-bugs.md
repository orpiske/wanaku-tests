# Wanaku findings from the MCP CLI plan

## 1. Bundled server rejects tool enable/disable endpoints

- **Severity:** Medium; blocks the disable/enable coverage in tests 7.4 and 7.5.
- **Run:** `run-f482191999704b4f8ad665c3e8e6d569`, evidence `ev-33` and `ev-34`.
- **Environment:** builtin server in `quay.io/wanaku/wanaku-saci-worker:latest`; image digest
  `sha256:08b9320cb9b5112c8010bb25b290612add694be0259c4b00f38257ae2081fa3c`.
  `wanaku-server --version` prints `wanaku-server 0.3.0`; CLI reports `0.3.0-SNAPSHOT`.
- **Reproduction:** register the supplied `fixtures/test-mcp-server` as a forward, confirm `restartService`
  appears in `GET /api/v1/tools`, then send `PUT /api/v1/tools/restartService/disable` and
  `PUT /api/v1/tools/restartService/enable` to the management listener.
- **Observed:** both requests return HTTP 404. A direct disable probe returned
  `{"data":null,"error":"Not Found"}`. The tool stays visible and callable; disabling never takes effect.
- **Expected:** HTTP 200, with disabling hiding the tool from MCP listing and rejecting calls;
  enabling restores access while retaining the management entry.
- **Source cross-check:** local `wanaku` checkout `b6cc19064` explicitly routes both PUT paths in
  `server/src/management/routes.rs` and tests them. The feature was introduced in `f6a5c7e1e`.
  The running management tool records also omit the `enabled` field found in the newer source.
- **Triage:** consistent with an older bundled server binary, but its exact source revision is not
  exposed by the worker image labels. Verify the image's server build provenance before filing this as
  a regression in current server source. The plan preserves the correct requests and assertions.
