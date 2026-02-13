package ai.wanaku.test.http;

import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.model.HttpToolConfig;
import ai.wanaku.test.model.ToolInfo;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests for HTTP tool registration via REST API.
 * Verifies tools can be registered with Router and listed via MCP protocol.
 */
@QuarkusTest
class HttpToolRegistrationITCase extends HttpCapabilityTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable())
                .as("Router must be available")
                .isTrue();
    }

    @DisplayName("Register HTTP tool via REST API and verify it appears in tool list")
    @Test
    void shouldRegisterHttpToolViaRestApi() {
        // Given
        HttpToolConfig config = HttpToolConfig.builder()
                .name("test-weather-api")
                .description("Test weather API tool")
                .uri("https://httpbin.org/get")
                .method("GET")
                .build();

        // When
        ToolInfo registered = routerClient.registerTool(config);

        // Then
        assertThat(registered).isNotNull();
        assertThat(registered.getName()).isEqualTo("test-weather-api");
        assertThat(registered.getDescription()).isEqualTo("Test weather API tool");
        assertThat(registered.getType()).isEqualTo("http");

        // Verify tool is listed
        assertThat(routerClient.toolExists("test-weather-api")).isTrue();
    }

    @DisplayName("Register 3 tools and verify all are returned when listing")
    @Test
    void shouldListMultipleRegisteredTools() {
        // Given - Register multiple tools
        routerClient.registerTool(HttpToolConfig.builder()
                .name("tool-alpha")
                .description("First tool")
                .uri("https://httpbin.org/get?tool=alpha")
                .build());

        routerClient.registerTool(HttpToolConfig.builder()
                .name("tool-beta")
                .description("Second tool")
                .uri("https://httpbin.org/get?tool=beta")
                .build());

        routerClient.registerTool(HttpToolConfig.builder()
                .name("tool-gamma")
                .description("Third tool")
                .uri("https://httpbin.org/get?tool=gamma")
                .build());

        // When
        List<ToolInfo> tools = routerClient.listTools();

        // Then
        assertThat(tools).hasSize(3);
        assertThat(tools)
                .extracting(ToolInfo::getName)
                .containsExactlyInAnyOrder("tool-alpha", "tool-beta", "tool-gamma");
    }

    @DisplayName("Remove a registered tool and verify it no longer exists")
    @Test
    void shouldRemoveRegisteredTool() {
        // Given
        routerClient.registerTool(HttpToolConfig.builder()
                .name("tool-to-remove")
                .description("This tool will be removed")
                .uri("https://httpbin.org/delete")
                .build());

        assertThat(routerClient.toolExists("tool-to-remove")).isTrue();

        // When
        boolean removed = routerClient.removeTool("tool-to-remove");

        // Then
        assertThat(removed).isTrue();
        assertThat(routerClient.toolExists("tool-to-remove")).isFalse();
    }

    @DisplayName("Reject registration of tool with duplicate name")
    @Test
    void shouldRejectDuplicateToolName() {
        // Given
        HttpToolConfig config = HttpToolConfig.builder()
                .name("duplicate-tool")
                .description("First registration")
                .uri("https://httpbin.org/get?first=true")
                .build();

        routerClient.registerTool(config);

        // When/Then
        HttpToolConfig duplicateConfig = HttpToolConfig.builder()
                .name("duplicate-tool")
                .description("Second registration attempt")
                .uri("https://httpbin.org/get?second=true")
                .build();

        assertThatThrownBy(() -> routerClient.registerTool(duplicateConfig))
                .isInstanceOf(RouterClient.ToolExistsException.class)
                .hasMessageContaining("duplicate-tool");
    }

    @DisplayName("Return false when trying to remove a tool that doesn't exist")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentTool() {
        // When
        boolean removed = routerClient.removeTool("nonexistent-tool");

        // Then
        assertThat(removed).isFalse();
    }

    @DisplayName("Get detailed info about a registered tool by name")
    @Test
    void shouldGetToolInfo() {
        // Given
        routerClient.registerTool(HttpToolConfig.builder()
                .name("info-test-tool")
                .description("Tool for info test")
                .uri("https://httpbin.org/post")
                .method("POST")
                .build());

        // When
        ToolInfo info = routerClient.getToolInfo("info-test-tool");

        // Then
        assertThat(info.getName()).isEqualTo("info-test-tool");
        assertThat(info.getDescription()).isEqualTo("Tool for info test");
        assertThat(info.getType()).isEqualTo("http");
    }

    @DisplayName("Throw ToolNotFoundException when getting a tool that doesn't exist")
    @Test
    void shouldThrowWhenGettingNonexistentTool() {
        assertThatThrownBy(() -> routerClient.getToolInfo("does-not-exist"))
                .isInstanceOf(RouterClient.ToolNotFoundException.class)
                .hasMessageContaining("does-not-exist");
    }

    @DisplayName("Register tool with input schema defining required parameters")
    @Test
    void shouldRegisterToolWithInputSchema() {
        // Given
        HttpToolConfig config = HttpToolConfig.builder()
                .name("schema-tool")
                .description("Tool with input schema")
                .uri("https://httpbin.org/get")
                .property("city", "string", "City name")
                .property("country", "string", "Country name")
                .required("city", "country")
                .build();

        // When
        ToolInfo registered = routerClient.registerTool(config);

        // Then
        assertThat(registered).isNotNull();
        assertThat(registered.getName()).isEqualTo("schema-tool");
    }

    @DisplayName("Clear all registered tools and verify the list is empty")
    @Test
    void shouldClearAllTools() {
        // Given
        routerClient.registerTool(HttpToolConfig.builder()
                .name("clear-test-1")
                .uri("https://httpbin.org/get?id=1")
                .build());

        routerClient.registerTool(HttpToolConfig.builder()
                .name("clear-test-2")
                .uri("https://httpbin.org/get?id=2")
                .build());

        assertThat(routerClient.listTools()).hasSize(2);

        // When
        routerClient.clearAllTools();

        // Then
        assertThat(routerClient.listTools()).isEmpty();
    }
}
