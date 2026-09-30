package ai.wanaku.test.governance;

import java.util.Map;
import ai.wanaku.test.client.McpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Namespace-scoped denial: the same tool name is forwarded into two namespaces, but a single rule
 * denies it only in the {@code restricted} namespace. Proves the {@code namespace} selector isolates
 * the denial so the identical operation still succeeds in the {@code open} namespace.
 */
class ActionPolicyNamespaceScopingITCase extends GovernanceTestBase {

    private static final int STATIC_DENY_CODE = -32003;
    private static final String RESTRICTED_NAMESPACE = "restricted";
    private static final String OPEN_NAMESPACE = "open";
    private static final String DENY_MESSAGE = "capture_tool is denied in the restricted namespace";

    @BeforeEach
    void startGovernedInfrastructure() throws Exception {
        String policy = GovernancePolicies.policy(GovernancePolicies.denyToolInNamespace(
                "deny-in-restricted", CAPTURE_TOOL, RESTRICTED_NAMESPACE, DENY_MESSAGE));
        startGovernedServer(policy, GovernancePolicies.enforce("allow"));
        registerCaptureForward(RESTRICTED_NAMESPACE);
        registerCaptureForward(OPEN_NAMESPACE);
    }

    @DisplayName("Tool call is denied in the restricted namespace")
    @Test
    void deniedInRestrictedNamespace() throws Exception {
        McpTestClient client = connect(RESTRICTED_NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "should-not-arrive"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("The same tool call succeeds in the open namespace")
    @Test
    void allowedInOpenNamespace() throws Exception {
        McpTestClient client = connect(OPEN_NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "hello"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls())
                .as("Tool call in the open namespace must reach the upstream capture server")
                .isEqualTo(1);
    }
}
