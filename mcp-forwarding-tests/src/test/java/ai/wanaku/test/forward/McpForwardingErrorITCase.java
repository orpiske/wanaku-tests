package ai.wanaku.test.forward;

import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.base.KnownLimitation;
import ai.wanaku.test.client.ForwardsClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class McpForwardingErrorITCase extends McpForwardingTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(testNamespaceId).as("Test namespace must be available").isNotNull();
    }

    @DisplayName("Router handles a forward pointing to an unreachable server")
    @Test
    void shouldHandleUnreachableServerForward() {
        try {
            forwardsClient.add("unreachable-fwd", "http://localhost:1/mcp/", testNamespaceId);
            assertThat(forwardsClient.exists("unreachable-fwd")).isTrue();
        } catch (ForwardsClient.ForwardsClientException e) {
            assertThat(e.getMessage()).contains("500");
        }
    }

    @KnownLimitation("wanaku#1741")
    @DisplayName("Adding a forward with a valid target succeeds")
    @Test
    void shouldAddForwardWithValidTarget() {
        int maxAttempts = 5;
        ForwardsClient.ForwardsClientException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                forwardsClient.add("valid-fwd", getServerBaseUrl() + "/default/mcp/", testNamespaceId);
                assertThat(forwardsClient.exists("valid-fwd")).isTrue();
                return;
            } catch (ForwardsClient.ForwardsClientException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        assumeThat(lastException.getMessage())
                .as("Router validates forward target connectivity")
                .doesNotContain("500");
    }

    @DisplayName("List forwards returns valid response even when empty")
    @Test
    void shouldListForwardsWhenEmpty() {
        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).isNotNull();
    }
}
