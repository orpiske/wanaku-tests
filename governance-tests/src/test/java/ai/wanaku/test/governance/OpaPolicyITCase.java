package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.OpaServerManager;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@Timeout(120)
class OpaPolicyITCase extends OpaTestBase {
    @Override
    protected void checkAdditionalRequirements(TestConfiguration config) {
        super.checkAdditionalRequirements(config);
        if (config.getOpaBinaryPath() == null) {
            LOG.warn("Skipping real OPA scenario: set -Dwanaku.test.opa.binary to a native OPA binary");
        }
        if (config.getOpaBinaryPath() != null) {
            assertThat(Files.isExecutable(config.getOpaBinaryPath()))
                    .as("Configured OPA binary must be executable")
                    .isTrue();
        }
        assumeThat(config.getOpaBinaryPath())
                .as("Set -Dwanaku.test.opa.binary to a native OPA binary")
                .isNotNull();
    }

    @Test
    void regoDecisionsPreserveTypesAndUseLiveData() throws Exception {
        OpaServerManager opa = new OpaServerManager(baseConfig.getOpaBinaryPath(), baseConfig);
        try (HttpClient http = HttpClient.newHttpClient()) {
            opa.prepare();
            opa.start(getClass().getSimpleName());
            opaUrl = opa.getBaseUrl();
            var policy = http.send(
                    HttpRequest.newBuilder(URI.create(opaUrl + "/v1/policies/wanaku-tests"))
                            .header("Content-Type", "text/plain")
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                    Files.readString(Path.of("src/test/resources/opa/policy.rego"))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(policy.statusCode()).as(policy.body()).isEqualTo(200);
            updateData(http, false, 100);
            startOpaScenario("enforce", "basic", "deny", GovernancePolicies.policy());
            for (String path : new String[] {"wanaku/tool_call/allow", DECISION_PATH}) {
                registerOpa(http, path);
                assertPolicyDecision(http, "opa-number-" + path, "{\"value\":{\"amount\":50}}", null, path);
                assertPolicyDecision(
                        http,
                        "opa-string-" + path,
                        "{\"value\":{\"amount\":\"50\"}}",
                        path.equals(DECISION_PATH) ? "amount_not_a_number" : "not_allowed",
                        path);
                assertPolicyDecision(
                        http,
                        "opa-limit-" + path,
                        "{\"value\":{\"amount\":250}}",
                        path.equals(DECISION_PATH) ? "amount_over_limit" : "not_allowed",
                        path);
            }
            updateData(http, false, 300);
            assertPolicyDecision(http, "opa-live-limit", "{\"value\":{\"amount\":250}}", null, DECISION_PATH);
            updateData(http, true, 300);
            assertPolicyDecision(http, "opa-live-block", "{\"value\":{\"amount\":50}}", "tool_blocked", DECISION_PATH);
        } finally {
            stopWanaku();
            opa.stop();
        }
    }

    private void assertPolicyDecision(HttpClient http, String correlation, String arguments, String reason, String path)
            throws Exception {
        int before = captureCounts().toolCalls();
        JsonNode response = call(http, correlation, MAPPER.readTree(arguments));
        if (reason == null) {
            assertThat(response.has("result")).isTrue();
        } else {
            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32001);
            assertThat(response.path("error").path("message").asText()).contains("denied by policy: " + reason);
        }
        assertThat(response.path("id").asText()).isEqualTo(correlation);
        assertThat(captureCounts().toolCalls()).isEqualTo(before + (reason == null ? 1 : 0));
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode event = AuditRequests.event(audit, correlation, "wanaku_evaluator");
            assertThat(event.path("reason_code").asText()).isEqualTo("evaluator_evaluated");
            assertThat(event.path("attributes").path("failure"))
                    .isEqualTo(MAPPER.getNodeFactory().booleanNode(false));
            assertThat(event.path("attributes").path("engine").asText()).isEqualTo("opa");
            assertThat(event.path("attributes").path("decision_path").asText()).isEqualTo(path);
        }
    }

    private void updateData(HttpClient http, boolean blocked, int limit) throws Exception {
        var data = MAPPER.createObjectNode();
        var blockedTools = data.putObject("blocked_tools").putArray(NAMESPACE);
        if (blocked) blockedTools.add(TOOL);
        data.putObject("amount_limits").putObject(NAMESPACE).put(TOOL, limit);
        var response = http.send(
                HttpRequest.newBuilder(URI.create(opaUrl + "/v1/data/policy"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(data.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isIn(200, 204);
    }
}
