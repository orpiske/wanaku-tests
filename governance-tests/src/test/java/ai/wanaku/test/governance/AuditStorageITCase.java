package ai.wanaku.test.governance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.McpTestClient;
import com.fasterxml.jackson.databind.JsonNode;

import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises bounded storage, redaction, and persistence through a real managed server. */
@Timeout(120)
class AuditStorageITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "audit-storage";
    private static final String DENIAL = "audit storage test denial";

    @Test
    void omitsPayloadsByDefault() throws Exception {
        startAuditServer("{}", true, "storage-deny");
        deny("default-payload", Map.of("payload", "ordinary-private-body"));
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode event = decision(audit, "default-payload");
            assertThat(event.path("payload").isMissingNode()
                            || event.path("payload").isNull())
                    .isTrue();
            assertThat(event.path("redaction").path("payload_captured").asBoolean())
                    .isFalse();
            assertThat(event.toString()).doesNotContain("ordinary-private-body");
        }
    }

    @Test
    void redactsNestedFieldsPointersCredentialStringsAndAttributesBeforePersistence() throws Exception {
        startAuditServer(
                """
                {"capture_payloads":true,"sensitive_fields":["private_value"],
                 "sensitive_json_pointers":["/params/arguments/customer/private"],
                 "credential_markers":["credential="],"token_prefixes":["custom_"]}
                """,
                true,
                "ghp_attribute-secret");
        Map<String, Object> arguments = Map.of(
                "payload", "visible-body",
                "nested", Map.of("PaSsWoRd", "nested-password-secret", "token", "nested-token-secret"),
                "customer", Map.of("private", "pointer-secret", "public", "visible-customer"),
                "private_value", "configured-field-secret",
                "headers", Map.of("Authorization", "Bearer auth-secret", "Cookie", "cookie-secret"),
                "marker", "credential=marker-secret",
                "prefix", "custom_prefix-secret");
        deny(
                "redacted-payload",
                arguments,
                Map.of("Authorization", "Bearer http-auth-secret", "Cookie", "http-cookie-secret"));
        List<String> secrets = List.of(
                "nested-password-secret",
                "nested-token-secret",
                "pointer-secret",
                "configured-field-secret",
                "auth-secret",
                "cookie-secret",
                "marker-secret",
                "prefix-secret",
                "ghp_attribute-secret",
                "http-auth-secret",
                "http-cookie-secret");
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode event = decision(audit, "redacted-payload");
            assertThat(event.path("redaction").path("payload_captured").asBoolean())
                    .isTrue();
            JsonNode payload = event.path("payload").path("params").path("arguments");
            assertThat(payload.path("payload").asText()).isEqualTo("visible-body");
            assertThat(payload.path("customer").path("public").asText()).isEqualTo("visible-customer");
            assertThat(payload.path("customer").path("private").asText()).isEqualTo("[REDACTED]");
            assertThat(event.path("attributes").path("deny_rule_ids").get(0).asText())
                    .isEqualTo("[REDACTED]");
            assertNoSecrets(event.toString(), secrets);
            assertNoSecrets(audit.getEvent(event.path("event_id").asText()).toString(), secrets);
            awaitPersistedEvent(event.path("event_id").asText());
            assertNoSecrets(Files.readString(server.getPersistDir().resolve("audit-events.json")), secrets);
            assertNoSecrets(Files.readString(server.getLogFile().toPath()), secrets);
        }
    }

    @Test
    void replacesOversizedCapturedPayloadWithRedactionMarker() throws Exception {
        startAuditServer("{\"capture_payloads\":true,\"payload_max_bytes\":256}", true, "storage-deny");
        deny("large-payload", Map.of("payload", "a".repeat(4096)));
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode event = decision(audit, "large-payload");
            assertThat(event.path("payload").asText()).isEqualTo("[REDACTED]");
            assertThat(event.path("redaction").path("payload_truncated").asBoolean())
                    .isTrue();
            assertThat(MAPPER.writeValueAsBytes(event.path("payload"))).hasSizeLessThanOrEqualTo(256);
        }
    }

    @Test
    void evictsOldestRecordsAtConfiguredCapacity() throws Exception {
        startAuditServer("{\"max_records\":3}", false, "storage-deny");
        List<String> ids = new ArrayList<>();
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            for (int i = 0; i < 5; i++) {
                String correlation = "retention-" + i;
                deny(correlation, Map.of("payload", "bounded"));
                ids.add(decision(audit, correlation).path("event_id").asText());
            }
            JsonNode page = audit.listEvents(Map.of("limit", "100"));
            assertThat(page.path("total").asInt()).isEqualTo(3);
            assertThat(audit.health().path("retained_events").asInt()).isEqualTo(3);
            List<String> retained = new ArrayList<>();
            page.path("events")
                    .forEach(event -> retained.add(event.path("event_id").asText()));
            assertThat(retained).contains(ids.get(4)).doesNotContain(ids.get(0), ids.get(1));
        }
    }

    @Test
    void reloadsPersistedEventsAfterRestart() throws Exception {
        startAuditServer("{}", true, "storage-deny");
        deny("persistent-request", Map.of("payload", "persist"));
        JsonNode persistedEvent;
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            persistedEvent = decision(audit, "persistent-request");
        }
        String eventId = persistedEvent.path("event_id").asText();
        // Persistence is asynchronous. Wait for the snapshot before stopping the process.
        awaitPersistedEvent(eventId);
        server.stopPreservingState();
        server.start(getClass().getSimpleName());
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            assertThat(audit.getEvent(eventId)).isEqualTo(persistedEvent);
        }
    }

    @Test
    void doesNotRetainInMemoryEventsAfterRestart() throws Exception {
        startAuditServer("{}", false, "storage-deny");
        deny("memory-request", Map.of("payload", "memory"));
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            decision(audit, "memory-request");
        }
        server.stopPreservingState();
        server.start(getClass().getSimpleName());
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            assertThat(audit.listEvents(Map.of("correlation_id", "memory-request"))
                            .path("total")
                            .asInt())
                    .isZero();
        }
    }

    @Test
    void storageFailureDegradesHealthWithoutChangingEnforcement() throws Exception {
        startAuditServer("{\"capture_payloads\":true}", true, "storage-deny");
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            McpTestClient allowedClient = connect(NAMESPACE);
            allowedClient
                    .when()
                    .promptsGet(CAPTURE_PROMPT)
                    .withArguments(Map.of("topic", "still-allowed"))
                    .withAssert(response -> assertThat(response.messages()).isNotEmpty())
                    .send()
                    .thenAssertResults();
            assertThat(captureCounts().promptGets()).isEqualTo(1);
            deny("before-storage-failure", Map.of("payload", "baseline"));
            String id =
                    decision(audit, "before-storage-failure").path("event_id").asText();
            awaitPersistedEvent(id);
            // A directory at the worker's temporary-file path guarantees File::create fails,
            // including when the test runs as root. Other server persistence files stay writable.
            Files.createDirectory(server.getPersistDir().resolve("audit-events.json.tmp"));
            deny("after-storage-failure", Map.of("payload", "failed-storage", "password", "failure-payload-secret"));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                JsonNode health = audit.health();
                assertThat(health.path("healthy").asBoolean()).isFalse();
                assertThat(health.path("dropped_events").asLong()).isPositive();
                assertThat(health.path("last_error").asText()).isEqualTo("audit storage unavailable");
                assertThat(audit.metrics()
                                .path("audit")
                                .path("storage_failures")
                                .asLong())
                        .isPositive();
            });
            assertThat(decision(audit, "after-storage-failure").path("decision").asText())
                    .isEqualTo("block");
            allowedClient
                    .when()
                    .promptsGet(CAPTURE_PROMPT)
                    .withArguments(Map.of("topic", "still-allowed"))
                    .withAssert(response -> assertThat(response.messages()).isNotEmpty())
                    .send()
                    .thenAssertResults();
            assertThat(captureCounts().toolCalls()).isZero();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                            Files.readString(server.getLogFile().toPath()))
                    .contains("audit event storage failed")
                    .doesNotContain("failure-payload-secret"));
            assertThat(audit.health().toString()).doesNotContain("failure-payload-secret");
        }
    }

    private void startAuditServer(String auditJson, boolean persistence, String ruleId) throws Exception {
        startGovernedServer(
                GovernancePolicies.policy(GovernancePolicies.denyTool(ruleId, CAPTURE_TOOL, DENIAL)),
                GovernancePolicies.enforce("allow"),
                auditJson,
                persistence);
        registerCaptureForward(NAMESPACE);
    }

    private void deny(String correlation, Map<String, Object> arguments) throws Exception {
        deny(correlation, arguments, Map.of());
    }

    private void deny(String correlation, Map<String, Object> arguments, Map<String, String> headers) throws Exception {
        Map<String, Object> correlated = new java.util.HashMap<>(arguments);
        correlated.put("x-request-id", correlation);
        connect(NAMESPACE, headers)
                .when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(correlated)
                .withErrorAssert(error -> assertThat(error.code()).isEqualTo(-32003))
                .send()
                .thenAssertResults();
    }

    private JsonNode decision(AuditClient audit, String correlation) {
        JsonNode events = audit.listEvents(Map.of("correlation_id", correlation, "operation", "tools/call"))
                .path("events");
        assertThat(events.size()).isPositive();
        for (JsonNode event : events) {
            if ("wanaku_action_policy".equals(event.path("filter").asText())) {
                return event;
            }
        }
        throw new AssertionError("Missing action-policy audit decision for " + correlation + ": " + events);
    }

    private void awaitPersistedEvent(String eventId) {
        Path file = server.getPersistDir().resolve("audit-events.json");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(Files.exists(file)).isTrue();
            assertThat(Files.readString(file)).contains(eventId);
        });
    }

    private static void assertNoSecrets(String text, List<String> secrets) {
        secrets.forEach(secret -> assertThat(text).doesNotContain(secret));
    }
}
