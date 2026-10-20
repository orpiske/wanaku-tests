# Test Plan: Wanaku Operator Basic Functionality on OpenShift

## Overview

This test plan verifies the basic functionality of the Wanaku Kubernetes operator on OpenShift. It covers operator
installation, the `WanakuRouter` lifecycle, the resources that the operator creates, Wanaku Server behavior through
the generated Routes, the optional Wanaku Barn deployment, `WanakuServiceCatalog` management, authentication through
oauth2-proxy, update reconciliation, and the deletion cascade.

**Components under test:**

| Component       | How the plan uses it                                                                                                                                                                                 |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Wanaku operator | Installed with Helm. Reconciles `WanakuRouter` and `WanakuServiceCatalog`.                                                                                                                           |
| Wanaku Server   | Deployed by the operator (`spec.server`). Deployment `<router>-server`, Service `server-<router>` (ports 8080 management, 8081 MCP, 8083 inference, 8084 A2A), Routes `<router>` and `<router>-mcp`. |
| Wanaku Barn     | Deployed by the operator because `spec.barn.enabled` is `true`. Deployment `<router>-barn`, Service `barn-<router>` (port 8080 to container port 8180). Barn is not exposed outside the cluster.     |
| oauth2-proxy    | Deployed by the operator in Phase 6 (`spec.auth.enabled: true`). Deployment `<router>-oauth2-proxy`, Service `oauth2-proxy-<router>` (ports 4180 MCP, 4181 management).                              |
| Keycloak        | OIDC provider for Phase 6 (see [common/keycloak-setup.md](common/keycloak-setup.md)).                                                                                                                |

The plan does not call tools. Wanaku Server only gets tools from forwards, and the forward workflow on OpenShift is
covered by [camel-integration-capability.md](camel-integration-capability.md).

Every step is fully automatable.

When Saci runs this plan, invoked procedures execute before their parent step. A parent's `Follow` instruction
refers to those completed procedure steps; do not locate the Markdown file in the worker or repeat the procedure.
Assess the parent criteria using the preceding procedure results and any additional state checks needed for evidence.

Run command blocks in Saci's shared Bash session, including procedure blocks, so helper functions and exported
variables remain available to later tests. Source each block in that session; a top-level `return 1` fails the
block without terminating the session. Do not execute blocks as separate scripts or enable `set -e` globally.

**Hard timeout rule:** Any single operation (oc wait, polling loop, port-forward setup, etc.) that takes longer than **3 minutes** MUST be considered broken. Mark the test as FAIL immediately and abort — do not wait longer. All `--timeout` values in this plan MUST NOT exceed `180s`.

> [!NOTE]
> `docs/operator.md` recommends a wait of 300 seconds for `Ready`, because the Server startup probe allows 150
> seconds and image pulls add time. On a slow cluster, pre-pull the images or record the timeout as an environment
> problem, not as an operator failure.

## Prerequisites

### Required tools

| Tool            | Minimum version              | Verify command            |
|-----------------|------------------------------|---------------------------|
| `oc`            | 4.12+                        | `oc version --client`     |
| `helm`          | 3.x                          | `helm version --short`    |
| `curl`          | any (HTTP-level checks only) | `curl --version`          |
| `jq`            | 1.6+                         | `jq --version`            |
| `openssl`       | any (cookie secret)          | `openssl version`         |
| `tar`           | any (source archive)         | `tar --version`           |
| `wanaku` CLI    | build from source            | `${WANAKU_CLI} --version` |

### Environment variables

Before running this plan, an administrator must provision the `wanaku-test-runner` service account and its RBAC
using [the service account setup guide](setup/service-account-setup.md). The local bootstrap script is
`setup/create-service-account.sh`, relative to this plan directory. Provisioning and token generation are
one-time administrative prerequisites; do not run the bootstrap script as part of this test plan.

Provide these inputs to the test environment before Step 0.1:

| Variable | Required | Purpose |
|----------|----------|---------|
| `OPENSHIFT_API_URL` | Yes | Target OpenShift API URL. |
| `OPENSHIFT_SA_TOKEN` | Yes | Secret token for the provisioned `wanaku-test-runner` service account. Do not print it. |
| `WANAKU_SA_NAMESPACE` | No | Namespace containing that service account; defaults to `wanaku-test-infra`. |

`WANAKU_SA_NAMESPACE` identifies the persistent service-account infrastructure. It is separate from
`WANAKU_NAMESPACE`, the unique per-run test namespace created in Step 1.1.

All later phases require a successful login and identity verification in Step 0.1. Use a Saci run policy that
stops on failure so a rejected identity cannot proceed to namespace creation or deployment.

Step S.0 reuses the current directory when it contains the Wanaku Barn operator chart and realm configuration;
otherwise it downloads those assets from `wanaku-ai/wanaku-barn`. Set `WANAKU_BARN_REF` to a branch, tag or commit
(default: `main`) to select the source version. To use an existing checkout instead, explicitly set
`WANAKU_REPO_ROOT` to its root; an invalid explicit path fails rather than triggering a download. Downloaded
sources are isolated in a temporary directory and removed after Helm teardown in Step 10.1.

Supply `WANAKU_KEYCLOAK_ADMIN_CLI` if its executable is not named `wanaku-keycloak-admin`. Both CLIs must already
exist in the worker; the plan does not build or download executable binaries.

All image tags default to `latest` and can be overridden. The setup phase sets the environment variables after
Step 1.1 creates and verifies the unique `WANAKU_NAMESPACE` for this run.

`WANAKU_CLI_TLS_OPTS` and `CURL_TLS_OPTS` are not quoted in the commands below on purpose: each holds zero or one
option.

---

## Phase 0: OpenShift Login

### Step 0.1: Log in to OpenShift

Log in to the target OpenShift cluster using the token for the provisioned `wanaku-test-runner` service account.

Follow [common/openshift-login.md](common/openshift-login.md).

After the login procedure, verify the exact identity before creating the test namespace. Reject personal accounts
and other service accounts. Run this block in the shared Bash session.

```bash run
EXPECTED_IDENTITY="system:serviceaccount:${WANAKU_SA_NAMESPACE:-wanaku-test-infra}:wanaku-test-runner"
if ! ACTUAL_IDENTITY=$(oc whoami 2>/dev/null); then
  echo "FAIL: cannot verify the logged-in service account"
  return 1
fi
if [ "${ACTUAL_IDENTITY}" != "${EXPECTED_IDENTITY}" ]; then
  echo "FAIL: logged-in identity is not the configured wanaku-test-runner service account"
  return 1
fi
echo "PASS: logged in as the configured wanaku-test-runner service account"
```

**Expected result:**

- The procedure logs in to the target OpenShift cluster using a service account token.
- The output includes `PASS: logged in as the configured wanaku-test-runner service account`.
- The output contains no line starting with `FAIL:`.
- The identity verification script exits with code 0.


---

## Phase 1: Environment Setup

### Step 1.1: Create namespace

Create and verify the namespace.

Follow [common/namespace-setup.md](common/namespace-setup.md).

**Expected result:**

- The namespace is created and verified.

## Phase S: Setup

### Step S.0: Prepare the operator Helm chart and realm configuration

Reuse a supplied Wanaku Barn checkout, or download its source archive from the official repository. Keep the
chart available through Helm teardown. Run this block in the shared Bash session.

```bash run
WANAKU_DOWNLOADED_SOURCE_DIR=""
export WANAKU_BARN_REF="${WANAKU_BARN_REF:-main}"
if [ -n "${WANAKU_REPO_ROOT+x}" ]; then
  if [ ! -r "${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
      || [ ! -r "${WANAKU_REPO_ROOT}/deploy/auth/wanaku-config.json" ]; then
    echo "FAIL: supplied WANAKU_REPO_ROOT lacks the operator chart or realm configuration"
    return 1
  fi
  export WANAKU_REPO_ROOT
  echo "PASS: existing Wanaku Barn source assets reused"
elif [ -r "./apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
    && [ -r "./deploy/auth/wanaku-config.json" ]; then
  export WANAKU_REPO_ROOT="${PWD}"
  echo "PASS: existing Wanaku Barn source assets reused"
else
  for CMD in curl tar; do
    command -v "${CMD}" > /dev/null 2>&1 || { echo "FAIL: ${CMD} is required to download source assets"; return 1; }
  done
  WANAKU_DOWNLOADED_SOURCE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/wanaku-operator-source.XXXXXX") || return 1
  mkdir "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo" || return 1
  curl --fail --location --connect-timeout 10 --max-time 120 \
    "https://github.com/wanaku-ai/wanaku-barn/archive/${WANAKU_BARN_REF}.tar.gz" \
    --output "${WANAKU_DOWNLOADED_SOURCE_DIR}/source.tar.gz" \
    || { echo "FAIL: Wanaku Barn source archive download failed"; return 1; }
  tar -xzf "${WANAKU_DOWNLOADED_SOURCE_DIR}/source.tar.gz" \
    --strip-components=1 -C "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo" \
    || { echo "FAIL: Wanaku Barn source archive extraction failed"; return 1; }
  if [ ! -r "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
      || [ ! -r "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo/deploy/auth/wanaku-config.json" ]; then
    echo "FAIL: downloaded source lacks the operator chart or realm configuration"
    return 1
  fi
  export WANAKU_REPO_ROOT="${WANAKU_DOWNLOADED_SOURCE_DIR}/repo"
  echo "PASS: Wanaku Barn source assets downloaded"
fi
```

**Expected result:**

- The output includes `PASS: existing Wanaku Barn source assets reused` or `PASS: Wanaku Barn source assets downloaded`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test S.1: Prerequisite check script

Run this block in the shared Bash session.

```bash run
FAIL=0
for CMD in oc helm curl jq openssl tar; do
  if ! command -v "${CMD}" > /dev/null 2>&1; then
    echo "FAIL: ${CMD} is not installed"
    FAIL=1
  else
    echo "PASS: ${CMD} found at $(command -v "${CMD}")"
  fi
done

if ! ${WANAKU_CLI:-wanaku} --help > /dev/null 2>&1; then
  echo "FAIL: the Wanaku CLI (${WANAKU_CLI:-wanaku}) does not run"
  FAIL=1
else
  echo "PASS: the Wanaku CLI runs"
fi

if ! ${WANAKU_KEYCLOAK_ADMIN_CLI:-wanaku-keycloak-admin} --help > /dev/null 2>&1; then
  echo "FAIL: the Keycloak admin CLI does not run"
  FAIL=1
else
  echo "PASS: the Keycloak admin CLI runs"
fi

for ASSET in "${WANAKU_REPO_ROOT:-.}/deploy/auth/wanaku-config.json" \
    "${WANAKU_REPO_ROOT:-.}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml"; do
  if [ -r "${ASSET}" ]; then
    echo "PASS: required deployment asset is readable: ${ASSET}"
  else
    echo "FAIL: required deployment asset is missing or unreadable: ${ASSET}"
    FAIL=1
  fi
done

[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || { echo "FAIL: one or more prerequisites missing"; return 1; }
```

**Expected result:**

- The output includes `PASS: oc found at`.
- The output includes `PASS: helm found at`.
- The output includes `PASS: curl found at`.
- The output includes `PASS: jq found at`.
- The output includes `PASS: openssl found at`.
- The output includes `PASS: tar found at`.
- The output includes `PASS: the Wanaku CLI runs`.
- The output includes `PASS: the Keycloak admin CLI runs`.
- The output includes `PASS: required deployment asset is readable:` for the realm configuration and operator chart.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step S.2: Set environment variables

All image tags default to `latest` and can be overridden.

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_BARN_IMAGE="${WANAKU_BARN_IMAGE:-quay.io/wanaku/wanaku-barn-backend:latest}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-test-router}"
# Wanaku CLI: an installed binary, or "java -jar ${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
export WANAKU_CLI="${WANAKU_CLI:-wanaku}"
# The operator creates edge-terminated Routes. Set these to empty values when the ingress certificate is trusted.
export WANAKU_CLI_TLS_OPTS="${WANAKU_CLI_TLS_OPTS---insecure}"
export CURL_TLS_OPTS="${CURL_TLS_OPTS--k}"
# Local port for "oc port-forward" to the Barn Service
export BARN_LOCAL_PORT="${BARN_LOCAL_PORT:-18180}"
# Isolate credentials per test run to avoid contention (see #1697)
export WANAKU_CREDENTIALS="${WANAKU_CREDENTIALS:-/tmp/wanaku-creds-${WANAKU_NAMESPACE}}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step S.3: Define the wait_for_deletion helper

Define the `wait_for_deletion` function.

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the `wait_for_deletion` function without errors (inferred).

### Step S.4: Define the custom resource condition helpers

The function polls a condition until it has the expected status and, optionally, the expected reason. It fails after
180 seconds.

```bash run
wait_cr_condition() {
  # $1 = kind, $2 = name, $3 = condition type, $4 = expected status, $5 = expected reason (optional)
  WCC_END=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "${WCC_END}" ]; do
    WCC_STATUS=$(oc get "$1" "$2" -n "${WANAKU_NAMESPACE}" \
      -o jsonpath="{.status.conditions[?(@.type==\"$3\")].status}" 2>/dev/null)
    WCC_REASON=$(oc get "$1" "$2" -n "${WANAKU_NAMESPACE}" \
      -o jsonpath="{.status.conditions[?(@.type==\"$3\")].reason}" 2>/dev/null)
    if [ "${WCC_STATUS}" = "$4" ] && { [ -z "$5" ] || [ "${WCC_REASON}" = "$5" ]; }; then
      echo "PASS: $1/$2 condition $3=${WCC_STATUS} (reason ${WCC_REASON})"
      return 0
    fi
    sleep 3
  done
  echo "FAIL: $1/$2 condition $3 is '${WCC_STATUS}' (reason '${WCC_REASON}'), expected '$4' ${5:+(reason $5)}"
  oc get "$1" "$2" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.conditions}'; echo
  return 1
}

router_jsonpath() {
  oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"
}

# Compares a value with the expected value. Sets FAIL=1 on a mismatch. Each test resets FAIL=0 first.
check() { # $1 = description, $2 = actual, $3 = expected
  if [ "$2" = "$3" ]; then echo "PASS: $1 = $2"; else echo "FAIL: $1 = '$2', expected '$3'"; FAIL=1; fi
}
```

**Expected result:**

- The block defines the `wait_cr_condition`, `router_jsonpath` and `check` functions without errors (inferred).

### Step S.5: Define the Wanaku Barn port-forward helpers

Barn has no Route. The CLI reaches it through `oc port-forward`. The function polls the Barn readiness endpoint
instead of a fixed sleep.

```bash run
start_barn_port_forward() {
  oc port-forward "service/barn-${WANAKU_ROUTER_NAME}" "${BARN_LOCAL_PORT}:8080" \
    -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 &
  BARN_PF_PID=$!
  for i in $(seq 1 30); do
    if curl --connect-timeout 1 --max-time 2 -s -o /dev/null -f "http://localhost:${BARN_LOCAL_PORT}/q/health/ready" 2>/dev/null; then
      echo "PASS: Barn reachable at http://localhost:${BARN_LOCAL_PORT} (attempt ${i})"
      export WANAKU_BARN_URL="http://localhost:${BARN_LOCAL_PORT}"
      return 0
    fi
    sleep 1
  done
  echo "FAIL: port-forward to barn-${WANAKU_ROUTER_NAME} did not become ready"
  return 1
}

stop_barn_port_forward() {
  if [ -n "${BARN_PF_PID}" ]; then
    kill "${BARN_PF_PID}" 2>/dev/null
    wait "${BARN_PF_PID}" 2>/dev/null
    unset BARN_PF_PID
  fi
}
```

**Expected result:**

- The block defines the `start_barn_port_forward` and `stop_barn_port_forward` functions without errors (inferred).


---

## Phase 1: Environment Setup (continued)

### Step 1.2: Deploy Keycloak

Follow [common/keycloak-setup.md](common/keycloak-setup.md).

Keycloak is used only in Phase 6. After completion,
verify these variables are set:

Run this block in the shared Bash session.

```bash run
for VAR_NAME in KEYCLOAK_URL WANAKU_CLIENT_SECRET WANAKU_AUTH_SECRET_NAME WANAKU_AUTH_ISSUER_URL WANAKU_AUTH_INTERNAL_ISSUER_URL WANAKU_TEST_USER WANAKU_TEST_PASS; do
  eval "VAL=\${${VAR_NAME}}"
  if [ -z "${VAL}" ]; then
    echo "FAIL: ${VAR_NAME} is not set"
    return 1
  fi
  echo "PASS: ${VAR_NAME} is set"
done
```

**Expected result:**

- The output includes `PASS: KEYCLOAK_URL is set`.
- The output includes `PASS: WANAKU_CLIENT_SECRET is set`.
- The output includes `PASS: WANAKU_AUTH_SECRET_NAME is set`.
- The output includes `PASS: WANAKU_AUTH_ISSUER_URL is set`.
- The output includes `PASS: WANAKU_AUTH_INTERNAL_ISSUER_URL is set`.
- The output includes `PASS: WANAKU_TEST_USER is set`.
- The output includes `PASS: WANAKU_TEST_PASS is set`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 2: Operator Installation

### Step 2.1: Install the operator

Install the operator via Helm and verify CRDs, RBAC, and health endpoints.

Follow [common/operator-deployment.md](common/operator-deployment.md).

**Expected result:**

- The operator is installed via Helm, and the procedure verifies CRDs, RBAC, and health endpoints.


---

## Phase 3: WanakuRouter Lifecycle

### Test 3.1: Create a WanakuRouter with Wanaku Barn and without authentication

**Description:** Create a `WanakuRouter` with `spec.barn.enabled: true`. Authentication stays disabled until Phase 6.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  imagePullPolicy: Always
  server:
    image: ${WANAKU_SERVER_IMAGE}
  barn:
    enabled: true
    image: ${WANAKU_BARN_IMAGE}
    storage:
      size: 1Gi
EOF
```

**Verification - CR accepted and Ready:**

```bash run
oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o name
echo "router-cr-exists=$?"
# Expected: router-cr-exists=0

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"
# Expected output: wanakurouter.wanaku.ai/wanaku-test-router condition met
```

**Expected result:**

- The output includes `router-cr-exists=0`.
- The output reports the `WanakuRouter` condition is met.

### Test 3.2: Verify the router conditions

**Description:** `Ready` is `True` only when every other condition is `True`. Without authentication, `AuthConfigured`
is `True` with the reason `AuthDisabled`, and the operator records one `Warning` event.

Run this block in the shared Bash session.

```bash run
FAIL=0
for EXPECTED in "Reconciled:True:ReconcileSucceeded" "ServerAvailable:True:Available" \
    "BarnAvailable:True:Available" "AuthConfigured:True:AuthDisabled" "ExposureReady:True:Exposed" \
    "Ready:True:"; do
  TYPE=$(echo "${EXPECTED}" | cut -d: -f1)
  STATUS=$(echo "${EXPECTED}" | cut -d: -f2)
  REASON=$(echo "${EXPECTED}" | cut -d: -f3)
  wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" "${TYPE}" "${STATUS}" "${REASON}" || FAIL=1
done

# Without auth there is no AuthProxyAvailable condition
[ -z "$(router_jsonpath '{.status.conditions[?(@.type=="AuthProxyAvailable")].status}')" ] \
  && echo "PASS: no AuthProxyAvailable condition without auth" \
  || { echo "FAIL: AuthProxyAvailable condition present without auth"; FAIL=1; }

GEN=$(router_jsonpath '{.metadata.generation}')
OBS=$(router_jsonpath '{.status.observedGeneration}')
[ "${GEN}" = "${OBS}" ] && echo "PASS: observedGeneration=${OBS}" \
  || { echo "FAIL: observedGeneration=${OBS}, generation=${GEN}"; FAIL=1; }

oc get events -n "${WANAKU_NAMESPACE}" --field-selector "involvedObject.name=${WANAKU_ROUTER_NAME},type=Warning" \
  -o jsonpath='{.items[*].reason}' | grep -q "AuthDisabled" \
  && echo "PASS: Warning event AuthDisabled recorded" \
  || echo "WARN: no AuthDisabled Warning event found (events may have expired)"

[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=True (reason ReconcileSucceeded)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition ServerAvailable=True (reason Available)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition BarnAvailable=True (reason Available)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=True (reason AuthDisabled)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition ExposureReady=True (reason Exposed)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=True`.
- The output includes `PASS: no AuthProxyAvailable condition without auth`.
- The output includes `PASS: observedGeneration=`.
- The output includes `PASS: Warning event AuthDisabled recorded` or `WARN: no AuthDisabled Warning event found (events may have expired)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.3: Verify the Wanaku Server Deployment

**Description:** The operator creates the `<router>-server` Deployment with the requested image, the global pull
policy, the persistence settings and the `/healthz` probes on the named `management` port.

Run this block in the shared Bash session.

```bash run
DEPLOYMENT="${WANAKU_ROUTER_NAME}-server"
oc wait "deployment/${DEPLOYMENT}" --for=condition=Available --timeout=180s -n "${WANAKU_NAMESPACE}"

dep_jsonpath() { oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
FAIL=0

check "image" "$(dep_jsonpath '{.spec.template.spec.containers[0].image}')" "${WANAKU_SERVER_IMAGE}"
check "imagePullPolicy" "$(dep_jsonpath '{.spec.template.spec.containers[0].imagePullPolicy}')" "Always"
check "container name" "$(dep_jsonpath '{.spec.template.spec.containers[0].name}')" "wanaku-server"
check "WANAKU_MGMT_LISTEN" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_MGMT_LISTEN")].value}')" "0.0.0.0:8080"
check "WANAKU_PERSIST_BACKEND" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_PERSIST_BACKEND")].value}')" "file"
check "WANAKU_PERSIST_PATH" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_PERSIST_PATH")].value}')" "/data/registry"
for PROBE in livenessProbe readinessProbe startupProbe; do
  check "${PROBE} path" "$(dep_jsonpath "{.spec.template.spec.containers[0].${PROBE}.httpGet.path}")" "/healthz"
  check "${PROBE} port" "$(dep_jsonpath "{.spec.template.spec.containers[0].${PROBE}.httpGet.port}")" "management"
done
check "registry volume claim" "$(dep_jsonpath '{.spec.template.spec.volumes[?(@.name=="server-volume")].persistentVolumeClaim.claimName}')" "server-volume-claim"
# Barn is enabled, so the Server reads the operator-generated wanaku.yaml
check "wanaku.yaml mount" "$(dep_jsonpath '{.spec.template.spec.containers[0].volumeMounts[?(@.name=="server-config")].mountPath}')" "/etc/wanaku/wanaku.yaml"
[ -n "$(dep_jsonpath '{.spec.template.metadata.annotations.wanaku\.ai/server-config-hash}')" ] \
  && echo "PASS: pod template has the wanaku.ai/server-config-hash annotation" \
  || { echo "FAIL: wanaku.ai/server-config-hash annotation missing"; FAIL=1; }

[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: image =`.
- The output includes `PASS: imagePullPolicy =`.
- The output includes `PASS: container name =`.
- The output includes `PASS: WANAKU_MGMT_LISTEN =`.
- The output includes `PASS: WANAKU_PERSIST_BACKEND =`.
- The output includes `PASS: WANAKU_PERSIST_PATH =`.
- The output includes `PASS: livenessProbe path =`.
- The output includes `PASS: livenessProbe port =`.
- The output includes `PASS: readinessProbe path =`.
- The output includes `PASS: readinessProbe port =`.
- The output includes `PASS: startupProbe path =`.
- The output includes `PASS: startupProbe port =`.
- The output includes `PASS: registry volume claim =`.
- The output includes `PASS: wanaku.yaml mount =`.
- The output includes `PASS: pod template has the wanaku.ai/server-config-hash annotation`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.4: Verify the Wanaku Server Service

**Description:** The `server-<router>` ClusterIP Service exposes the four listener ports.

Run this block in the shared Bash session.

```bash run
SVC="server-${WANAKU_ROUTER_NAME}"
SVC_TYPE=$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.type}')
[ "${SVC_TYPE}" = "ClusterIP" ] && echo "PASS: ${SVC} is ClusterIP" || { echo "FAIL: ${SVC} type is '${SVC_TYPE}'"; return 1; }

PORTS=$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{range .spec.ports[*]}{.name}={.port}{" "}{end}')
echo "ports: ${PORTS}"
for EXPECTED in management=8080 mcp=8081 inference=8083 a2a=8084; do
  echo " ${PORTS} " | grep -q " ${EXPECTED} " && echo "PASS: port ${EXPECTED}" || { echo "FAIL: port ${EXPECTED} missing"; return 1; }
done
```

**Expected result:**

- The output includes `PASS: <SVC> is ClusterIP`.
- The output includes `PASS: port management=8080`.
- The output includes `PASS: port mcp=8081`.
- The output includes `PASS: port inference=8083`.
- The output includes `PASS: port a2a=8084`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.5: Verify the Wanaku Barn Deployment and Service

**Description:** With `spec.barn.enabled: true`, the operator creates the `<router>-barn` Deployment and the
`barn-<router>` Service. Barn listens on 8180. The Service exposes 8080. The probes use the Quarkus health endpoints.

Run this block in the shared Bash session.

```bash run
DEPLOYMENT="${WANAKU_ROUTER_NAME}-barn"
oc wait "deployment/${DEPLOYMENT}" --for=condition=Available --timeout=180s -n "${WANAKU_NAMESPACE}"

dep_jsonpath() { oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
FAIL=0
check "Barn image" "$(dep_jsonpath '{.spec.template.spec.containers[0].image}')" "${WANAKU_BARN_IMAGE}"
check "Barn imagePullPolicy" "$(dep_jsonpath '{.spec.template.spec.containers[0].imagePullPolicy}')" "Always"
check "Barn liveness" "$(dep_jsonpath '{.spec.template.spec.containers[0].livenessProbe.httpGet.path}')" "/q/health/live"
check "Barn readiness" "$(dep_jsonpath '{.spec.template.spec.containers[0].readinessProbe.httpGet.path}')" "/q/health/ready"
check "Barn startup" "$(dep_jsonpath '{.spec.template.spec.containers[0].startupProbe.httpGet.path}')" "/q/health/started"
check "Barn data claim" "$(dep_jsonpath '{.spec.template.spec.volumes[?(@.name=="barn-volume")].persistentVolumeClaim.claimName}')" "${WANAKU_ROUTER_NAME}-barn-data"
check "Barn persistence folder" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_PERSISTENCE_INFINISPAN_BASE_FOLDER")].value}')" "/home/default/.wanaku/barn/"

SVC="barn-${WANAKU_ROUTER_NAME}"
check "Barn Service type" "$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.type}')" "ClusterIP"
check "Barn Service port" "$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[0].port}')" "8080"
check "Barn Service targetPort" "$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[0].targetPort}')" "8180"
check "status.barnUrl" "$(router_jsonpath '{.status.barnUrl}')" "http://barn-${WANAKU_ROUTER_NAME}:8080"

[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: Barn image =`.
- The output includes `PASS: Barn imagePullPolicy =`.
- The output includes `PASS: Barn liveness =`.
- The output includes `PASS: Barn readiness =`.
- The output includes `PASS: Barn startup =`.
- The output includes `PASS: Barn data claim =`.
- The output includes `PASS: Barn persistence folder =`.
- The output includes `PASS: Barn Service type =`.
- The output includes `PASS: Barn Service port =`.
- The output includes `PASS: Barn Service targetPort =`.
- The output includes `PASS: status.barnUrl =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.6: Verify the PVCs

**Description:** The Server stores `registry.json` on the `server-volume-claim` PVC. Barn stores its data on the
`<router>-barn-data` PVC, with the size from `spec.barn.storage.size`.

Run this block in the shared Bash session.

```bash run
FAIL=0
for PVC in server-volume-claim "${WANAKU_ROUTER_NAME}-barn-data"; do
  PHASE=$(oc get pvc "${PVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.phase}' 2>/dev/null)
  check "PVC ${PVC} phase" "${PHASE}" "Bound"
done
check "Barn PVC size" "$(oc get pvc "${WANAKU_ROUTER_NAME}-barn-data" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.resources.requests.storage}')" "1Gi"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: PVC server-volume-claim phase =`.
- The output includes `PASS: PVC <WANAKU_ROUTER_NAME>-barn-data phase =`.
- The output includes `PASS: Barn PVC size =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.7: Verify the Server configuration ConfigMap

**Description:** The operator writes `<router>-server-config`. Its `wanaku.yaml` points the `wanaku-barn` plugin
service `wanaku-barn-api` at the Barn Service.

Run this block in the shared Bash session.

```bash run
CONFIG=$(oc get configmap "${WANAKU_ROUTER_NAME}-server-config" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}')
echo "${CONFIG}"
FAIL=0
for TEXT in "id: wanaku-barn" "wanaku-barn-api:" "target: http://barn-${WANAKU_ROUTER_NAME}:8080"; do
  echo "${CONFIG}" | grep -q "${TEXT}" && echo "PASS: wanaku.yaml contains '${TEXT}'" \
    || { echo "FAIL: wanaku.yaml does not contain '${TEXT}'"; FAIL=1; }
done
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: wanaku.yaml contains 'id: wanaku-barn'`.
- The output includes `PASS: wanaku.yaml contains 'wanaku-barn-api:'`.
- The output includes `PASS: wanaku.yaml contains 'target: http://barn-<WANAKU_ROUTER_NAME>:8080'`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.8: Verify the OpenShift Routes

**Description:** Without `spec.exposure`, the operator creates two Routes on OpenShift. The `<router>` Route sends
`/` to the management port 8080. The `<router>-mcp` Route sends `/` to the MCP port 8081. Both use edge TLS
termination with a redirect from HTTP.

Run this block in the shared Bash session.

```bash run
FAIL=0
for ENTRY in "${WANAKU_ROUTER_NAME}:8080" "${WANAKU_ROUTER_NAME}-mcp:8081"; do
  ROUTE=$(echo "${ENTRY}" | cut -d: -f1)
  PORT=$(echo "${ENTRY}" | cut -d: -f2)
  route_jsonpath() { oc get route "${ROUTE}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
  [ -n "$(route_jsonpath '{.spec.host}')" ] && echo "PASS: Route ${ROUTE} has a host" \
    || { echo "FAIL: Route ${ROUTE} has no host"; FAIL=1; }
  check "Route ${ROUTE} service" "$(route_jsonpath '{.spec.to.name}')" "server-${WANAKU_ROUTER_NAME}"
  check "Route ${ROUTE} targetPort" "$(route_jsonpath '{.spec.port.targetPort}')" "${PORT}"
  check "Route ${ROUTE} path" "$(route_jsonpath '{.spec.path}')" "/"
  check "Route ${ROUTE} TLS" "$(route_jsonpath '{.spec.tls.termination}')" "edge"
  check "Route ${ROUTE} admitted" "$(route_jsonpath '{.status.ingress[0].conditions[?(@.type=="Admitted")].status}')" "True"
done
[ "${FAIL}" -eq 0 ] || return 1

export WANAKU_ROUTER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_ROUTER_MCP_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
echo "management: ${WANAKU_ROUTER_URL}"
echo "mcp:        ${WANAKU_ROUTER_MCP_URL}"
```

**Expected result:**

- The output includes `PASS: Route <WANAKU_ROUTER_NAME> has a host`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> service =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> targetPort =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> path =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> TLS =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> admitted =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp has a host`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp service =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp targetPort =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp path =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp TLS =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp admitted =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.9: Verify the status endpoints

**Description:** `status.host` contains the management URL. `status.endpoints` contains one URL per exposed listener
and, for the standard Server variant, the admin UI URL.

Run this block in the shared Bash session.

```bash run
FAIL=0
MCP_HOST=$(echo "${WANAKU_ROUTER_MCP_URL}" | sed 's|^https://||')
check "status.host" "$(router_jsonpath '{.status.host}')" "${WANAKU_ROUTER_URL}"
check "status.endpoints.management" "$(router_jsonpath '{.status.endpoints.management}')" "${WANAKU_ROUTER_URL}/"
check "status.endpoints.mcp" "$(router_jsonpath '{.status.endpoints.mcp}')" "${WANAKU_ROUTER_MCP_URL}/mcp"
check "status.endpoints.adminUi" "$(router_jsonpath '{.status.endpoints.adminUi}')" "${WANAKU_ROUTER_URL}/admin/"

STREAMABLE=$(router_jsonpath '{.status.streamableEndpoint}')
case "${STREAMABLE}" in
  "https://${MCP_HOST}/"*) echo "PASS: status.streamableEndpoint=${STREAMABLE}" ;;
  *) echo "FAIL: status.streamableEndpoint='${STREAMABLE}'"; FAIL=1 ;;
esac
[ "${FAIL}" -eq 0 ] || return 1
```

> [!WARNING]
> **Known issue:** `status.streamableEndpoint` (`https://<mcp-host>/mcp/`), `status.endpoints.mcp`
> (`https://<mcp-host>/mcp`) and the obsolete `status.sseEndpoint` do not contain a namespace. Wanaku Server rejects
> the bare `/mcp` path (Test 4.4). MCP clients must use `${WANAKU_ROUTER_MCP_URL}/<namespace>/mcp`. Record the values
> above as PASS, and report the issue if it is still open.

**Expected result:**

- The output includes `PASS: status.host =`.
- The output includes `PASS: status.endpoints.management =`.
- The output includes `PASS: status.endpoints.mcp =`.
- The output includes `PASS: status.endpoints.adminUi =`.
- The output includes `PASS: status.streamableEndpoint=`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.10: Verify owner references

**Description:** All operator-managed resources have an owner reference to the `WanakuRouter`. Kubernetes uses it to
delete them in Phase 9.

Run this block in the shared Bash session.

```bash run
ROUTER_UID=$(router_jsonpath '{.metadata.uid}')
FAIL=0
for RESOURCE in "deployment/${WANAKU_ROUTER_NAME}-server" "service/server-${WANAKU_ROUTER_NAME}" \
    "deployment/${WANAKU_ROUTER_NAME}-barn" "service/barn-${WANAKU_ROUTER_NAME}" \
    "configmap/${WANAKU_ROUTER_NAME}-server-config" "pvc/server-volume-claim" "pvc/${WANAKU_ROUTER_NAME}-barn-data" \
    "route.route.openshift.io/${WANAKU_ROUTER_NAME}" "route.route.openshift.io/${WANAKU_ROUTER_NAME}-mcp"; do
  OWNER_UID=$(oc get "${RESOURCE}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.metadata.ownerReferences[0].uid}' 2>/dev/null)
  check "${RESOURCE} owner" "${OWNER_UID}" "${ROUTER_UID}"
done

# The operator labels the Deployments that it manages
for DEPLOYMENT in "${WANAKU_ROUTER_NAME}-server" "${WANAKU_ROUTER_NAME}-barn"; do
  check "${DEPLOYMENT} managed-by label" \
    "$(oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.metadata.labels.app\.kubernetes\.io/managed-by}')" \
    "wanaku-operator"
done
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-server owner =`.
- The output includes `PASS: service/server-<WANAKU_ROUTER_NAME> owner =`.
- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-barn owner =`.
- The output includes `PASS: service/barn-<WANAKU_ROUTER_NAME> owner =`.
- The output includes `PASS: configmap/<WANAKU_ROUTER_NAME>-server-config owner =`.
- The output includes `PASS: pvc/server-volume-claim owner =`.
- The output includes `PASS: pvc/<WANAKU_ROUTER_NAME>-barn-data owner =`.
- The output includes `PASS: route.route.openshift.io/<WANAKU_ROUTER_NAME> owner =`.
- The output includes `PASS: route.route.openshift.io/<WANAKU_ROUTER_NAME>-mcp owner =`.
- The output includes `PASS: <WANAKU_ROUTER_NAME>-server managed-by label = wanaku-operator`.
- The output includes `PASS: <WANAKU_ROUTER_NAME>-barn managed-by label = wanaku-operator`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 4: Wanaku Server Through the Routes

Authentication is disabled in this phase. Use `--no-auth` for the CLI.

### Test 4.1: Verify the health endpoints

**Description:** `GET /health` and `GET /healthz` return `200` with the response envelope `{"data":{"status":"ok"},"error":null}`.
`curl` is used because this test checks HTTP-level behavior.

Run this block in the shared Bash session.

```bash run
for PATH_SUFFIX in /health /healthz; do
  BODY=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -w '\n%{http_code}' "${WANAKU_ROUTER_URL}${PATH_SUFFIX}")
  CODE=$(echo "${BODY}" | tail -1)
  STATUS=$(echo "${BODY}" | sed '$d' | jq -r '.data.status' 2>/dev/null)
  if [ "${CODE}" = "200" ] && [ "${STATUS}" = "ok" ]; then
    echo "PASS: ${PATH_SUFFIX} returns 200 and status ok"
  else
    echo "FAIL: ${PATH_SUFFIX} returned HTTP ${CODE}, status '${STATUS}'"
    return 1
  fi
done
```

**Expected result:**

- The output includes `PASS: /health returns 200 and status ok`.
- The output includes `PASS: /healthz returns 200 and status ok`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.2: Verify the management API with the CLI

**Description:** The CLI lists the namespaces through the management Route. The `default` namespace always exists.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} namespaces list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "^default"; then
  echo "PASS: namespace 'default' listed through ${WANAKU_ROUTER_URL}"
else
  echo "FAIL: namespaces list failed (exit code ${EXIT_CODE})"
  return 1
fi
```

**Expected result:**

- The output includes `PASS: namespace 'default' listed through`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.3: Create a namespace, and reject an invalid name

**Description:** Namespace names are DNS labels. The Server rejects a name with uppercase letters or underscores.

Run this block in the shared Bash session.

```bash run
export OP_TEST_NAMESPACE="${OP_TEST_NAMESPACE:-op-test-ns}"

OUTPUT=$(${WANAKU_CLI} namespaces create "${OP_TEST_NAMESPACE}" --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "Namespace created: ${OP_TEST_NAMESPACE}" \
  && echo "PASS: namespace ${OP_TEST_NAMESPACE} created" \
  || { echo "FAIL: namespace ${OP_TEST_NAMESPACE} not created"; return 1; }

# Negative test
OUTPUT=$(${WANAKU_CLI} namespaces create "Bad_NS" --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "lowercase alphanumeric"; then
  echo "PASS: invalid namespace name rejected"
else
  echo "FAIL: invalid namespace name was not rejected (exit code ${EXIT_CODE})"
  return 1
fi
```

**Expected result:**

- The output includes `PASS: namespace <OP_TEST_NAMESPACE> created`.
- The output includes `PASS: invalid namespace name rejected`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.4: Verify the MCP endpoint through the MCP Route

**Description:** The MCP endpoint is `<mcp-route>/<namespace>/mcp` (Streamable HTTP). No forward exists, so the tool
list is empty. The bare `/mcp` path is rejected with the JSON-RPC error `-32600`.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: MCP endpoint ${WANAKU_ROUTER_MCP_URL}/default/mcp answers" \
  || { echo "FAIL: MCP tool list failed (exit code ${EXIT_CODE})"; return 1; }

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/${OP_TEST_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
[ $? -eq 0 ] && echo "PASS: namespace endpoint /${OP_TEST_NAMESPACE}/mcp answers" \
  || { echo "FAIL: namespace endpoint failed: ${OUTPUT}"; return 1; }

# Negative test: bare /mcp
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "invalid MCP endpoint path"; then
  echo "PASS: bare /mcp rejected (-32600 invalid MCP endpoint path)"
else
  echo "FAIL: bare /mcp was not rejected (exit code ${EXIT_CODE}): ${OUTPUT}"
  return 1
fi
```

**Expected result:**

- The output includes `PASS: MCP endpoint <WANAKU_ROUTER_MCP_URL>/default/mcp answers`.
- The output includes `PASS: namespace endpoint /<OP_TEST_NAMESPACE>/mcp answers`.
- The output includes `PASS: bare /mcp rejected (-32600 invalid MCP endpoint path)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.5: Verify the admin UI and the Barn plugin proxy

**Description:** The standard Server variant serves the admin UI at `/admin/`. Because Barn is enabled, the Server
proxies `/api/plugins/wanaku-barn/wanaku-barn-api/...` to `http://barn-<router>:8080`. `curl` is used because this
test checks HTTP-level routing.

Run this block in the shared Bash session.

```bash run
CODE=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/admin/")
[ "${CODE}" = "200" ] && echo "PASS: admin UI returns 200" || { echo "FAIL: admin UI returned HTTP ${CODE}"; return 1; }

CODE=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' \
  "${WANAKU_ROUTER_URL}/api/plugins/wanaku-barn/wanaku-barn-api/api/v1/service-catalog")
[ "${CODE}" = "200" ] && echo "PASS: the Server proxies the Barn service catalog API" \
  || { echo "FAIL: Barn plugin proxy returned HTTP ${CODE} (404 'service wanaku-barn-api not found' means the wanaku.yaml is not loaded)"; return 1; }
```

**Expected result:**

- The output includes `PASS: admin UI returns 200`.
- The output includes `PASS: the Server proxies the Barn service catalog API`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.6: Verify Barn with the CLI

Run this block in the shared Bash session.

```bash run
start_barn_port_forward || return 1

OUTPUT=$(${WANAKU_CLI} service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: the CLI can list the Barn service catalogs" \
  || { echo "FAIL: service catalog list failed (exit code ${EXIT_CODE})"; stop_barn_port_forward; return 1; }

stop_barn_port_forward
```

**Expected result:**

- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: the CLI can list the Barn service catalogs`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.7: Verify the Server registry survives a pod restart

**Description:** The Server writes `registry.json` to `/data/registry` on the `server-volume-claim` PVC. A namespace
created in Test 4.3 must still exist after the pod is replaced.

Run this block in the shared Bash session.

```bash run
OLD_POD=$(oc get pods -l "app=${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
oc delete pod "${OLD_POD}" -n "${WANAKU_NAMESPACE}" --wait=true --timeout=120s

oc rollout status "deployment/${WANAKU_ROUTER_NAME}-server" --timeout=180s -n "${WANAKU_NAMESPACE}"
oc wait pod -l "app=${WANAKU_ROUTER_NAME}-server" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"

OUTPUT=$(${WANAKU_CLI} namespaces list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${OP_TEST_NAMESPACE}" \
  && echo "PASS: namespace ${OP_TEST_NAMESPACE} persisted across the pod restart" \
  || { echo "FAIL: namespace ${OP_TEST_NAMESPACE} lost after the pod restart"; echo "${OUTPUT}"; return 1; }
```

**Expected result:**

- The output includes `PASS: namespace <OP_TEST_NAMESPACE> persisted across the pod restart`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 5: WanakuServiceCatalog Lifecycle

### Test 5.1: Package a catalog and create the ConfigMap

**Description:** `wanaku service package` writes the Base64-encoded ZIP that the operator expects under the
`catalog.zip` key.

Run this block in the shared Bash session.

```bash run
export OP_CATALOG_NAME="${OP_CATALOG_NAME:-op-test-catalog}"
TEMP_DIR=$(mktemp -d)
mkdir -p "${TEMP_DIR}/catalog/${OP_CATALOG_NAME}"
cat > "${TEMP_DIR}/catalog/index.properties" <<PROPS
catalog.name=${OP_CATALOG_NAME}
catalog.description=Minimal catalog for operator verification
catalog.services=${OP_CATALOG_NAME}
catalog.routes.${OP_CATALOG_NAME}=${OP_CATALOG_NAME}/${OP_CATALOG_NAME}.camel.yaml
PROPS
cat > "${TEMP_DIR}/catalog/${OP_CATALOG_NAME}/${OP_CATALOG_NAME}.camel.yaml" <<'ROUTES'
- route:
    id: op-echo
    from:
      uri: ai-tool:op-echo
      parameters:
        description: "Echo back the input message"
        parameter.message: string
        parameter.message.description: "The message to echo back"
      steps:
        - setBody:
            simple: "${header.message}-${header.message}"
ROUTES

${WANAKU_CLI} service package --path="${TEMP_DIR}/catalog" -o "${TEMP_DIR}/catalog.b64"
[ -s "${TEMP_DIR}/catalog.b64" ] && echo "PASS: catalog packaged" || { echo "FAIL: catalog not packaged"; return 1; }

oc create configmap op-test-catalog-data --from-file=catalog.zip="${TEMP_DIR}/catalog.b64" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label configmap op-test-catalog-data wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"
# A ConfigMap without the catalog.zip key, for Test 5.5
oc create configmap op-test-catalog-nokey --from-literal=other.txt=x \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label configmap op-test-catalog-nokey wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"
rm -rf "${TEMP_DIR}"

oc get configmap op-test-catalog-data -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data}' | jq -r 'keys[]' | grep -q "^catalog.zip$"
echo "configmap-has-catalog-zip=$?"
# Expected: configmap-has-catalog-zip=0
```

**Expected result:**

- The output includes `PASS: catalog packaged`.
- The output includes `configmap-has-catalog-zip=0`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.2: Create a WanakuServiceCatalog

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-test-service-catalogs
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: ${OP_CATALOG_NAME}
      configMapRef: op-test-catalog-data
EOF

wait_cr_condition wanakuservicecatalog op-test-service-catalogs Ready True ReconciliationSucceeded || return 1

DEPLOYED=$(oc get wanakuservicecatalog op-test-service-catalogs -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.deployedCatalogs[*]}')
echo " ${DEPLOYED} " | grep -q " ${OP_CATALOG_NAME} " \
  && echo "PASS: ${OP_CATALOG_NAME} listed in status.deployedCatalogs" \
  || { echo "FAIL: status.deployedCatalogs is '${DEPLOYED}'"; return 1; }
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-test-service-catalogs condition Ready=True (reason ReconciliationSucceeded)`.
- The output includes `PASS: <OP_CATALOG_NAME> listed in status.deployedCatalogs`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.3: Verify the catalog is stored in Barn

Run this block in the shared Bash session.

```bash run
start_barn_port_forward || return 1
OUTPUT=$(${WANAKU_CLI} service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
stop_barn_port_forward
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "^${OP_CATALOG_NAME}" \
  && echo "PASS: ${OP_CATALOG_NAME} is stored in Barn" \
  || { echo "FAIL: ${OP_CATALOG_NAME} not found in Barn"; return 1; }
```

**Expected result:**

- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: <OP_CATALOG_NAME> is stored in Barn`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.4: Negative test - missing ConfigMap

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-missing-configmap
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: missing-catalog
      configMapRef: this-configmap-does-not-exist
EOF

wait_cr_condition wanakuservicecatalog op-bad-missing-configmap Ready False DeploymentError || return 1
oc get wanakuservicecatalog op-bad-missing-configmap -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}' | grep -q "this-configmap-does-not-exist" \
  && echo "PASS: the message names the missing ConfigMap" || echo "FAIL: the message does not name the ConfigMap"
oc delete wanakuservicecatalog op-bad-missing-configmap -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-bad-missing-configmap condition Ready=False (reason DeploymentError)`.
- The output includes `PASS: the message names the missing ConfigMap`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.5: Negative test - ConfigMap without the catalog.zip key

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-no-key
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: no-key-catalog
      configMapRef: op-test-catalog-nokey
EOF

wait_cr_condition wanakuservicecatalog op-bad-no-key Ready False DeploymentError || return 1
oc get wanakuservicecatalog op-bad-no-key -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}' | grep -q "catalog.zip" \
  && echo "PASS: the message names the missing catalog.zip key" || echo "FAIL: the message does not name catalog.zip"
oc delete wanakuservicecatalog op-bad-no-key -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-bad-no-key condition Ready=False (reason DeploymentError)`.
- The output includes `PASS: the message names the missing catalog.zip key`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.6: Negative test - non-existent routerRef

Run this block in the shared Bash session.

```bash run
cat <<'EOF' | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-router-ref
spec:
  routerRef: non-existent-router
  catalogs:
    - name: orphan-catalog
      configMapRef: op-test-catalog-data
EOF

wait_cr_condition wanakuservicecatalog op-bad-router-ref Ready False ValidationError || return 1
oc delete wanakuservicecatalog op-bad-router-ref -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-bad-router-ref condition Ready=False (reason ValidationError)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 6: Authentication Through oauth2-proxy

### Test 6.1: Negative test - auth enabled without issuerUrl

**Description:** `spec.auth.issuerUrl` and `spec.auth.secretName` are required when `spec.auth.enabled` is `true`.
Validation fails before the operator changes any resource.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p "{\"spec\":{\"auth\":{\"enabled\":true,\"secretName\":\"${WANAKU_AUTH_SECRET_NAME}\"}}}"

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Reconciled False ValidationError || return 1
router_jsonpath '{.status.conditions[?(@.type=="Reconciled")].message}' | grep -q "spec.auth.issuerUrl is required" \
  && echo "PASS: the message names spec.auth.issuerUrl" || echo "FAIL: unexpected validation message"
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready False ValidationError || return 1
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=False (reason ValidationError)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=False (reason ValidationError)`.
- The output includes `PASS: the message names spec.auth.issuerUrl`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.2: Negative test - the auth Secret does not exist

**Description:** The operator does not deploy the oauth2-proxy Deployment while the Secret is missing.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p "{\"spec\":{\"auth\":{\"enabled\":true,\"issuerUrl\":\"${WANAKU_AUTH_ISSUER_URL}\",\"internalIssuerUrl\":\"${WANAKU_AUTH_INTERNAL_ISSUER_URL}\",\"clientId\":\"wanaku-mcp-router\",\"secretName\":\"op-missing-secret\"}}}"

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthConfigured False SecretNotFound || return 1
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready False || return 1

if oc get deployment "${WANAKU_ROUTER_NAME}-oauth2-proxy" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1; then
  echo "FAIL: oauth2-proxy Deployment exists although the Secret is missing"
  return 1
fi
echo "PASS: no oauth2-proxy Deployment without a valid Secret"
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=False (reason SecretNotFound)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=False`.
- The output includes `PASS: no oauth2-proxy Deployment without a valid Secret`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.3: Negative test - invalid cookie secret

Run this block in the shared Bash session.

```bash run
oc create secret generic op-bad-cookie-secret \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="too-short" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label secret op-bad-cookie-secret wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"

oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p '{"spec":{"auth":{"secretName":"op-bad-cookie-secret"}}}'

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthConfigured False InvalidCookieSecret || return 1
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=False (reason InvalidCookieSecret)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.4: Enable authentication with a valid Secret

**Description:** With the Secret from [common/keycloak-setup.md](common/keycloak-setup.md) step 9, the operator
deploys the two oauth2-proxy containers and points both Routes at the proxy Service.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p "{\"spec\":{\"auth\":{\"secretName\":\"${WANAKU_AUTH_SECRET_NAME}\"}}}"

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthConfigured True Configured || return 1
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthProxyAvailable True Available || return 1
oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"
```

**Verification - oauth2-proxy resources:**

Run this block in the shared Bash session.

```bash run
FAIL=0
PROXY="${WANAKU_ROUTER_NAME}-oauth2-proxy"
CONTAINERS=$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.template.spec.containers[*].name}')
for C in oauth2-proxy-mcp oauth2-proxy-mgmt; do
  echo " ${CONTAINERS} " | grep -q " ${C} " && echo "PASS: container ${C}" || { echo "FAIL: container ${C} missing"; FAIL=1; }
done
check "proxy client id" "$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="oauth2-proxy-mgmt")].env[?(@.name=="OAUTH2_PROXY_CLIENT_ID")].value}')" "wanaku-mcp-router"
check "proxy issuer" "$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="oauth2-proxy-mgmt")].env[?(@.name=="OAUTH2_PROXY_OIDC_ISSUER_URL")].value}')" "${WANAKU_AUTH_ISSUER_URL}"

PORTS=$(oc get service "oauth2-proxy-${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[*].port}')
check "proxy Service ports" "${PORTS}" "4180 4181"

check "management Route service" "$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.to.name}')" "oauth2-proxy-${WANAKU_ROUTER_NAME}"
check "management Route port" "$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.port.targetPort}')" "4181"
check "MCP Route service" "$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.to.name}')" "oauth2-proxy-${WANAKU_ROUTER_NAME}"
check "MCP Route port" "$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.port.targetPort}')" "4180"

# The Server advertises the issuer to MCP clients
SERVER_ENV='{.spec.template.spec.containers[0].env[?(@.name=="%s")].value}'
check "WANAKU_AUTH_ISSUER" "$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" -o jsonpath="$(printf "${SERVER_ENV}" WANAKU_AUTH_ISSUER)")" "${WANAKU_AUTH_ISSUER_URL}"
check "WANAKU_AUTH_UPSTREAM_ISSUER" "$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" -o jsonpath="$(printf "${SERVER_ENV}" WANAKU_AUTH_UPSTREAM_ISSUER)")" "${WANAKU_AUTH_INTERNAL_ISSUER_URL}"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: proxy client id =`.
- The output includes `PASS: proxy issuer =`.
- The output includes `PASS: proxy Service ports =`.
- The output includes `PASS: management Route service =`.
- The output includes `PASS: management Route port =`.
- The output includes `PASS: MCP Route service =`.
- The output includes `PASS: MCP Route port =`.
- The output includes `PASS: WANAKU_AUTH_ISSUER =`.
- The output includes `PASS: WANAKU_AUTH_UPSTREAM_ISSUER =`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=True (reason Configured)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthProxyAvailable=True (reason Available)`.
- The output includes `PASS: container oauth2-proxy-mcp`.
- The output includes `PASS: container oauth2-proxy-mgmt`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.5: Verify unauthenticated requests through the proxies

**Description:** Health endpoints do not need a token. Management API and MCP requests without a token get `401`.
They are not redirected to the login page. `curl` is used because this test checks HTTP status codes.

Run this block in the shared Bash session.

```bash run
FAIL=0
check "/health without token" "$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/health")" "200"
check "/api/v1/namespaces without token" "$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/api/v1/namespaces")" "401"
check "/default/mcp without token" "$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}' \
  "${WANAKU_ROUTER_MCP_URL}/default/mcp")" "401"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: /health without token =`.
- The output includes `PASS: /api/v1/namespaces without token =`.
- The output includes `PASS: /default/mcp without token =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.6: Verify the OAuth protected-resource metadata

**Description:** When `WANAKU_AUTH_ISSUER` is set, the Server serves RFC 9728 metadata with a non-empty
`authorization_servers` list. The Server names its own MCP host as the authorization server and serves the discovery
document at `/.well-known/oauth-authorization-server`. Without `WANAKU_AUTH_ISSUER`, the list is empty. The proxy lets
`/.well-known/*` pass without a token.

Run this block in the shared Bash session.

```bash run
MCP_HOST=$(echo "${WANAKU_ROUTER_MCP_URL}" | sed 's|^https://||')
BODY=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_MCP_URL}/.well-known/oauth-protected-resource/default/mcp")
echo "${BODY}"
echo "${BODY}" | jq -e '.authorization_servers | length > 0' > /dev/null \
  && echo "PASS: authorization_servers is not empty" \
  || { echo "FAIL: authorization_servers is empty (WANAKU_AUTH_ISSUER not applied?)"; return 1; }
echo "${BODY}" | jq -r '.authorization_servers[0]' | grep -q "${MCP_HOST}" \
  && echo "PASS: authorization server is the MCP host" \
  || echo "FAIL: authorization server is not ${MCP_HOST}"
# The Server builds the URLs from the Host header with the http scheme, also behind the TLS Route
echo "${BODY}" | jq -r '.authorization_servers[0]' | grep -q '^https://' \
  || echo "WARN: authorization_servers uses http:// behind an edge-terminated Route (report if still open)"
```

**Expected result:**

- The output includes `PASS: authorization_servers is not empty`.
- The output includes `PASS: authorization server is the MCP host`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.7: Verify the OIDC login and the token through the management proxy

Use `WANAKU_ROUTER_URL` from Test 3.8.

Follow [common/oidc-login-verification.md](common/oidc-login-verification.md).

It logs in as `WANAKU_TEST_USER`, calls the management API with the token, and verifies that a request
without a token fails.

**Expected result:**

- The procedure logs in as `WANAKU_TEST_USER`, calls the management API with the token, and verifies that a request without a token fails.

### Test 6.8: Verify the token through the MCP proxy

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --plain 2>&1)
[ $? -eq 0 ] && echo "PASS: MCP proxy accepts the token" || { echo "FAIL: MCP proxy rejected the token: ${OUTPUT}"; return 1; }

# Negative test: no token
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
[ $? -ne 0 ] && echo "PASS: MCP proxy rejects requests without a token" \
  || { echo "FAIL: MCP proxy accepted a request without a token"; return 1; }
```

**Expected result:**

- The output includes `PASS: MCP proxy accepts the token`.
- The output includes `PASS: MCP proxy rejects requests without a token`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.9: Verify a Secret change restarts the proxies

**Description:** The operator stores the Secret `resourceVersion` in the `wanaku.ai/auth-secret-version` pod
annotation. A Secret change rolls the proxy pods.

Run this block in the shared Bash session.

```bash run
PROXY="${WANAKU_ROUTER_NAME}-oauth2-proxy"
OLD_VERSION=$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.metadata.annotations.wanaku\.ai/auth-secret-version}')

oc create secret generic "${WANAKU_AUTH_SECRET_NAME}" \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="$(openssl rand -hex 16)" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -

NEW_VERSION="${OLD_VERSION}"
for i in $(seq 1 30); do
  NEW_VERSION=$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.spec.template.metadata.annotations.wanaku\.ai/auth-secret-version}')
  [ "${NEW_VERSION}" != "${OLD_VERSION}" ] && break
  sleep 3
done
[ "${NEW_VERSION}" != "${OLD_VERSION}" ] && echo "PASS: auth-secret-version changed (${OLD_VERSION} -> ${NEW_VERSION})" \
  || { echo "FAIL: auth-secret-version did not change"; return 1; }
oc rollout status "deployment/${PROXY}" --timeout=180s -n "${WANAKU_NAMESPACE}"
```

**Expected result:**

- The output includes `PASS: auth-secret-version changed`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 7: Update Reconciliation

### Test 7.1: Add an environment variable to the Server

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p '{"spec":{"server":{"env":[{"name":"RUST_LOG","value":"info"}]}}}'

for i in $(seq 1 30); do
  VALUE=$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="RUST_LOG")].value}')
  [ "${VALUE}" = "info" ] && break
  sleep 3
done
[ "${VALUE}" = "info" ] && echo "PASS: RUST_LOG added to the Server Deployment" || { echo "FAIL: RUST_LOG='${VALUE}'"; return 1; }
oc rollout status "deployment/${WANAKU_ROUTER_NAME}-server" --timeout=180s -n "${WANAKU_NAMESPACE}"
```

**Expected result:**

- The output includes `PASS: RUST_LOG added to the Server Deployment`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.2: Negative tests - invalid spec values

**Description:** An invalid `spec.server.variant` or `spec.exposure.type` sets `Reconciled=False` with the reason
`ValidationError`. The operator does not change the workloads.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"server":{"variant":"invalid"}}}'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Reconciled False ValidationError || return 1
router_jsonpath '{.status.conditions[?(@.type=="Reconciled")].message}' | grep -q "spec.server.variant must be standard or headless" \
  && echo "PASS: variant validation message" || echo "FAIL: unexpected validation message"
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=json -p '[{"op":"remove","path":"/spec/server/variant"}]'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready True || return 1

oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"exposure":{"type":"Bogus"}}}'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Reconciled False ValidationError || return 1
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=json -p '[{"op":"remove","path":"/spec/exposure"}]'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready True || return 1
```

**Expected result:**

- The output includes `PASS: variant validation message`.
- For the invalid `spec.server.variant`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=False (reason ValidationError)`.
- After restoring `spec.server.variant`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=True`.
- For the invalid `spec.exposure.type`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=False (reason ValidationError)`.
- After restoring `spec.exposure`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=True`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.3: Disable Wanaku Barn

**Description:** With `spec.barn.enabled: false`, the operator deletes the Barn Deployment, the Barn Service and the
Server configuration ConfigMap. It keeps the Barn PVC. A `WanakuServiceCatalog` for this router then fails validation.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"barn":{"enabled":false}}}'

wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-barn" "${WANAKU_NAMESPACE}" 120 || return 1
wait_for_deletion service "barn-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || return 1
wait_for_deletion configmap "${WANAKU_ROUTER_NAME}-server-config" "${WANAKU_NAMESPACE}" 60 || return 1

oc get pvc "${WANAKU_ROUTER_NAME}-barn-data" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
  && echo "PASS: Barn PVC kept" || { echo "FAIL: Barn PVC was deleted"; return 1; }

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"
[ -z "$(router_jsonpath '{.status.conditions[?(@.type=="BarnAvailable")].status}')" ] \
  && echo "PASS: no BarnAvailable condition" || echo "FAIL: BarnAvailable condition still present"
[ -z "$(router_jsonpath '{.status.barnUrl}')" ] && echo "PASS: status.barnUrl removed" || echo "FAIL: status.barnUrl still set"

# Negative test: a catalog needs Barn
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-no-barn
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: no-barn-catalog
      configMapRef: op-test-catalog-data
EOF
wait_cr_condition wanakuservicecatalog op-bad-no-barn Ready False ValidationError || return 1
oc get wanakuservicecatalog op-bad-no-barn -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}' | grep -q "spec.barn.enabled must be true" \
  && echo "PASS: the message names spec.barn.enabled" || echo "FAIL: unexpected message"
oc delete wanakuservicecatalog op-bad-no-barn -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-barn deleted`.
- The output includes `PASS: service/barn-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: configmap/<WANAKU_ROUTER_NAME>-server-config deleted`.
- The output includes `PASS: Barn PVC kept`.
- The output includes `PASS: no BarnAvailable condition`.
- The output includes `PASS: status.barnUrl removed`.
- The output includes `PASS: wanakuservicecatalog/op-bad-no-barn condition Ready=False (reason ValidationError)`.
- The output includes `PASS: the message names spec.barn.enabled`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.4: Enable Wanaku Barn again and verify the data is kept

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"barn":{"enabled":true}}}'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" BarnAvailable True Available || return 1
oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"

start_barn_port_forward || return 1
OUTPUT=$(${WANAKU_CLI} service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
stop_barn_port_forward
echo "${OUTPUT}" | grep -q "^${OP_CATALOG_NAME}" \
  && echo "PASS: ${OP_CATALOG_NAME} is still in Barn after Barn was enabled again" \
  || { echo "FAIL: Barn data was lost"; echo "${OUTPUT}"; return 1; }
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition BarnAvailable=True (reason Available)`.
- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: <OP_CATALOG_NAME> is still in Barn after Barn was enabled again`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.5: Verify the operator logs and restarts

```bash run
OPERATOR_POD=$(oc get pods -l app.kubernetes.io/name=wanaku-operator -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
LOGS=$(oc logs "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}")
echo "${LOGS}" | grep -q "Starting router reconciliation for ${WANAKU_ROUTER_NAME}" \
  && echo "PASS: router reconciliation logged" || echo "FAIL: no router reconciliation in the logs"
echo "${LOGS}" | grep -q "Starting service catalog reconciliation for op-test-service-catalogs" \
  && echo "PASS: service catalog reconciliation logged" || echo "FAIL: no service catalog reconciliation in the logs"

RESTARTS=$(oc get pod "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.containerStatuses[0].restartCount}')
[ "${RESTARTS}" = "0" ] && echo "PASS: operator has zero restarts" || echo "WARN: operator restarted ${RESTARTS} time(s)"
```

**Expected result:**

- The output includes `PASS: router reconciliation logged`.
- The output includes `PASS: service catalog reconciliation logged`.
- The output includes `PASS: operator has zero restarts` or `WARN: operator restarted`.
- The output contains no line starting with `FAIL:`.


---

## Phase 8: Service Catalog Removal

### Test 8.1: Delete the WanakuServiceCatalog and verify Barn removes the catalog

**Description:** The operator removes the deployed catalogs from Barn before it releases the resource. Barn keeps a
removed catalog in its removed list, so it can be restored.

Run this block in the shared Bash session.

```bash run
oc delete wanakuservicecatalog op-test-service-catalogs -n "${WANAKU_NAMESPACE}" --timeout=120s
wait_for_deletion wanakuservicecatalog op-test-service-catalogs "${WANAKU_NAMESPACE}" 60 || return 1

start_barn_port_forward || return 1
ACTIVE=$(${WANAKU_CLI} service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
REMOVED=$(${WANAKU_CLI} service removed --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
stop_barn_port_forward

echo "${ACTIVE}" | grep -q "^${OP_CATALOG_NAME}" \
  && { echo "FAIL: ${OP_CATALOG_NAME} is still an active catalog"; return 1; } \
  || echo "PASS: ${OP_CATALOG_NAME} is no longer an active catalog"
echo "${REMOVED}" | grep -q "^${OP_CATALOG_NAME}" \
  && echo "PASS: ${OP_CATALOG_NAME} is in the Barn removed list" \
  || echo "WARN: ${OP_CATALOG_NAME} is not in the removed list"
```

**Expected result:**

- The output reports `PASS:` for deletion of `wanakuservicecatalog/op-test-service-catalogs`.
- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: <OP_CATALOG_NAME> is no longer an active catalog`.
- The output includes `PASS: <OP_CATALOG_NAME> is in the Barn removed list` or `WARN: <OP_CATALOG_NAME> is not in the removed list`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 9: Deletion and Ownership Cascade

### Test 9.1: Delete the router and verify the managed resources are removed

Run this block in the shared Bash session.

```bash run
oc delete wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}"

FAIL=0
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-server" "${WANAKU_NAMESPACE}" 120 || FAIL=1
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-barn" "${WANAKU_NAMESPACE}" 120 || FAIL=1
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-oauth2-proxy" "${WANAKU_NAMESPACE}" 120 || FAIL=1
wait_for_deletion service "server-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion service "barn-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion service "oauth2-proxy-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion route "${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion route "${WANAKU_ROUTER_NAME}-mcp" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion configmap "${WANAKU_ROUTER_NAME}-server-config" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion pvc server-volume-claim "${WANAKU_NAMESPACE}" 120 || FAIL=1
wait_for_deletion pvc "${WANAKU_ROUTER_NAME}-barn-data" "${WANAKU_NAMESPACE}" 120 || FAIL=1
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-server deleted`.
- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-barn deleted`.
- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-oauth2-proxy deleted`.
- The output includes `PASS: service/server-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: service/barn-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: service/oauth2-proxy-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: route/<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: route/<WANAKU_ROUTER_NAME>-mcp deleted`.
- The output includes `PASS: configmap/<WANAKU_ROUTER_NAME>-server-config deleted`.
- The output includes `PASS: pvc/server-volume-claim deleted`.
- The output includes `PASS: pvc/<WANAKU_ROUTER_NAME>-barn-data deleted`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 10: Cleanup

### Step 10.1: Full teardown and local credential removal

Perform full teardown. Then remove the local credential store:

Follow [common/cleanup.md](common/cleanup.md).

```bash run
stop_barn_port_forward 2>/dev/null || true
rm -rf "${WANAKU_CREDENTIALS}"
echo "PASS: local credentials removed"
if [ -n "${WANAKU_DOWNLOADED_SOURCE_DIR}" ]; then
  rm -rf "${WANAKU_DOWNLOADED_SOURCE_DIR}" || return 1
  unset WANAKU_DOWNLOADED_SOURCE_DIR
fi
echo "PASS: downloaded source assets removed or no download was owned"
```

**Expected result:**

- The output includes `PASS: local credentials removed`.
- The output includes `PASS: downloaded source assets removed or no download was owned`.


---

## Test Summary Matrix

| Phase | Test ID | Test Name                                                              | Priority |
|-------|---------|------------------------------------------------------------------------|----------|
| S     | S.0     | Prepare the operator Helm chart and realm configuration                 | Critical |
| S     | S.1     | Prerequisite check script                                              | Critical |
| S     | S.2     | Environment variables                                                  | Critical |
| S     | S.3-S.5 | Deletion, condition and Barn port-forward helpers                      | Critical |
| 0     | 0.1     | Log in to OpenShift                                                    | Critical |
| 0     | —       | OpenShift login                                                        | Critical |
| 1     | 1.1-1.2 | Environment setup (namespace, Keycloak, auth Secret)                   | Critical |
| 2     | 2.1     | Install the operator                                                   | Critical |
| 2     | —       | Operator installation, CRDs, RBAC and health                           | Critical |
| 3     | 3.1-3.2 | WanakuRouter Ready and conditions                                      | Critical |
| 3     | 3.3-3.7 | Server and Barn Deployments, Services, PVCs, ConfigMap                 | Critical |
| 3     | 3.8-3.9 | Routes and status endpoints                                            | Critical |
| 3     | 3.10    | Owner references and managed-by labels                                 | High     |
| 4     | 4.1-4.2 | Health endpoints and management API through the Route                  | Critical |
| 4     | 4.3     | Namespace creation and DNS-label validation                            | High     |
| 4     | 4.4     | MCP endpoint `/<namespace>/mcp`, bare `/mcp` rejected                  | Critical |
| 4     | 4.5-4.6 | Admin UI, Barn plugin proxy, Barn CLI access                           | High     |
| 4     | 4.7     | Registry persistence across a pod restart                              | High     |
| 5     | 5.1-5.3 | WanakuServiceCatalog deploys a packaged catalog to Barn                | High     |
| 5     | 5.4-5.6 | Service catalog negative tests                                         | High     |
| 6     | 6.1-6.3 | Authentication negative tests (validation, Secret)                     | High     |
| 6     | 6.4-6.6 | oauth2-proxy resources, 401 without token, protected-resource metadata | Critical |
| 6     | 6.7-6.8 | OIDC login, token accepted by the management and MCP proxies           | Critical |
| 6     | 6.9     | Secret change restarts the proxies                                     | Medium   |
| 7     | 7.1-7.2 | Update reconciliation and spec validation                              | Medium   |
| 7     | 7.3-7.4 | Disable and enable Barn, data kept                                     | High     |
| 7     | 7.5     | Operator logs and restarts                                             | Medium   |
| 8     | 8.1     | Catalog removed from Barn on CR deletion                               | High     |
| 9     | 9.1     | Deletion and ownership cascade                                         | Critical |
| 10    | 10.1    | Full teardown and local credential removal                             | Critical |
| 10    | —       | Cleanup                                                                | Critical |
