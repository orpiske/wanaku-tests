package ai.wanaku.test.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableAssert;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.vertx.core.MultiMap;

/**
 * MCP Streamable HTTP client wrapper for integration tests.
 * Uses McpAssured from quarkus-mcp-server-test for MCP protocol testing.
 * <p>
 * Uses Streamable HTTP transport (endpoint: /mcp/) instead of SSE (/mcp/sse)
 * because Streamable HTTP properly supports Bearer token authentication
 * on all requests including the initial connection.
 *
 * Usage example:
 * <pre>
 * McpTestClient mcpClient = new McpTestClient("http://localhost:8080", token);
 * mcpClient.connect();
 *
 * // List tools
 * mcpClient.when()
 *     .toolsList()
 *     .withAssert(page -&gt; assertThat(page.tools()).isNotEmpty())
 *     .send();
 *
 * // Call tool
 * mcpClient.when()
 *     .toolsCall("my-tool")
 *     .withArguments(Map.of("param", "value"))
 *     .withAssert(response -&gt; assertThat(response.isError()).isFalse())
 *     .send();
 *
 * mcpClient.disconnect();
 * </pre>
 */
public class McpTestClient {

    private static final Logger LOG = LoggerFactory.getLogger(McpTestClient.class);

    private final McpStreamableTestClient client;
    private final String baseUrl;

    /**
     * Creates a new MCP test client with optional bearer token authentication.
     *
     * @param baseUrl     the Router base URL (e.g., "http://localhost:8080")
     * @param accessToken the bearer token for authorization (can be null for no auth)
     */
    public McpTestClient(String baseUrl, String accessToken) {
        this(baseUrl, accessToken, Collections.emptyMap());
    }

    /**
     * Creates a new MCP test client with optional bearer token and custom HTTP headers.
     *
     * @param baseUrl      the Router base URL (e.g., "http://localhost:8080")
     * @param accessToken  the bearer token for authorization (can be null for no auth)
     * @param extraHeaders additional HTTP headers sent with every MCP request
     */
    public McpTestClient(String baseUrl, String accessToken, Map<String, String> extraHeaders) {
        this.baseUrl = baseUrl;
        try {
            String normalizedUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

            McpStreamableTestClient.Builder builder = McpAssured.newStreamableClient()
                    .setBaseUri(new URI(normalizedUrl))
                    .setMcpPath("mcp/");

            boolean hasToken = accessToken != null && !accessToken.isEmpty();
            boolean hasExtra = extraHeaders != null && !extraHeaders.isEmpty();

            if (hasToken || hasExtra) {
                builder.setAdditionalHeaders(message -> {
                    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
                    if (hasExtra) {
                        extraHeaders.forEach(headers::add);
                    }
                    if (hasToken && !headers.contains("Authorization")) {
                        headers.add("Authorization", "Bearer " + accessToken);
                    }
                    return headers;
                });
                LOG.debug(
                        "MCP client configured with {} header(s)",
                        (hasToken ? 1 : 0) + (hasExtra ? extraHeaders.size() : 0));
            }

            this.client = builder.build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid base URL: " + baseUrl, e);
        }
    }

    /**
     * Connects to the MCP Streamable HTTP endpoint.
     */
    public void connect() {
        LOG.debug("Connecting MCP client to {}/mcp/", baseUrl);
        client.connect();
        LOG.debug("MCP client connected, session: {}", client.mcpSessionId());
    }

    /**
     * Disconnects from the MCP endpoint.
     */
    public void disconnect() {
        LOG.debug("Disconnecting MCP client");
        client.disconnect();
    }

    /**
     * Starts a new MCP operation chain.
     * Use this to build toolsList() or toolsCall() operations.
     *
     * @return the assert builder for chaining operations
     */
    public McpStreamableAssert when() {
        return client.when();
    }
}
