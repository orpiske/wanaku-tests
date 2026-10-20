# Procedure: Wait for Resource Deletion

Reusable helper that polls for Kubernetes resource deletion instead of using a fixed sleep.

## Prerequisites

- `oc` CLI installed and logged in
- `WANAKU_NAMESPACE` environment variable set

## Phase 1: Wait for Resource Deletion

### Step 0: Define the wait_for_deletion helper

Define this function before use:

```bash
# POSIX sh has no "local": the variables use a WFD_ prefix to avoid collisions
wait_for_deletion() {
  WFD_TYPE="$1"
  WFD_NAME="$2"
  WFD_NAMESPACE="$3"
  WFD_TIMEOUT="${4:-60}"
  WFD_INTERVAL=3
  WFD_ELAPSED=0

  while oc get "${WFD_TYPE}" "${WFD_NAME}" -n "${WFD_NAMESPACE}" > /dev/null 2>&1; do
    if [ "${WFD_ELAPSED}" -ge "${WFD_TIMEOUT}" ]; then
      echo "FAIL: ${WFD_TYPE}/${WFD_NAME} still exists after ${WFD_TIMEOUT}s"
      return 1
    fi
    sleep ${WFD_INTERVAL}
    WFD_ELAPSED=$((WFD_ELAPSED + WFD_INTERVAL))
  done
  echo "PASS: ${WFD_TYPE}/${WFD_NAME} deleted (${WFD_ELAPSED}s)"
  return 0
}
```

**Expected result:**

- The block defines the function without errors (inferred).

## Usage

```bash
wait_for_deletion deployment my-deployment "${WANAKU_NAMESPACE}" 60
wait_for_deletion route my-route "${WANAKU_NAMESPACE}" 30
```

The function returns `0` when the resource is gone and `1` after the timeout. It prints one PASS or FAIL line.
