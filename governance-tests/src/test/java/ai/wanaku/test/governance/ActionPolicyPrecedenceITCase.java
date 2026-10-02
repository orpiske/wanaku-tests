package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ai.wanaku.test.client.ActionPolicyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Deny wins over every matching allow; the lowest deny ID supplies the stable caller-safe reason. */
class ActionPolicyPrecedenceITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "governed";
    private static final String SECRET = "precedence-secret-do-not-disclose";
    private static final String MESSAGE = "Action denied by precedence policy.";

    @ParameterizedTest(name = "deny precedence with no_match={0}")
    @ValueSource(strings = {"allow", "deny"})
    void activatedRuleOrderDoesNotChangeDenial(String noMatch) throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce(noMatch));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE); // Complete MCP initialization before sending governed requests.
        try (ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl());
                HttpClient http = HttpClient.newHttpClient()) {
            for (String operation : List.of("tools/call", "resources/read", "prompts/get")) {
                ObjectNode specificAllow = rule("allow-specific", "allow", operation, true);
                ObjectNode broadAllow = rule("allow-broad", "allow", operation, false);
                ObjectNode specificDeny = rule("a-specific-deny", "deny", operation, true);
                ObjectNode broadDeny = rule("z-broad-deny", "deny", operation, false);
                broadDeny.put("message", "Secondary safe denial reason.").put("reason_code", "secondary_denied");
                ObjectNode irrelevant = rule("0-irrelevant-deny", "deny", operation, false);
                ((ObjectNode) irrelevant.get("selectors")).put("namespace", "another-namespace");

                // Explicit allows continue even when the default posture would deny a no-match.
                activate(policies, List.of(specificAllow, broadAllow, irrelevant));
                JsonNode explicitAllowResponse = request(http, operation);
                if ("allow".equals(noMatch)) {
                    assertThat(explicitAllowResponse.has("result")).isTrue();
                } else {
                    // Action-policy allow continues to the independent evaluator's no-match deny.
                    assertThat(explicitAllowResponse.path("error").path("code").asInt())
                            .isEqualTo(-32001);
                    assertThat(explicitAllowResponse.path("error").toString()).doesNotContain(SECRET);
                }
                CaptureCounts allowed = captureCounts();

                List<ObjectNode> rules =
                        new ArrayList<>(List.of(broadAllow, specificAllow, broadDeny, specificDeny, irrelevant));
                for (int order = 0; order < rules.size(); order++) {
                    Collections.rotate(rules, 1);
                    activate(policies, rules);
                    assertDenied(request(http, operation), "precedence_denied", MESSAGE);
                    assertThat(captureCounts()).isEqualTo(allowed);
                }
                Collections.reverse(rules);
                activate(policies, rules);
                assertDenied(request(http, operation), "precedence_denied", MESSAGE);
                assertThat(captureCounts()).isEqualTo(allowed);

                // Swap specificity of the primary deny: ID, not specificity, chooses the reason.
                specificDeny.set("selectors", broadDeny.get("selectors").deepCopy());
                broadDeny.set("selectors", specificAllow.get("selectors").deepCopy());
                activate(policies, rules);
                assertDenied(request(http, operation), "precedence_denied", MESSAGE);
                assertThat(captureCounts()).isEqualTo(allowed);

                activate(policies, List.of(irrelevant));
                JsonNode noMatchResponse = request(http, operation);
                if ("deny".equals(noMatch)) {
                    assertDenied(noMatchResponse, "governance_no_match", "No governance policy permits this action.");
                    assertThat(captureCounts()).isEqualTo(allowed);
                } else {
                    assertThat(noMatchResponse.has("result")).isTrue();
                }
            }
            int expected = "allow".equals(noMatch) ? 2 : 0;
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(expected, expected, expected));
        }
    }

    @Test
    void unavailablePolicyHasDistinctSafeReasonAndCanRecover() throws Exception {
        startGovernedServer("{\"rules\":\"invalid\"}", GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        try (ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl());
                HttpClient http = HttpClient.newHttpClient()) {
            assertThat(policies.getActiveRevision().statusCode()).isEqualTo(404);
            for (String operation : List.of("tools/call", "resources/read", "prompts/get")) {
                assertDenied(request(http, operation), "action_policy_invalid", "The action policy is unavailable.");
            }
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(0, 0, 0));
            activate(policies, List.of(rule("recovered-deny", "deny", "tools/call", true)));
            assertDenied(request(http, "tools/call"), "precedence_denied", MESSAGE);
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(0, 0, 0));
        }
    }

    private static ObjectNode rule(String id, String effect, String operation, boolean specific) {
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("id", id).put("effect", effect);
        if ("deny".equals(effect)) {
            rule.put("reason_code", "precedence_denied").put("message", MESSAGE);
        }
        ObjectNode selectors = rule.putObject("selectors");
        selectors.put("operation", operation);
        if (specific) {
            selectors.put("namespace", NAMESPACE);
            if ("resources/read".equals(operation)) {
                selectors.putObject("uri").put("matcher", "exact").put("value", CAPTURE_RESOURCE_URI);
            } else {
                selectors
                        .putObject("target_name")
                        .put("matcher", "exact")
                        .put("value", "tools/call".equals(operation) ? CAPTURE_TOOL : CAPTURE_PROMPT);
            }
        }
        return rule;
    }

    private static void activate(ActionPolicyClient policies, List<ObjectNode> rules) throws Exception {
        JsonNode document = MAPPER.readTree(GovernancePolicies.policy(rules.toArray(ObjectNode[]::new)));
        var response = policies.updatePolicy(document);
        assertThat(response.statusCode())
                .as("Activate policy: %s", response.body())
                .isEqualTo(200);
        var active = policies.getActiveRevision();
        assertThat(active.statusCode()).isEqualTo(200);
        assertThat(active.body().path("policy")).isEqualTo(response.body().path("policy"));
        assertThat(active.body().path("revision").path("id"))
                .isEqualTo(response.body().path("revision").path("id"));
        assertThat(active.body().path("revision").path("id").asText()).isNotEmpty();
    }

    private JsonNode request(HttpClient http, String operation) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0").put("id", 222).put("method", operation);
        ObjectNode params = body.putObject("params");
        if ("resources/read".equals(operation)) {
            params.put("uri", CAPTURE_RESOURCE_URI);
        } else {
            params.put("name", "tools/call".equals(operation) ? CAPTURE_TOOL : CAPTURE_PROMPT);
            params.putObject("arguments").put("tools/call".equals(operation) ? "payload" : "topic", SECRET);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.getMcpBaseUrl() + "/" + NAMESPACE + "/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String json = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            json = json.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();
        }
        JsonNode result = MAPPER.readTree(json);
        assertThat(result.path("id").asInt()).isEqualTo(222);
        return result;
    }

    private static void assertDenied(JsonNode response, String reason, String message) {
        JsonNode error = response.path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32003);
        assertThat(error.path("message").asText()).isEqualTo(message);
        assertThat(error.path("data")).isEqualTo(MAPPER.createObjectNode().put("reason_code", reason));
        assertThat(error.toString()).doesNotContain(SECRET);
    }
}
