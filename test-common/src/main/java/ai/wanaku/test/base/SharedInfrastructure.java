package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.PraxisManager;

import org.junit.jupiter.api.extension.ExtensionContext;

public class SharedInfrastructure implements ExtensionContext.Store.CloseableResource {

    private static final Logger LOG = LoggerFactory.getLogger(SharedInfrastructure.class);

    private TestConfiguration config;
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
                .praxisBinaryPath(baseConfig.getPraxisBinaryPath())
                .camelCapabilityJarPath(baseConfig.getCamelCapabilityJarPath())
                .tempDataDir(tempDataDir)
                .defaultTimeout(baseConfig.getDefaultTimeout())
                .build();

        LOG.debug("Praxis binary: {}", config.getPraxisBinaryPath());

        String externalMgmtPort = System.getProperty("wanaku.test.external.mgmt.port");
        String externalMcpPort = System.getProperty("wanaku.test.external.mcp.port");

        if (externalMgmtPort != null && externalMcpPort != null) {
            praxisManager = PraxisManager.external(
                    config, Integer.parseInt(externalMgmtPort), Integer.parseInt(externalMcpPort));
            LOG.info("Using external praxis on management port {} and MCP port {}", externalMgmtPort, externalMcpPort);
            LOG.info("=== Shared infrastructure ready (external) ===");
            return;
        }

        if (config.getPraxisBinaryPath() == null
                || !config.getPraxisBinaryPath().toFile().exists()) {
            LOG.info("Praxis binary not available, skipping infrastructure setup");
            return;
        }

        praxisManager = new PraxisManager(config);
        praxisManager.prepare();
        praxisManager.start("shared");
        LOG.info(
                "Praxis started on management port {} and MCP port {}",
                praxisManager.getHttpPort(),
                praxisManager.getMcpPort());

        LOG.info("=== Shared infrastructure ready ===");
    }

    @Override
    public void close() {
        LOG.info("=== Tearing down shared infrastructure ===");

        if (praxisManager != null) {
            praxisManager.stop();
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

    public PraxisManager getPraxisManager() {
        return praxisManager;
    }

    public Path getTempDataDir() {
        return tempDataDir;
    }

    public String getBaseUrl() {
        return praxisManager != null ? praxisManager.getBaseUrl() : null;
    }

    public String getMcpBaseUrl() {
        return praxisManager != null ? praxisManager.getMcpBaseUrl() : null;
    }

    public int getHttpPort() {
        return praxisManager != null ? praxisManager.getHttpPort() : -1;
    }

    public boolean isServerRunning() {
        return praxisManager != null && praxisManager.isRunning();
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
