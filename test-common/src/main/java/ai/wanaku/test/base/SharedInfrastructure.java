package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.KeycloakManager;
import ai.wanaku.test.managers.PraxisManager;
import ai.wanaku.test.managers.RouterManager;

import org.junit.jupiter.api.extension.ExtensionContext;

public class SharedInfrastructure implements ExtensionContext.Store.CloseableResource {

    private static final Logger LOG = LoggerFactory.getLogger(SharedInfrastructure.class);

    private TestConfiguration config;
    private KeycloakManager keycloakManager;
    private RouterManager routerManager;
    private PraxisManager praxisManager;
    private Path tempDataDir;

    SharedInfrastructure() {}

    void start() throws Exception {
        LOG.info("=== Starting shared infrastructure (once per module) ===");

        tempDataDir = Files.createTempDirectory("wanaku-test-");
        LOG.debug("Created shared temp directory: {}", tempDataDir);

        TestConfiguration baseConfig = TestConfiguration.fromSystemProperties();
        config = TestConfiguration.builder()
                .artifactsDir(baseConfig.getArtifactsDir())
                .routerJarPath(baseConfig.getRouterJarPath())
                .praxisBinaryPath(baseConfig.getPraxisBinaryPath())
                .httpToolServiceJarPath(baseConfig.getHttpToolServiceJarPath())
                .fileProviderJarPath(baseConfig.getFileProviderJarPath())
                .camelCapabilityJarPath(baseConfig.getCamelCapabilityJarPath())
                .tempDataDir(tempDataDir)
                .defaultTimeout(baseConfig.getDefaultTimeout())
                .build();

        LOG.debug("Router JAR: {}", config.getRouterJarPath());
        LOG.debug("Praxis binary: {}", config.getPraxisBinaryPath());
        LOG.debug("HTTP Capability JAR: {}", config.getHttpToolServiceJarPath());

        if (shouldSkipInfrastructure()) {
            LOG.info("Skipping infrastructure setup (no JARs or binary available)");
            return;
        }

        if (config.isPraxisMode()) {
            startPraxisMode();
        } else {
            startRouterMode();
        }

        LOG.info("=== Shared infrastructure ready ===");
    }

    private void startPraxisMode() throws Exception {
        LOG.info("Starting in PRAXIS mode");

        praxisManager = new PraxisManager(config);
        praxisManager.prepare();
        praxisManager.start("shared");
        LOG.info(
                "Praxis started on management port {} and MCP port {}",
                praxisManager.getHttpPort(),
                praxisManager.getMcpPort());
    }

    private void startRouterMode() throws Exception {
        LOG.info("Starting in ROUTER mode");

        keycloakManager = new KeycloakManager();
        try {
            keycloakManager.start();
        } catch (Exception e) {
            LOG.warn("Keycloak startup failed, Router will run without authentication: {}", e.getMessage());
            keycloakManager = null;
        }

        if (config.getRouterJarPath() != null
                && config.getRouterJarPath().toFile().exists()) {
            routerManager = new RouterManager(config);
            routerManager.prepare();
            routerManager.start("shared");
            LOG.info("Router started on port {}", routerManager.getHttpPort());
        } else {
            LOG.warn("Router JAR not found at {}, skipping Router startup", config.getRouterJarPath());
        }
    }

    @Override
    public void close() {
        LOG.info("=== Tearing down shared infrastructure ===");

        if (praxisManager != null) {
            praxisManager.stop();
        }

        if (routerManager != null) {
            routerManager.stop();
        }

        if (keycloakManager != null) {
            keycloakManager.stop();
        }

        if (tempDataDir != null) {
            try {
                deleteRecursively(tempDataDir);
            } catch (IOException e) {
                LOG.warn("Failed to cleanup shared temp directory: {}", e.getMessage());
            }
        }

        LOG.info("=== Shared infrastructure teardown complete ===");
    }

    public TestConfiguration getConfig() {
        return config;
    }

    public KeycloakManager getKeycloakManager() {
        return keycloakManager;
    }

    public RouterManager getRouterManager() {
        return routerManager;
    }

    public PraxisManager getPraxisManager() {
        return praxisManager;
    }

    public Path getTempDataDir() {
        return tempDataDir;
    }

    public String getBaseUrl() {
        if (praxisManager != null) {
            return praxisManager.getBaseUrl();
        }
        if (routerManager != null) {
            return routerManager.getBaseUrl();
        }
        return null;
    }

    public String getMcpBaseUrl() {
        if (praxisManager != null) {
            return praxisManager.getMcpBaseUrl();
        }
        if (routerManager != null) {
            return routerManager.getMcpBaseUrl();
        }
        return null;
    }

    public int getHttpPort() {
        if (praxisManager != null) {
            return praxisManager.getHttpPort();
        }
        if (routerManager != null) {
            return routerManager.getHttpPort();
        }
        return -1;
    }

    public boolean isServerRunning() {
        if (praxisManager != null) {
            return praxisManager.isRunning();
        }
        if (routerManager != null) {
            return routerManager.isRunning();
        }
        return false;
    }

    private boolean shouldSkipInfrastructure() {
        if (config.isPraxisMode()) {
            return false;
        }
        Path artifactsDir = Path.of(System.getProperty("wanaku.test.artifacts.dir", "artifacts"));
        return !Files.exists(artifactsDir) || !hasJars(artifactsDir);
    }

    private boolean hasJars(Path dir) {
        try {
            return Files.list(dir).anyMatch(p -> {
                if (p.toString().endsWith(".jar")) {
                    return true;
                }
                if (Files.isDirectory(p)) {
                    Path quarkusRunJar = p.resolve("quarkus-run.jar");
                    return Files.exists(quarkusRunJar);
                }
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (IOException e) {
                        LOG.warn("Failed to delete: {}", p);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
