package ai.wanaku.test.a2a;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(180)
class A2aProxyITCase extends A2aTestBase {
    @Test
    void shouldPreserveUpstreamTaskNotFoundErrors() throws Exception {
        registerAgent("default", startAgent("unknown-task-agent"));
        for (String method : new String[] {"tasks/get", "tasks/cancel"}) {
            JsonNode missing = rpc("default", method, Map.of("id", "missing-task"));
            assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32001);
            assertThat(missing.path("error").path("message").asText()).isEqualTo("Fixture task not found");
        }
    }

    @Test
    void shouldRejectInvalidAgentAddressAndDuplicateRegistration() throws Exception {
        String upstream = startAgent("registration-agent");
        HttpResponse<String> invalid =
                request("POST", getServerBaseUrl() + "/api/v1/agents", entry("default", "file:///tmp/agent"));
        assertThat(invalid.statusCode()).as(invalid.body()).isEqualTo(400);
        assertThat(request("GET", getServerBaseUrl() + agentPath("default"), null)
                        .statusCode())
                .isEqualTo(404);
        registerAgent("default", upstream);
        HttpResponse<String> duplicate =
                request("POST", getServerBaseUrl() + "/api/v1/agents", entry("default", upstream));
        assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
    }

    @Test
    void shouldRejectInvalidJsonRpcEnvelopeAndVersion() throws Exception {
        registerAgent("default", startAgent("envelope-agent"));
        HttpResponse<String> invalid = request(
                "POST",
                proxyUrl("default"),
                Map.of(
                        "jsonrpc",
                        "1.0",
                        "id",
                        "invalid-envelope",
                        "method",
                        "message/send",
                        "params",
                        message("valid-message")));
        assertThat(json(invalid).path("error").path("code").asInt()).isEqualTo(-32600);
        HttpRequest request = HttpRequest.newBuilder(URI.create(proxyUrl("default")))
                .timeout(config.getDefaultTimeout())
                .header("Content-Type", "application/json")
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        "invalid-version",
                        "method",
                        "message/send",
                        "params",
                        message("valid-message")))))
                .build();
        HttpResponse<String> version = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(json(version).path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void shouldRewriteAgentCardForManagedProxy() throws Exception {
        JsonNode agent = registerAgent("default", startAgent("discovery-agent"));
        assertThat(agent.path("proxyUrl").asText()).isEqualTo(proxyUrl("default"));
        HttpResponse<String> response = request("GET", proxyUrl("default") + "/.well-known/agent-card.json", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode card = json(response);
        assertThat(card.path("name").asText()).isEqualTo("discovery-agent");
        assertThat(card.path("url").asText()).isEqualTo(proxyUrl("default"));
        assertThat(card.path("preferredTransport").asText()).isEqualTo("JSONRPC");
        assertThat(card.path("capabilities").path("streaming").asBoolean()).isFalse();
        assertThat(card.path("capabilities").path("pushNotifications").asBoolean())
                .isFalse();
        assertThat(card.has("additionalInterfaces")).isFalse();
        assertThat(card.has("signatures")).isFalse();
        assertThat(card.path("supportsAuthenticatedExtendedCard").asBoolean()).isFalse();
    }

    @Test
    void shouldForwardMessageAndFollowUpTaskOperations() throws Exception {
        registerAgent("default", startAgent("task-agent"));
        JsonNode send = rpc("default", "message/send", message("allowed-message"));
        assertThat(send.has("error")).as(send.toString()).isFalse();
        JsonNode task = send.path("result");
        assertThat(task.path("id").asText()).isEqualTo("fixture-task");
        assertThat(task.path("artifacts")
                        .get(0)
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("task-agent--a2a--A2A integration test");
        assertThat(rpc("default", "tasks/get", Map.of("id", task.path("id").asText()))
                        .path("result"))
                .isEqualTo(task);
        // The deterministic upstream finishes synchronously; terminal tasks reject cancellation.
        JsonNode cancel =
                rpc("default", "tasks/cancel", Map.of("id", task.path("id").asText()));
        assertThat(cancel.path("error").path("code").asInt()).isEqualTo(-32002);
        assertThat(cancel.path("error").path("message").asText())
                .isEqualTo("Completed fixture task cannot be canceled");
    }

    @Test
    void shouldApplyPolicyBeforeSendingMessage() throws Exception {
        registerAgent("default", startAgent("policy-agent"));
        JsonNode denied = rpc("default", "message/send", message("denied-message"));
        assertThat(denied.has("error")).as(denied.toString()).isTrue();
        assertThat(denied.has("result")).isFalse();
        assertThat(denied.at("/error/code").asInt()).isEqualTo(-32003);
        assertThat(denied.at("/error/data/reason_code").asText()).isEqualTo("action_policy_denied");
        assertThat(rpc("default", "message/send", message("allowed-message")).has("result"))
                .isTrue();
    }

    @Test
    void shouldRejectUnsupportedStreamingAndPushOperations() throws Exception {
        registerAgent("default", startAgent("unsupported-agent"));
        for (String method : new String[] {
            "message/stream",
            "tasks/resubscribe",
            "tasks/pushNotificationConfig/set",
            "tasks/pushNotificationConfig/get",
            "UnknownMethod"
        }) {
            JsonNode rejected = rpc("default", method, Map.of("id", "fixture-task"));
            assertThat(rejected.path("error").path("code").asInt())
                    .as("Reject %s: %s", method, rejected)
                    .isEqualTo(-32601);
            assertThat(rejected.has("result")).isFalse();
            assertThat(rejected.at("/error/message").asText())
                    .isEqualTo("A2A method is not supported; streaming and push are disabled.");
        }
    }

    @Test
    void shouldRejectStreamingAndPushOptionsInNormalMessage() throws Exception {
        registerAgent("default", startAgent("options-agent"));
        for (String option : new String[] {"stream", "streaming", "pushNotificationConfig"}) {
            Object value = option.equals("pushNotificationConfig") ? Map.of("url", "https://example.invalid/") : true;
            Map<String, Object> params = Map.of(
                    "message", message("configured-message").get("message"), "configuration", Map.of(option, value));
            JsonNode rejected = rpc("default", "message/send", params);
            assertThat(rejected.path("error").path("code").asInt())
                    .as("Reject %s: %s", option, rejected)
                    .isEqualTo(-32602);
        }
    }

    @Test
    void shouldRejectMissingMessageAndTaskFields() throws Exception {
        registerAgent("default", startAgent("validation-agent"));
        assertThat(rpc("default", "message/send", Map.of())
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
        assertThat(rpc("default", "tasks/get", Map.of("id", ""))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
        assertThat(rpc("default", "tasks/cancel", Map.of())
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
    }

    @Test
    void shouldUpdateLiveRoutingWithoutRestart() throws Exception {
        String first = startAgent("first-agent");
        String second = startAgent("second-agent");
        registerAgent("default", first);
        assertThat(rpc("default", "message/send", message("before-edit"))
                        .path("result")
                        .toString())
                .contains("first-agent--a2a--A2A integration test");
        HttpResponse<String> update =
                request("PUT", getServerBaseUrl() + agentPath("default"), entry("default", second));
        assertThat(update.statusCode()).as(update.body()).isEqualTo(200);
        assertThat(json(update).path("data").path("address").asText()).isEqualTo(second);
        HttpResponse<String> get = request("GET", getServerBaseUrl() + agentPath("default"), null);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(json(get).path("data").path("address").asText()).isEqualTo(second);
        assertThat(rpc("default", "message/send", message("after-edit"))
                        .path("result")
                        .toString())
                .contains("second-agent--a2a--A2A integration test");
    }

    @Test
    void shouldIsolateSameNameAndTaskIdAcrossNamespacesAndDeletion() throws Exception {
        registerAgent("default", startAgent("default-agent"));
        String namespace = "other-" + agentName;
        registerAgent(namespace, startAgent("other-agent"));
        JsonNode defaultTask =
                rpc("default", "message/send", message("default-message")).path("result");
        JsonNode otherTask =
                rpc(namespace, "message/send", message("other-message")).path("result");
        assertThat(defaultTask.path("id").asText()).isEqualTo("fixture-task");
        assertThat(defaultTask.path("id")).isEqualTo(otherTask.path("id"));
        assertThat(defaultTask.at("/artifacts/0/parts/0/text").asText())
                .isEqualTo("default-agent--a2a--A2A integration test");
        assertThat(otherTask.at("/artifacts/0/parts/0/text").asText())
                .isEqualTo("other-agent--a2a--A2A integration test");
        assertThat(rpc("default", "tasks/get", Map.of("id", "fixture-task")).path("result"))
                .isEqualTo(defaultTask);
        assertThat(rpc(namespace, "tasks/get", Map.of("id", "fixture-task")).path("result"))
                .isEqualTo(otherTask);
        HttpResponse<String> list = request("GET", getServerBaseUrl() + "/api/v1/agents?namespace=" + namespace, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).path("data").size()).isEqualTo(1);
        assertThat(json(list).path("data").get(0).path("namespace").asText()).isEqualTo(namespace);
        HttpResponse<String> delete = request("DELETE", getServerBaseUrl() + agentPath("default"), null);
        assertThat(delete.statusCode()).as(delete.body()).isIn(200, 204);
        assertThat(request("GET", getServerBaseUrl() + agentPath("default"), null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(request("GET", proxyUrl("default") + "/.well-known/agent-card.json", null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(request(
                                "POST",
                                proxyUrl("default"),
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        "removed",
                                        "method",
                                        "tasks/get",
                                        "params",
                                        Map.of("id", "fixture-task")))
                        .statusCode())
                .isEqualTo(404);
        assertThat(rpc(namespace, "tasks/get", Map.of("id", "fixture-task")).path("result"))
                .isEqualTo(otherTask);
    }
}
