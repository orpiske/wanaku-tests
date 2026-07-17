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
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(isTargetRouterAvailable())
                .as("Target router must be available for forwarding tests")
                .isTrue();
    }

    @DisplayName("Add a forward to a remote MCP server")
    @Test
    void shouldAddForward() {
        forwardsClient.add("test-forward", getTargetMcpUrl());

        assertThat(forwardsClient.exists("test-forward")).isTrue();
    }

    @DisplayName("List forwards after adding multiple entries")
    @Test
    void shouldListForwards() {
        forwardsClient.add("fwd-alpha", getTargetMcpUrl());
        forwardsClient.add("fwd-beta", "http://localhost:9999/mcp/");
        forwardsClient.add("fwd-gamma", "http://localhost:9998/mcp/");

        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).hasSizeGreaterThanOrEqualTo(3);
    }

    @DisplayName("Remove a forward and verify it no longer exists")
    @Test
    void shouldRemoveForward() {
        forwardsClient.add("fwd-to-remove", getTargetMcpUrl());
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

    @DisplayName("Refresh forwards without error")
    @Test
    void shouldRefreshForwards() {
        forwardsClient.add("refresh-test-fwd", getTargetMcpUrl());

        forwardsClient.refresh();

        assertThat(forwardsClient.exists("refresh-test-fwd")).isTrue();
    }
}
