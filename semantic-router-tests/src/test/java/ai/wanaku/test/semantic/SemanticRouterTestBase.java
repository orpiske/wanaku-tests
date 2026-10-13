package ai.wanaku.test.semantic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.SemanticRouterManager;
import ai.wanaku.test.managers.WanakuServerManager;
import com.fasterxml.jackson.databind.JsonNode;

import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

abstract class SemanticRouterTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(SemanticRouterTestBase.class);
    protected static final String NAME = "support-semantic";
    protected static final String NAMESPACE = "semantic";
    protected static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path directory;

    protected TestConfiguration config;
    protected WanakuServerManager server;
    protected SemanticRouterManager router;
    protected ForwardsClient forwards;
    protected AuditClient audit;
    protected McpTestClient mcp;
    private SessionIdProxy sessionProxy;
    private SemanticHttpFixtures http;
    protected SemanticHttpFixtures auditProxy;
    protected String instance;
    protected String revision;
    protected String digest;

    @BeforeEach
    void requireArtifacts() {
        config = TestConfiguration.fromSystemProperties();
        requireArtifact(config.getServerBinaryPath(), "Wanaku binary");
        requireArtifact(config.getSemanticRouterJarPath(), "WSR JAR");
    }

    private static void requireArtifact(Path path, String description) {
        boolean present = path != null && Files.isRegularFile(path);
        if (!present) LOG.warn("Skipping semantic router integration test: {} missing at {}", description, path);
        assumeThat(present).as("%s required at %s", description, path).isTrue();
    }

    protected void start(boolean ingestion, Boolean runtimeAudit, Boolean catalogAudit, boolean proxy)
            throws Exception {
        config = config.toBuilder()
                .auditJson("{\"ingestion_enabled\":" + ingestion + "}")
                .build();
        server = new WanakuServerManager(config);
        server.prepare();
        server.start(getClass().getSimpleName());
        audit = new AuditClient(server.getBaseUrl(), null);
        forwards = new ForwardsClient(server.getBaseUrl(), null);
        new NamespaceClient(server.getBaseUrl(), null).create(NAMESPACE);
        http = SemanticHttpFixtures.provider();
        Path root = directory.resolve("fixtures");
        for (String resource : List.of(
                "semantic-router.properties",
                "service.properties",
                "support.camel.yaml",
                "support.dependencies.txt",
                "kamelets/wsr-billing-action.kamelet.yaml",
                "kamelets/wsr-technical-action.kamelet.yaml")) {
            Path target = root.resolve("support").resolve(resource);
            Files.createDirectories(target.getParent());
            try (var input = getClass().getResourceAsStream("/support/" + resource)) {
                if (input == null) throw new IllegalStateException("Missing WSR fixture " + resource);
                Files.copy(input, target);
            }
        }
        Path route = root.resolve("support/support.camel.yaml");
        if (catalogAudit != null) {
            String settings = "    audit:\n      enabled: " + catalogAudit
                    + "\n      experts:\n        supportExpert:\n          enabled: " + catalogAudit
                    + "\n      sinks: [memory, wanakuAudit]\n      reader: memory\n";
            Files.writeString(route, Files.readString(route).replace("- semantic:\n", "- semantic:\n" + settings));
        }
        router = new SemanticRouterManager(config);
        router.prepare(
                NAME,
                NAMESPACE,
                route,
                root.resolve("support/support.dependencies.txt"),
                directory.resolve("work"),
                server.getBaseUrl(),
                http.baseUrl());
        router.setAuditEnabled(runtimeAudit);
        if (proxy) {
            auditProxy = SemanticHttpFixtures.auditProxy(server.getBaseUrl() + "/api/v1/audit/semantic");
            router.setAuditUrl(auditProxy.baseUrl() + "/api/v1/audit/semantic");
        }
        router.start(getClass().getSimpleName());
        JsonNode forward = forwards.list().stream()
                .filter(f -> NAME.equals(f.path("name").asText()))
                .findFirst()
                .orElseThrow();
        instance = forward.path("labels").path("wsr.instance").asText();
        assertThat(instance).isNotBlank();
        JsonNode status = router.getStatus();
        revision = status.path("revision").asText();
        digest = status.path("digest").asText();
        assertThat(revision).isNotBlank();
        assertThat(digest).matches("[a-f0-9]{64}");
        connectFreshSession();
    }

    protected void connectFreshSession() throws java.io.IOException {
        if (mcp != null) {
            mcp.disconnect();
            mcp = null;
        }
        if (sessionProxy != null) {
            sessionProxy.close();
            sessionProxy = null;
        }
        sessionProxy = new SessionIdProxy(server.getMcpBaseUrl() + "/" + NAMESPACE);
        sessionProxy.start();
        mcp = new McpTestClient(sessionProxy.getBaseUrl(), null);
        mcp.connect();
    }

    protected void invoke(String message, String expected) {
        mcp.when()
                .toolsCall("route_support", Map.of("message", message), response -> {
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    assertThat(response.content().getFirst().asText().text()).contains(expected);
                })
                .thenAssertResults();
    }

    protected JsonNode events() {
        return audit.listEvents(Map.of("category", "semantic", "source_instance_id", instance))
                .path("events");
    }

    protected void awaitCounter(String counter, long count) {
        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> assertThat(
                        router.getStatus().path("audit").path(counter).asLong())
                .isEqualTo(count));
    }

    @AfterEach
    void cleanup() throws Exception {
        List<AutoCloseable> cleanup = new java.util.ArrayList<>();
        if (mcp != null) cleanup.add(mcp::disconnect);
        if (sessionProxy != null) cleanup.add(sessionProxy);
        if (router != null) cleanup.add(router::stop);
        if (auditProxy != null) cleanup.add(auditProxy);
        if (http != null) cleanup.add(http);
        if (audit != null) cleanup.add(audit);
        if (server != null) cleanup.add(server::stop);
        Exception failure = null;
        for (AutoCloseable resource : cleanup) {
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
