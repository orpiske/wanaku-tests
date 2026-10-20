# Test Plan: LangChain4j Agent Service Template

## Overview

This test plan verifies the built-in `langchain4j-agent-tool` service template (issue #1181). It covers the template
source, the listing and the instantiation in Wanaku Barn (including the Forage dependency resolution), the tool
registration in Wanaku Server through a Camel Integration Capability (CIC) runtime, and an end-to-end MCP tool call
to a LangChain4j agent backed by an LLM.

The template turns into an MCP tool in this order:

```text
langchain4j-agent-tool template (Barn) --instantiate--> catalog (Barn) --CIC downloads--> CIC runtime :9190/mcp
    --forward--> Wanaku Server --MCP--> ${WANAKU_MCP_BASE_URL}/default/mcp --> LLM (Ollama or OpenAI-compatible API)
```

### Components under test

| Component | Needed for | Common procedure |
|-----------|------------|------------------|
| Wanaku Barn | Phases 2 to 4 (templates and catalogs) | [common/start-barn.md](common/start-barn.md) |
| Wanaku Server | Phases 5 and 6 (tool registration and MCP call) | [common/start-server.md](common/start-server.md) |
| CIC runtime (container) | Phases 5 and 6 | [common/start-cic.md](common/start-cic.md) |
| LLM backend (Ollama, OpenRouter or OpenAI) | Phase 6 only | Phase 0 of this plan |

The shared template checks are in [common/service-template-checks.md](common/service-template-checks.md). This plan
adds the checks that are specific to the LangChain4j agent template.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java --version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |
| `unzip` | any | `unzip -v` |
| `docker` or `podman` | 20+ | `docker --version` or `podman --version` (Phases 5 and 6) |
| `ollama` | any | `ollama --version` (Phase 6, Option A only) |

Run all commands from the root of this repository. Call the CLI as `java -jar "${CLI_JAR}"`.

### Environment variables

```bash
export TEMPLATE_NAME="langchain4j-agent-tool"
export TEMPLATE_SYSTEM="langchain4j-agent"
export TEMPLATE_TOOL="langchain4j-agent-chat"
export TEMPLATE_TOOL_PARAMS="message systemMessage"
export TEMPLATE_SEARCH="langchain4j"
export TEMPLATE_CATALOG="${TEMPLATE_CATALOG:-langchain4j-agent-test}"
export TEMPLATE_PROPS_FILE="${TEMPLATE_PROPS_FILE:-$(mktemp)}"

export AGENT_MODEL_KIND="${AGENT_MODEL_KIND:-ollama}"
export AGENT_MODEL_NAME="${AGENT_MODEL_NAME:-llama3.2}"
export AGENT_BASE_URL="${AGENT_BASE_URL:-http://localhost:11434}"
export AGENT_API_KEY="${AGENT_API_KEY:-unused}"

export CIC_NAME="${CIC_NAME:-cic-langchain4j}"
export CIC_NAMESPACE="${CIC_NAMESPACE:-default}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${WANAKU_TEST_GOVERNED_NAMESPACES:-${CIC_NAMESPACE}}"
```

| Variable | Default | Description |
|----------|---------|-------------|
| `TEMPLATE_CATALOG` | `langchain4j-agent-test` | Name of the catalog that the instantiation creates |
| `TEMPLATE_PROPS_FILE` | `$(mktemp)` | Properties file for the instantiation (written in Phase 4) |
| `AGENT_MODEL_KIND` | `ollama` | Forage model kind (`ollama`, `openai`, ...) |
| `AGENT_MODEL_NAME` | `llama3.2` | Model name |
| `AGENT_BASE_URL` | `http://localhost:11434` | LLM base URL, as the CIC container sees it |
| `AGENT_API_KEY` | `unused` | LLM API key (`unused` for Ollama) |
| `CIC_NAME` | `cic-langchain4j` | CIC container name and forward name |
| `CIC_NAMESPACE` | `default` | Wanaku namespace of the forward |
| `WANAKU_TEST_GOVERNED_NAMESPACES` | `${CIC_NAMESPACE}` | Namespaces where the test governance allows tool calls |

The template keys are `forage.agent.model.kind`, `forage.agent.model.name`, `forage.agent.base.url` and
`forage.agent.api.key`. They have default values (Ollama, `llama3.2`), not `{{placeholders}}`. The instantiation
replaces the defaults with the supplied values.

---

## Phase 0: LLM Backend Setup (required for Phase 6)

### Option A: Local Ollama (recommended)

Install Ollama (<https://ollama.com/download>), start it, and pull a small model:

```bash
ollama serve > /dev/null 2>&1 &
for i in $(seq 1 20); do
  curl -sf http://localhost:11434/api/tags > /dev/null 2>&1 && break
  sleep 2
done
ollama pull "${AGENT_MODEL_NAME}"

curl -sf http://localhost:11434/api/tags | jq -r '.models[].name' | grep -q "^${AGENT_MODEL_NAME}" \
  && echo "PASS: Ollama runs and ${AGENT_MODEL_NAME} is available" || echo "FAIL: ${AGENT_MODEL_NAME} not available"
```

### Option B: OpenRouter (OpenAI-compatible)

```bash
export AGENT_MODEL_KIND=openai
export AGENT_MODEL_NAME=meta-llama/llama-3-8b-instruct
export AGENT_BASE_URL=https://openrouter.ai/api/v1
export AGENT_API_KEY="${AGENT_API_KEY:?set AGENT_API_KEY to your OpenRouter key}"
```

### Option C: OpenAI

```bash
export AGENT_MODEL_KIND=openai
export AGENT_MODEL_NAME=gpt-4o-mini
export AGENT_BASE_URL=https://api.openai.com/v1
export AGENT_API_KEY="${AGENT_API_KEY:?set AGENT_API_KEY to your OpenAI key}"
```

You do not add the Forage model library manually. Barn adds the dependencies for `forage.agent.model.kind` to the
catalog at instantiation (Test 4.3).

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server with the test governance configuration

Follow [common/start-server.md](common/start-server.md) and run step 2b. After completion, `WANAKU_SERVER_URL`,
`WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID` and `WANAKU_SERVER_CONFIG` must be set.

### Step 1.2: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md). After completion, `WANAKU_BARN_URL`, `CLI_JAR` and
`WANAKU_BARN_PID` must be set.

### Step 1.3: Detect the container runtime

Follow [common/container-runtime.md](common/container-runtime.md). If no container runtime is available, Phases 5 and 6
are skipped.

---

## Phase 2: Template Source

### Test 2.1: Shared source checks

Run the Setup and section A of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. Barn reports `Service template 'langchain4j-agent-tool' is valid`.

### Test 2.2: The route calls the LangChain4j agent component

```bash
ROUTE_FILE="${TEMPLATE_ROUTE_FILE}"

grep -q 'uri: "langchain4j-agent:{{agent.id}}"' "${ROUTE_FILE}" \
  && echo "PASS: route sends to langchain4j-agent:{{agent.id}}" || echo "FAIL: langchain4j-agent endpoint missing"
grep -q 'agent: "#agent"' "${ROUTE_FILE}" && echo "PASS: route uses the agent bean" || echo "FAIL: agent bean missing"
```

### Test 2.3: Dependencies and properties

```bash
DIR="${TEMPLATE_DIR}/${TEMPLATE_SYSTEM}"
DEPS="${DIR}/${TEMPLATE_SYSTEM}.dependencies.txt"

for GA in org.apache.camel:camel-langchain4j-agent io.kaoto.forage:forage-agent io.kaoto.forage:forage-agent-factories \
  io.kaoto.forage:forage-core-ai; do
  grep -q "^${GA}$" "${DEPS}" && echo "PASS: dependency ${GA}" || echo "FAIL: dependency ${GA} missing"
done

PROPS="${TEMPLATE_DIR}/${TEMPLATE_PROPS_PATH}"
grep -q "^tool.description=." "${PROPS}" && echo "PASS: tool.description" || echo "FAIL: tool.description"
grep -q "^agent.id=agent$" "${PROPS}" && echo "PASS: agent.id matches the agent bean" || echo "FAIL: agent.id"
for KEY in forage.agent.model.kind forage.agent.model.name forage.agent.base.url forage.agent.api.key; do
  grep -q "^${KEY}=." "${PROPS}" && echo "PASS: ${KEY} has a default" || echo "FAIL: ${KEY} missing"
done
```

---

## Phase 3: Template Listing

### Test 3.1: Shared listing checks

Run section B of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`.

---

## Phase 4: Template Instantiation

### Step 4.1: Write the instantiation properties

```bash
cat > "${TEMPLATE_PROPS_FILE}" <<EOF
forage.agent.model.kind=${AGENT_MODEL_KIND}
forage.agent.model.name=${AGENT_MODEL_NAME}
forage.agent.base.url=${AGENT_BASE_URL}
forage.agent.api.key=${AGENT_API_KEY}
EOF
[ -s "${TEMPLATE_PROPS_FILE}" ] && echo "PASS: properties written" || echo "FAIL: properties file is empty"
```

### Test 4.2: Shared instantiation checks

Run section C of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. The catalog `${TEMPLATE_CATALOG}` contains the supplied `forage.agent.*` values.

### Test 4.3: Barn adds the Forage model dependencies

Barn reads each `forage.*.kind` property and appends the Forage libraries for that kind to the dependencies of the
catalog.

```bash
case "${AGENT_MODEL_KIND}" in
  openai) MODEL_LIB="forage-model-open-ai" ;;
  *)      MODEL_LIB="forage-model-${AGENT_MODEL_KIND}" ;;
esac

unzip -p "${TEMPLATE_WORK_DIR}/catalog-v1.zip" "${TEMPLATE_SYSTEM}/${TEMPLATE_SYSTEM}.dependencies.txt" \
  > "${TEMPLATE_WORK_DIR}/deps.txt"
cat "${TEMPLATE_WORK_DIR}/deps.txt"
grep -q "^io.kaoto.forage:${MODEL_LIB}:" "${TEMPLATE_WORK_DIR}/deps.txt" \
  && echo "PASS: ${MODEL_LIB} added for kind ${AGENT_MODEL_KIND}" || echo "FAIL: ${MODEL_LIB} not added"
grep -q "^org.apache.camel:camel-langchain4j-agent$" "${TEMPLATE_WORK_DIR}/deps.txt" \
  && echo "PASS: template dependencies kept" || echo "FAIL: template dependencies lost"
```

### Test 4.4: Instantiation without properties uses the Ollama defaults

```bash
DEFAULT_CATALOG="${TEMPLATE_CATALOG}-defaults"
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --service-name "${DEFAULT_CATALOG}" > /dev/null 2>&1 \
  && echo "PASS: instantiation without properties succeeded" || echo "FAIL: instantiation without properties failed"

java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${DEFAULT_CATALOG}" --version 1 --output "${TEMPLATE_WORK_DIR}/defaults.zip" > /dev/null 2>&1
unzip -p "${TEMPLATE_WORK_DIR}/defaults.zip" "${TEMPLATE_SYSTEM}/${TEMPLATE_SYSTEM}.dependencies.txt" \
  | grep -q "^io.kaoto.forage:forage-model-ollama:" \
  && echo "PASS: default kind ollama adds forage-model-ollama" || echo "FAIL: forage-model-ollama not added"
```

---

## Phase 5: Serve the Catalog through the CIC

### Test 5.0: Pre-flight check

```bash
[ -n "${CONTAINER_RUNTIME}" ] && echo "PASS: container runtime ${CONTAINER_RUNTIME} is available" \
  || echo "SKIP: no container runtime -- skip Phases 5 and 6"
```

### Step 5.1: Start the CIC runtime

Follow [common/start-cic.md](common/start-cic.md) with these values:

```bash
export CIC_CATALOG="${TEMPLATE_CATALOG}"
export CIC_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}"
```

The CIC uses host networking, so `AGENT_BASE_URL=http://localhost:11434` reaches a local Ollama. The first start
downloads the LangChain4j and Forage libraries. This can take some minutes.

### Test 5.2: Shared tool registration checks

Run section D of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** `langchain4j-agent-chat` is in `wanaku tools list` with the type `mcp-forward` and the input schema
properties `message` and `systemMessage`, and it is listed at `${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp`.

---

## Phase 6: End-to-End MCP Tool Call (requires an LLM backend)

> [!NOTE]
> `wanaku mcp tool --param` splits values at commas. Do not use commas in the message values.

### Test 6.0: Pre-flight check

```bash
PHASE6_SKIP=0
if [ "${AGENT_MODEL_KIND}" = "ollama" ]; then
  curl -sf "${AGENT_BASE_URL}/api/tags" | grep -q "${AGENT_MODEL_NAME}" \
    || { echo "SKIP: Ollama or ${AGENT_MODEL_NAME} is not available"; PHASE6_SKIP=1; }
elif [ "${AGENT_API_KEY}" = "unused" ] || [ -z "${AGENT_API_KEY}" ]; then
  echo "SKIP: AGENT_API_KEY is not set for kind ${AGENT_MODEL_KIND}"
  PHASE6_SKIP=1
fi
[ -z "${CONTAINER_RUNTIME}" ] && { echo "SKIP: no CIC runtime"; PHASE6_SKIP=1; }
[ "${PHASE6_SKIP}" -eq 0 ] && echo "PASS: Phase 6 pre-flight checks passed (${AGENT_MODEL_KIND}/${AGENT_MODEL_NAME})"
```

### Test 6.1: Call the agent through Wanaku Server

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "message=Say hello in exactly three words." 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && [ -n "${OUTPUT}" ] && echo "PASS: agent returned an answer" \
  || echo "FAIL: agent call failed (exit ${EXIT_CODE})"
```

### Test 6.2: Call the agent with a system message

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "message=What is 2+2?" \
  --param "systemMessage=You are a math tutor. Answer with the number only." 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "4" && echo "PASS: agent answered 4" \
  || echo "FAIL: agent call with a system message failed (exit ${EXIT_CODE})"
```

---

## Phase 7: Negative Tests

### Test 7.1: A call to an unknown tool fails

```bash
java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "does-not-exist-99999" --param "message=hello" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: unknown tool rejected" || echo "FAIL: unknown tool accepted"
```

### Test 7.2: An unknown Forage model kind adds no model library

Barn logs a warning and does not fail the instantiation. The catalog then has no model library, so the CIC cannot
create the agent.

```bash
BAD_CATALOG="${TEMPLATE_CATALOG}-badkind"
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --property forage.agent.model.kind=does-not-exist \
  --service-name "${BAD_CATALOG}" > /dev/null 2>&1 \
  && echo "PASS: instantiation with an unknown kind completed" || echo "FAIL: instantiation failed"
java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${BAD_CATALOG}" --version 1 --output "${TEMPLATE_WORK_DIR}/badkind.zip" > /dev/null 2>&1
unzip -p "${TEMPLATE_WORK_DIR}/badkind.zip" "${TEMPLATE_SYSTEM}/${TEMPLATE_SYSTEM}.dependencies.txt" \
  | grep -q "forage-model-" && echo "FAIL: a model library was added for an unknown kind" \
  || echo "PASS: no model library for an unknown kind"
```

---

## Phase 8: Cleanup

### Step 8.1: Stop the CIC runtime

Run the Shutdown section of [common/start-cic.md](common/start-cic.md).

### Step 8.2: Remove the catalogs

Run section E of [common/service-template-checks.md](common/service-template-checks.md), then:

```bash
for SUFFIX in defaults badkind; do
  java -jar "${CLI_JAR}" service catalog remove --host "${WANAKU_BARN_URL}" --no-auth --plain \
    --name "${TEMPLATE_CATALOG}-${SUFFIX}" > /dev/null 2>&1 || true
done
rm -f "${TEMPLATE_PROPS_FILE}"
echo "PASS: catalogs removed"
```

### Step 8.3: Stop Barn, Wanaku Server and Ollama

Run the Shutdown sections of [common/start-barn.md](common/start-barn.md) and
[common/start-server.md](common/start-server.md). Stop `ollama serve` if Phase 0 started it.

---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | -- | LLM backend setup (Ollama, OpenRouter or OpenAI) | P1 |
| 1 | 1.1-1.3 | Start Wanaku Server (with test governance), Barn, detect container runtime | P0 |
| 2 | 2.1 | Shared source checks (files, index, ai-tool route, credentials, Barn validation) | P0 |
| 2 | 2.2 | Route calls langchain4j-agent with the agent bean | P0 |
| 2 | 2.3 | LangChain4j and Forage dependencies, property defaults | P0 |
| 3 | 3.1 | Shared listing checks | P1 |
| 4 | 4.1-4.2 | Instantiate and verify the substituted values, versions and negative cases | P0 |
| 4 | 4.3 | Barn adds the Forage model library for the model kind | P0 |
| 4 | 4.4 | Defaults resolve to the Ollama model library | P1 |
| 5 | 5.0-5.2 | Serve the catalog through the CIC and verify the tool in Wanaku Server | P0 |
| 6 | 6.0-6.2 | End-to-end agent call (live LLM) | P1 |
| 7 | 7.1 | Unknown tool rejected | P1 |
| 7 | 7.2 | Unknown Forage kind adds no model library | P2 |
| 8 | 8.1-8.3 | Cleanup (CIC, forward, catalogs, processes) | P1 |
