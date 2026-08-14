package org.acme;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;

public class InfraRemediationServer {

    private static final Set<String> BLOCKED_SERVICES = Set.of("database", "payment-gateway");
    private static final int MAX_REPLICAS = 5;
    private final AtomicInteger ticketCounter = new AtomicInteger(9938);

    // ── Resources ──

    @Resource(uri = "config://policies/safety-limits", mimeType = "application/json")
    String safetyLimits() {
        return "{\"max_db_replicas\": 5, \"blocked_services\": [\"database\", \"payment-gateway\"]}";
    }

    @ResourceTemplate(uriTemplate = "logs://{server_id}/syslog")
    TextResourceContents syslog(String server_id) {
        return TextResourceContents.create(
                "logs://" + server_id + "/syslog",
                "ERROR: nginx process consuming 99% memory on " + server_id + "\n"
                        + "WARN: disk usage at 92% on /var/log\n"
                        + "ERROR: OOM killer invoked for pid 4521\n"
                        + "INFO: systemd restarting failed units");
    }

    // ── Prompts ──

    @Prompt(description = "Summarize a server incident for the ops team")
    PromptMessage summarizeIncident(
            @PromptArg(description = "The server experiencing the issue") String serverId,
            @PromptArg(description = "Brief description of the incident") String incident) {
        return PromptMessage.withUserRole(
                "Summarize the following incident on server " + serverId + " for the ops team: " + incident
                        + ". Include severity assessment, affected services, and recommended next steps.");
    }

    @Prompt(description = "Draft an escalation message for on-call engineers")
    PromptMessage draftEscalation(
            @PromptArg(description = "Urgency level: low, medium, or high") String urgency,
            @PromptArg(description = "Description of what needs escalation") String reason) {
        return PromptMessage.withUserRole(
                "Draft an escalation message with urgency=" + urgency + " for the on-call team: " + reason
                        + ". Follow the incident response playbook format.");
    }

    // ── Tools ──

    @Tool(description = "Restart a service on a specific server")
    String restartService(
            @ToolArg(description = "The server identifier") String serverId,
            @ToolArg(description = "The service to restart") String service) {
        if (BLOCKED_SERVICES.contains(service)) {
            throw new ToolCallException(
                    "POLICY BLOCK: Service '" + service + "' is in the blocked list and cannot be restarted"
                            + " (see config://policies/safety-limits).");
        }
        return "SUCCESS: Service '" + service + "' restarted on server '" + serverId + "'.";
    }

    @Tool(description = "Scale a deployment to a given number of replicas")
    String scaleDeployment(
            @ToolArg(description = "The deployment target") String target,
            @ToolArg(description = "Desired number of replicas (1-5)") int replicas) {
        if (replicas < 1) {
            throw new ToolCallException(
                    "INVALID: Replica count must be at least 1, got " + replicas + ".");
        }
        if (replicas > MAX_REPLICAS) {
            throw new ToolCallException(
                    "POLICY BLOCK: Cannot scale '" + target + "' to " + replicas
                            + " replicas. Maximum allowed is " + MAX_REPLICAS
                            + " (see config://policies/safety-limits).");
        }
        return "SUCCESS: Deployment '" + target + "' scaled to " + replicas + " replica(s).";
    }

    @Tool(description = "Escalate an incident by creating a support ticket")
    String escalateTicket(
            @ToolArg(description = "Reason for escalation") String reason,
            @ToolArg(description = "Urgency level: low, medium, or high") Urgency urgency) {
        int id = ticketCounter.incrementAndGet();
        return "Ticket INC-" + id + " created with urgency=" + urgency + ": " + reason;
    }
}
