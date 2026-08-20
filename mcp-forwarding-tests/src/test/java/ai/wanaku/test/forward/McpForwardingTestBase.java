package ai.wanaku.test.forward;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.NamespaceClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public abstract class McpForwardingTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(McpForwardingTestBase.class);

    protected ForwardsClient forwardsClient;
    protected NamespaceClient namespaceClient;
    protected String testNamespaceId;

    @BeforeEach
    void setupForwardingClients(TestInfo testInfo) {
        if (isServerRunning()) {
            String baseUrl = getServerBaseUrl();
            forwardsClient = new ForwardsClient(baseUrl, null);
            namespaceClient = new NamespaceClient(baseUrl, null);
            try {
                List<JsonNode> namespaces = namespaceClient.list();
                for (JsonNode ns : namespaces) {
                    if (ns.has("name") && "fwd-test-ns".equals(ns.get("name").asText())) {
                        testNamespaceId = ns.get("id").asText();
                        break;
                    }
                }
                if (testNamespaceId == null) {
                    testNamespaceId = namespaceClient.create("fwd-test-ns", "fwd-test-ns");
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
    }

    protected String getTargetMcpUrl() {
        return getServerMcpBaseUrl() + "/default/mcp";
    }

    @Override
    protected String getLogProfile() {
        return "mcp-forwarding";
    }
}
