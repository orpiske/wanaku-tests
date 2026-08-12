package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Path;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.client.ServiceClient;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.HttpCapabilityManager;
import ai.wanaku.test.managers.KeycloakManager;
import ai.wanaku.test.managers.PraxisManager;
import ai.wanaku.test.managers.RouterManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({SharedInfrastructureExtension.class, SkipThresholdExtension.class})
public abstract class BaseIntegrationTest {

    private static Logger LOG = LoggerFactory.getLogger(BaseIntegrationTest.class);

    protected static TestConfiguration config;
    protected static KeycloakManager keycloakManager;
    protected static RouterManager routerManager;
    protected static PraxisManager praxisManager;
    protected static Path tempDataDir;

    protected HttpCapabilityManager httpCapabilityManager;
    protected McpTestClient mcpClient;
    protected RouterClient routerClient;
    protected ServiceClient serviceClient;
    protected String testName;

    @BeforeAll
    static void setupSuiteInfrastructure(TestInfo testInfo) {
        Class<?> testClass = testInfo.getTestClass().orElse(BaseIntegrationTest.class);
        LOG = LoggerFactory.getLogger(testClass);
        LOG.info("=== Test class starting: {} (reusing shared infrastructure) ===", testClass.getSimpleName());
    }

    @BeforeEach
    void setupTestInfrastructure(TestInfo testInfo) throws IOException {
        testName = testInfo.getDisplayName();
        String testMethodName = testInfo.getTestMethod().map(m -> m.getName()).orElse("unknown");
        LOG.info("[{}] >>> {}", testMethodName, testName);

        if (isServerRunning()) {
            String baseUrl = getServerBaseUrl();
            String mcpBaseUrl = getServerMcpBaseUrl();

            String accessToken = null;
            if (!isPraxisMode() && keycloakManager != null && keycloakManager.isRunning()) {
                accessToken = keycloakManager.getMcpToken();
                LOG.debug("Obtained MCP access token with wanaku-mcp-client scope");
            }

            routerClient = new RouterClient(baseUrl, accessToken);
            serviceClient = new ServiceClient(baseUrl, accessToken);

            try {
                mcpClient = new McpTestClient(mcpBaseUrl, accessToken);
                mcpClient.connect();
                LOG.debug("MCP client connected to {}", mcpBaseUrl);
            } catch (Exception e) {
                LOG.warn("Failed to connect MCP client: {}", e.getMessage());
                mcpClient = null;
            }
        }

        if (config.getHttpToolServiceJarPath() != null
                && config.getHttpToolServiceJarPath().toFile().exists()
                && isServerRunning()) {

            httpCapabilityManager = new HttpCapabilityManager(config);

            if (isPraxisMode()) {
                httpCapabilityManager.prepareStandalone();
            } else {
                OidcCredentials oidcCredentials = null;
                if (keycloakManager != null && keycloakManager.isRunning()) {
                    oidcCredentials = keycloakManager.getServiceCredentials();
                }
                httpCapabilityManager.prepare(new TargetConfiguration(
                        "localhost", getServerHttpPort(), getServerGrpcPort(), oidcCredentials));
            }

            String profile = getLogProfile();
            String testClassName =
                    testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");
            httpCapabilityManager.setLogContext(profile, testClassName, testMethodName);

            httpCapabilityManager.start(testName);

            if (isPraxisMode()) {
                serviceClient.register("http", "localhost:" + httpCapabilityManager.getGrpcPort(), "tool-invoker");
                LOG.debug("Registered HTTP capability service with praxis");
            }

            LOG.debug("Waiting for HTTP Capability registration...");
            Awaitility.await()
                    .pollInterval(WanakuTestConstants.DEFAULT_REGISTRATION_POLL_INTERVAL)
                    .until(() -> routerClient.isCapabilityRegistered("http"));
            LOG.debug("HTTP Capability is registered");
        }

        LOG.debug("Test infrastructure ready: {}", testName);
    }

    @AfterEach
    void teardownTestInfrastructure() throws IOException {
        LOG.debug("Tearing down test: {}", testName);

        if (httpCapabilityManager != null) {
            if (isPraxisMode() && serviceClient != null) {
                try {
                    serviceClient.remove("http");
                } catch (Exception e) {
                    LOG.warn("Failed to deregister HTTP service: {}", e.getMessage());
                }
            }
            httpCapabilityManager.stop();
            httpCapabilityManager = null;
        }

        if (mcpClient != null) {
            try {
                mcpClient.disconnect();
            } catch (Exception e) {
                LOG.warn("Failed to disconnect MCP client: {}", e.getMessage());
            }
            mcpClient = null;
        }

        if (routerClient != null) {
            try {
                routerClient.clearAllTools();
            } catch (Exception e) {
                LOG.warn("Failed to clear tools: {}", e.getMessage());
            }
        }

        LOG.debug("Test teardown complete: {}", testName);
    }

    protected boolean isPraxisMode() {
        return config != null && config.isPraxisMode();
    }

    protected boolean isRouterAvailable() {
        return isServerRunning();
    }

    protected boolean isServerRunning() {
        if (praxisManager != null && praxisManager.isRunning()) {
            return true;
        }
        return routerManager != null && routerManager.isRunning();
    }

    protected String getServerBaseUrl() {
        if (praxisManager != null && praxisManager.isRunning()) {
            return praxisManager.getBaseUrl();
        }
        if (routerManager != null && routerManager.isRunning()) {
            return routerManager.getBaseUrl();
        }
        return null;
    }

    protected String getServerMcpBaseUrl() {
        if (praxisManager != null && praxisManager.isRunning()) {
            return praxisManager.getMcpBaseUrl();
        }
        if (routerManager != null && routerManager.isRunning()) {
            return routerManager.getMcpBaseUrl();
        }
        return null;
    }

    protected int getServerHttpPort() {
        if (praxisManager != null && praxisManager.isRunning()) {
            return praxisManager.getHttpPort();
        }
        if (routerManager != null && routerManager.isRunning()) {
            return routerManager.getHttpPort();
        }
        return -1;
    }

    protected int getServerGrpcPort() {
        if (routerManager != null && routerManager.isRunning()) {
            return routerManager.getGrpcPort();
        }
        return -1;
    }

    protected boolean isHttpToolServiceAvailable() {
        if (httpCapabilityManager != null && httpCapabilityManager.isRunning()) {
            return true;
        }
        return isServerRunning()
                && config != null
                && config.getHttpToolServiceJarPath() != null
                && config.getHttpToolServiceJarPath().toFile().exists();
    }

    protected boolean isFullStackAvailable() {
        return isServerRunning() && isHttpToolServiceAvailable();
    }

    protected boolean isMcpClientAvailable() {
        if (mcpClient != null) {
            return true;
        }
        return isServerRunning();
    }

    protected String getLogProfile() {
        return "default";
    }
}
