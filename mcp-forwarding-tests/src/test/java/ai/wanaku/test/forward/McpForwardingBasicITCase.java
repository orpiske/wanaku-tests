package ai.wanaku.test.forward;

import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class McpForwardingBasicITCase extends McpForwardingTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(testNamespaceName).as("Test namespace must be available").isNotNull();
    }

    @DisplayName("Add a forward to the target MCP server")
    @Test
    void shouldAddForward() throws Exception {
        forwardsClient.add("test-forward", startTargetMcpServer(), testNamespaceName);

        assertThat(forwardsClient.exists("test-forward")).isTrue();
    }

    @DisplayName("List forwards returns a valid response")
    @Test
    void shouldListForwards() {
        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).isNotNull();
    }

    @DisplayName("Remove a forward and verify it no longer exists")
    @Test
    void shouldRemoveForward() throws Exception {
        forwardsClient.add("fwd-to-remove", startTargetMcpServer(), testNamespaceName);
        assertThat(forwardsClient.exists("fwd-to-remove")).isTrue();

        boolean removed = forwardsClient.remove("fwd-to-remove");

        assertThat(removed).isTrue();
        assertThat(forwardsClient.exists("fwd-to-remove")).isFalse();
    }

    @DisplayName("Return false when removing a non-existent forward")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentForward() {
        boolean removed = forwardsClient.remove("nonexistent-forward");

        assertThat(removed).isFalse();
    }

    @DisplayName("Refresh a forward without error")
    @Test
    void shouldRefreshForwards() throws Exception {
        forwardsClient.add("refresh-test-fwd", startTargetMcpServer(), testNamespaceName);
        assertThat(forwardsClient.exists("refresh-test-fwd")).isTrue();

        forwardsClient.refresh("refresh-test-fwd");

        assertThat(forwardsClient.exists("refresh-test-fwd")).isTrue();
    }
}
