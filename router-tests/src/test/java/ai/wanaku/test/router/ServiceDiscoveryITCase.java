package ai.wanaku.test.router;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import ai.wanaku.test.WanakuTestConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class ServiceDiscoveryITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(routerClient).as("RouterClient must be available").isNotNull();
    }

    @DisplayName("List services endpoint returns a successful response")
    @Test
    void shouldListServices() throws Exception {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(getServerBaseUrl() + WanakuTestConstants.SERVICES_PATH))
                .GET()
                .timeout(Duration.ofSeconds(30));

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
    }
}
