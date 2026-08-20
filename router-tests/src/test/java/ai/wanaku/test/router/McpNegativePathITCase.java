package ai.wanaku.test.router;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Verifies that the Wanaku router returns proper JSON-RPC error responses
 * when MCP operations target non-existent entities (tools, resources, prompts).
 *
 * <p>The server returns JSON-RPC error code {@code -32602} (Invalid Params)
 * for requests that reference entities not present in the registry.
 */
@QuarkusTest
class McpNegativePathITCase extends RouterTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(isMcpClientAvailable()).as("MCP client must be available").isTrue();
        assumeThat(mcpClient).as("MCP client must not be null").isNotNull();
    }

    @DisplayName("Calling a non-existent tool returns JSON-RPC error")
    @Test
    void shouldReturnErrorForNonExistentToolCall() {
        mcpClient
                .when()
                .toolsCall("nonexistent-tool-xyz")
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(-32602);
                    assertThat(error.message()).contains("tool not found");
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Reading a non-existent resource returns JSON-RPC error")
    @Test
    void shouldReturnErrorForNonExistentResourceRead() {
        mcpClient
                .when()
                .resourcesRead("file:///nonexistent/path/that/does/not/exist.txt")
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(-32602);
                    assertThat(error.message()).contains("resource not found");
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Getting a non-existent prompt returns JSON-RPC error")
    @Test
    void shouldReturnErrorForNonExistentPromptGet() {
        mcpClient
                .when()
                .promptsGet("nonexistent-prompt-xyz")
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(-32602);
                    assertThat(error.message()).contains("prompt not found");
                })
                .send()
                .thenAssertResults();
    }
}
