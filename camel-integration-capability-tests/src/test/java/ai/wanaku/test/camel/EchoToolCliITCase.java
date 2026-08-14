package ai.wanaku.test.camel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;
import ai.wanaku.test.fixtures.TestFixtures;
import ai.wanaku.test.managers.CamelCapabilityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI tests for forward-based tool lifecycle.
 *
 * <p>Uses the CLI to add/list/remove forwards and verify tool discovery,
 * with a CIC instance providing the echo tool.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EchoToolCliITCase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(EchoToolCliITCase.class);
    private static final String SERVICE_NAME = "cli-echo-svc";
    private static final Path FIXTURES_TARGET_DIR = Path.of("target", "test-fixtures");

    private CLIExecutor cliExecutor;
    private CamelCapabilityManager cicManager;

    @BeforeEach
    void setup() throws Exception {
        assertThat(isServerRunning()).as("Router must be available").isTrue();
        cliExecutor = CLIExecutor.createDefault();
        assertThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();

        Files.createDirectories(FIXTURES_TARGET_DIR);

        if (cicManager == null || !cicManager.isRunning()) {
            Path fixtureDir = TestFixtures.load("simple-tool", FIXTURES_TARGET_DIR);
            Path routesRef = fixtureDir.resolve("routes.camel.yaml");

            cicManager = new CamelCapabilityManager(config);
            cicManager.prepare(SERVICE_NAME, "file://" + routesRef.toAbsolutePath(), null);
            cicManager.setLogContext("camel-capability", getClass().getSimpleName(), SERVICE_NAME);
            cicManager.start(SERVICE_NAME);
        }
    }

    @AfterEach
    void teardown() {
        cliExecutor.execute("forwards", "remove", "--host", getServerBaseUrl(), "--name", SERVICE_NAME);

        if (cicManager != null) {
            cicManager.stop();
            cicManager = null;
        }
    }

    @DisplayName("Add a forward via CLI and verify it appears in the list")
    @Test
    @Order(1)
    void shouldAddForwardViaCli() {
        CLIResult result = cliExecutor.execute(
                "forwards",
                "add",
                "--host",
                getServerBaseUrl(),
                "--name",
                SERVICE_NAME,
                "--service",
                cicManager.getMcpUrl(),
                "-N",
                "default");

        LOG.info("forwards add output: {}", result.getCombinedOutput());
        assertThat(result.isSuccess())
                .as("CLI forwards add should succeed: %s", result.getCombinedOutput())
                .isTrue();

        CLIResult listResult = cliExecutor.execute("forwards", "list", "--host", getServerBaseUrl());

        LOG.info("forwards list output: {}", listResult.getCombinedOutput());
        assertThat(listResult.isSuccess()).isTrue();
        assertThat(listResult.getCombinedOutput()).contains(SERVICE_NAME);
    }

    @DisplayName("List tools via CLI after forward registration and verify echo tool appears")
    @Test
    @Order(2)
    void shouldListDiscoveredToolViaCli() {
        cliExecutor.execute(
                "forwards",
                "add",
                "--host",
                getServerBaseUrl(),
                "--name",
                SERVICE_NAME,
                "--service",
                cicManager.getMcpUrl(),
                "-N",
                "default");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    CLIResult result = cliExecutor.execute("tools", "list", "--host", getServerBaseUrl());
                    return result.isSuccess() && result.getCombinedOutput().contains("echo");
                });

        CLIResult result = cliExecutor.execute("tools", "list", "--host", getServerBaseUrl());
        LOG.info("tools list output: {}", result.getCombinedOutput());
        assertThat(result.getCombinedOutput()).contains("echo");
    }

    @DisplayName("Show echo tool details via CLI")
    @Test
    @Order(3)
    void shouldShowToolDetailsViaCli() {
        cliExecutor.execute(
                "forwards",
                "add",
                "--host",
                getServerBaseUrl(),
                "--name",
                SERVICE_NAME,
                "--service",
                cicManager.getMcpUrl(),
                "-N",
                "default");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    CLIResult result = cliExecutor.execute("tools", "list", "--host", getServerBaseUrl());
                    return result.isSuccess() && result.getCombinedOutput().contains("echo");
                });

        CLIResult result = cliExecutor.execute("tools", "show", "--host", getServerBaseUrl(), "echo");

        LOG.info("tools show output: {}", result.getCombinedOutput());
        assertThat(result.isSuccess())
                .as("CLI tools show should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(result.getCombinedOutput()).contains("echo");
    }

    @DisplayName("Remove forward via CLI and verify tool disappears")
    @Test
    @Order(4)
    void shouldRemoveForwardAndToolViaCli() {
        cliExecutor.execute(
                "forwards",
                "add",
                "--host",
                getServerBaseUrl(),
                "--name",
                SERVICE_NAME,
                "--service",
                cicManager.getMcpUrl(),
                "-N",
                "default");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    CLIResult result = cliExecutor.execute("tools", "list", "--host", getServerBaseUrl());
                    return result.isSuccess() && result.getCombinedOutput().contains("echo");
                });

        CLIResult removeResult =
                cliExecutor.execute("forwards", "remove", "--host", getServerBaseUrl(), "--name", SERVICE_NAME);

        LOG.info("forwards remove output: {}", removeResult.getCombinedOutput());
        assertThat(removeResult.isSuccess())
                .as("CLI forwards remove should succeed: %s", removeResult.getCombinedOutput())
                .isTrue();

        CLIResult toolsResult = cliExecutor.execute("tools", "list", "--host", getServerBaseUrl());
        LOG.info("tools list after remove: {}", toolsResult.getCombinedOutput());
        assertThat(toolsResult.getCombinedOutput()).doesNotContain("echo");
    }

    @DisplayName("CLI should return non-zero exit code for invalid subcommand")
    @Test
    @Order(5)
    void shouldFailWithInvalidSubcommand() {
        CLIResult result = cliExecutor.execute("forwards", "invalid-subcommand");
        assertThat(result.isSuccess()).isFalse();
    }
}
