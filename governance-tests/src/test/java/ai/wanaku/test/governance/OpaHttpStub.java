package ai.wanaku.test.governance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;

/** Local OPA protocol fixture; blocked handlers are released before shutdown. */
final class OpaHttpStub implements AutoCloseable {
    record Request(String method, String path, String body, Map<String, List<String>> headers) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int status = 200;
    private volatile String body = "{\"result\":true}";
    private volatile boolean hold;
    private boolean closed;

    OpaHttpStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        Map.copyOf(exchange.getRequestHeaders())));
                if (hold) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    void holdRequests() {
        hold = true;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }
}
