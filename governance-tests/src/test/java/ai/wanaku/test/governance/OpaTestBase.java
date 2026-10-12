package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import ai.wanaku.test.config.TestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Shared protocol setup; each scenario owns and closes its OPA endpoint explicitly. */
abstract class OpaTestBase extends GovernanceTestBase {
    protected static final String NAMESPACE = "opa-tests";
    protected static final String TOOL = "capture_typed_tool";
    protected static final String DECISION_PATH = "wanaku/tool_call/decision";
    protected static final String TOKEN = "opa-fixture-secret";
    protected String opaUrl;
    protected String opaToken = TOKEN;
    protected int opaTimeoutMs = 2000;

    @Override
    protected void checkAdditionalRequirements(TestConfiguration config) {
        if (config.getOpaWasmPath() == null) {
            LOG.warn("Skipping OPA scenario: set -Dwanaku.test.opa.wasm to the OPA allow action");
        }
        if (config.getOpaWasmPath() != null) {
            assertThat(Files.isRegularFile(config.getOpaWasmPath()))
                    .as("Configured OPA WASM must exist")
                    .isTrue();
        }
        assumeThat(config.getOpaWasmPath())
                .as("Set -Dwanaku.test.opa.wasm to the OPA allow action")
                .isNotNull();
    }

    @Override
    protected void configureGovernedServer(TestConfiguration.Builder builder) {
        ObjectNode connection = MAPPER.createObjectNode()
                .put("name", "opa-fixture")
                .put("url", opaUrl)
                .put("token", opaToken)
                .put("timeout_ms", opaTimeoutMs);
        builder.opaConnectionsJson(MAPPER.createArrayNode().add(connection).toString());
    }

    protected void startOpaScenario(String mode, String level, String onFailure, String policy) throws Exception {
        ObjectNode governance = MAPPER.createObjectNode();
        governance
                .putObject("default")
                .put("mode", mode)
                .put("audit_level", level)
                .put("no_match", "allow")
                .put("on_failure", onFailure);
        startGovernedServer(policy, governance.toString());
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
    }

    protected ObjectNode evaluator(String path, String connection, String method) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode evaluator = root.putArray("evaluators").addObject().put("name", "opa-evaluator");
        evaluator.putObject("trigger").put("method", method).put("namespace", NAMESPACE);
        evaluator
                .putObject("engine")
                .put("type", "opa")
                .put("connection", connection)
                .put("decision_path", path);
        evaluator.putObject("processor").put("path", baseConfig.getOpaWasmPath().toString());
        return root;
    }

    protected void registerOpa(HttpClient http, String path) throws Exception {
        HttpResponse<String> response =
                manage(http, "PUT", "/api/v1/evaluators", evaluator(path, "opa-fixture", "tools/call"));
        assertThat(response.statusCode())
                .as("Register OPA evaluator: %s", response.body())
                .isEqualTo(200);
    }

    protected HttpResponse<String> manage(HttpClient http, String method, String path, JsonNode body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected ObjectNode tool(String id, JsonNode arguments) {
        ObjectNode request =
                MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call");
        ObjectNode params = request.putObject("params").put("name", TOOL);
        if (arguments != null) params.set("arguments", arguments);
        return request;
    }

    protected JsonNode call(HttpClient http, String correlation, JsonNode arguments) throws Exception {
        return AuditRequests.send(http, server.getMcpBaseUrl(), NAMESPACE, tool(correlation, arguments), correlation);
    }

    protected void stopWanaku() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
