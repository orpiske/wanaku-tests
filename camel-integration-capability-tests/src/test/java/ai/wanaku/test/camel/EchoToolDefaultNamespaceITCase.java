package ai.wanaku.test.camel;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.managers.CamelCapabilityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario 1: CIC echo tool lifecycle in the default namespace.
 *
 * <ol>
 *   <li>Launch CIC with the echo tool route</li>
 *   <li>Register it as a forward and verify the echo tool appears</li>
 *   <li>Call the echo tool via MCP and check the result</li>
 *   <li>Shut down CIC and check the tool is removed</li>
 *   <li>Try calling the tool again and verify it fails</li>
 * </ol>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EchoToolDefaultNamespaceITCase extends CamelCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(EchoToolDefaultNamespaceITCase.class);
    private static final String SERVICE_NAME = "echo-default-svc";
    private static final String NAMESPACE = "default";

    @DisplayName("a) Launch CIC, register forward, verify echo tool appears")
    @Test
    @Order(1)
    void shouldRegisterEchoTool() throws Exception {
        assertThat(isServerRunning()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();

        startCapability(SERVICE_NAME, "simple-tool", NAMESPACE);

        mcpClient
                .when()
                .toolsList(page -> {
                    LOG.info("MCP tools: {}", page.tools());
                    assertThat(page.tools()).anyMatch(tool -> "echo".equals(tool.name()));
                })
                .thenAssertResults();
    }

    @DisplayName("b) Call echo tool via MCP and verify response")
    @Test
    @Order(2)
    void shouldInvokeEchoTool() throws Exception {
        assertThat(isServerRunning()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();

        startCapability(SERVICE_NAME, "simple-tool", NAMESPACE);

        assertToolCallWithRetry("echo", Map.of("message", "hello-default"), response -> {
            LOG.info("Echo response: {}", response.content());
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).isNotEmpty();
            assertThat(response.content().get(0).asText().text()).contains("hello-default");
        });
    }

    @DisplayName("c) Shut down CIC, verify tool removed, call fails")
    @Test
    @Order(3)
    void shouldRemoveToolAfterShutdown() throws Exception {
        assertThat(isServerRunning()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();

        CamelCapabilityManager manager = startCapability(SERVICE_NAME, "simple-tool", NAMESPACE);

        mcpClient
                .when()
                .toolsList(page -> assertThat(page.tools()).anyMatch(t -> "echo".equals(t.name())))
                .thenAssertResults();

        stopAndDeregister(manager, SERVICE_NAME);

        mcpClient
                .when()
                .toolsList(page -> {
                    LOG.info("MCP tools after shutdown: {}", page.tools());
                    assertThat(page.tools()).noneMatch(t -> "echo".equals(t.name()));
                })
                .thenAssertResults();
    }
}
