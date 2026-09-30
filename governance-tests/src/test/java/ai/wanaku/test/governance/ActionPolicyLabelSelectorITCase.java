package ai.wanaku.test.governance;

import java.util.Map;
import ai.wanaku.test.client.McpTestClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Label-selector denial: a rule denies any tool carrying a given label, rather than naming the tool
 * explicitly. The rule has no namespace or name constraint, so the label match is the only thing that
 * can make it fire. The capture tool is imported through a forward that carries {@code tier=restricted}
 * (discovered tools inherit their forward's labels).
 *
 * <p>Each test starts its own server so the policy can vary. {@link #deniedByLabelSelector} denies the
 * label the tool actually has and expects a static deny; {@link #allowedWhenLabelDoesNotMatch} is the
 * negative control — it denies a different label value and expects the same call to reach the upstream.
 * Without that control, a regression where the server ignored the label selector and denied every
 * {@code tools/call} would still turn the deny assertion green.
 */
class ActionPolicyLabelSelectorITCase extends GovernanceTestBase {

    private static final int STATIC_DENY_CODE = -32003;
    private static final String NAMESPACE = "governed";
    private static final String DENY_MESSAGE = "tools with the denied label are blocked by static policy";

    @DisplayName("Tool call is denied because the tool carries the label the rule targets")
    @Test
    void deniedByLabelSelector() throws Exception {
        String policy = GovernancePolicies.policy(
                GovernancePolicies.denyToolWithLabel("deny-by-label", "tier", "restricted", DENY_MESSAGE));
        startGovernedServer(policy, GovernancePolicies.enforce("allow"));
        registerLabeledCaptureForward(NAMESPACE, Map.of("tier", "restricted"));

        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "should-not-arrive"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls())
                .as("A label-denied tool call must never reach the upstream capture server")
                .isZero();
    }

    @DisplayName("Tool call is allowed when its label does not match the rule's label value")
    @Test
    void allowedWhenLabelDoesNotMatch() throws Exception {
        String policy = GovernancePolicies.policy(
                GovernancePolicies.denyToolWithLabel("deny-by-label", "tier", "premium", DENY_MESSAGE));
        startGovernedServer(policy, GovernancePolicies.enforce("allow"));
        registerLabeledCaptureForward(NAMESPACE, Map.of("tier", "restricted"));

        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "hello"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls())
                .as("A tool whose label does not match the rule must reach the upstream capture server")
                .isEqualTo(1);
    }
}
