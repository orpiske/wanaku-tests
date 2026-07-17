package ai.wanaku.test.forward;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class McpForwardingErrorITCase extends McpForwardingTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
    }

    @DisplayName("Add a forward pointing to an unreachable server without error")
    @Test
    void shouldAddForwardToUnreachableServer() {
        assertThatCode(() -> forwardsClient.add("unreachable-fwd", "http://localhost:1/mcp/"))
                .doesNotThrowAnyException();

        assertThat(forwardsClient.exists("unreachable-fwd")).isTrue();
    }

    @DisplayName("Refresh forwards with an unreachable target does not throw")
    @Test
    void shouldRefreshWithUnreachableTarget() {
        forwardsClient.add("bad-target-fwd", "http://localhost:1/mcp/");

        assertThatCode(() -> forwardsClient.refresh()).doesNotThrowAnyException();
    }

    @DisplayName("Clear all forwards including ones with bad targets")
    @Test
    void shouldClearAllForwardsIncludingBadTargets() {
        forwardsClient.add("good-fwd", "http://localhost:8080/mcp/");
        forwardsClient.add("bad-fwd", "http://localhost:1/mcp/");

        forwardsClient.clearAll();

        assertThat(forwardsClient.list()).isEmpty();
    }
}
