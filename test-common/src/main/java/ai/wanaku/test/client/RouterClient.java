package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.model.HttpToolConfig;
import ai.wanaku.test.model.ToolInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST API client for Router management operations.
 *
 * API Endpoints (as of Wanaku Router):
 * - POST /api/v1/tools/add - Register a tool
 * - GET /api/v1/tools/list - List all tools
 * - POST /api/v1/tools?name={name} - Get tool by name
 * - PUT /api/v1/tools/remove?tool={name} - Remove a tool
 * - DELETE /api/v1/tools?labelExpression={expr} - Remove tools by label
 */
public class RouterClient {

    private static final Logger LOG = LoggerFactory.getLogger(RouterClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private String accessToken;
    private Instant tokenExpiry;

    public RouterClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sets the access token for authentication.
     */
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
        this.tokenExpiry = Instant.now().plusSeconds(300); // Assume 5 min validity
    }

    /**
     * Sets the access token with explicit expiry.
     */
    public void setAccessToken(String accessToken, Instant expiry) {
        this.accessToken = accessToken;
        this.tokenExpiry = expiry;
    }

    /**
     * Registers a new HTTP tool.
     *
     * @param config the tool configuration
     * @return information about the registered tool
     * @throws ToolExistsException if a tool with the same name already exists
     */
    public ToolInfo registerTool(HttpToolConfig config) {
        LOG.debug("Registering tool: {}", config.getName());

        Map<String, Object> body = new HashMap<>();
        body.put("name", config.getName());
        body.put("description", config.getDescription());
        body.put("type", "http");
        body.put("uri", config.getUri());
        body.put("inputSchema", config.getInputSchema());
        body.put("labels", Map.of("test", "true"));

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "/add")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;
                return objectMapper.treeToValue(dataNode, ToolInfo.class);
            } else if (response.statusCode() == 409) {
                throw new ToolExistsException("Tool '" + config.getName() + "' already exists");
            } else {
                throw new RouterClientException(
                        "Failed to register tool: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to register tool", e);
        }
    }

    /**
     * Lists all registered tools.
     */
    public List<ToolInfo> listTools() {
        LOG.debug("Listing tools");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "/list")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                // Parse WanakuResponse wrapper
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                if (dataNode.isArray()) {
                    return objectMapper.convertValue(dataNode, new TypeReference<List<ToolInfo>>() {});
                }
                return new ArrayList<>();
            } else {
                throw new RouterClientException("Failed to list tools: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to list tools", e);
        }
    }

    /**
     * Gets information about a specific tool.
     *
     * @throws ToolNotFoundException if the tool does not exist
     */
    public ToolInfo getToolInfo(String name) {
        LOG.debug("Getting tool info: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "?name=" + encodedName)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Get tool response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                // Parse WanakuResponse wrapper
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    throw new ToolNotFoundException("Tool '" + name + "' not found");
                }

                return objectMapper.treeToValue(dataNode, ToolInfo.class);
            } else if (response.statusCode() == 404) {
                throw new ToolNotFoundException("Tool '" + name + "' not found");
            } else {
                // Check if response contains error
                try {
                    JsonNode root = objectMapper.readTree(response.body());
                    if (root.has("error") && !root.get("error").isNull()) {
                        String errorMsg = root.get("error").has("message")
                                ? root.get("error").get("message").asText()
                                : root.get("error").asText();
                        if (errorMsg.contains("not found")) {
                            throw new ToolNotFoundException("Tool '" + name + "' not found");
                        }
                    }
                } catch (IOException ignored) {
                }
                throw new RouterClientException("Failed to get tool: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to get tool", e);
        }
    }

    /**
     * Removes a registered tool.
     *
     * @return true if removed, false if not found
     */
    public boolean removeTool(String name) {
        LOG.debug("Removing tool: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "/remove?tool=" + encodedName)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Remove response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Tool removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Tool not found: {}", name);
                return false;
            } else {
                throw new RouterClientException("Failed to remove tool: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to remove tool", e);
        }
    }

    /**
     * Removes all registered tools.
     * Uses label expression to remove all tools marked with test=true label.
     */
    public void clearAllTools() {
        LOG.debug("Clearing all tools");

        try {
            // First try to remove test tools by label
            String labelExpr = URLEncoder.encode("test=true", StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "?labelExpression=" + labelExpr)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Clear by label response: {} - {}", response.statusCode(), response.body());

            // Also list and remove all remaining tools
            List<ToolInfo> remainingTools = listTools();
            for (ToolInfo tool : remainingTools) {
                try {
                    removeTool(tool.getName());
                } catch (Exception e) {
                    LOG.warn("Failed to remove tool {}: {}", tool.getName(), e.getMessage());
                }
            }

            LOG.debug("All tools cleared");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to clear tools", e);
        }
    }

    /**
     * Checks if a tool exists.
     */
    public boolean toolExists(String name) {
        try {
            getToolInfo(name);
            return true;
        } catch (ToolNotFoundException e) {
            return false;
        }
    }

    /**
     * Registers a tool with static headers/config via /addWithPayload endpoint.
     *
     * @param config the tool configuration
     * @param configurationData properties-format string (e.g., "header.X-Api-Key=secret")
     */
    public ToolInfo registerToolWithConfig(HttpToolConfig config, String configurationData) {
        LOG.debug("Registering tool with configurationData: {}", config.getName());

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", config.getName());
        payload.put("description", config.getDescription());
        payload.put("type", "http");
        payload.put("uri", config.getUri());
        payload.put(
                "inputSchema",
                config.getInputSchema() != null
                        ? config.getInputSchema()
                        : Map.of("type", "object", "properties", Map.of()));

        Map<String, Object> body = new HashMap<>();
        body.put("payload", payload);
        body.put("configurationData", configurationData);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "/addWithPayload")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;
                return objectMapper.treeToValue(dataNode, ToolInfo.class);
            } else if (response.statusCode() == 409) {
                throw new ToolExistsException("Tool '" + config.getName() + "' already exists");
            } else {
                throw new RouterClientException(
                        "Failed to register tool: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to register tool", e);
        }
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));

        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        return builder;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // Exception classes
    public static class RouterClientException extends RuntimeException {
        public RouterClientException(String message) {
            super(message);
        }

        public RouterClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ToolExistsException extends RouterClientException {
        public ToolExistsException(String message) {
            super(message);
        }
    }

    public static class ToolNotFoundException extends RouterClientException {
        public ToolNotFoundException(String message) {
            super(message);
        }
    }
}
