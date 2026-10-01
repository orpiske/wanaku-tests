package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Management client retaining error bodies for action-policy validation assertions. */
public final class ActionPolicyClient implements AutoCloseable {

    private static final String PATH = "/api/v1/action-policies";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ActionPolicyClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Response updatePolicy(JsonNode policy) throws IOException, InterruptedException {
        var request = MAPPER.createObjectNode().set("policy", policy);
        return execute(builder(PATH)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build());
    }

    public Response getPolicy() throws IOException, InterruptedException {
        return execute(builder(PATH).GET().build());
    }

    public Response getActiveRevision() throws IOException, InterruptedException {
        return execute(builder(PATH + "/revisions/active").GET().build());
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
    }

    private Response execute(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = MAPPER.readTree(response.body());
        // Successful management responses use a data envelope. Preserve the complete error envelope.
        if (response.statusCode() == 200 && body.has("data")) {
            body = body.get("data");
        }
        return new Response(response.statusCode(), body);
    }

    @Override
    public void close() {
        httpClient.close();
    }

    public record Response(int statusCode, JsonNode body) {}
}
