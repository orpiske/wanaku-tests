package ai.wanaku.test.cross;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.fixtures.TestFixtures;
import ai.wanaku.test.managers.CamelCapabilityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class CrossCapabilityTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(CrossCapabilityTestBase.class);
    private static final Path FIXTURES_TARGET_DIR = Path.of("target", "test-fixtures");

    protected CamelCapabilityManager camelCapabilityManager;

    @BeforeEach
    void setupCrossCapabilityInfrastructure() throws IOException {
        Files.createDirectories(FIXTURES_TARGET_DIR);
    }

    @AfterEach
    void teardownCrossCapabilityInfrastructure() {
        stopCamelCapability();
        clearRouterState();
    }

    protected CamelCapabilityManager startCamelCapability(String serviceName, String fixtureName) throws Exception {
        Path fixtureDir = TestFixtures.load(fixtureName, FIXTURES_TARGET_DIR);
        Path routesRef = fixtureDir.resolve("routes.camel.yaml");
        Path rulesRef = fixtureDir.resolve("rules.yaml");

        camelCapabilityManager = new CamelCapabilityManager(config);
        camelCapabilityManager.prepare(
                serviceName,
                "file://" + routesRef.toAbsolutePath(),
                rulesRef.toFile().exists() ? "file://" + rulesRef.toAbsolutePath() : null,
                null);
        camelCapabilityManager.setLogContext("camel-capability", getClass().getSimpleName(), serviceName);
        camelCapabilityManager.start(serviceName);

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> routerClient.isCapabilityRegistered(serviceName));
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> routerClient.listTools().stream().anyMatch(t -> serviceName.equals(t.getType())));
        return camelCapabilityManager;
    }

    protected void clearRouterState() {
        if (routerClient == null) {
            return;
        }

        try {
            routerClient.clearAllTools();
        } catch (Exception e) {
            LOG.warn("Failed to clear tools: {}", e.getMessage());
        }

        try {
            routerClient.clearAllResources();
        } catch (Exception e) {
            LOG.warn("Failed to clear resources: {}", e.getMessage());
        }
    }

    protected void stopCamelCapability() {
        if (camelCapabilityManager == null) {
            return;
        }

        try {
            camelCapabilityManager.stop();
        } catch (Exception e) {
            LOG.warn("Failed to stop Camel capability: {}", e.getMessage());
        } finally {
            camelCapabilityManager = null;
        }
    }

    protected Path createTestFile(String filename, String content) throws IOException {
        Path file = tempDataDir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    protected boolean isCamelCapabilityAvailable() {
        return config != null
                && config.getCamelCapabilityJarPath() != null
                && config.getCamelCapabilityJarPath().toFile().exists();
    }

    protected OidcCredentials getOidcCredentials() {
        if (!isPraxisMode() && keycloakManager != null && keycloakManager.isRunning()) {
            return keycloakManager.getServiceCredentials();
        }
        return null;
    }

    @Override
    protected String getLogProfile() {
        return "cross-capability";
    }
}
