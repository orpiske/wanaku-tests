package ai.wanaku.test.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.DataStoreClient;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.ManagementClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.PromptsClient;
import ai.wanaku.test.client.ServiceCatalogClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public abstract class RouterTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(RouterTestBase.class);

    protected DataStoreClient dataStoreClient;
    protected NamespaceClient namespaceClient;
    protected PromptsClient promptsClient;
    protected ForwardsClient forwardsClient;
    protected ManagementClient managementClient;
    protected ServiceCatalogClient serviceCatalogClient;

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
