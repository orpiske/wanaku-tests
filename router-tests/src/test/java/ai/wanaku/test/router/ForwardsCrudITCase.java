package ai.wanaku.test.router;

import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class ForwardsCrudITCase extends RouterTestBase {

    private String nsId;

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        nsId = getOrCreateNamespace("fwd-test-ns");
        assumeThat(nsId)
                .as("Test namespace must be available for forwards tests")
                .isNotNull();
    }

    @DisplayName("Add a forward and verify it exists")
    @Test
    void shouldAddForward() throws Exception {
        forwardsClient.add("test-fwd", startTargetMcpServer(), nsId);

        assertThat(forwardsClient.exists("test-fwd")).isTrue();
    }

    @DisplayName("List forwards returns a valid response")
    @Test
    void shouldListForwards() {
        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).isNotNull();
    }

    @DisplayName("Remove a non-existent forward returns false")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentForward() {
        boolean removed = forwardsClient.remove("nonexistent");

        assertThat(removed).isFalse();
    }

    @DisplayName("Remove a forward and verify it no longer exists")
    @Test
    void shouldRemoveForward() throws Exception {
        forwardsClient.add("fwd-to-remove", startTargetMcpServer(), nsId);
        assertThat(forwardsClient.exists("fwd-to-remove")).isTrue();

        boolean removed = forwardsClient.remove("fwd-to-remove");

        assertThat(removed).isTrue();
        assertThat(forwardsClient.exists("fwd-to-remove")).isFalse();
    }

    @DisplayName("Refresh a forward without error")
    @Test
    void shouldRefreshForwards() throws Exception {
        forwardsClient.add("refresh-fwd", startTargetMcpServer(), nsId);
        assertThat(forwardsClient.exists("refresh-fwd")).isTrue();

        forwardsClient.refresh("refresh-fwd");

        assertThat(forwardsClient.exists("refresh-fwd")).isTrue();
    }
}
