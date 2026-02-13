package ai.wanaku.test.utils;

import ai.wanaku.test.WanakuTestConstants;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Utility class for component health check verification.
 * Provides methods to wait for components to become ready.
 */
public final class HealthCheckUtils {

    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckUtils.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HealthCheckUtils() {
        // Utility class
    }

    /**
     * Waits for a health check endpoint to return a successful response.
     *
     * @param url     the health check URL
     * @param timeout maximum time to wait
     * @return true if the endpoint became healthy within the timeout
     */
    public static boolean waitForHealthy(String url, Duration timeout) {
        return waitForHealthy(url, timeout, WanakuTestConstants.DEFAULT_HEALTH_CHECK_INTERVAL);
    }

    /**
     * Waits for a health check endpoint to return a successful response.
     *
     * @param url      the health check URL
     * @param timeout  maximum time to wait
     * @param interval time between health check attempts (ignored, uses Awaitility default 100ms)
     * @return true if the endpoint became healthy within the timeout
     */
    public static boolean waitForHealthy(String url, Duration timeout, Duration interval) {
        LOG.debug("Waiting for health check: {} (timeout: {}s)", url, timeout.toSeconds());

        try {
            Awaitility.await()
                    .atMost(timeout)
                    .until(() -> checkHealth(url));
            LOG.debug("Health check passed");
            return true;
        } catch (ConditionTimeoutException e) {
            LOG.error("Health check timeout: {}", url);
            return false;
        }
    }

    /**
     * Performs a single health check request.
     *
     * @param url the health check URL
     * @return true if the health check was successful (2xx response)
     */
    public static boolean checkHealth(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();

            return statusCode >= HttpURLConnection.HTTP_OK && statusCode < HttpURLConnection.HTTP_MULT_CHOICE;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Waits for a port to become available (listening).
     *
     * @param host    the host to check
     * @param port    the port to check
     * @param timeout maximum time to wait
     * @return true if the port became available within the timeout
     */
    public static boolean waitForPort(String host, int port, Duration timeout) {
        LOG.debug("Waiting for port {}:{} (timeout: {}s)", host, port, timeout.toSeconds());

        try {
            Awaitility.await()
                    .atMost(timeout)
                    .until(() -> isPortOpen(host, port));
            LOG.debug("Port {}:{} is available", host, port);
            return true;
        } catch (ConditionTimeoutException e) {
            LOG.error("Timeout waiting for port {}:{}", host, port);
            return false;
        }
    }

    /**
     * Checks if a port is open (listening).
     *
     * @param host the host to check
     * @param port the port to check
     * @return true if the port is open
     */
    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
