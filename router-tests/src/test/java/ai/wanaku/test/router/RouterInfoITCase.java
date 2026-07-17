package ai.wanaku.test.router;

import io.quarkus.test.junit.QuarkusTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class RouterInfoITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(managementClient).as("ManagementClient must be available").isNotNull();
    }

    @DisplayName("Return router info from management endpoint")
    @Test
    void shouldReturnRouterInfo() {
        JsonNode info = managementClient.getInfo();

        assertThat(info).isNotNull();
        assertThat(info.isEmpty()).isFalse();
    }

    @DisplayName("Return router statistics from management endpoint")
    @Test
    void shouldReturnRouterStatistics() {
        JsonNode statistics = managementClient.getStatistics();

        assertThat(statistics).isNotNull();
    }

    @DisplayName("Indicate management API is available")
    @Test
    void shouldIndicateManagementApiAvailable() {
        boolean available = managementClient.isAvailable();

        assertThat(available).isTrue();
    }
}
