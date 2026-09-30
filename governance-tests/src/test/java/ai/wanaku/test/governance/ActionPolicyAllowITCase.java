package ai.wanaku.test.governance;

import java.util.Map;
import ai.wanaku.test.client.McpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complements {@link ActionPolicyDenyITCase}: with an empty rule set and {@code no_match=allow}, the
 * governed operations pass the action-policy filter and reach the upstream capture server. The
 * capture counters confirm the request was actually executed rather than short-circuited.
 */
class ActionPolicyAllowITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "governed";

    @BeforeEach
    void startGovernedInfrastructure() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
    }

    @DisplayName("Allowed tool call passes the policy filter and reaches the upstream capture server")
    @Test
    void allowsToolCallToReachUpstream() throws Exception {
        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "hello"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls())
                .as("Allowed tool call must be forwarded to the upstream capture server exactly once")
                .isEqualTo(1);
    }
}
