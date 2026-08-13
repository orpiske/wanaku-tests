package ai.wanaku.test.router;

import java.time.Duration;
import org.awaitility.Awaitility;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.managers.CamelCapabilityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@Disabled("Not applicable to praxis")
class CapabilityResilienceITCase extends RouterTestBase {

    private CamelCapabilityManager resilienceCapability;

    @BeforeEach
    void assumeRouterModeAvailable() {
        assumeThat(false).as("Capability resilience tests require router mode").isFalse();
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
    }

    @AfterEach
    void stopResilienceCapability() {
        if (resilienceCapability != null) {
            try {
                resilienceCapability.stop();
            } catch (Exception e) {
                // ignore
            }
            resilienceCapability = null;
        }
    }

    @DisplayName("Detect that a capability is no longer registered after it stops")
    @Test
    void shouldDetectCapabilityStoppage() throws Exception {
        resilienceCapability = new CamelCapabilityManager(config);
        resilienceCapability.prepare("resilience-test", "file:///dev/null", null, null);
        resilienceCapability.start("resilience-test");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(WanakuTestConstants.DEFAULT_HEALTH_CHECK_INTERVAL)
                .until(() -> routerClient.isCapabilityRegistered("resilience-test"));

        assertThat(routerClient.isCapabilityRegistered("resilience-test")).isTrue();

        resilienceCapability.stop();
        resilienceCapability = null;

        routerClient.deregisterCapability("resilience-test", null);

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(WanakuTestConstants.DEFAULT_HEALTH_CHECK_INTERVAL)
                .until(() -> !routerClient.isCapabilityRegistered("resilience-test"));
    }

    @DisplayName("Capability can re-register after being stopped and restarted")
    @Test
    void shouldReRegisterAfterRestart() throws Exception {
        resilienceCapability = new CamelCapabilityManager(config);
        resilienceCapability.prepare("resilience-restart", "file:///dev/null", null, null);
        resilienceCapability.start("resilience-restart-test");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(WanakuTestConstants.DEFAULT_HEALTH_CHECK_INTERVAL)
                .until(() -> routerClient.isCapabilityRegistered("resilience-restart"));

        resilienceCapability.stop();

        routerClient.deregisterCapability("resilience-restart", null);

        resilienceCapability = new CamelCapabilityManager(config);
        resilienceCapability.prepare("resilience-restart-2", "file:///dev/null", null, null);
        resilienceCapability.start("resilience-restart-test-2");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(WanakuTestConstants.DEFAULT_HEALTH_CHECK_INTERVAL)
                .until(() -> routerClient.isCapabilityRegistered("resilience-restart-2"));

        assertThat(routerClient.isCapabilityRegistered("resilience-restart-2")).isTrue();
    }
}
