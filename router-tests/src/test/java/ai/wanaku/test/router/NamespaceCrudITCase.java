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
class NamespaceCrudITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
    }

    @DisplayName("Create a namespace and verify it exists")
    @Test
    void shouldCreateNamespace() {
        // Given
        String name = "test-ns";

        // When
        namespaceClient.create(name);

        // Then
        assertThat(namespaceClient.exists(name)).isTrue();
    }

    @DisplayName("List namespaces and verify result is non-null")
    @Test
    void shouldListNamespaces() {
        // When
        List<JsonNode> namespaces = namespaceClient.list();

        // Then
        assertThat(namespaces).isNotNull();
    }

    @DisplayName("Create a namespace, show it, and verify returned node has the name")
    @Test
    void shouldShowNamespace() {
        // Given
        String name = "show-ns";
        namespaceClient.create(name);

        // When
        JsonNode node = namespaceClient.show(name);

        // Then
        assertThat(node).isNotNull();
        assertThat(node.has("name")).isTrue();
        assertThat(node.get("name").asText()).isEqualTo(name);
    }

    @DisplayName("Create a namespace, delete it, and verify it no longer exists")
    @Test
    void shouldDeleteNamespace() {
        // Given
        String name = "delete-ns";
        namespaceClient.create(name);
        assertThat(namespaceClient.exists(name)).isTrue();

        // When
        boolean deleted = namespaceClient.delete(name);

        // Then
        assertThat(deleted).isTrue();
        assertThat(namespaceClient.exists(name)).isFalse();
    }

    @DisplayName("Return false when deleting a namespace that does not exist")
    @Test
    void shouldReturnFalseWhenDeletingNonexistentNamespace() {
        // When
        boolean deleted = namespaceClient.delete("nonexistent");

        // Then
        assertThat(deleted).isFalse();
    }

    @DisplayName("Add a label to a namespace, verify it, remove it, and verify removal")
    @Test
    void shouldAddAndRemoveLabel() {
        // Given
        String name = "label-ns";
        namespaceClient.create(name);

        // When - add label
        namespaceClient.addLabel(name, "env", "test");

        // Then - verify label present
        JsonNode node = namespaceClient.show(name);
        assertThat(node.has("labels")).isTrue();
        JsonNode labels = node.get("labels");
        assertThat(labels.has("env")).isTrue();
        assertThat(labels.get("env").asText()).isEqualTo("test");

        // When - remove label
        namespaceClient.removeLabel(name, "env");

        // Then - verify label removed
        JsonNode updated = namespaceClient.show(name);
        if (updated.has("labels") && !updated.get("labels").isNull()) {
            assertThat(updated.get("labels").has("env")).isFalse();
        }
    }
}
