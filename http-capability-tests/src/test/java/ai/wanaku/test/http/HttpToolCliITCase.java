package ai.wanaku.test.http;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests for HTTP tool registration via CLI.
 * Validates that CLI commands work correctly for tool management.
 *
 * Note: The /api/v1/* endpoints are public (no auth required) per Wanaku's
 * application.properties configuration, so we use --no-auth flag.
 */
@QuarkusTest
class HttpToolCliITCase extends HttpCapabilityTestBase {

    private CLIExecutor cliExecutor;

    @BeforeEach
    void setupCli() {
        cliExecutor = CLIExecutor.createDefault();
    }

    /**
     * Gets the Router host URL for CLI commands.
     */
    private String getRouterHost() {
        return routerManager != null ? routerManager.getBaseUrl() : "http://localhost:8080";
    }

    @DisplayName("Run 'wanaku tools add' command to register a new HTTP tool")
    @Test
    void shouldRegisterHttpToolViaCli() {
        // Skip if CLI or Router not available
        assumeThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();

        // Given
        String toolName = "cli-weather-api";
        String toolUri = "https://httpbin.org/get";

        // When - use --no-auth since /api/v1/* is public per Wanaku design
        CLIResult result = cliExecutor.execute(
                "tools",
                "add",
                "--host",
                getRouterHost(),
                "--no-auth",
                "--name",
                toolName,
                "--type",
                "http",
                "--uri",
                toolUri,
                "--description",
                "Weather API registered via CLI");

        // Then
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();

        // Verify via REST API
        assertThat(routerClient.toolExists(toolName)).isTrue();
    }

    @DisplayName("Run 'wanaku tools remove' command to delete a registered tool")
    @Test
    void shouldRemoveToolViaCli() {
        // Skip if CLI or Router not available
        assumeThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();

        // Given - Register a tool first
        String toolName = "cli-remove-test";
        routerClient.registerTool(ai.wanaku.test.model.HttpToolConfig.builder()
                .name(toolName)
                .uri("https://httpbin.org/delete")
                .build());

        assertThat(routerClient.toolExists(toolName)).isTrue();

        // When - use --no-auth since /api/v1/* is public per Wanaku design
        CLIResult result =
                cliExecutor.execute("tools", "remove", "--host", getRouterHost(), "--no-auth", "--name", toolName);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(routerClient.toolExists(toolName)).isFalse();
    }
}
