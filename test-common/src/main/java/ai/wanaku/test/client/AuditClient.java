package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read-only management client for governance audit events and storage health. */
public class AuditClient implements AutoCloseable {
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String accessToken;

    public AuditClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
    }

    public JsonNode listEvents(Map<String, String> filters) {
        String query = filters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return get("/api/v1/audit/events" + (query.isEmpty() ? "" : "?" + query));
    }

    public JsonNode getEvent(String eventId) {
        return get("/api/v1/audit/events/" + encode(eventId));
    }

    public JsonNode health() {
        return get("/api/v1/audit/health");
    }

    public JsonNode schema() {
        return get("/api/v1/audit/schema");
    }

    public JsonNode metrics() {
        return get("/api/v1/metrics");
    }

    private JsonNode get(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (accessToken != null && !accessToken.isBlank()) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        try {
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Audit API GET " + path + " returned " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            return root.has("data") ? root.get("data") : root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Audit API request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Audit API request failed", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
