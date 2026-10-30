package ai.wanaku.test.forward;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.managers.MockMcpServerManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import static org.assertj.core.api.Assumptions.assumeThat;

public abstract class McpForwardingTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(McpForwardingTestBase.class);
    private static final String MOCK_SERVER_JAR = "../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar";

    protected ForwardsClient forwardsClient;
    protected NamespaceClient namespaceClient;
    protected String testNamespaceName;
    private MockMcpServerManager targetMcpServer;

    @BeforeEach
    void setupForwardingClients(TestInfo testInfo) {
        if (isServerRunning()) {
            String baseUrl = getServerBaseUrl();
            forwardsClient = new ForwardsClient(baseUrl, null);
            namespaceClient = new NamespaceClient(baseUrl, null);
            try {
                if (namespaceClient.exists("fwd-test-ns")) {
                    testNamespaceName = "fwd-test-ns";
                } else {
                    testNamespaceName = namespaceClient.create("fwd-test-ns");
                }
            } catch (Exception e) {
                LOG.warn("Failed to setup test namespace: {}", e.getMessage());
            }
        }
    }

    @AfterEach
    void cleanupForwards() {
        if (forwardsClient != null) {
            try {
                forwardsClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear forwards: {}", e.getMessage());
            }
        }
        if (targetMcpServer != null) {
            targetMcpServer.stop();
            targetMcpServer = null;
        }
    }

    /**
     * Starts the test MCP server fixture and returns its MCP URL, for use as a forward target.
     * Wanaku rejects forwards that point back to itself, so forwards need an external MCP server.
     */
    protected String startTargetMcpServer() throws Exception {
        Path jarPath = Path.of(MOCK_SERVER_JAR).toAbsolutePath();
        assumeThat(jarPath.toFile().exists())
                .as("Mock MCP server JAR must be available at " + jarPath)
                .isTrue();

        targetMcpServer = new MockMcpServerManager(jarPath, config);
        targetMcpServer.prepare();
        targetMcpServer.setLogContext("mock-mcp-server", getClass().getSimpleName(), "forward-target");
        targetMcpServer.start("forward-target");
        return targetMcpServer.getMcpUrl();
    }

    @Override
    protected String getLogProfile() {
        return "mcp-forwarding";
    }
}
