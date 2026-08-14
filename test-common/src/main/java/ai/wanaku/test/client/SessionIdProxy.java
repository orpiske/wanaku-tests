package ai.wanaku.test.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.utils.PortUtils;
import com.sun.net.httpserver.HttpServer;

/**
 * Lightweight HTTP proxy that forwards MCP requests to praxis and injects
 * {@code Mcp-Session-Id} into responses. Needed because the quarkus-mcp-server-test
 * library requires this header but praxis does not return it.
 */
public class SessionIdProxy implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SessionIdProxy.class);

    private final HttpServer server;
    private final HttpClient httpClient;
    private final String targetBaseUrl;
    private final int port;
    private final String sessionId = UUID.randomUUID().toString();

    public SessionIdProxy(String targetBaseUrl) throws IOException {
        this.targetBaseUrl = targetBaseUrl.endsWith("/") ? targetBaseUrl : targetBaseUrl + "/";
        this.port = PortUtils.findAvailablePort();
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", exchange -> {
            try {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                String targetUrl = targetBaseUrl + exchange.getRequestURI().getRawPath();
                if (targetUrl.contains("//mcp")) {
                    targetUrl = targetUrl.replace("//mcp", "/mcp");
                }

                HttpRequest.Builder reqBuilder =
                        HttpRequest.newBuilder().uri(URI.create(targetUrl)).timeout(Duration.ofSeconds(30));

                String method = exchange.getRequestMethod();
                if ("POST".equals(method)) {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
                } else {
                    reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
                }

                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!"Host".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                        for (String v : values) {
                            reqBuilder.header(name, v);
                        }
                    }
                });

                HttpResponse<byte[]> resp =
                        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

                resp.headers().map().forEach((name, values) -> {
                    for (String v : values) {
                        exchange.getResponseHeaders().add(name, v);
                    }
                });
                exchange.getResponseHeaders().add("Mcp-Session-Id", sessionId);

                byte[] body = resp.body();
                exchange.sendResponseHeaders(resp.statusCode(), body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (Exception e) {
                LOG.warn("Proxy error: {}", e.getMessage());
                byte[] err = "proxy error".getBytes();
                try {
                    exchange.sendResponseHeaders(502, err.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err);
                    }
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void start() {
        server.start();
        LOG.debug("SessionIdProxy started on port {}, forwarding to {}", port, targetBaseUrl);
    }

    public int getPort() {
        return port;
    }

    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    public void close() {
        server.stop(0);
        LOG.debug("SessionIdProxy stopped");
    }
}
