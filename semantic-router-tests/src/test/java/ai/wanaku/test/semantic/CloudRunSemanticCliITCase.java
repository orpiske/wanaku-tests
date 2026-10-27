package ai.wanaku.test.semantic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;
import ai.wanaku.test.utils.PortUtils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Exercises the input contract of {@code wanaku cloud run semantic} (wanaku-barn#271) without a cluster. The
 * kubeconfig points at a closed local port: a valid input reaches the cluster and fails with exit code 3, so exit
 * code 2 shows that the command rejected the input before it contacted the cluster.
 */
@Timeout(120)
class CloudRunSemanticCliITCase {
    private static final Logger LOG = LoggerFactory.getLogger(CloudRunSemanticCliITCase.class);
    private static final int EXIT_INVALID_INPUT = 2;
    private static final int EXIT_TARGET_UNREACHABLE = 3;
    private static final String NAMESPACE = "itcase-namespace";
    private static final String ROUTER = "itcase-router";
    private static final List<String> FIXTURES = List.of(
            "semantic-router.properties",
            "service.properties",
            "support.camel.yaml",
            "support.dependencies.txt",
            "kamelets/wsr-billing-action.kamelet.yaml",
            "kamelets/wsr-technical-action.kamelet.yaml");

    @TempDir
    Path directory;

    private CLIExecutor cli;
    private Path support;
    private Path route;
    private Path manifest;

    @BeforeAll
    static void requireCloudRunSemantic() {
        CLIExecutor cli = CLIExecutor.createDefault();
        assumeThat(cli.isAvailable()).as("Wanaku CLI must be available").isTrue();
        // A CLI without the command (before wanaku-barn#271) prints the help of "cloud run" instead, with exit code 0
        CLIResult help = cli.execute("cloud", "run", "semantic", "--help");
        boolean supported =
                help.getExitCode() == 0 && help.getCombinedOutput().contains("Usage: wanaku cloud run semantic");
        if (!supported) {
            LOG.warn("Skipping cloud run semantic tests: the CLI has no such command: {}", help.getCombinedOutput());
        }
        assumeThat(supported)
                .as("Wanaku CLI must have the cloud run semantic command")
                .isTrue();
    }

    @BeforeEach
    void setUp() throws IOException {
        cli = CLIExecutor.createDefault();
        // A private WANAKU_HOME keeps a stored cloud target out of the other tests
        cli.setEnvironment("WANAKU_HOME", directory.resolve("wanaku-home").toString());
        cli.setEnvironment("KUBECONFIG", writeUnreachableKubeconfig().toString());
        // An inherited KUBERNETES_MASTER would replace the kubeconfig server; the client ignores an empty value
        cli.setEnvironment("KUBERNETES_MASTER", "");
        // Without this, the client retries the refused connection for about 20 seconds per command
        cli.setEnvironment("KUBERNETES_REQUEST_RETRY_BACKOFFLIMIT", "0");

        support = directory.resolve("catalog/support");
        for (String fixture : FIXTURES) {
            Path target = support.resolve(fixture);
            Files.createDirectories(target.getParent());
            try (InputStream input = getClass().getResourceAsStream("/support/" + fixture)) {
                if (input == null) throw new IllegalStateException("Missing WSR fixture " + fixture);
                Files.copy(input, target);
            }
        }
        route = support.resolve("support.camel.yaml");
        manifest = support.resolve("semantic-router.properties");
    }

    @Test
    void shouldReportUnreachableClusterAsUnavailableTarget() {
        CLIResult result = runSemantic("--namespace", NAMESPACE, "--router", ROUTER);

        assertThat(result.getExitCode())
                .as("CLI output: %s", result.getCombinedOutput())
                .isEqualTo(EXIT_TARGET_UNREACHABLE);
        assertThat(result.getCombinedOutput())
                .contains("Cannot read WanakuRouter %s in namespace %s".formatted(ROUTER, NAMESPACE));
    }

    @Test
    void shouldUseStoredTargetUnlessOptionsOverrideIt() {
        CLIResult stored =
                cli.execute("cloud", "target", "set", "--namespace", "stored-ns", "--router", "stored-router");
        assertThat(stored.getExitCode())
                .as("CLI output: %s", stored.getCombinedOutput())
                .isZero();

        CLIResult fromTarget = runSemantic();
        assertThat(fromTarget.getExitCode())
                .as("CLI output: %s", fromTarget.getCombinedOutput())
                .isEqualTo(EXIT_TARGET_UNREACHABLE);
        assertThat(fromTarget.getCombinedOutput())
                .contains("Cannot read WanakuRouter stored-router in namespace stored-ns");

        CLIResult overridden = runSemantic("--namespace", NAMESPACE, "--router", ROUTER);
        assertThat(overridden.getExitCode())
                .as("CLI output: %s", overridden.getCombinedOutput())
                .isEqualTo(EXIT_TARGET_UNREACHABLE);
        assertThat(overridden.getCombinedOutput())
                .contains("Cannot read WanakuRouter %s in namespace %s".formatted(ROUTER, NAMESPACE));
    }

    @Test
    void shouldRequireRouter() {
        assertInvalidInput(runSemantic("--namespace", NAMESPACE), "No router is set");
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "dependencies", "configuration", "kamelets", "tool.name", "expert.bean"})
    void shouldRejectManifestWithoutRequiredSetting(String key) throws IOException {
        List<String> lines = Files.readAllLines(manifest).stream()
                .filter(line -> !line.startsWith(key + "="))
                .toList();
        Files.write(manifest, lines);

        assertInvalidInput(runSemanticWithTarget(), "has no " + key);
    }

    @Test
    void shouldRejectRouteWithoutAdjacentManifest() throws IOException {
        Files.delete(manifest);

        assertInvalidInput(runSemanticWithTarget(), "needs semantic-router.properties in the same directory");
    }

    @Test
    void shouldRejectMainThatNamesAnotherFile() throws IOException {
        replaceInManifest("main=support/support.camel.yaml", "main=support/other.camel.yaml");

        assertInvalidInput(runSemanticWithTarget(), "main=support/other.camel.yaml in", "does not name");
    }

    @Test
    void shouldRejectReferenceOutsideTheRouteTree() throws IOException {
        Files.writeString(directory.resolve("outside.properties"), "action.secret=outside\n");
        replaceInManifest("configuration=support/service.properties", "configuration=../outside.properties");

        assertInvalidInput(runSemanticWithTarget(), "../outside.properties points outside");
    }

    @Test
    void shouldRejectMissingReferencedFile() throws IOException {
        Files.delete(support.resolve("support.dependencies.txt"));

        assertInvalidInput(runSemanticWithTarget(), "support.dependencies.txt does not exist");
    }

    @Test
    void shouldRejectRouteThatIsNotYamlList() throws IOException {
        Files.writeString(route, "route:\n  from:\n    uri: direct:support\n");

        assertInvalidInput(runSemanticWithTarget(), "the top level must be a list");
    }

    @ParameterizedTest
    @ValueSource(strings = {"#", "€"})
    void shouldRejectRouteFilesLargerThanConfigMapLimit(String filler) throws IOException {
        // Each file is below the per-file limit; together they exceed the 900 KiB that a ConfigMap can hold.
        // The limit is in bytes: the euro sign takes three bytes in UTF-8, so its files hold fewer characters.
        String padding = "# " + filler.repeat(480 * 1024 / filler.getBytes(StandardCharsets.UTF_8).length) + "\n";
        Files.writeString(support.resolve("kamelets/padding-1.kamelet.yaml"), padding);
        Files.writeString(support.resolve("kamelets/padding-2.kamelet.yaml"), padding);

        assertInvalidInput(runSemanticWithTarget(), "The semantic route files are larger than 900 KiB");
    }

    @Test
    void shouldRejectInvalidResourceName() {
        assertInvalidInput(
                runSemanticWithTarget("--name", "Support_Route"), "'Support_Route' is not a valid resource name");
    }

    private CLIResult runSemantic(String... options) {
        List<String> args = new ArrayList<>(List.of("cloud", "run", "semantic", route.toString()));
        args.addAll(List.of(options));
        return cli.execute(args.toArray(String[]::new));
    }

    /** Names a router and a namespace, so that only an input problem stops the command before the cluster. */
    private CLIResult runSemanticWithTarget(String... options) {
        List<String> args = new ArrayList<>(List.of("--namespace", NAMESPACE, "--router", ROUTER));
        args.addAll(List.of(options));
        return runSemantic(args.toArray(String[]::new));
    }

    private void replaceInManifest(String line, String replacement) throws IOException {
        String content = Files.readString(manifest);
        assertThat(content).contains(line);
        Files.writeString(manifest, content.replace(line, replacement));
    }

    private Path writeUnreachableKubeconfig() throws IOException {
        // Nothing listens on the port, so the API server refuses every request
        int closedPort = PortUtils.findAvailablePort();
        return Files.writeString(
                directory.resolve("kubeconfig"),
                """
                apiVersion: v1
                kind: Config
                clusters:
                  - name: unreachable
                    cluster:
                      server: https://127.0.0.1:%d
                contexts:
                  - name: unreachable
                    context:
                      cluster: unreachable
                      user: unreachable
                current-context: unreachable
                users:
                  - name: unreachable
                    user:
                      token: unused
                """
                        .formatted(closedPort));
    }

    private static void assertInvalidInput(CLIResult result, String... diagnostics) {
        assertThat(result.getExitCode())
                .as("CLI output: %s", result.getCombinedOutput())
                .isEqualTo(EXIT_INVALID_INPUT);
        assertThat(result.getCombinedOutput()).contains(diagnostics);
    }
}
