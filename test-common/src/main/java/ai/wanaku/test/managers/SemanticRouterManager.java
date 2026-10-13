package ai.wanaku.test.managers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.PortUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Starts a packaged WSR distribution against local semantic route fixtures. */
public class SemanticRouterManager extends ProcessManager {
    private final TestConfiguration config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private String name;
    private String namespace;
    private Path routes;
    private Path dependencies;
    private Path workDirectory;
    private String registrationUrl;
    private String providerBaseUrl;
    private int httpPort;
    private int statusPort;
    private Boolean auditEnabled;
    private String auditUrl;

    public SemanticRouterManager(TestConfiguration config) {
        this.config = config;
    }

    public void prepare(
            String name,
            String namespace,
            Path routes,
            Path dependencies,
            Path workDirectory,
            String registrationUrl,
            String providerBaseUrl) {
        this.name = name;
        this.namespace = namespace;
        this.routes = routes.toAbsolutePath().normalize();
        this.dependencies =
                dependencies == null ? null : dependencies.toAbsolutePath().normalize();
        this.workDirectory = workDirectory.toAbsolutePath().normalize();
        this.registrationUrl = registrationUrl;
        this.providerBaseUrl = providerBaseUrl;
        httpPort = PortUtils.findAvailablePort();
        do {
            statusPort = PortUtils.findAvailablePort();
        } while (statusPort == httpPort);
    }

    public void setAuditEnabled(Boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }

    public void setAuditUrl(String auditUrl) {
        this.auditUrl = auditUrl;
    }

    @Override
    protected String getProcessName() {
        return "semantic-router";
    }

    @Override
    protected Path getExecutablePath() {
        return config.getSemanticRouterJarPath();
    }

    @Override
    protected List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(getExecutablePath().toAbsolutePath().toString());
        command.addAll(getProcessArguments());
        return command;
    }

    @Override
    protected List<String> getProcessArguments() {
        List<String> args = new ArrayList<>(List.of(
                "runtime",
                "--name",
                name,
                "--namespace",
                namespace,
                "--routes-ref",
                routes.toUri().toString(),
                "--mcp-port",
                String.valueOf(httpPort),
                "--status-port",
                String.valueOf(statusPort),
                "--bind",
                "127.0.0.1",
                "--status-bind",
                "127.0.0.1",
                "--mcp-address",
                getMcpUrl(),
                "--work-directory",
                workDirectory.toString(),
                "--expert",
                "supportExpert=org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter",
                "--property",
                "camel.component.typesafe-ai.api-key=local-test-key",
                "--property",
                "camel.component.typesafe-ai.model=local-test-model",
                "--property",
                "camel.component.typesafe-ai.base-url=" + providerBaseUrl));
        if (dependencies != null) {
            args.addAll(List.of("--dependencies", dependencies.toUri().toString()));
        }
        if (registrationUrl != null) {
            args.addAll(List.of("--registration-url", registrationUrl));
        }
        if (auditEnabled != null) {
            args.add("--audit-enabled=" + auditEnabled);
        }
        if (auditUrl != null) {
            args.addAll(List.of("--audit-url", auditUrl));
        }
        return args;
    }

    @Override
    protected void configureDataIsolation() {}

    @Override
    protected void configureEnvironment(Map<String, String> processEnvironment) {
        processEnvironment.keySet().removeIf(key -> key.startsWith("WSR_") || key.startsWith("TYPESAFE_"));
        super.configureEnvironment(processEnvironment);
    }

    public JsonNode getStatus() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + statusPort + "/api/v1/status"))
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("WSR status returned HTTP " + response.statusCode() + " on port " + statusPort);
        }
        return mapper.readTree(response.body());
    }

    @Override
    protected boolean performHealthCheck() {
        long deadline = System.nanoTime() + config.getDefaultTimeout().toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                String runtimeState = getStatus().path("state").asText();
                if ("ready".equals(runtimeState)) {
                    return true;
                }
                if ("failed".equals(runtimeState)) {
                    return false;
                }
            } catch (IOException ignored) {
                // Listener can be unavailable while the runtime initializes.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void stop() {
        try {
            super.stop();
        } finally {
            client.close();
        }
    }

    public String getName() {
        return name;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getStatusPort() {
        return statusPort;
    }

    public String getMcpUrl() {
        return "http://127.0.0.1:" + httpPort + "/mcp";
    }
}
