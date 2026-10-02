package ai.wanaku.test.governance;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.client.RawMcpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Exercises the shared typed request adapters with bytes the MCP SDK would reject or normalize. */
@Timeout(180)
class McpRequestParsingITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "raw-parsing";
    private static final String TYPED_TOOL = "capture_typed_tool";

    @Override
    protected void checkAdditionalRequirements(ai.wanaku.test.config.TestConfiguration config) {
        assumeThat(System.getProperty("wanaku.test.external.mgmt.port"))
                .as("Raw parsing tests require an isolated managed server")
                .isNull();
    }

    @BeforeEach
    void startGovernedInfrastructure() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
    }

    @Test
    @DisplayName("Malformed envelopes use the configured fallback and do not poison the connection")
    void rejectsMalformedEnvelopes() throws Exception {
        try (RawMcpClient client = initializedClient()) {
            // Batch payloads are rejected at the transport filter even with on_invalid=continue.
            for (String batch : List.of("[]", "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")) {
                HttpResponse<String> response = client.send(batch);
                assertThat(response.statusCode()).as("Batch: %s", batch).isEqualTo(400);
                assertThat(response.body())
                        .as("Batch transport rejection: %s", batch)
                        .isEmpty();
                assertNoExecution();
            }
            // on_invalid=continue deliberately routes these to the configured method-not-supported fallback.
            for (String body : List.of(
                    "",
                    "{",
                    "null",
                    "42",
                    "{}",
                    "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":null}",
                    "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":42}",
                    "{\"id\":17,\"method\":\"tools/call\",\"params\":{\"name\":\"capture_typed_tool\"}}",
                    "{\"jsonrpc\":\"1.0\",\"id\":17,\"method\":\"tools/call\",\"params\":{\"name\":\"capture_typed_tool\"}}",
                    "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"tools/call\",\"params\":{\"name\":\"capture_typed_tool\"}}",
                    "{\"jsonrpc\":\"2.0\",\"id\":[],\"method\":\"tools/call\",\"params\":{\"name\":\"capture_typed_tool\"}}")) {
                assertAll("Envelope: " + body, () -> assertError(client.send(body), MAPPER.nullNode(), -32601));
                assertNoExecution();
            }
            assertSuccess(
                    client.send(request("tools/call", "recovery", "{\"name\":\"capture_typed_tool\"}")),
                    MAPPER.valueToTree("recovery"));
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Every governed adapter rejects missing and mistyped params, targets and arguments")
    void rejectsInvalidGovernedParameters() throws Exception {
        try (RawMcpClient client = initializedClient()) {
            for (String method : List.of("tools/call", "resources/read", "prompts/get")) {
                String targetKey = method.equals("resources/read") ? "uri" : "name";
                String target = method.equals("resources/read")
                        ? CAPTURE_RESOURCE_URI
                        : method.equals("prompts/get") ? CAPTURE_PROMPT : TYPED_TOOL;
                for (String params : List.of(
                        "OMITTED",
                        "null",
                        "[]",
                        "false",
                        "0",
                        "\"params\"",
                        "{}",
                        "{\"" + targetKey + "\":null}",
                        "{\"" + targetKey + "\":42}",
                        "{\"" + targetKey + "\":[]}",
                        "{\"" + targetKey + "\":{}}")) {
                    // The metadata filter declines invalid selectors before governance sees the method.
                    assertAll(
                            method + " params=" + params,
                            () -> assertError(client.send(request(method, 73, params)), MAPPER.nullNode(), -32601));
                    assertNoExecution();
                }
                if (!method.equals("resources/read")) {
                    for (String arguments : List.of("null", "[]", "false", "42", "\"arguments\"")) {
                        String params = "{\"name\":\"" + target + "\",\"arguments\":" + arguments + "}";
                        assertAll(
                                method + " params=" + params,
                                () -> assertInvalidAction(
                                        client.send(request(method, "typed-id", params)),
                                        MAPPER.valueToTree("typed-id")));
                        assertNoExecution();
                    }
                }
            }
            assertSuccess(
                    client.send(request("resources/read", 74, "{\"uri\":\"" + CAPTURE_RESOURCE_URI + "\"}")),
                    MAPPER.valueToTree(74));
            assertSuccess(
                    client.send(request(
                            "prompts/get",
                            "prompt-ok",
                            "{\"name\":\"capture_prompt\",\"arguments\":{\"topic\":\"valid\"}}")),
                    MAPPER.valueToTree("prompt-ok"));
            assertSuccess(
                    client.send(request("tools/call", 75, "{\"name\":\"capture_typed_tool\"}")),
                    MAPPER.valueToTree(75));
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(1, 1, 1));
        }
    }

    @Test
    @DisplayName("Raw typed arguments remain distinct through policy predicates and upstream forwarding")
    void preservesTypedArguments() throws Exception {
        try (RawMcpClient client = initializedClient();
                ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl())) {
            for (String value :
                    List.of("0", "\"0\"", "false", "[null,false,0,\"0\"]", "{\"flag\":false}", "null", "OMITTED")) {
                ObjectNode rule = GovernancePolicies.denyTool("raw-value", TYPED_TOOL, "Typed value matched.");
                ObjectNode predicate = rule.putArray("predicates").addObject();
                predicate.put("pointer", "/arguments/value");
                if (value.equals("OMITTED")) {
                    predicate.put("operator", "exists").put("value", false);
                } else {
                    predicate.put("operator", "equals").set("value", MAPPER.readTree(value));
                }
                assertThat(policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy(rule)))
                                .statusCode())
                        .isEqualTo(200);
                String params = "{\"name\":\"" + TYPED_TOOL + "\",\"arguments\":"
                        + (value.equals("OMITTED") ? "{}" : "{\"value\":" + value + "}") + "}";
                int before = captureCounts().toolCalls();
                assertError(client.send(request("tools/call", "match", params)), MAPPER.valueToTree("match"), -32003);
                assertThat(captureCounts().toolCalls()).isEqualTo(before);
                // A different type/value must not match the exact predicate.
                JsonNode otherValue = value.equals("OMITTED")
                        ? MAPPER.nullNode()
                        : value.equals("\"0\"") ? MAPPER.readTree("0") : MAPPER.valueToTree(value);
                String otherParams = "{\"name\":\"" + TYPED_TOOL + "\",\"arguments\":{\"value\":" + otherValue + "}}";
                JsonNode different = assertSuccess(
                        client.send(request("tools/call", "different", otherParams)), MAPPER.valueToTree("different"));
                assertThat(MAPPER.readTree(
                                different.path("content").get(0).path("text").asText()))
                        .isEqualTo(otherValue);
                assertThat(policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy()))
                                .statusCode())
                        .isEqualTo(200);
                JsonNode result = assertSuccess(
                        client.send(request("tools/call", "allowed", params)), MAPPER.valueToTree("allowed"));
                assertThat(MAPPER.readTree(
                                result.path("content").get(0).path("text").asText()))
                        .isEqualTo(value.equals("OMITTED") ? MAPPER.nullNode() : MAPPER.readTree(value));
                assertThat(captureCounts().toolCalls()).isEqualTo(before + 2);
            }
            int toolCallsBeforeOmission = captureCounts().toolCalls();
            assertSuccess(
                    client.send(request("tools/call", 90, "{\"name\":\"capture_typed_tool\"}")),
                    MAPPER.valueToTree(90));
            assertThat(captureCounts().toolCalls()).isEqualTo(toolCallsBeforeOmission + 1);
            assertSuccess(
                    client.send(request("prompts/get", 91, "{\"name\":\"capture_optional_prompt\"}")),
                    MAPPER.valueToTree(91));
            int promptGets = captureCounts().promptGets();
            assertThat(promptGets).isEqualTo(1);
            for (String params :
                    List.of("{\"name\":\"capture_prompt\"}", "{\"name\":\"capture_prompt\",\"arguments\":{}}")) {
                // Missing required fixture arguments are upstream invocation errors, not adapter failures.
                assertError(client.send(request("prompts/get", 92, params)), MAPPER.valueToTree(92), -32603);
                assertThat(captureCounts().promptGets()).isEqualTo(promptGets);
            }
            assertThat(llmStub.getCallCount()).isZero();
        }
    }

    @Test
    @DisplayName("Malformed governed requests bypass active evaluators; valid calls reach each evaluator")
    void rejectsBeforeEvaluator() throws Exception {
        var wasm = baseConfig.getEvaluatorWasmPath();
        assumeThat(wasm != null && Files.exists(wasm))
                .as("A compiled evaluator WASM is required")
                .isTrue();
        ObjectNode root = MAPPER.createObjectNode();
        var evaluators = root.putArray("evaluators");
        for (String method : List.of("tools/call", "resources/read", "prompts/get")) {
            ObjectNode evaluator = evaluators.addObject();
            evaluator.put("name", "raw-parser-" + method.replace('/', '-'));
            evaluator.putObject("trigger").put("method", method);
            evaluator
                    .putObject("engine")
                    .put("type", "llm")
                    .put("operation", "classify")
                    .put("prompt", "Classify the safety of this MCP request.")
                    .put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
            evaluator.putObject("processor").put("path", wasm.toString());
        }
        assertThat(new EvaluatorClient(server.getBaseUrl(), null)
                        .updateEvaluators(root.toString())
                        .statusCode())
                .isEqualTo(200);
        try (RawMcpClient client = initializedClient()) {
            for (String method : List.of("tools/call", "resources/read", "prompts/get")) {
                String targetKey = method.equals("resources/read") ? "uri" : "name";
                String target = method.equals("prompts/get") ? CAPTURE_PROMPT : CAPTURE_TOOL;
                for (String params : List.of(
                        "OMITTED", "null", "[]", "{}", "{\"" + targetKey + "\":null}", "{\"" + targetKey + "\":42}")) {
                    assertAll(
                            method + " params=" + params,
                            () -> assertError(client.send(request(method, 101, params)), MAPPER.nullNode(), -32601));
                    assertNoExecution();
                }
                if (!method.equals("resources/read")) {
                    for (String arguments : List.of("[]", "null")) {
                        String params = "{\"name\":\"" + target + "\",\"arguments\":" + arguments + "}";
                        assertAll(
                                method + " params=" + params,
                                () -> assertInvalidAction(
                                        client.send(request(method, 101, params)), MAPPER.valueToTree(101)));
                        assertNoExecution();
                    }
                }
            }
            int before = llmStub.getCallCount();
            assertSuccess(
                    client.send(request(
                            "tools/call", 102, "{\"name\":\"capture_tool\",\"arguments\":{\"payload\":\"benign\"}}")),
                    MAPPER.valueToTree(102));
            assertThat(llmStub.getCallCount()).isGreaterThan(before);
            before = llmStub.getCallCount();
            assertSuccess(
                    client.send(request("resources/read", 103, "{\"uri\":\"" + CAPTURE_RESOURCE_URI + "\"}")),
                    MAPPER.valueToTree(103));
            assertThat(llmStub.getCallCount()).isGreaterThan(before);
            before = llmStub.getCallCount();
            assertSuccess(
                    client.send(request(
                            "prompts/get", 104, "{\"name\":\"capture_prompt\",\"arguments\":{\"topic\":\"benign\"}}")),
                    MAPPER.valueToTree(104));
            assertThat(llmStub.getCallCount()).isGreaterThan(before);
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(1, 1, 1));
        }
    }

    private RawMcpClient initializedClient() throws Exception {
        RawMcpClient client = new RawMcpClient(server.getMcpBaseUrl() + "/" + NAMESPACE + "/mcp");
        try {
            client.initialize();
            return client;
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    private static String request(String method, Object id, String params) throws Exception {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + MAPPER.writeValueAsString(id) + ",\"method\":\"" + method + "\""
                + (params.equals("OMITTED") ? "" : ",\"params\":" + params) + "}";
    }

    private void assertNoExecution() throws Exception {
        assertThat(captureCounts()).isEqualTo(new CaptureCounts(0, 0, 0));
        assertThat(llmStub.getCallCount()).isZero();
    }

    private static JsonNode assertError(HttpResponse<String> response, JsonNode id, int code) throws Exception {
        JsonNode envelope = envelope(response, id);
        assertThat(envelope.has("result")).isFalse();
        assertThat(envelope.path("error").path("code").isIntegralNumber()).isTrue();
        assertThat(envelope.path("error").path("code").intValue()).isEqualTo(code);
        assertThat(envelope.path("error").path("message").isTextual()).isTrue();
        assertThat(envelope.path("error").path("message").textValue()).isNotBlank();
        return envelope.get("error");
    }

    private static void assertInvalidAction(HttpResponse<String> response, JsonNode id) throws Exception {
        JsonNode error = assertError(response, id, -32003);
        assertThat(error.path("data").path("reason_code").asText()).isEqualTo("invalid_action_request");
    }

    private static JsonNode assertSuccess(HttpResponse<String> response, JsonNode id) throws Exception {
        JsonNode envelope = envelope(response, id);
        assertThat(envelope.has("error")).isFalse();
        assertThat(envelope.path("result").isObject()).isTrue();
        assertThat(envelope.path("result").path("isError").asBoolean(false)).isFalse();
        return envelope.get("result");
    }

    private static JsonNode envelope(HttpResponse<String> response, JsonNode id) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        JsonNode envelope = MAPPER.readTree(response.body());
        assertThat(envelope.isObject()).isTrue();
        assertThat(envelope.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(envelope.get("id")).as("Response: %s", response.body()).isEqualTo(id);
        return envelope;
    }
}
