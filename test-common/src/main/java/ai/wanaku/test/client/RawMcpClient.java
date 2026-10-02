package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** HTTP MCP client that sends exact request bodies without SDK validation or normalization. */
public final class RawMcpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final URI endpoint;
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String sessionId;
    private String protocolVersion = "2025-03-26";

    public RawMcpClient(String endpoint) {
        this.endpoint = URI.create(endpoint);
    }

    public void initialize() throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                """
                {"jsonrpc":"2.0","id":"raw-init","method":"initialize","params":{
                  "protocolVersion":"2025-03-26","capabilities":{},
                  "clientInfo":{"name":"wanaku-raw-tests","version":"1.0"}}}
                """);
        JsonNode envelope = MAPPER.readTree(response.body());
        if (response.statusCode() != 200
                || !envelope.path("id").asText().equals("raw-init")
                || !envelope.path("result").path("protocolVersion").isTextual()) {
            throw new IOException("MCP initialization failed: " + response.statusCode() + " " + response.body());
        }
        sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        protocolVersion = envelope.get("result").get("protocolVersion").textValue();
        HttpResponse<String> initialized = send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        if (initialized.statusCode() != 202) {
            throw new IOException("MCP initialized notification failed: " + initialized.body());
        }
    }

    public HttpResponse<String> send(String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Protocol-Version", protocolVersion)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
