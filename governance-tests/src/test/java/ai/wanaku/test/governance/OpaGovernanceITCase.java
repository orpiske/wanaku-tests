package ai.wanaku.test.governance;

import java.net.http.HttpClient;
import java.util.stream.Stream;
import ai.wanaku.test.client.AuditClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(120)
class OpaGovernanceITCase extends OpaTestBase {
    static Stream<Arguments> postures() {
        return Stream.of(
                Arguments.of("enforce", "basic", "deny", 1, 0),
                Arguments.of("enforce", "basic", "allow", 1, 0),
                Arguments.of("audit", "full", "deny", 1, 1),
                Arguments.of("audit", "basic", "deny", 0, 1));
    }

    @ParameterizedTest
    @MethodSource("postures")
    void denialsRespectGovernanceMode(String mode, String level, String onFailure, int evaluations, int forwarded)
            throws Exception {
        try (OpaHttpStub opa = new OpaHttpStub();
                HttpClient http = HttpClient.newHttpClient()) {
            opaUrl = opa.baseUrl();
            try {
                startOpaScenario(mode, level, onFailure, GovernancePolicies.policy());
                registerOpa(http, DECISION_PATH);
                opa.respond(200, "{\"result\":{\"allow\":false,\"reason\":\"tool_blocked\"}}");
                try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
                    JsonNode response = call(http, "opa-denial", MAPPER.createObjectNode());
                    if (forwarded == 0) {
                        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32001);
                        assertThat(response.path("error").path("message").asText())
                                .contains("tool_blocked");
                    } else assertThat(response.has("result")).isTrue();
                    assertThat(response.path("id").asText()).isEqualTo("opa-denial");
                    assertThat(opa.requests()).hasSize(evaluations);
                    assertThat(captureCounts().toolCalls()).isEqualTo(forwarded);
                    JsonNode event = AuditRequests.event(audit, "opa-denial", "wanaku_evaluator");
                    assertThat(event.path("attributes").path("failure"))
                            .isEqualTo(MAPPER.getNodeFactory().booleanNode(false));
                    assertThat(event.path("attributes").path("engine").asText()).isEqualTo("opa");
                    assertThat(event.path("attributes").path("decision_path").asText())
                            .isEqualTo(DECISION_PATH);
                    assertThat(event.path("reason_code").asText())
                            .isEqualTo(
                                    evaluations == 0
                                            ? "evaluator_external_evaluation_disabled"
                                            : "evaluator_evaluated");
                    if (mode.equals("audit") && level.equals("full")) {
                        assertThat(event.path("attributes")
                                        .path("would_be_action")
                                        .asText())
                                .isEqualTo("block");
                        assertThat(event.path("attributes")
                                        .path("effective_action")
                                        .asText())
                                .isEqualTo("pass");
                    }
                }
            } finally {
                stopWanaku();
            }
        }
    }

    @Test
    void staticDenialAndUnmatchedTriggerBypassOpa() throws Exception {
        try (OpaHttpStub opa = new OpaHttpStub();
                HttpClient http = HttpClient.newHttpClient()) {
            opaUrl = opa.baseUrl();
            try {
                startOpaScenario(
                        "enforce",
                        "basic",
                        "deny",
                        GovernancePolicies.policy(GovernancePolicies.denyTool("static-denial", TOOL, "Static denial")));
                registerOpa(http, DECISION_PATH);
                assertThat(call(http, "opa-static", MAPPER.createObjectNode())
                                .path("error")
                                .path("code")
                                .asInt())
                        .isEqualTo(-32003);
                assertThat(manage(http, "PUT", "/api/v1/action-policies", MAPPER.readTree(GovernancePolicies.policy()))
                                .statusCode())
                        .isEqualTo(200);
                assertThat(manage(
                                        http,
                                        "PUT",
                                        "/api/v1/evaluators",
                                        evaluator(DECISION_PATH, "opa-fixture", "resources/read"))
                                .statusCode())
                        .isEqualTo(200);
                assertThat(call(http, "opa-unmatched", MAPPER.createObjectNode())
                                .has("result"))
                        .isTrue();
                assertThat(opa.requests()).isEmpty();
                assertThat(captureCounts().toolCalls()).isEqualTo(1);
            } finally {
                stopWanaku();
            }
        }
    }

    @Test
    void invalidUpdatesPreserveActiveEvaluatorAndManagementRedactsCredentials() throws Exception {
        try (OpaHttpStub opa = new OpaHttpStub();
                HttpClient http = HttpClient.newHttpClient()) {
            opaUrl = opa.baseUrl();
            try {
                startOpaScenario("enforce", "basic", "deny", GovernancePolicies.policy());
                registerOpa(http, DECISION_PATH);
                opa.respond(200, "{\"result\":false}");
                for (JsonNode invalid : new JsonNode[] {
                    evaluator(DECISION_PATH, "unknown-connection", "tools/call"),
                    evaluator("../invalid", "opa-fixture", "tools/call")
                }) {
                    assertThat(manage(http, "PUT", "/api/v1/evaluators", invalid)
                                    .statusCode())
                            .isBetween(400, 499);
                    assertThat(call(http, "opa-preserved-" + opa.requests().size(), MAPPER.createObjectNode())
                                    .path("error")
                                    .path("code")
                                    .asInt())
                            .isEqualTo(-32001);
                }
                for (String path :
                        new String[] {"/api/v1/evaluators", "/api/v1/evaluators/status", "/api/v1/evaluators/revisions"
                        }) {
                    var response = manage(http, "GET", path, MAPPER.createObjectNode());
                    assertThat(response.statusCode()).as(path).isEqualTo(200);
                    assertThat(response.body()).doesNotContain(TOKEN);
                }
                assertThat(opa.requests()).hasSize(2);
                assertThat(captureCounts().toolCalls()).isZero();
            } finally {
                stopWanaku();
            }
        }
    }
}
