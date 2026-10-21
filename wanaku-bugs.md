# Wanaku test-plan findings

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

## 2. Barn remains unready after disable/re-enable with retained storage

- **Status:** Reproduced; ticket not yet filed. Root cause remains unconfirmed.
- **Severity:** High; blocks Barn availability and the persistence verification after re-enable.
- **Plan:** `plans/operator-basic-functionality.md`, test 7.4.
- **Run:** `run-1e9a305275734b4bbf26f7fe98f80439`, command evidence `ev-92` timed out after
  122007 ms. The preceding Barn-disable test 7.3 passed. The same scenario failed in the preceding full run.
- **Public image:** `quay.io/wanaku/wanaku-barn-backend:latest`. Startup reports
  `wanaku-barn-backend 0.3.0-SNAPSHOT`, Quarkus 3.33.4.
  Resolved digest: `sha256:0a16e16e851290592b224b5a000bd453096fc88a32d62497a46514ae2793ff53`.
- **Reproduction:** Deploy a router with Barn enabled, create service-catalog data, disable Barn while
  retaining its PVC, then patch the router with `{"spec":{"barn":{"enabled":true}}}`.
  Wait for `BarnAvailable=True` and `Ready=True`, then verify the retained catalog data.
- **Observed:** Barn starts successfully in about eight seconds, but remains unready. Repeated
  `GET /q/health/ready` requests return HTTP 503. Sanitized health-check evidence:
  `infinispan=DOWN; failedCaches=catalog-version,catalog-version-counter; degradedCaches=(empty)`.
  `Persistence schema=UP (schemaVersion=1)` and messaging readiness is `UP`; liveness returns HTTP 200.
  Router conditions are `Reconciled=True/ReconcileSucceeded`, `ServerAvailable=True/Available`,
  `BarnAvailable=False/Progressing`, and `Ready=False/Progressing`.
- **Expected:** Barn becomes ready after re-enable, and the retained catalog data can be queried.
- **Limits:** The command timed out before the data query. This proves an availability failure, not data loss.
  The failed cache names identify the failing health component, not the underlying cause.
- **Disclosure:** These notes intentionally omit cluster endpoints, namespaces, pod names, internal addresses,
  credentials, and raw logs. Any ticket must retain that sanitization.
