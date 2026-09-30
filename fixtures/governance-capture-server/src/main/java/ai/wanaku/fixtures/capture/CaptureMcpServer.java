package ai.wanaku.fixtures.capture;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

/**
 * Upstream MCP server used by the governance tests. Each governed operation records that it was
 * actually reached (via {@link CaptureState}), so a test can distinguish "request forwarded to
 * upstream" from "request stopped at the router by governance". The entity names are stable so
 * static action-policy rules can select them by name.
 */
public class CaptureMcpServer {

    static final String RESOURCE_URI = "capture://resource/data";

    @Inject
    CaptureState state;

    @Tool(name = "capture_tool", description = "Records the invocation and echoes the payload")
    String captureTool(@ToolArg(description = "Arbitrary payload echoed back") String payload) {
        int count = state.incrementToolCalls();
        return "capture_tool invoked (count=" + count + ") payload=" + payload;
    }

    @Resource(uri = RESOURCE_URI, mimeType = "text/plain")
    String captureResource() {
        int count = state.incrementResourceReads();
        return "capture resource read (count=" + count + ")";
    }

    @Prompt(name = "capture_prompt", description = "Records the invocation and returns a message")
    PromptMessage capturePrompt(@PromptArg(description = "Arbitrary topic") String topic) {
        int count = state.incrementPromptGets();
        return PromptMessage.withUserRole("capture_prompt invoked (count=" + count + ") topic=" + topic);
    }
}
