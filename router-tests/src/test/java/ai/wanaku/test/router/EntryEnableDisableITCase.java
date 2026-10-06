package ai.wanaku.test.router;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.managers.MockMcpServerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Tests management enable/disable operations against entries discovered from a real MCP forward. */
@QuarkusTest
@Timeout(90)
class EntryEnableDisableITCase extends RouterTestBase {

    private static final String FORWARD_NAME = "entry-toggle-svc";
    private static final String RESOURCE_URI = "config://policies/safety-limits";
    private static final Map<String, Object> TOOL_ARGUMENTS =
            Map.of("serverId", "web-01", "service", "nginx", "x-request-id", "entry-toggle-test");
    private static final Map<String, String> PROMPT_ARGUMENTS =
            Map.of("serverId", "web-01", "incident", "high memory usage");

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMcpServerManager mockServer;
    private boolean forwardRegistered;
    private String resourceName;

    @BeforeEach
    void discoverForwardEntries() throws Exception {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(isMcpClientAvailable()).as("MCP client must be available").isTrue();
        Path jarPath = Path.of("../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar")
                .toAbsolutePath();
        assumeThat(jarPath.toFile().exists())
                .as("Mock MCP server JAR must be available at %s", jarPath)
                .isTrue();

        mockServer = new MockMcpServerManager(jarPath, config);
        mockServer.prepare();
        mockServer.setLogContext("mock-mcp-server", getClass().getSimpleName(), FORWARD_NAME);
        mockServer.start(FORWARD_NAME);
        forwardsClient.add(FORWARD_NAME, mockServer.getMcpUrl(), "default");
        forwardRegistered = true;

        assertThat(getEntry("tools", "restartService").path("enabled").asBoolean())
                .isTrue();
        assertThat(getEntry("prompts", "summarizeIncident").path("enabled").asBoolean())
                .isTrue();
        for (JsonNode resource : managementData("resources")) {
            if (RESOURCE_URI.equals(resource.path("location").asText())) {
                resourceName = resource.path("name").asText();
            }
        }
        assertThat(resourceName).isNotBlank();
    }

    @AfterEach
    void stopForward() {
        try {
            if (forwardRegistered) {
                forwardsClient.remove(FORWARD_NAME);
            }
        } finally {
            try {
                if (mockServer != null) {
                    mockServer.stop();
                }
            } finally {
                httpClient.close();
            }
        }
    }

    @Test
    @DisplayName("Disable and re-enable a discovered tool without deleting it")
    void shouldToggleToolVisibilityAndInvocation() throws Exception {
        verifyToggleLifecycle("tools", "restartService");
    }

    @Test
    @DisplayName("Disable and re-enable a discovered resource without deleting it")
    void shouldToggleResourceVisibilityAndRead() throws Exception {
        verifyToggleLifecycle("resources", resourceName);
    }

    @Test
    @DisplayName("Disable and re-enable a discovered prompt without deleting it")
    void shouldTogglePromptVisibilityAndInvocation() throws Exception {
        verifyToggleLifecycle("prompts", "summarizeIncident");
    }

    private void verifyToggleLifecycle(String kind, String name) throws Exception {
        JsonNode original = getEntry(kind, name);
        assertThat(original.path("enabled").asBoolean()).isTrue();
        assertMcpVisibility(kind, name, true);
        assertMcpOperationSucceeds(kind);

        assertUpdatedEntry(kind, name, false, original);
        assertUpdatedEntry(kind, name, false, original);
        assertManagementRetainsEntry(kind, name, false);
        assertMcpVisibility(kind, name, false);
        assertMcpOperationRejected(kind);

        assertUpdatedEntry(kind, name, true, original);
        assertUpdatedEntry(kind, name, true, original);
        assertManagementRetainsEntry(kind, name, true);
        assertMcpVisibility(kind, name, true);
        assertMcpOperationSucceeds(kind);
    }

    @Test
    @DisplayName("Refreshing a forward preserves disabled tools, resources and prompts")
    void shouldKeepEntriesDisabledAfterForwardRefresh() throws Exception {
        Map<String, String> entries =
                Map.of("tools", "restartService", "resources", resourceName, "prompts", "summarizeIncident");
        for (var entry : entries.entrySet()) {
            assertUpdatedEntry(entry.getKey(), entry.getValue(), false, getEntry(entry.getKey(), entry.getValue()));
        }

        forwardsClient.refresh(FORWARD_NAME);

        for (var entry : entries.entrySet()) {
            assertManagementRetainsEntry(entry.getKey(), entry.getValue(), false);
            assertMcpVisibility(entry.getKey(), entry.getValue(), false);
            assertMcpOperationRejected(entry.getKey());
            assertUpdatedEntry(entry.getKey(), entry.getValue(), true, getEntry(entry.getKey(), entry.getValue()));
            assertMcpOperationSucceeds(entry.getKey());
        }
    }

    @Test
    @DisplayName("Enable and disable return 404 for missing tools, resources and prompts")
    void shouldRejectTogglingMissingEntries() throws Exception {
        for (String kind : new String[] {"tools", "resources", "prompts"}) {
            for (String operation : new String[] {"enable", "disable"}) {
                HttpResponse<String> response = toggle(kind, "missing-entry-enable-disable-test", operation);
                assertThat(response.statusCode())
                        .as("%s missing %s: %s", operation, kind, response.body())
                        .isEqualTo(404);
            }
        }
    }

    private void assertUpdatedEntry(String kind, String name, boolean enabled, JsonNode original) throws Exception {
        HttpResponse<String> response = toggle(kind, name, enabled ? "enable" : "disable");
        assertThat(response.statusCode())
                .as("Toggle %s/%s: %s", kind, name, response.body())
                .isEqualTo(200);
        JsonNode updated = objectMapper.readTree(response.body()).path("data");
        assertThat(updated.path("name").asText()).isEqualTo(name);
        assertThat(updated.path("enabled").isBoolean()).isTrue();
        assertThat(updated.path("enabled").asBoolean()).isEqualTo(enabled);
        assertThat(updated.path("id")).isEqualTo(original.path("id"));
        assertThat(original.path("forwardId").isTextual()).isTrue();
        assertThat(updated.path("forwardId")).isEqualTo(original.path("forwardId"));
    }

    private void assertManagementRetainsEntry(String kind, String name, boolean enabled) throws Exception {
        JsonNode entry = getEntry(kind, name);
        assertThat(entry.path("enabled").isBoolean()).isTrue();
        assertThat(entry.path("enabled").asBoolean()).isEqualTo(enabled);
        assertThat(managementData(kind))
                .anyMatch(item -> name.equals(item.path("name").asText())
                        && item.path("enabled").asBoolean() == enabled);
    }

    private void assertMcpVisibility(String kind, String name, boolean visible) {
        switch (kind) {
            case "tools" ->
                mcpClient
                        .when()
                        .toolsList(page -> {
                            assertThat(page.tools().stream().anyMatch(tool -> name.equals(tool.name())))
                                    .isEqualTo(visible);
                            assertThat(page.tools()).anyMatch(tool -> "scaleDeployment".equals(tool.name()));
                        })
                        .thenAssertResults();
            case "resources" ->
                mcpClient
                        .when()
                        .resourcesList(page -> assertThat(page.resources().stream()
                                        .anyMatch(resource -> RESOURCE_URI.equals(resource.uri())))
                                .isEqualTo(visible))
                        .thenAssertResults();
            case "prompts" ->
                mcpClient
                        .when()
                        .promptsList(page -> {
                            assertThat(page.prompts().stream().anyMatch(prompt -> name.equals(prompt.name())))
                                    .isEqualTo(visible);
                            assertThat(page.prompts()).anyMatch(prompt -> "draftEscalation".equals(prompt.name()));
                        })
                        .thenAssertResults();
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private void assertMcpOperationSucceeds(String kind) {
        switch (kind) {
            case "tools" ->
                mcpClient
                        .when()
                        .toolsCall("restartService", TOOL_ARGUMENTS, response -> {
                            assertThat(response.isError()).isFalse();
                            assertThat(response.content().get(0).asText().text())
                                    .contains("nginx", "web-01", "SUCCESS");
                        })
                        .thenAssertResults();
            case "resources" ->
                mcpClient
                        .when()
                        .resourcesRead(RESOURCE_URI)
                        .withAssert(response -> assertThat(
                                        response.contents().get(0).asText().text())
                                .contains("blocked_services"))
                        .send()
                        .thenAssertResults();
            case "prompts" ->
                mcpClient
                        .when()
                        .promptsGet("summarizeIncident", PROMPT_ARGUMENTS, response -> assertThat(response.messages()
                                        .get(0)
                                        .content()
                                        .asText()
                                        .text())
                                .contains("web-01", "high memory usage"))
                        .thenAssertResults();
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private void assertMcpOperationRejected(String kind) {
        switch (kind) {
            case "tools" ->
                mcpClient
                        .when()
                        .toolsCall("restartService")
                        .withArguments(TOOL_ARGUMENTS)
                        .withErrorAssert(error -> assertThat(error.code()).isEqualTo(-32602))
                        .send()
                        .thenAssertResults();
            case "resources" ->
                mcpClient
                        .when()
                        .resourcesRead(RESOURCE_URI)
                        .withErrorAssert(error -> assertThat(error.code()).isEqualTo(-32602))
                        .send()
                        .thenAssertResults();
            case "prompts" ->
                mcpClient
                        .when()
                        .promptsGet("summarizeIncident")
                        .withArguments(PROMPT_ARGUMENTS)
                        .withErrorAssert(error -> assertThat(error.code()).isEqualTo(-32602))
                        .send()
                        .thenAssertResults();
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private JsonNode getEntry(String kind, String name) throws Exception {
        return managementData(kind + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8));
    }

    private JsonNode managementData(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(getServerBaseUrl() + "/api/v1/" + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("GET %s: %s", path, response.body())
                .isEqualTo(200);
        return objectMapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> toggle(String kind, String name, String operation) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(getServerBaseUrl() + "/api/v1/" + kind + "/"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/" + operation))
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
