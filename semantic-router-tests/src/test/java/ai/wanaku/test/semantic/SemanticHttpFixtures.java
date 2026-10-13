package ai.wanaku.test.semantic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Real TypeSafe AI wire adapter talks to this deterministic provider; audit proxy never fabricates acknowledgements. */
final class SemanticHttpFixtures implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicBoolean rejecting = new AtomicBoolean(true);
    private final AtomicInteger requests = new AtomicInteger();

    private SemanticHttpFixtures() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(executor);
    }

    static SemanticHttpFixtures provider() throws IOException {
        var fixture = new SemanticHttpFixtures();
        fixture.server.createContext("/v1/systemone", exchange -> {
            try (exchange) {
                var request = JSON.readTree(exchange.getRequestBody());
                String state = request.path("state").asText();
                String label = state.startsWith("billing")
                        ? "billing"
                        : state.startsWith("technical") ? "technical" : "no_match";
                Map<String, Object> answers = new LinkedHashMap<>();
                request.path("questions").fields().forEachRemaining(question -> {
                    Map<String, Double> probabilities = new LinkedHashMap<>();
                    question.getValue()
                            .path("criteria")
                            .fieldNames()
                            .forEachRemaining(key -> probabilities.put(key, key.equals(label) ? 1.0 : 0.0));
                    answers.put(
                            question.getKey(),
                            Map.of(
                                    "type",
                                    "choice",
                                    "choice",
                                    label,
                                    "probabilities",
                                    probabilities,
                                    "confidence",
                                    1.0));
                });
                respond(
                        exchange,
                        200,
                        JSON.writeValueAsBytes(Map.of(
                                "model",
                                "fixture",
                                "usage",
                                Map.of("input_tokens", 1, "output_tokens", 1),
                                "answers",
                                answers)));
            }
        });
        fixture.server.start();
        return fixture;
    }

    static SemanticHttpFixtures auditProxy(String target) throws IOException {
        var fixture = new SemanticHttpFixtures();
        fixture.server.createContext("/api/v1/audit/semantic", exchange -> {
            try (exchange) {
                fixture.requests.incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (fixture.rejecting.get()) {
                    respond(exchange, 503, JSON.writeValueAsBytes(Map.of("error", "controlled outage")));
                    return;
                }
                try {
                    var request = HttpRequest.newBuilder(URI.create(target))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
                    var response = fixture.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    respond(exchange, response.statusCode(), response.body());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Audit proxy interrupted", e);
                }
            }
        });
        fixture.server.start();
        return fixture;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requestCount() {
        return requests.get();
    }

    void restoreDelivery() {
        rejecting.set(false);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
        client.close();
    }
}
