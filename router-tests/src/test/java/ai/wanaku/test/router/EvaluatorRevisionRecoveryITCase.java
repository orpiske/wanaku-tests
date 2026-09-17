package ai.wanaku.test.router;

import java.nio.file.Files;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.client.EvaluatorClient.EvaluatorResponse;
import ai.wanaku.test.managers.WanakuServerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Recovery tests for persisted evaluator configuration revisions (wanaku-ai/wanaku#1868). */
class EvaluatorRevisionRecoveryITCase extends RouterTestBase {

    private final ObjectMapper mapper = new ObjectMapper();

    private WanakuServerManager managedServer;
    private EvaluatorClient evaluatorClient;

    @BeforeEach
    void startManagedServer() throws Exception {
        assumeThat(config.getServerBinaryPath() != null && Files.exists(config.getServerBinaryPath()))
                .as("A managed Wanaku server binary is required for evaluator recovery tests")
                .isTrue();
        assumeThat(config.getEvaluatorWasmPath() != null && Files.exists(config.getEvaluatorWasmPath()))
                .as("A compiled evaluator WASM action is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();

        managedServer = new WanakuServerManager(config);
        managedServer.prepare();
        managedServer.start("evaluator-revision-recovery");
        evaluatorClient = new EvaluatorClient(managedServer.getBaseUrl(), null);
    }

    @AfterEach
    void stopManagedServer() {
        if (managedServer != null) {
            managedServer.stop();
        }
    }

    @DisplayName("A failed activation keeps the previous revision active")
    @Test
    void shouldKeepPreviousRevisionActiveWhenActivationFails() {
        EvaluatorResponse accepted = evaluatorClient.updateEvaluators(validPayload("known-good-evaluator"));
        assertThat(accepted.statusCode()).isEqualTo(200);
        long activeRevisionId = accepted.body().get("revision").get("id").asLong();

        EvaluatorResponse rejected =
                evaluatorClient.updateEvaluators(payload("invalid-replacement", "/not-found.wasm"));

        assertThat(rejected.statusCode()).isEqualTo(422);
        EvaluatorResponse active = evaluatorClient.getActiveRevision();
        assertThat(active.statusCode()).isEqualTo(200);
        assertThat(active.body().get("revision").get("id").asLong()).isEqualTo(activeRevisionId);
        assertThat(active.body().get("evaluators").get(0).get("name").asText()).isEqualTo("known-good-evaluator");
    }

    @DisplayName("Evaluator revisions and the active configuration survive a managed restart")
    @Test
    void shouldRecoverActiveRevisionAfterRestart() throws Exception {
        EvaluatorResponse update = evaluatorClient.updateEvaluators(validPayload("restart-persisted-evaluator"));
        assertThat(update.statusCode()).isEqualTo(200);
        long revisionId = update.body().get("revision").get("id").asLong();

        managedServer.stopPreservingState();
        managedServer.start("evaluator-revision-recovery");

        EvaluatorResponse active = evaluatorClient.getActiveRevision();
        assertThat(active.statusCode()).isEqualTo(200);
        assertThat(active.body().get("revision").get("id").asLong()).isEqualTo(revisionId);
        assertThat(active.body().get("evaluators").get(0).get("name").asText())
                .isEqualTo("restart-persisted-evaluator");
        assertNoConnectionSecret(active.body());

        JsonNode revisions = evaluatorClient.listRevisions();
        assertThat(revisions).anyMatch(revision -> revision.get("id").asLong() == revisionId);
    }

    private String validPayload(String evaluatorName) {
        return payload(evaluatorName, config.getEvaluatorWasmPath().toString());
    }

    private String payload(String evaluatorName, String processorPath) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode evaluator = root.putArray("evaluators").addObject();
        evaluator.put("name", evaluatorName);
        evaluator.putObject("trigger").put("method", "tools/call");
        ObjectNode llm = evaluator.putObject("llm");
        llm.put("operation", "classify");
        llm.put("prompt", "Test prompt for " + evaluatorName);
        llm.put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        evaluator.putObject("processor").put("path", processorPath);
        evaluator.put("on_error", "continue");
        return root.toString();
    }

    private void assertNoConnectionSecret(JsonNode body) {
        assertThat(body.toString())
                .doesNotContain(WanakuTestConstants.TEST_LLM_CONNECTION_SECRET)
                .doesNotContain("api_key")
                .contains("\"connection\":\"" + WanakuTestConstants.TEST_LLM_CONNECTION_NAME + "\"");
    }
}
