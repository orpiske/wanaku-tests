package ai.wanaku.test.resources;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.managers.MockMcpServerManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public abstract class ResourceTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceTestBase.class);
    private static final String MOCK_SERVER_JAR = "../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar";
    protected static final String FORWARD_NAME = "mock-resource-svc";

    protected MockMcpServerManager mockServer;

    @BeforeEach
    void startMockServer(TestInfo testInfo) throws Exception {
        Path jarPath = Path.of(MOCK_SERVER_JAR).toAbsolutePath();
        if (!jarPath.toFile().exists()) {
            LOG.warn("Mock MCP server JAR not found at {}", jarPath);
            return;
        }

        mockServer = new MockMcpServerManager(jarPath, config);
        mockServer.prepare();
        mockServer.setLogContext("mock-mcp-server", getClass().getSimpleName(), FORWARD_NAME);
        mockServer.start(FORWARD_NAME);

        registerAndWaitForResources();
    }

    @AfterEach
    void stopMockServer() {
        if (routerClient != null) {
            try {
                new ForwardsClient(getServerBaseUrl(), null).remove(FORWARD_NAME);
            } catch (Exception e) {
                LOG.warn("Failed to remove forward: {}", e.getMessage());
            }
        }
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    private void registerAndWaitForResources() {
        new ForwardsClient(getServerBaseUrl(), null).add(FORWARD_NAME, mockServer.getMcpUrl(), "default");
        LOG.info("Registered forward '{}' -> '{}'", FORWARD_NAME, mockServer.getMcpUrl());

        reconnectMcpClient();
    }

    private void reconnectMcpClient() {
        if (mcpClient != null) {
            try {
                mcpClient.disconnect();
            } catch (Exception e) {
                LOG.debug("MCP disconnect: {}", e.getMessage());
            }
            mcpClient = null;
        }
        try {
            SessionIdProxy proxy = new SessionIdProxy(getServerMcpBaseUrl() + "/default");
            proxy.start();
            mcpClient = new McpTestClient(proxy.getBaseUrl(), null);
            mcpClient.connect();
        } catch (Exception e) {
            LOG.warn("Failed to reconnect MCP client: {}", e.getMessage());
        }
    }

    protected boolean isMockServerAvailable() {
        return mockServer != null && mockServer.isRunning();
    }

    @Override
    protected String getLogProfile() {
        return "mock-mcp-server";
    }
}
