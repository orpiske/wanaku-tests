package ai.wanaku.test.router;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.DataStoreClient;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.ManagementClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.PromptsClient;
import ai.wanaku.test.client.ServiceCatalogClient;
import ai.wanaku.test.managers.MockMcpServerManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import static org.assertj.core.api.Assumptions.assumeThat;

public abstract class RouterTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(RouterTestBase.class);
    private static final String MOCK_SERVER_JAR = "../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar";

    protected DataStoreClient dataStoreClient;
    protected NamespaceClient namespaceClient;
    protected PromptsClient promptsClient;
    protected ForwardsClient forwardsClient;
    protected ManagementClient managementClient;
    protected ServiceCatalogClient serviceCatalogClient;
    private MockMcpServerManager targetMcpServer;

    @BeforeEach
    void setupRouterClients(TestInfo testInfo) {
        if (isServerRunning()) {
            String baseUrl = getServerBaseUrl();
            dataStoreClient = new DataStoreClient(baseUrl, null);
            namespaceClient = new NamespaceClient(baseUrl, null);
            promptsClient = new PromptsClient(baseUrl, null);
            forwardsClient = new ForwardsClient(baseUrl, null);
            managementClient = new ManagementClient(baseUrl, null);
            serviceCatalogClient = new ServiceCatalogClient(baseUrl, null);
        }
    }

    @AfterEach
    void cleanupRouterState() {
        if (promptsClient != null) {
            try {
                promptsClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear prompts: {}", e.getMessage());
            }
        }
        if (forwardsClient != null) {
            try {
                forwardsClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear forwards: {}", e.getMessage());
            }
        }
        if (dataStoreClient != null) {
            try {
                dataStoreClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear data store: {}", e.getMessage());
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

    protected String getOrCreateNamespace(String name) {
        if (namespaceClient == null) {
            return null;
        }
        try {
            if (namespaceClient.exists(name)) {
                return name;
            }
            return namespaceClient.create(name);
        } catch (Exception e) {
            LOG.warn("Failed to get/create namespace '{}': {}", name, e.getMessage());
            return null;
        }
    }

    @Override
    protected String getLogProfile() {
        return "router";
    }
}
