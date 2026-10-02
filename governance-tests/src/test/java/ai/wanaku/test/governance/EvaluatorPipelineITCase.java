package ai.wanaku.test.governance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.client.EvaluatorClient.EvaluatorResponse;
import ai.wanaku.test.client.McpTestClient;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Exercises the second governance stage: after a request passes the static action policy, the
 * {@code wanaku_evaluator} filter classifies it via the LLM engine. The engine is pointed at the
 * deterministic in-JVM stub, so a chosen payload marker yields a {@code red} verdict (mapped by the
 * safety-review WASM processor to {@code Block} → JSON-RPC -32001) and any other payload yields
 * {@code green} ({@code Pass}), which reaches the upstream capture server.
 *
 * <p>The stub's call count proves the evaluator engine — not a paid or external LLM — produced the
 * verdict. Requires a compiled evaluator WASM action; the whole class skips when none is available.
 */
class EvaluatorPipelineITCase extends GovernanceTestBase {

    private static final int EVALUATOR_BLOCK_CODE = -32001;
    private static final String NAMESPACE = "governed";
    private static final String EVALUATOR_NAME = "governance-safety-eval";
    private static final String DENY_MARKER = "please-block";

    @Override
    protected void checkAdditionalRequirements(ai.wanaku.test.config.TestConfiguration config) {
        Path wasm = config.getEvaluatorWasmPath();
        assumeThat(wasm != null && Files.exists(wasm))
                .as("A compiled evaluator WASM action is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();
    }

    @BeforeEach
    void startGovernedInfrastructure() throws Exception {
        // no rules + no_match=allow: every request clears the action policy and reaches the evaluator.
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        registerSafetyEvaluator(baseConfig.getEvaluatorWasmPath());
    }

    @DisplayName("Evaluator blocks a flagged tool call (-32001) without reaching the upstream")
    @Test
    void evaluatorBlocksFlaggedCall() throws Exception {
        llmStub.setDenyMarker(DENY_MARKER);

        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", DENY_MARKER))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(EVALUATOR_BLOCK_CODE);
                    assertThat(error.message()).contains("blocked by evaluator");
                })
                .send()
                .thenAssertResults();

        assertThat(llmStub.getCallCount())
                .as("The evaluator engine (stub), not an external LLM, must have produced the verdict")
                .isPositive();
        assertThat(captureCounts().toolCalls())
                .as("A blocked tool call must not reach the upstream capture server")
                .isZero();
    }

    @DisplayName("Evaluator passes a benign tool call, which reaches the upstream")
    @Test
    void evaluatorPassesBenignCall() throws Exception {
        llmStub.setDenyMarker(DENY_MARKER);

        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "allow-me"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        assertThat(llmStub.getCallCount())
                .as("The evaluator engine (stub) must have classified the request")
                .isPositive();
        assertThat(captureCounts().toolCalls())
                .as("A passed tool call must reach the upstream capture server")
                .isEqualTo(1);
    }

    @DisplayName("Multiple matching allows continue to the evaluator; a matching deny skips it")
    @Test
    void staticPrecedenceControlsEvaluatorExecution() throws Exception {
        llmStub.setDenyMarker(DENY_MARKER);
        ObjectNode broadAllow = MAPPER.createObjectNode();
        broadAllow.put("id", "allow-broad").put("effect", "allow");
        broadAllow.putObject("selectors").put("operation", "tools/call");
        ObjectNode specificAllow = GovernancePolicies.denyTool("allow-specific", CAPTURE_TOOL, "unused");
        specificAllow.put("effect", "allow");
        specificAllow.remove("message");
        specificAllow.remove("reason_code");
        ObjectNode deny = GovernancePolicies.denyTool("deny-specific", CAPTURE_TOOL, "Static precedence denial.");

        try (ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl())) {
            assertThat(policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy(broadAllow, specificAllow)))
                            .statusCode())
                    .isEqualTo(200);
            McpTestClient client = connect(NAMESPACE);
            client.when()
                    .toolsCall(CAPTURE_TOOL)
                    .withArguments(Map.of("payload", DENY_MARKER))
                    .withErrorAssert(error -> assertThat(error.code()).isEqualTo(EVALUATOR_BLOCK_CODE))
                    .send()
                    .thenAssertResults();
            int evaluatorCalls = llmStub.getCallCount();
            assertThat(evaluatorCalls).isPositive();
            assertThat(captureCounts().toolCalls()).isZero();

            assertThat(policies.updatePolicy(
                                    MAPPER.readTree(GovernancePolicies.policy(broadAllow, specificAllow, deny)))
                            .statusCode())
                    .isEqualTo(200);
            client.when()
                    .toolsCall(CAPTURE_TOOL)
                    .withArguments(Map.of("payload", DENY_MARKER))
                    .withErrorAssert(error -> {
                        assertThat(error.code()).isEqualTo(-32003);
                        assertThat(error.message()).isEqualTo("Static precedence denial.");
                    })
                    .send()
                    .thenAssertResults();
            assertThat(llmStub.getCallCount()).isEqualTo(evaluatorCalls);
            assertThat(captureCounts().toolCalls()).isZero();
        }
    }

    private void registerSafetyEvaluator(Path wasm) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode evaluators = root.putArray("evaluators");

        ObjectNode evaluator = MAPPER.createObjectNode();
        evaluator.put("name", EVALUATOR_NAME);

        ObjectNode trigger = MAPPER.createObjectNode();
        trigger.put("method", "tools/call");
        evaluator.set("trigger", trigger);

        ObjectNode engine = MAPPER.createObjectNode();
        engine.put("type", "llm");
        engine.put("operation", "classify");
        engine.put("prompt", "Classify the safety of this MCP request.");
        engine.put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        evaluator.set("engine", engine);

        ObjectNode processor = MAPPER.createObjectNode();
        processor.put("path", wasm.toString());
        evaluator.set("processor", processor);

        evaluators.add(evaluator);

        EvaluatorClient evaluatorClient = new EvaluatorClient(server.getBaseUrl(), null);
        EvaluatorResponse response = evaluatorClient.updateEvaluators(root.toString());
        assertThat(response.statusCode())
                .as("Registering the safety evaluator should succeed: %s", response.body())
                .isEqualTo(200);
    }
}
