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
 * Scenario 2: CIC echo tool lifecycle in a custom namespace ("integration-test").
 *
 * Same steps as Scenario 1 but using namespace "integration-test" instead of "default".
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EchoToolCustomNamespaceITCase extends CamelCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(EchoToolCustomNamespaceITCase.class);
    private static final String SERVICE_NAME = "echo-custom-ns-svc";
    private static final String NAMESPACE = "integration-test";

    @DisplayName("a) Launch CIC, register forward in custom namespace, verify echo tool appears")
    @Test
    @Order(1)
    void shouldRegisterEchoToolInCustomNamespace() throws Exception {
        assertThat(isServerRunning()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();

        startCapability(SERVICE_NAME, "simple-tool", NAMESPACE);

        mcpClient
                .when()
                .toolsList(page -> {
                    LOG.info("MCP tools (namespace {}): {}", NAMESPACE, page.tools());
                    assertThat(page.tools()).anyMatch(tool -> "echo".equals(tool.name()));
                })
                .thenAssertResults();
    }

    @DisplayName("b) Call echo tool via MCP in custom namespace and verify response")
    @Test
    @Order(2)
    void shouldInvokeEchoToolInCustomNamespace() throws Exception {
        assertThat(isServerRunning()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();

        startCapability(SERVICE_NAME, "simple-tool", NAMESPACE);

        assertToolCallWithRetry("echo", Map.of("message", "hello-custom-ns"), response -> {
            LOG.info("Echo response (namespace {}): {}", NAMESPACE, response.content());
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).isNotEmpty();
            assertThat(response.content().get(0).asText().text()).contains("hello-custom-ns");
        });
    }

    @DisplayName("c) Shut down CIC, verify tool removed from custom namespace")
    @Test
    @Order(3)
    void shouldRemoveToolAfterShutdownInCustomNamespace() throws Exception {
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
                    LOG.info("MCP tools after shutdown (namespace {}): {}", NAMESPACE, page.tools());
                    assertThat(page.tools()).noneMatch(t -> "echo".equals(t.name()));
                })
                .thenAssertResults();
    }
}
