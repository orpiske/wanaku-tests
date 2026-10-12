package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
class OpaContractITCase extends OpaTestBase {
    @Test
    void preservesExactInputTypesAndKeepsCredentialsPrivate() throws Exception {
        try (OpaHttpStub opa = new OpaHttpStub();
                HttpClient http = HttpClient.newHttpClient()) {
            opaUrl = opa.baseUrl();
            try {
                startOpaScenario("enforce", "basic", "deny", GovernancePolicies.policy());
                registerOpa(http, DECISION_PATH);
                JsonNode arguments = MAPPER.readTree(
                        "{\"value\":{\"number\":250,\"string\":\"250\",\"bool\":true,\"null\":null,\"array\":[1,\"2\"]}}");
                var inbound = tool("opa-types", arguments);
                ((com.fasterxml.jackson.databind.node.ObjectNode) inbound.path("params"))
                        .putObject("_meta")
                        .put("history", "private-conversation");
                var response = http.send(
                        HttpRequest.newBuilder(URI.create(server.getMcpBaseUrl() + "/" + NAMESPACE + "/mcp"))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .header("Authorization", "Bearer inbound-secret")
                                .POST(HttpRequest.BodyPublishers.ofString(inbound.toString()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("result").doesNotContain("\"error\"");
                assertThat(call(http, "opa-omitted", null).has("result")).isTrue();
                assertThat(opa.requests()).hasSize(2);
                OpaHttpStub.Request request = opa.requests().getFirst();
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/v1/data/" + DECISION_PATH);
                JsonNode expected = MAPPER.createObjectNode()
                        .set(
                                "input",
                                MAPPER.createObjectNode()
                                        .put("version", "wanaku.opa.input/v1")
                                        .put("method", "tools/call")
                                        .put("namespace", NAMESPACE)
                                        .put("tool_name", TOOL)
                                        .set("arguments", arguments));
                assertThat(MAPPER.readTree(request.body())).isEqualTo(expected);
                assertThat(request.headers().entrySet()).anySatisfy(entry -> {
                    assertThat(entry.getKey()).isEqualToIgnoringCase("Authorization");
                    assertThat(entry.getValue()).containsExactly("Bearer " + TOKEN);
                });
                assertThat(request.body())
                        .doesNotContain(
                                TOKEN,
                                "forged-actor",
                                "forged-workload",
                                "opa-types",
                                "inbound-secret",
                                "private-conversation");
                assertThat(MAPPER.readTree(opa.requests().get(1).body())
                                .path("input")
                                .path("arguments"))
                        .isEqualTo(MAPPER.createObjectNode());
                assertThat(captureCounts().toolCalls()).isEqualTo(2);
            } finally {
                stopWanaku();
            }
        }
    }

    @Test
    void absentConnectionTokenDoesNotForwardInboundCredentials() throws Exception {
        try (OpaHttpStub opa = new OpaHttpStub();
                HttpClient http = HttpClient.newHttpClient()) {
            opaUrl = opa.baseUrl();
            opaToken = null;
            try {
                startOpaScenario("enforce", "basic", "deny", GovernancePolicies.policy());
                registerOpa(http, DECISION_PATH);
                var response = http.send(
                        HttpRequest.newBuilder(URI.create(server.getMcpBaseUrl() + "/" + NAMESPACE + "/mcp"))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .header("Authorization", "Bearer inbound-secret")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        tool("opa-no-token", null).toString()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("result").doesNotContain("\"error\"");
                assertThat(opa.requests()).hasSize(1);
                assertThat(opa.requests().getFirst().headers().keySet()).noneMatch("Authorization"::equalsIgnoreCase);
                assertThat(captureCounts().toolCalls()).isEqualTo(1);
            } finally {
                stopWanaku();
            }
        }
    }

    static Stream<Arguments> failures() {
        return Stream.of("deny", "allow")
                .flatMap(posture -> Stream.of(
                        Arguments.of(posture, "unavailable", 200, "{}", "evaluator_remote_unavailable"),
                        Arguments.of(posture, "timeout", 200, "{}", "evaluator_remote_timeout"),
                        Arguments.of(posture, "401", 401, "{}", "evaluator_remote_auth_failed"),
                        Arguments.of(posture, "403", 403, "{}", "evaluator_remote_auth_failed"),
                        Arguments.of(posture, "policy", 500, "{}", "evaluator_policy_error"),
                        Arguments.of(posture, "undefined", 200, "{}", "evaluator_decision_undefined"),
                        Arguments.of(posture, "null", 200, "{\"result\":null}", "evaluator_decision_invalid"),
                        Arguments.of(posture, "malformed", 200, "not-json", "evaluator_decision_invalid"),
                        Arguments.of(posture, "nonobject", 200, "[]", "evaluator_decision_invalid"),
                        Arguments.of(
                                posture,
                                "bad-allow",
                                200,
                                "{\"result\":{\"allow\":\"true\"}}",
                                "evaluator_decision_invalid"),
                        Arguments.of(
                                posture,
                                "bad-reason",
                                200,
                                "{\"result\":{\"allow\":false,\"reason\":123}}",
                                "evaluator_decision_invalid"),
                        Arguments.of(
                                posture,
                                "oversized",
                                200,
                                "{\"result\":true,\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}",
                                "evaluator_decision_invalid")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("failures")
    void failuresFollowConfiguredPosture(String posture, String scenario, int status, String body, String reason)
            throws Exception {
        try (OpaHttpStub opa = new OpaHttpStub();
                HttpClient http = HttpClient.newHttpClient()) {
            opaUrl = opa.baseUrl();
            if (scenario.equals("timeout")) opaTimeoutMs = 250;
            try {
                startOpaScenario("enforce", "basic", posture, GovernancePolicies.policy());
                registerOpa(http, DECISION_PATH);
                opa.respond(status, body);
                if (scenario.equals("timeout")) opa.holdRequests();
                if (scenario.equals("unavailable")) opa.close();
                try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
                    JsonNode result = call(http, "opa-failure", MAPPER.createObjectNode());
                    if (posture.equals("deny"))
                        assertThat(result.path("error").path("code").asInt()).isEqualTo(-32001);
                    else assertThat(result.has("result")).isTrue();
                    assertThat(captureCounts().toolCalls()).isEqualTo(posture.equals("allow") ? 1 : 0);
                    JsonNode event = AuditRequests.event(audit, "opa-failure", "wanaku_evaluator");
                    assertThat(event.path("reason_code").asText()).isEqualTo(reason);
                    assertThat(event.path("attributes").path("failure").asBoolean())
                            .isTrue();
                    assertThat(llmStub.getCallCount()).isZero();
                    assertThat(event.path("attributes").path("engine").asText()).isEqualTo("opa");
                    assertThat(event.path("attributes").path("decision_path").asText())
                            .isEqualTo(DECISION_PATH);
                }
            } finally {
                stopWanaku();
            }
        }
    }
}
