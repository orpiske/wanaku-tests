package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class NamespaceClient {

    private static final Logger LOG = LoggerFactory.getLogger(NamespaceClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public NamespaceClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates a namespace and returns its server-generated ID.
     */
    public String create(String name, String path) {
        LOG.debug("Creating namespace: {} with path: {}", name, path);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("path", path);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Namespace created: {}", name);
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;
                if (dataNode != null && dataNode.has("id")) {
                    return dataNode.get("id").asText();
                }
                return null;
            } else if (response.statusCode() == 409) {
                throw new NamespaceExistsException("Namespace '" + name + "' already exists");
            } else {
                throw new NamespaceClientException(
                        "Failed to create namespace: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to create namespace", e);
        }
    }

    public List<JsonNode> list() {
        LOG.debug("Listing namespaces");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List namespaces response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                List<JsonNode> namespaces = new ArrayList<>();
                if (dataNode.isArray()) {
                    for (JsonNode ns : dataNode) {
                        namespaces.add(ns);
                    }
                } else if (dataNode.isObject()) {
                    namespaces.add(dataNode);
                }
                return namespaces;
            } else {
                throw new NamespaceClientException(
                        "Failed to list namespaces: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to list namespaces", e);
        }
    }

    /**
     * Shows a namespace by its server-generated ID.
     */
    public JsonNode show(String id) {
        LOG.debug("Showing namespace by id: {}", id);

        try {
            String encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedId)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Show namespace response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    throw new NamespaceNotFoundException("Namespace with id '" + id + "' not found");
                }
                return dataNode;
            } else if (response.statusCode() == 404) {
                throw new NamespaceNotFoundException("Namespace with id '" + id + "' not found");
            } else {
                throw new NamespaceClientException(
                        "Failed to show namespace: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to show namespace", e);
        }
    }

    /**
     * Deletes a namespace by its server-generated ID.
     */
    public boolean delete(String id) {
        LOG.debug("Deleting namespace by id: {}", id);

        try {
            String encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedId)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Delete namespace response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Namespace deleted: {}", id);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Namespace not found: {}", id);
                return false;
            } else {
                throw new NamespaceClientException("Failed to delete namespace: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to delete namespace", e);
        }
    }

    /**
     * Updates a namespace by its server-generated ID.
     */
    public void update(String id, Map<String, Object> updates) {
        LOG.debug("Updating namespace by id: {}", id);

        try {
            String encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8);
            String json = objectMapper.writeValueAsString(updates);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedId)
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                LOG.debug("Namespace updated: {}", id);
            } else if (response.statusCode() == 404) {
                throw new NamespaceNotFoundException("Namespace with id '" + id + "' not found");
            } else {
                throw new NamespaceClientException(
                        "Failed to update namespace: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to update namespace", e);
        }
    }

    /**
     * Cleans up stale namespaces via DELETE /namespaces/stale.
     */
    public void cleanupStale() {
        LOG.debug("Cleaning up stale namespaces");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/stale")
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                LOG.debug("Stale namespaces cleaned up");
            } else {
                throw new NamespaceClientException(
                        "Failed to cleanup stale namespaces: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to cleanup stale namespaces", e);
        }
    }

    /**
     * Checks whether a namespace with the given name exists by listing all namespaces.
     */
    public boolean exists(String name) {
        List<JsonNode> namespaces = list();
        return namespaces.stream()
                .anyMatch(ns -> ns.has("name") && name.equals(ns.get("name").asText()));
    }

    /**
     * Finds a namespace by name and returns its server-generated ID, or null if not found.
     */
    public String findIdByName(String name) {
        List<JsonNode> namespaces = list();
        return namespaces.stream()
                .filter(ns -> ns.has("name") && name.equals(ns.get("name").asText()))
                .findFirst()
                .map(ns -> ns.has("id") ? ns.get("id").asText() : null)
                .orElse(null);
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (accessToken != null && !accessToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public static class NamespaceClientException extends RuntimeException {
        public NamespaceClientException(String message) {
            super(message);
        }

        public NamespaceClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NamespaceExistsException extends NamespaceClientException {
        public NamespaceExistsException(String message) {
            super(message);
        }
    }

    public static class NamespaceNotFoundException extends NamespaceClientException {
        public NamespaceNotFoundException(String message) {
            super(message);
        }
    }
}
