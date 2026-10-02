package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.AuditClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

/** Audit evidence is observed through real MCP traffic, independently of the caller response. */
@Timeout(120)
class AuditEventsITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "audit-events";
    private static final String SAFE_REASON = "Denied by the audit test policy.";

    @Test
    void decisionsRetainMetadataAndAllDeniesWithoutDisclosingPayloads() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null);
                ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl())) {
            ObjectNode body = AuditRequests.tool("jsonrpc-id", CAPTURE_TOOL, "synthetic-private-payload");
            body.withObject("/params/arguments")
                    .put("x-request-id", "conversation-225")
                    .put("actor", "forged-actor")
                    .put("workload", "forged-workload");
            assertThat(AuditRequests.send(http, server.getMcpBaseUrl(), NAMESPACE, body, "allowed-225")
                            .has("result"))
                    .isTrue();
            JsonNode allowed = AuditRequests.event(audit, "allowed-225", "wanaku_action_policy");
            AuditRequests.assertMetadata(allowed, "allowed-225", "decision", "tools/call");
            assertThat(allowed.path("decision").asText()).isEqualTo("allow");
            assertThat(allowed.path("namespace").asText()).isEqualTo(NAMESPACE);
            assertThat(allowed.path("target").asText()).isEqualTo(CAPTURE_TOOL);
            assertThat(allowed.path("target_type").asText()).isEqualTo("tool");
            assertThat(allowed.path("conversation_id").asText()).isEqualTo("conversation-225");
            assertThat(allowed.path("policy_revision").asText()).isNotEmpty();

            ObjectNode first = GovernancePolicies.denyTool("a-deny", CAPTURE_TOOL, SAFE_REASON);
            first.put("reason_code", "audit_primary_denial");
            ObjectNode second = GovernancePolicies.denyTool("z-deny", CAPTURE_TOOL, "Secondary safe reason.");
            second.put("reason_code", "audit_secondary_denial");
            var activated = policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy(second, first)));
            assertThat(activated.statusCode()).isEqualTo(200);
            JsonNode deniedResponse = AuditRequests.send(http, server.getMcpBaseUrl(), NAMESPACE, body, "denied-225");
            assertThat(deniedResponse.path("error").path("code").asInt()).isEqualTo(-32003);
            assertThat(deniedResponse.path("error").path("message").asText()).isEqualTo(SAFE_REASON);
            assertThat(deniedResponse.path("error").path("data"))
                    .isEqualTo(MAPPER.createObjectNode().put("reason_code", "audit_primary_denial"));
            JsonNode denied = AuditRequests.event(audit, "denied-225", "wanaku_action_policy");
            AuditRequests.assertMetadata(denied, "denied-225", "decision", "tools/call");
            assertThat(denied.path("decision").asText()).isEqualTo("block");
            assertThat(denied.path("policy_revision").asText())
                    .isEqualTo(activated.body().path("revision").path("id").asText());
            assertThat(AuditRequests.textValues(denied.path("attributes").path("matched_rule_ids")))
                    .containsExactlyInAnyOrder("a-deny", "z-deny");
            assertThat(AuditRequests.textValues(denied.path("attributes").path("deny_reason_codes")))
                    .containsExactlyInAnyOrder("audit_primary_denial", "audit_secondary_denial");

            ObjectNode malformed = AuditRequests.tool("malformed-jsonrpc", CAPTURE_TOOL, "private-malformed-payload");
            // Keep method/name routable; the governance adapter rejects scalar arguments.
            malformed.withObject("/params").put("arguments", "not-an-object");
            assertThat(AuditRequests.send(http, server.getMcpBaseUrl(), NAMESPACE, malformed, "malformed-225")
                            .path("error")
                            .path("code")
                            .asInt())
                    .isEqualTo(-32003);
            JsonNode rejected = AuditRequests.event(audit, "malformed-225", "wanaku_action_policy");
            AuditRequests.assertMetadata(rejected, "malformed-225", "decision", "tools/call");
            assertThat(rejected.path("decision").asText()).isEqualTo("reject_malformed");
            assertThat(rejected.path("reason_code").asText()).isEqualTo("invalid_action_request");
            for (JsonNode event : List.of(allowed, denied, rejected)) {
                assertThat(event.hasNonNull("payload")).isFalse();
                assertThat(event.path("redaction").path("payload_captured").asBoolean())
                        .isFalse();
                assertThat(event.hasNonNull("actor")).isFalse();
                assertThat(event.hasNonNull("workload")).isFalse();
                assertThat(event.toString())
                        .doesNotContain("synthetic-private-payload", "forged-actor", "forged-workload");
                assertThat(audit.getEvent(event.path("event_id").asText())).isEqualTo(event);
            }
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
            assertThat(audit.schema().path("schema_version").asText()).isEqualTo("1.0");
        }
    }

    @Test
    void paginationAndEveryDocumentedFilterSelectControlledEvents() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        registerCaptureForward("audit-other");
        connect(NAMESPACE);
        connect("audit-other");
        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null);
                ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl())) {
            AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool("first", CAPTURE_TOOL, "first"),
                    "query-first");
            ObjectNode resource = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", "resource")
                    .put("method", "resources/read");
            resource.putObject("params").put("uri", CAPTURE_RESOURCE_URI);
            AuditRequests.send(http, server.getMcpBaseUrl(), "audit-other", resource, "query-resource");
            ObjectNode deny = GovernancePolicies.denyTool("query-deny", CAPTURE_TOOL, SAFE_REASON);
            deny.put("reason_code", "query_distinct_denial");
            assertThat(policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy(deny)))
                            .statusCode())
                    .isEqualTo(200);
            AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool("last", CAPTURE_TOOL, "last"),
                    "query-last");
            JsonNode last = AuditRequests.event(audit, "query-last", "wanaku_action_policy");
            Map<String, String> unique = Map.of("correlation_id", "query-last");
            for (Map.Entry<String, String> filter : Map.of(
                            "namespace",
                            NAMESPACE,
                            "operation",
                            "tools/call",
                            "target",
                            CAPTURE_TOOL,
                            "decision",
                            "block",
                            "reason_code",
                            "query_distinct_denial",
                            "from",
                            last.path("timestamp").asText(),
                            "to",
                            last.path("timestamp").asText())
                    .entrySet()) {
                var query = new java.util.HashMap<>(unique);
                query.put(filter.getKey(), filter.getValue());
                assertThat(audit.listEvents(query).path("events")).hasSize(1);
                query.put(
                        filter.getKey(),
                        switch (filter.getKey()) {
                            case "from" -> "2999-01-01T00:00:00Z";
                            case "to" -> "2000-01-01T00:00:00Z";
                            case "decision" -> "warn";
                            default -> "does-not-match";
                        });
                assertThat(audit.listEvents(query).path("events")).isEmpty();
            }
            assertThat(audit.listEvents(Map.of("actor", "forged-actor")).path("events"))
                    .isEmpty();
            JsonNode all = audit.listEvents(Map.of("limit", "1000"));
            int total = all.path("total").asInt();
            assertThat(total).isGreaterThanOrEqualTo(5);
            List<String> ids = new ArrayList<>();
            for (int offset = 0; offset < total; offset += 2) {
                JsonNode page = audit.listEvents(Map.of("offset", Integer.toString(offset), "limit", "2"));
                assertThat(page.path("total").asInt()).isEqualTo(total);
                assertThat(page.path("offset").asInt()).isEqualTo(offset);
                assertThat(page.path("limit").asInt()).isEqualTo(2);
                page.path("events")
                        .forEach(event -> ids.add(event.path("event_id").asText()));
            }
            assertThat(ids)
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(AuditRequests.eventIds(all.path("events")));
            assertThat(audit.listEvents(Map.of("offset", Integer.toString(total), "limit", "2"))
                            .path("events"))
                    .isEmpty();
        }
    }
}

/** Raw MCP requests retain deterministic correlation headers and JSON-RPC IDs for audit assertions. */
final class AuditRequests {
    private AuditRequests() {}

    static ObjectNode tool(String id, String name, String payload) {
        ObjectNode body = GovernanceTestBase.MAPPER
                .createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call");
        body.putObject("params").put("name", name).putObject("arguments").put("payload", payload);
        return body;
    }

    static JsonNode send(HttpClient http, String baseUrl, String namespace, JsonNode body, String correlation)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + namespace + "/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("x-request-id", correlation)
                .header("x-actor", "forged-actor")
                .header("x-workload", "forged-workload")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("MCP response: %s", response.body())
                .isEqualTo(200);
        String json = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            json = json.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();
        }
        JsonNode result = GovernanceTestBase.MAPPER.readTree(json);
        assertThat(result.path("id")).isEqualTo(body.path("id"));
        return result;
    }

    static JsonNode event(AuditClient audit, String correlation, String filter) {
        List<JsonNode> matching = new ArrayList<>();
        audit.listEvents(Map.of("correlation_id", correlation)).path("events").forEach(event -> {
            if (filter.equals(event.path("filter").asText())) matching.add(event);
        });
        assertThat(matching).as("Audit event for %s at %s", correlation, filter).hasSize(1);
        return matching.getFirst();
    }

    static void assertMetadata(JsonNode event, String correlation, String category, String operation) {
        assertThat(event.path("schema_version").asText()).isEqualTo("1.0");
        assertThat(event.path("event_id").asText()).isNotEmpty();
        assertThat(event.path("sequence").isIntegralNumber()).isTrue();
        assertThat(event.path("sequence").asLong()).isNotNegative();
        assertThat(Instant.parse(event.path("timestamp").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(event.path("category").asText()).isEqualTo(category);
        assertThat(event.path("operation").asText()).isEqualTo(operation);
        for (String field : List.of("correlation_id", "request_id", "stream_id")) {
            assertThat(event.path(field).asText()).isEqualTo(correlation);
        }
    }

    static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    static List<String> eventIds(JsonNode events) {
        List<String> ids = new ArrayList<>();
        events.forEach(event -> ids.add(event.path("event_id").asText()));
        return ids;
    }
}
