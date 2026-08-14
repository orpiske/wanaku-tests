package ai.wanaku.test.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests for resource operations via MCP protocol using the mock MCP server.
 * The mock server exposes:
 * - config://policies/safety-limits (static JSON resource)
 * - logs://{server_id}/syslog (resource template)
 */
@QuarkusTest
class McpResourceITCase extends ResourceTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(McpResourceITCase.class);

    @BeforeEach
    void checkInfrastructureAvailable() {
        assertThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(isMockServerAvailable())
                .as("Mock MCP server must be available")
                .isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be available").isTrue();
    }

    @DisplayName("List resources via MCP and verify safety-limits resource appears")
    @Test
    void shouldListResourcesViaMcp() {
        mcpClient
                .when()
                .resourcesList()
                .withAssert(page -> {
                    LOG.info("MCP resources: {}", page.resources());
                    assertThat(page.resources()).isNotEmpty();
                    assertThat(page.resources()).anyMatch(r -> r.uri().contains("config://policies/safety-limits"));
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Read safety-limits resource via MCP and verify JSON content")
    @Test
    void shouldReadStaticResourceViaMcp() {
        mcpClient
                .when()
                .resourcesRead("config://policies/safety-limits")
                .withAssert(response -> {
                    LOG.info("Resource read response: {}", response.contents());
                    assertThat(response.contents()).isNotEmpty();
                    String text = response.contents().get(0).asText().text();
                    assertThat(text).contains("max_db_replicas");
                    assertThat(text).contains("blocked_services");
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Read syslog resource template via MCP and verify server-specific content")
    @Test
    void shouldReadResourceTemplateViaMcp() {
        mcpClient
                .when()
                .resourcesRead("logs://web-01/syslog")
                .withAssert(response -> {
                    LOG.info("Syslog resource response: {}", response.contents());
                    assertThat(response.contents()).isNotEmpty();
                    String text = response.contents().get(0).asText().text();
                    assertThat(text).contains("web-01");
                    assertThat(text).contains("nginx");
                })
                .send()
                .thenAssertResults();
    }
}
