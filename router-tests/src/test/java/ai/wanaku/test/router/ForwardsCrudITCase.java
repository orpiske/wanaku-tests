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

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
    }

    @DisplayName("Add a forward and verify it exists")
    @Test
    void shouldAddForward() {
        // Given
        String name = "test-fwd";
        String target = "http://example.com/mcp";

        // When
        forwardsClient.add(name, target, "default");

        // Then
        assertThat(forwardsClient.exists(name)).isTrue();
    }

    @DisplayName("Add 3 forwards and verify all are present in the list")
    @Test
    void shouldListForwards() {
        // Given
        forwardsClient.add("fwd-alpha", "http://alpha.example.com/mcp", "default");
        forwardsClient.add("fwd-beta", "http://beta.example.com/mcp", "default");
        forwardsClient.add("fwd-gamma", "http://gamma.example.com/mcp", "default");

        // When
        List<JsonNode> forwards = forwardsClient.list();

        // Then
        assertThat(forwards).hasSizeGreaterThanOrEqualTo(3);
        assertThat(forwards).extracting(f -> f.get("name").asText()).contains("fwd-alpha", "fwd-beta", "fwd-gamma");
    }

    @DisplayName("Add a forward, remove it, and verify it no longer exists")
    @Test
    void shouldRemoveForward() {
        // Given
        String name = "fwd-to-remove";
        forwardsClient.add(name, "http://example.com/mcp", "default");
        assertThat(forwardsClient.exists(name)).isTrue();

        // When
        boolean removed = forwardsClient.remove(name);

        // Then
        assertThat(removed).isTrue();
        assertThat(forwardsClient.exists(name)).isFalse();
    }

    @DisplayName("Return false when removing a forward that does not exist")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentForward() {
        // When
        boolean removed = forwardsClient.remove("nonexistent");

        // Then
        assertThat(removed).isFalse();
    }

    @DisplayName("Add a forward, call refresh, and verify no error occurs")
    @Test
    void shouldRefreshForwards() {
        // Given
        forwardsClient.add("refresh-fwd", "http://example.com/mcp", "default");

        // When / Then - no exception expected
        forwardsClient.refresh("refresh-fwd");
    }
}
