package ai.wanaku.test.governance;

import java.util.Map;
import ai.wanaku.test.client.McpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static action-policy denials across the three governed MCP operations (tools/call, resources/read,
 * prompts/get). Each rule carries a distinctive message so the test can assert on the JSON-RPC error
 * the router returns, and the capture fixture's counters prove the upstream was never invoked.
 *
 * <p>Governance posture is {@code no_match=allow}, so only the operations explicitly denied by a rule
 * are blocked; everything else (notably discovery) flows through.
 */
class ActionPolicyDenyITCase extends GovernanceTestBase {

    private static final int STATIC_DENY_CODE = -32003;
    private static final String NAMESPACE = "governed";

    private static final String TOOL_DENY_MESSAGE = "capture_tool is denied by static policy";
    private static final String RESOURCE_DENY_MESSAGE = "capture resource is denied by static policy";
    private static final String PROMPT_DENY_MESSAGE = "capture_prompt is denied by static policy";

    @BeforeEach
    void startGovernedInfrastructure() throws Exception {
        String policy = GovernancePolicies.policy(
                GovernancePolicies.denyTool("deny-capture-tool", CAPTURE_TOOL, TOOL_DENY_MESSAGE),
                GovernancePolicies.denyResourceByUri(
                        "deny-capture-resource", CAPTURE_RESOURCE_URI, RESOURCE_DENY_MESSAGE),
                GovernancePolicies.denyPrompt("deny-capture-prompt", CAPTURE_PROMPT, PROMPT_DENY_MESSAGE));
        startGovernedServer(policy, GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
    }

    @DisplayName("tools/call denied by static policy returns -32003 and never reaches upstream")
    @Test
    void deniesToolCall() throws Exception {
        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "should-not-arrive"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(TOOL_DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls())
                .as("Denied tool call must not reach the upstream capture server")
                .isZero();
    }

    @DisplayName("resources/read denied by static policy returns -32003 and never reaches upstream")
    @Test
    void deniesResourceRead() throws Exception {
        McpTestClient client = connect(NAMESPACE);
        client.when()
                .resourcesRead(CAPTURE_RESOURCE_URI)
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(RESOURCE_DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();

        assertThat(captureCounts().resourceReads())
                .as("Denied resource read must not reach the upstream capture server")
                .isZero();
    }

    @DisplayName("prompts/get denied by static policy returns -32003 and never reaches upstream")
    @Test
    void deniesPromptGet() throws Exception {
        McpTestClient client = connect(NAMESPACE);
        client.when()
                .promptsGet(CAPTURE_PROMPT)
                .withArguments(Map.of("topic", "should-not-arrive"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(PROMPT_DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();

        assertThat(captureCounts().promptGets())
                .as("Denied prompt get must not reach the upstream capture server")
                .isZero();
    }

    @DisplayName("Denial does not remove the tool from discovery (tools/list still lists it)")
    @Test
    void discoveryStillListsDeniedTool() throws Exception {
        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsList(page -> assertThat(page.tools()).anyMatch(t -> CAPTURE_TOOL.equals(t.name())))
                .thenAssertResults();
    }
}
