package ai.wanaku.test.managers;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import ai.wanaku.test.config.TestConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticRouterManagerTest {
    private SemanticRouterManager manager;

    @AfterEach
    void closeManager() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void localLaunchUsesAbsoluteArtifactsAndIndependentPorts() {
        Path jar = Path.of("artifacts/wanaku-semantic-router/router.jar");
        manager = new SemanticRouterManager(
                TestConfiguration.builder().semanticRouterJarPath(jar).build());
        manager.prepare(
                "support",
                "default",
                Path.of("fixtures/routes.yaml"),
                Path.of("fixtures/dependencies.txt"),
                Path.of("target/wsr"),
                "http://localhost:8080",
                "http://localhost:9000");
        assertThat(manager.getHttpPort()).isNotEqualTo(manager.getStatusPort());
        assertThat(manager.buildCommand())
                .containsSubsequence("-jar", jar.toAbsolutePath().toString(), "runtime");
        assertThat(manager.getProcessArguments())
                .containsSubsequence(
                        "--routes-ref",
                        Path.of("fixtures/routes.yaml").toAbsolutePath().toUri().toString());
        assertThat(manager.getProcessArguments())
                .doesNotContain("--audit-enabled=true", "--audit-enabled=false", "--audit-url");
        manager.setAuditEnabled(false);
        manager.setAuditUrl("http://localhost:8080/api/v1/audit/semantic");
        assertThat(manager.getProcessArguments())
                .contains("--audit-enabled=false")
                .containsSubsequence("--audit-url", "http://localhost:8080/api/v1/audit/semantic");
    }

    @Test
    void clearsAmbientDeploymentAndProviderSettingsBeforeApplyingExplicitOverrides() {
        manager = new SemanticRouterManager(TestConfiguration.builder().build());
        Map<String, String> variables =
                new HashMap<>(Map.of("WSR_SEMANTIC_ROUTE", "ambient", "TYPESAFE_API_KEY", "ambient", "PATH", "path"));
        manager.addEnvironmentVariable("WSR_HTTP_TIMEOUT_MS", "500");
        manager.configureEnvironment(variables);
        assertThat(variables).containsExactlyInAnyOrderEntriesOf(Map.of("PATH", "path", "WSR_HTTP_TIMEOUT_MS", "500"));
    }
}
