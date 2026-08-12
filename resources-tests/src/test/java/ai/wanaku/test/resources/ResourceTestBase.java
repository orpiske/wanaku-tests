package ai.wanaku.test.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.client.ServiceClient;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.managers.ResourceProviderManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;

public abstract class ResourceTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceTestBase.class);

    protected static ResourceProviderManager resourceProviderManager;

    @BeforeAll
    static void startFileProvider(TestInfo testInfo) throws Exception {
        if (!isFileProviderAvailable()) {
            LOG.warn("File provider JAR not available, skipping provider startup");
            return;
        }

        boolean praxis = config != null && config.isPraxisMode();

        if (!praxis && (routerManager == null || !routerManager.isRunning())) {
            LOG.warn("Router not running, skipping file provider startup");
            return;
        }

        if (praxis && (praxisManager == null || !praxisManager.isRunning())) {
            LOG.warn("Praxis not running, skipping file provider startup");
            return;
        }

        String testClassName = testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");

        resourceProviderManager = new ResourceProviderManager(config);

        if (praxis) {
            resourceProviderManager.prepareStandalone();
        } else {
            OidcCredentials oidcCredentials = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                oidcCredentials = keycloakManager.getServiceCredentials();
            }
            resourceProviderManager.prepare(new TargetConfiguration(
                    "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials));
        }

        resourceProviderManager.setLogContext("file-provider", testClassName, "file-provider");
        resourceProviderManager.start(testClassName);

        String baseUrl;
        String accessToken = null;
        if (praxis) {
            baseUrl = praxisManager.getBaseUrl();
            ServiceClient svcClient = new ServiceClient(baseUrl, null);
            svcClient.register("file", "localhost:" + resourceProviderManager.getGrpcPort(), "resource-provider");
            LOG.debug("Registered file provider service with praxis");
        } else {
            baseUrl = routerManager.getBaseUrl();
            if (keycloakManager != null && keycloakManager.isRunning()) {
                accessToken = keycloakManager.getMcpToken();
            }
        }

        LOG.debug("Waiting for file provider registration...");
        RouterClient client = new RouterClient(baseUrl, accessToken);
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(WanakuTestConstants.DEFAULT_REGISTRATION_POLL_INTERVAL)
                .until(() -> client.isCapabilityRegistered("file"));
        LOG.info("File provider is registered");
    }

    @AfterAll
    static void stopFileProvider() {
        if (resourceProviderManager != null) {
            boolean praxis = config != null && config.isPraxisMode();
            if (praxis && praxisManager != null && praxisManager.isRunning()) {
                try {
                    ServiceClient svcClient = new ServiceClient(praxisManager.getBaseUrl(), null);
                    svcClient.remove("file");
                } catch (Exception e) {
                    LOG.warn("Failed to deregister file provider service: {}", e.getMessage());
                }
            }
            resourceProviderManager.stop();
            resourceProviderManager = null;
        }
    }

    @AfterEach
    void clearResources() {
        if (routerClient != null) {
            try {
                routerClient.clearAllResources();
            } catch (Exception e) {
                LOG.warn("Failed to clear resources: {}", e.getMessage());
            }
        }
    }

    @Override
    protected String getLogProfile() {
        return "file-provider";
    }

    protected Path createTestFile(String filename, String content) throws IOException {
        Path file = tempDataDir.resolve(filename);
        Files.writeString(file, content);
        LOG.debug("Created test file: {}", file);
        return file;
    }

    protected static boolean isFileProviderAvailable() {
        if (config == null) {
            LOG.warn("A configuration was not provided");
            return false;
        }
        return isFileAvailable(config.getFileProviderJarPath());
    }

    protected static boolean isFileAvailable(Path path) {
        if (path == null) {
            LOG.warn("Couldn't determine the path to the jar (config returned null)");
            return false;
        }

        final boolean exists = path.toFile().exists();
        if (!exists) {
            LOG.warn("The expected file doesn't exist at the location {}", path);
            return false;
        }

        return true;
    }
}
