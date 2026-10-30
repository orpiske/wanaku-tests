package ai.wanaku.test.router;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class ForwardsCliITCase extends RouterTestBase {

    private CLIExecutor cliExecutor;
    private String authToken;
    private String nsId;

    @BeforeEach
    void setupCli() {
        cliExecutor = CLIExecutor.createDefault();
        assertThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();
        assertThat(isServerRunning()).as("Router must be available").isTrue();

        nsId = getOrCreateNamespace("fwd-cli-test-ns");
        assumeThat(nsId).as("Test namespace must be available").isNotNull();
    }

    @DisplayName("Add a forward via CLI and verify it exists via REST")
    @Test
    void shouldAddForwardViaCli() throws Exception {
        String name = "test-fwd";
        String targetMcpUrl = startTargetMcpServer();

        CLIResult result = executeWithAuth(
                "forwards",
                "add",
                "--host",
                getRouterHost(),
                "--name",
                name,
                "--service",
                targetMcpUrl,
                "--namespace-name",
                "fwd-cli-test-ns");

        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(forwardsClient.exists(name)).isTrue();
    }

    @DisplayName("List forwards via CLI returns valid output")
    @Test
    void shouldListForwardsViaCli() {
        CLIResult result = executeWithAuth("forwards", "list", "--host", getRouterHost());

        assertThat(result.isSuccess())
                .as("CLI list should succeed: %s", result.getCombinedOutput())
                .isTrue();
    }

    @DisplayName("Remove a forward via CLI")
    @Test
    void shouldRemoveForwardViaCli() throws Exception {
        forwardsClient.add("cli-remove-fwd", startTargetMcpServer(), nsId);
        assertThat(forwardsClient.exists("cli-remove-fwd")).isTrue();

        CLIResult result = executeWithAuth("forwards", "remove", "--host", getRouterHost(), "--name", "cli-remove-fwd");

        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(forwardsClient.exists("cli-remove-fwd")).isFalse();
    }

    private String getRouterHost() {
        return getServerBaseUrl();
    }

    private CLIResult executeWithAuth(String... args) {
        if (authToken != null) {
            String[] authArgs = new String[args.length + 2];
            System.arraycopy(args, 0, authArgs, 0, args.length);
            authArgs[args.length] = "--token";
            authArgs[args.length + 1] = authToken;
            return cliExecutor.execute(authArgs);
        }
        return cliExecutor.execute(args);
    }
}
