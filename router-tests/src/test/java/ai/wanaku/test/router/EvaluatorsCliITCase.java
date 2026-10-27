package ai.wanaku.test.router;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;
import ai.wanaku.test.client.EvaluatorClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Exercises the evaluator CLI against the real management API (wanaku-barn#235). */
@Timeout(120)
class EvaluatorsCliITCase extends RouterTestBase {
    private static final Pattern REVISION_ID = Pattern.compile("(?m)^id\\t([0-9]+)$");
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private CLIExecutor cli;
    private EvaluatorClient evaluators;
    private JsonNode originalRevision;
    private long ownedRevisionId;

    @BeforeEach
    void setupCli() {
        assumeThat(isServerRunning()).as("Wanaku server must be running").isTrue();
        cli = CLIExecutor.createDefault();
        assumeThat(cli.isAvailable()).as("Wanaku CLI must be available").isTrue();
        evaluators = new EvaluatorClient(getServerBaseUrl(), null);
        var active = evaluators.getActiveRevision();
        // The server has no active revision (404) until the first evaluator update.
        assertThat(active.statusCode()).as("Active evaluator revision lookup").isIn(200, 404);
        originalRevision = active.statusCode() == 200 ? active.body() : null;
        ownedRevisionId = originalRevisionId();
    }

    @AfterEach
    void restoreEvaluators() {
        if (ownedRevisionId == originalRevisionId()) {
            return;
        }
        // The expected revision rejects the restore if a concurrent change replaced this test's revision.
        String expected = "\"expected_revision\": " + ownedRevisionId;
        var restored = originalRevision == null
                ? evaluators.updateEvaluators("{\"evaluators\": [], " + expected + "}")
                : evaluators.activateRevision(originalRevisionId(), "{" + expected + "}");
        assertThat(restored.statusCode())
                .as("Restore original evaluators without overwriting concurrent changes")
                .isEqualTo(200);
    }

    @Test
    void shouldUpdateFromYamlConfigurationAndJsonList() throws Exception {
        ArrayNode first = definitions("cli-yaml");
        // Flow syntax is valid YAML; surrounding keys verify wanaku.yaml extraction.
        Path yaml =
                Files.writeString(tempDir.resolve("wanaku.yaml"), "llm_connections: []\nevaluators: " + first + "\n");
        assertSuccess(execute("update", yaml.toAbsolutePath().toString()));
        assertStoredEvaluator(active(), "cli-yaml");

        ArrayNode second = definitions("cli-json");
        assertSuccess(execute("update", writeJson("evaluators.json", second).toString()));
        assertStoredEvaluator(active(), "cli-json");
    }

    @Test
    void shouldPrintRevisionHistoryAndEvaluatorSummaries() throws Exception {
        long first = update("cli-first");
        long second = update("cli-second");

        CLIResult list = execute("revisions", "list");
        assertSuccess(list);
        assertThat(list.getCombinedOutput()).contains("id", "status", "checksum", "superseded", "active");
        assertThat(list.getCombinedOutput()).containsPattern("(?m)^\\s*" + first + "\\s+");
        assertThat(list.getCombinedOutput()).containsPattern("(?m)^\\s*" + second + "\\s+");
        assertThat(list.getCombinedOutput()).containsPattern("(?ms)^" + second + "\\t[^\\n]*\\n.*?^" + first + "\\t");

        CLIResult active = execute("revisions", "active");
        assertSuccess(active);
        assertThat(active.getCombinedOutput()).contains("cli-second", "tools/call", "llm", "checksum");
        assertThat(active.getCombinedOutput()).containsPattern("(?m)^\\s*id\\s+" + second + "\\b");

        CLIResult get = execute("revisions", "get", Long.toString(first));
        assertSuccess(get);
        assertThat(get.getCombinedOutput()).contains("cli-first", "superseded").doesNotContain("cli-second");
        assertThat(get.getCombinedOutput()).containsPattern("(?m)^\\s*id\\s+" + first + "\\b");
    }

    @Test
    void shouldActivatePreviousConfigurationAsNewRevision() throws Exception {
        long first = update("cli-original");
        long second = update("cli-replacement");

        assertSuccess(execute("revisions", "activate", Long.toString(first)));

        JsonNode restored = active();
        assertThat(restored.path("revision").path("id").asLong()).isGreaterThan(second);
        assertStoredEvaluator(restored, "cli-original");
        assertThat(evaluators.getRevision(first).body().path("evaluators")).isEqualTo(restored.path("evaluators"));
    }

    @Test
    void shouldEnforceExpectedRevisionOnUpdate() throws Exception {
        long first = update("cli-update-first");
        Path replacement = writeJson("replacement.json", definitions("cli-update-second"));
        assertSuccess(execute("update", replacement.toString(), "--expected-revision", Long.toString(first)));
        JsonNode accepted = active();
        assertThat(accepted.path("revision").path("id").asLong()).isGreaterThan(first);
        assertStoredEvaluator(accepted, "cli-update-second");

        Path stale = writeJson("stale.json", definitions("cli-stale"));
        assertFailure(execute("update", stale.toString(), "--expected-revision", Long.toString(first)), "409");
        assertThat(active()).isEqualTo(accepted);
    }

    @Test
    void shouldEnforceExpectedRevisionOnActivation() throws Exception {
        long first = update("cli-activate-first");
        long second = update("cli-activate-second");
        JsonNode before = active();
        assertFailure(
                execute("revisions", "activate", Long.toString(first), "--expected-revision", Long.toString(first)),
                "409");
        assertThat(active()).isEqualTo(before);

        assertSuccess(
                execute("revisions", "activate", Long.toString(first), "--expected-revision", Long.toString(second)));
        JsonNode restored = active();
        assertThat(restored.path("revision").path("id").asLong()).isGreaterThan(second);
        assertStoredEvaluator(restored, "cli-activate-first");
    }

    @Test
    void shouldReportMissingRevisionsWithoutChangingActiveConfiguration() {
        var cleared = evaluators.updateEvaluators("{\"evaluators\": []}");
        assertThat(cleared.statusCode()).isEqualTo(200);
        ownedRevisionId = cleared.body().path("revision").path("id").asLong();
        JsonNode before = active();
        String missing = Long.toString(Long.MAX_VALUE);
        assertThat(evaluators.getRevision(Long.MAX_VALUE).statusCode()).isEqualTo(404);

        assertFailure(execute("revisions", "get", missing), "not found");
        assertFailure(execute("revisions", "activate", missing), "not found");
        assertThat(active()).isEqualTo(before);
    }

    @Test
    void shouldReportInvalidProcessorWithoutReplacingActiveConfiguration() throws Exception {
        update("cli-known-good");
        JsonNode before = active();
        ArrayNode invalid = definitions("cli-invalid");
        ((ObjectNode) invalid.get(0).path("processor"))
                .put("path", tempDir.resolve("missing.wasm").toAbsolutePath().toString());

        assertFailure(execute("update", writeJson("invalid.json", invalid).toString()), "422");
        assertThat(active()).isEqualTo(before);
    }

    private long update(String name) throws Exception {
        assertSuccess(
                execute("update", writeJson(name + ".json", definitions(name)).toString()));
        return active().path("revision").path("id").asLong();
    }

    private ArrayNode definitions(String name) {
        Path wasm = config.getEvaluatorWasmPath();
        assumeThat(wasm != null && Files.exists(wasm))
                .as("A compiled evaluator WASM is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();
        ArrayNode definitions = mapper.createArrayNode();
        ObjectNode evaluator = definitions.addObject().put("name", name);
        evaluator.putObject("trigger").put("method", "tools/call");
        evaluator
                .putObject("engine")
                .put("type", "llm")
                .put("operation", "classify")
                .put("prompt", "Test prompt for " + name)
                .put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        evaluator.putObject("processor").put("path", wasm.toAbsolutePath().toString());
        return definitions;
    }

    private void assertStoredEvaluator(JsonNode revision, String name) {
        assertThat(revision.path("evaluators").size()).isEqualTo(1);
        JsonNode evaluator = revision.path("evaluators").get(0);
        assertThat(evaluator.path("name").asText()).isEqualTo(name);
        assertThat(evaluator.path("trigger").path("method").asText()).isEqualTo("tools/call");
        assertThat(evaluator.path("engine").path("type").asText()).isEqualTo("llm");
        assertThat(evaluator.path("engine").path("connection").asText())
                .isEqualTo(WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        assertThat(evaluator.path("processor").path("path").asText())
                .isEqualTo(config.getEvaluatorWasmPath().toAbsolutePath().toString());
    }

    private Path writeJson(String filename, JsonNode content) throws Exception {
        return Files.writeString(tempDir.resolve(filename), content.toString()).toAbsolutePath();
    }

    private JsonNode active() {
        var response = evaluators.getActiveRevision();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().path("revision").path("id").asLong()).isPositive();
        return response.body();
    }

    private long originalRevisionId() {
        return originalRevision == null
                ? 0
                : originalRevision.path("revision").path("id").asLong();
    }

    private CLIResult execute(String... args) {
        List<String> command = new ArrayList<>(List.of("evaluators"));
        command.addAll(List.of(args));
        command.addAll(List.of("--host", getServerBaseUrl(), "--no-auth"));
        CLIResult result = cli.execute(command.toArray(String[]::new));
        if (result.isSuccess() && (args[0].equals("update") || (args.length > 1 && args[1].equals("activate")))) {
            var revision = REVISION_ID.matcher(result.getCombinedOutput());
            boolean printed = revision.find();
            // Record the revision before asserting, so cleanup can still restore if the output is unexpected.
            ownedRevisionId = printed
                    ? Long.parseLong(revision.group(1))
                    : active().path("revision").path("id").asLong();
            assertThat(printed)
                    .as("Successful evaluator mutation must print its revision ID: %s", result.getCombinedOutput())
                    .isTrue();
        }
        return result;
    }

    private static void assertSuccess(CLIResult result) {
        assertThat(result.getExitCode())
                .as("CLI output: %s", result.getCombinedOutput())
                .isZero();
    }

    private static void assertFailure(CLIResult result, String diagnostic) {
        assertThat(result.getExitCode())
                .as("CLI output: %s", result.getCombinedOutput())
                .isEqualTo(1);
        assertThat(result.getCombinedOutput()).containsIgnoringCase(diagnostic);
    }
}
