package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.config.TestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Deterministic LLM verdicts and management revisions produce distinct audit categories. */
@Timeout(120)
class AuditEvaluatorITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "audit-evaluator";
    private static final String EVALUATOR = "audit-safety-review";
    private static final String DENY_MARKER = "audit-please-block";

    @Override
    protected void checkAdditionalRequirements(TestConfiguration config) {
        assumeThat(config.getEvaluatorWasmPath() != null && Files.exists(config.getEvaluatorWasmPath()))
                .as("A compiled safety-review WASM is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();
    }

    @Test
    void evaluatorVerdictsFollowUpdatesAndActivatedRevisions() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        llmStub.setDenyMarker(DENY_MARKER);
        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode first = manage(http, "/api/v1/evaluators", "PUT", evaluatorConfig(), "eval-update-225");
            String firstRevision = first.path("revision").path("id").asText();
            assertThat(firstRevision).isNotEmpty();
            assertAdministrative(audit, "eval-update-225", "evaluator.update", "/api/v1/evaluators");

            JsonNode passed = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool("pass", CAPTURE_TOOL, "benign-audit-payload"),
                    "eval-pass-225");
            assertThat(passed.has("result")).isTrue();
            assertVerdict(audit, "eval-pass-225", firstRevision, "allow");
            JsonNode blocked = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool("block", CAPTURE_TOOL, DENY_MARKER),
                    "eval-block-225");
            assertThat(blocked.path("error").path("code").asInt()).isEqualTo(-32001);
            assertVerdict(audit, "eval-block-225", firstRevision, "block");
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
            int evaluations = llmStub.getCallCount();
            assertThat(evaluations).isEqualTo(2);

            ObjectNode empty = MAPPER.createObjectNode();
            empty.putArray("evaluators");
            JsonNode second = manage(http, "/api/v1/evaluators", "PUT", empty, "eval-clear-225");
            String secondRevision = second.path("revision").path("id").asText();
            assertThat(secondRevision).isNotEqualTo(firstRevision);
            assertAdministrative(audit, "eval-clear-225", "evaluator.update", "/api/v1/evaluators");
            assertThat(AuditRequests.send(
                                    http,
                                    server.getMcpBaseUrl(),
                                    NAMESPACE,
                                    AuditRequests.tool("no-match", CAPTURE_TOOL, DENY_MARKER),
                                    "eval-no-match-225")
                            .has("result"))
                    .isTrue();
            JsonNode noMatch = AuditRequests.event(audit, "eval-no-match-225", "wanaku_evaluator");
            assertThat(noMatch.path("policy_revision").asText()).isEqualTo(secondRevision);
            assertThat(noMatch.path("decision").asText()).isEqualTo("allow");
            assertThat(noMatch.path("attributes").path("evaluation_status").asText())
                    .isEqualTo("no_match");
            assertThat(llmStub.getCallCount()).isEqualTo(evaluations);

            String activationPath = "/api/v1/evaluators/revisions/" + firstRevision + "/activate";
            JsonNode activation = manage(http, activationPath, "POST", MAPPER.createObjectNode(), "eval-activate-225");
            String activatedRevision = activation.path("revision").path("id").asText();
            assertThat(activatedRevision).isNotEmpty().isNotEqualTo(secondRevision);
            assertAdministrative(audit, "eval-activate-225", "evaluator.revision.activate", activationPath);
            assertThat(AuditRequests.send(
                                    http,
                                    server.getMcpBaseUrl(),
                                    NAMESPACE,
                                    AuditRequests.tool("rollback-block", CAPTURE_TOOL, DENY_MARKER),
                                    "eval-reactivated-225")
                            .path("error")
                            .path("code")
                            .asInt())
                    .isEqualTo(-32001);
            assertVerdict(audit, "eval-reactivated-225", activatedRevision, "block");
            assertThat(llmStub.getCallCount()).isEqualTo(evaluations + 1);
            assertThat(captureCounts().toolCalls()).isEqualTo(2);
        }
    }

    private ObjectNode evaluatorConfig() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode evaluator = root.putArray("evaluators").addObject().put("name", EVALUATOR);
        evaluator.putObject("trigger").put("method", "tools/call");
        evaluator
                .putObject("engine")
                .put("type", "llm")
                .put("operation", "classify")
                .put("prompt", "Classify the safety of this MCP request.")
                .put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        evaluator
                .putObject("processor")
                .put("path", baseConfig.getEvaluatorWasmPath().toString());
        return root;
    }

    private JsonNode manage(HttpClient http, String path, String method, JsonNode body, String correlation)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-request-id", correlation)
                .header("x-actor", "forged-admin")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Evaluator management: %s", response.body())
                .isEqualTo(200);
        return MAPPER.readTree(response.body()).path("data");
    }

    private static void assertAdministrative(AuditClient audit, String correlation, String operation, String path) {
        JsonNode page = audit.listEvents(Map.of("correlation_id", correlation));
        assertThat(page.path("events")).hasSize(1);
        JsonNode event = page.path("events").get(0);
        AuditRequests.assertMetadata(event, correlation, "administrative", operation);
        assertThat(event.path("decision").asText()).isEqualTo("allow");
        assertThat(event.path("reason_code").asText()).isEqualTo("configuration_changed");
        assertThat(event.path("protocol").asText()).isEqualTo("http");
        assertThat(event.path("target_type").asText()).isEqualTo("evaluator_configuration");
        assertThat(event.path("target").asText()).isEqualTo(path);
        assertThat(event.path("response_status").asInt()).isEqualTo(200);
        assertThat(event.hasNonNull("actor")).isFalse();
        assertThat(event.hasNonNull("workload")).isFalse();
        assertThat(event.hasNonNull("payload")).isFalse();
        assertThat(audit.getEvent(event.path("event_id").asText())).isEqualTo(event);
    }

    private static void assertVerdict(AuditClient audit, String correlation, String revision, String decision) {
        JsonNode event = AuditRequests.event(audit, correlation, "wanaku_evaluator");
        AuditRequests.assertMetadata(event, correlation, "decision", "tools/call");
        assertThat(event.path("evaluator").asText()).isEqualTo(EVALUATOR);
        assertThat(event.path("namespace").asText()).isEqualTo(NAMESPACE);
        assertThat(event.path("target").asText()).isEqualTo(CAPTURE_TOOL);
        assertThat(event.path("policy_revision").asText()).isEqualTo(revision);
        assertThat(event.path("decision").asText()).isEqualTo(decision);
        assertThat(event.path("reason_code").asText()).isEqualTo("evaluator_evaluated");
        assertThat(event.path("attributes").path("evaluation_status").asText()).isEqualTo("evaluated");
        assertThat(event.path("attributes").path("effective_action").asText())
                .isEqualTo("allow".equals(decision) ? "pass" : decision);
        for (String field : List.of("actor", "workload", "payload")) {
            assertThat(event.hasNonNull(field)).isFalse();
        }
    }
}
