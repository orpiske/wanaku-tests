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

public class ServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public ServiceClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public void register(String name, String address, String serviceType) {
        LOG.debug("Registering service: {} at {} (type: {})", name, address, serviceType);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("address", address);
        body.put("service_type", serviceType);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.PRAXIS_SERVICES_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                LOG.debug("Service registered: {}", name);
            } else {
                throw new ServiceClientException(
                        "Failed to register service: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceClientException("Failed to register service", e);
        }
    }

    public List<JsonNode> list() {
        LOG.debug("Listing services");

        try {
            HttpRequest request =
                    buildRequest(WanakuTestConstants.PRAXIS_SERVICES_PATH).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                List<JsonNode> services = new ArrayList<>();
                if (dataNode.isArray()) {
                    for (JsonNode svc : dataNode) {
                        services.add(svc);
                    }
                } else if (dataNode.isObject()) {
                    services.add(dataNode);
                }
                return services;
            } else {
                throw new ServiceClientException("Failed to list services: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceClientException("Failed to list services", e);
        }
    }

    public boolean remove(String name) {
        LOG.debug("Removing service: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.PRAXIS_SERVICES_PATH + "/" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                LOG.debug("Service removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Service not found: {}", name);
                return false;
            } else {
                throw new ServiceClientException("Failed to remove service: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceClientException("Failed to remove service", e);
        }
    }

    public void clearAll() {
        LOG.debug("Clearing all services");

        List<JsonNode> services = list();
        for (JsonNode svc : services) {
            String name = svc.has("name") ? svc.get("name").asText() : null;
            if (name != null) {
                try {
                    remove(name);
                } catch (Exception e) {
                    LOG.warn("Failed to remove service {}: {}", name, e.getMessage());
                }
            }
        }
        LOG.debug("Cleared {} services", services.size());
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (accessToken != null && !accessToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder;
    }

    public static class ServiceClientException extends RuntimeException {
        public ServiceClientException(String message) {
            super(message);
        }

        public ServiceClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
