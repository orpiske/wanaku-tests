package ai.wanaku.test.router;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.managers.MockMcpServerManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Verifies that prompts exposed by a forwarded MCP server are discovered
 * and accessible through the router's MCP endpoint.
 *
 * <p>The mock MCP server exposes two prompts: {@code summarizeIncident}
 * and {@code draftEscalation}. This test registers the mock server as a
 * forward and verifies the prompts appear via {@code prompts/list} and
 * can be invoked via {@code prompts/get}.
 */
@QuarkusTest
class PromptsCrudITCase extends RouterTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(PromptsCrudITCase.class);
    private static final String MOCK_SERVER_JAR = "../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar";
    private static final String FORWARD_NAME = "mock-prompt-svc";

    private MockMcpServerManager mockServer;
    private SessionIdProxy proxy;
    private McpTestClient promptMcpClient;

    @BeforeEach
    void setupMockServer() throws Exception {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();

        Path jarPath = Path.of(MOCK_SERVER_JAR).toAbsolutePath();
        assumeThat(jarPath.toFile().exists())
                .as("Mock MCP server JAR must be available at " + jarPath)
                .isTrue();

        mockServer = new MockMcpServerManager(jarPath, config);
        mockServer.prepare();
        mockServer.setLogContext("mock-mcp-server", getClass().getSimpleName(), FORWARD_NAME);
        mockServer.start(FORWARD_NAME);

        new ForwardsClient(getServerBaseUrl(), null).add(FORWARD_NAME, mockServer.getMcpUrl(), "default");
        LOG.info("Registered forward '{}' -> '{}'", FORWARD_NAME, mockServer.getMcpUrl());

        waitForPromptDiscovery();
    }

    private void waitForPromptDiscovery() throws Exception {
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    SessionIdProxy tempProxy = new SessionIdProxy(getServerMcpBaseUrl() + "/default");
                    try {
                        tempProxy.start();
                        McpTestClient tempClient = new McpTestClient(tempProxy.getBaseUrl(), null);
                        tempClient.connect();
                        try {
                            tempClient
                                    .when()
                                    .promptsList(
                                            page -> assertThat(page.prompts()).isNotEmpty())
                                    .thenAssertResults();
                        } finally {
                            tempClient.disconnect();
                        }
                    } finally {
                        tempProxy.close();
                    }
                });

        proxy = new SessionIdProxy(getServerMcpBaseUrl() + "/default");
        proxy.start();
        promptMcpClient = new McpTestClient(proxy.getBaseUrl(), null);
        promptMcpClient.connect();
    }

    @AfterEach
    void teardownMockServer() {
        if (promptMcpClient != null) {
            try {
                promptMcpClient.disconnect();
            } catch (Exception e) {
                LOG.debug("MCP disconnect: {}", e.getMessage());
            }
            promptMcpClient = null;
        }
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception e) {
                LOG.debug("Proxy close: {}", e.getMessage());
            }
            proxy = null;
        }
        try {
            new ForwardsClient(getServerBaseUrl(), null).remove(FORWARD_NAME);
        } catch (Exception e) {
            LOG.warn("Failed to remove forward: {}", e.getMessage());
        }
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    @DisplayName("Prompts from forwarded MCP server appear in prompts/list")
    @Test
    void shouldDiscoverPromptsFromForward() {
        promptMcpClient
                .when()
                .promptsList(page -> {
                    LOG.info("Discovered prompts: {}", page.prompts());
                    assertThat(page.prompts())
                            .as("Should discover prompts from the mock MCP server")
                            .isNotEmpty();
                    assertThat(page.prompts()).anyMatch(p -> "summarizeIncident".equals(p.name()));
                    assertThat(page.prompts()).anyMatch(p -> "draftEscalation".equals(p.name()));
                })
                .thenAssertResults();
    }

    @DisplayName("Invoke discovered prompt via prompts/get and verify response")
    @Test
    void shouldInvokeDiscoveredPrompt() {
        promptMcpClient
                .when()
                .promptsGet(
                        "summarizeIncident",
                        java.util.Map.of("serverId", "web-01", "incident", "high memory usage"),
                        response -> {
                            LOG.info("Prompt response: {}", response.messages());
                            assertThat(response.messages()).isNotEmpty();
                            assertThat(response.messages().get(0).content()).isNotNull();
                            assertThat(response.messages().get(0).content().asText())
                                    .isNotNull();
                            String text = response.messages()
                                    .get(0)
                                    .content()
                                    .asText()
                                    .text();
                            assertThat(text).contains("web-01");
                            assertThat(text).contains("high memory usage");
                        })
                .thenAssertResults();
    }
}
