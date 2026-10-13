package ai.wanaku.test.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(180)
class SemanticRouterITCase extends SemanticRouterTestBase {
    @ParameterizedTest
    @CsvSource({
        "billing,Billing demo response",
        "technical,Technical demo response",
        "unclassified,No suitable support action."
    })
    void shouldRouteRequestsThroughWanaku(String message, String expected) throws Exception {
        start(true, null, null, false);
        invoke(message, expected);
    }

    @Test
    void shouldRetainLinkedEvaluationAndDecisionWithoutSubmittedContent() throws Exception {
        start(true, null, null, false);
        String sentinel = "private-semantic-content-7f2e";
        invoke("billing " + sentinel, "Billing demo response");
        assertAuditPair("billing", sentinel);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true"})
    void shouldOverrideCatalogAuditWithRuntimeSetting(boolean runtime, boolean catalog) throws Exception {
        start(true, runtime, catalog, !runtime);
        invoke("technical", "Technical demo response");
        assertThat(router.getStatus().path("audit").path("enabled").asBoolean()).isEqualTo(runtime);
        assertThat(router.getStatus()
                        .path("audit")
                        .path("capture")
                        .path("enabled")
                        .asBoolean())
                .isEqualTo(runtime);
        if (runtime) {
            assertAuditPair("technical", "private-sentinel-unused");
        } else {
            // Disabled capture is synchronous; shutting down drains any erroneously queued records.
            router.stop();
            assertThat(auditProxy.requestCount()).isZero();
            assertThat(events()).isEmpty();
        }
    }

    @Test
    void shouldKeepRoutingWhenAuditReceiverDisabled() throws Exception {
        start(false, null, null, false);
        invoke("billing", "Billing demo response");
        awaitCounter("failed", 2);
        assertThat(router.getStatus().path("state").asText()).isEqualTo("ready");
        assertThat(router.getStatus().path("audit").path("delivered").asLong()).isZero();
        assertThat(events()).isEmpty();
    }

    @Test
    void shouldDeliverNewInvocationAfterAuditReceiverRecovers() throws Exception {
        start(true, null, null, true);
        invoke("billing", "Billing demo response");
        awaitCounter("failed", 2);
        assertThat(events()).isEmpty();
        assertThat(router.getStatus().path("audit").path("retries").asLong()).isPositive();
        auditProxy.restoreDelivery();
        invoke("technical", "Technical demo response");
        assertAuditPair("technical", "private-sentinel-unused");
        assertThat(router.getStatus().path("audit").path("failed").asLong()).isEqualTo(2);
    }

    @Test
    void shouldDeregisterForwardAndToolOnGracefulShutdown() throws Exception {
        start(true, null, null, false);
        invoke("billing", "Billing demo response");
        mcp.when()
                .toolsList(page -> assertThat(page.tools()).anyMatch(tool -> "route_support".equals(tool.name())))
                .thenAssertResults();
        router.stop();
        await().atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> assertThat(forwards.exists(NAME)).isFalse());
        connectFreshSession();
        mcp.when()
                .toolsList(page -> assertThat(page.tools()).noneMatch(tool -> "route_support".equals(tool.name())))
                .thenAssertResults();
    }

    private void assertAuditPair(String result, String sentinel) {
        awaitCounter("delivered", 2);
        List<JsonNode> retained = new ArrayList<>();
        events().forEach(retained::add);
        assertThat(retained).hasSize(2);
        JsonNode evaluation = retained.stream()
                .filter(event ->
                        "evaluation".equals(semantic(event).path("category").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode decision = retained.stream()
                .filter(event ->
                        "decision".equals(semantic(event).path("category").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(semantic(evaluation).path("result").path("value").asText()).isEqualTo(result);
        assertThat(semantic(evaluation).path("expert").asText()).isEqualTo("supportExpert");
        assertThat(semantic(decision).path("action").asText()).isEqualTo("continue");
        assertThat(semantic(decision).path("target").asText()).isEqualTo("route_support");
        String sourceEventId = evaluation.path("source").path("event_id").asText();
        assertThat(sourceEventId).isNotBlank();
        assertThat(decision.path("source").path("evidence")).hasSize(1);
        assertThat(decision.path("source").path("evidence").get(0).asText()).isEqualTo(sourceEventId);
        assertThat(audit.listEvents(Map.of("source_event_id", sourceEventId)).path("events"))
                .hasSize(1);
        for (JsonNode event : retained) {
            assertThat(event.path("category").asText()).isEqualTo("semantic");
            assertThat(event.path("decision").asText()).isEqualTo("observed");
            assertThat(event.path("protocol").asText()).isEqualTo("camel-semantic");
            assertThat(event.path("source").path("instance_id").asText()).isEqualTo(instance);
            assertThat(event.path("source").path("forward_name").asText()).isEqualTo(NAME);
            assertThat(event.path("source").path("namespace").asText()).isEqualTo(NAMESPACE);
            assertThat(event.path("source").path("catalog_revision").asText()).isEqualTo(revision);
            assertThat(event.path("source").path("catalog_digest").asText()).isEqualTo(digest);
            assertThat(event.path("source").path("producer_asserted").asBoolean())
                    .isTrue();
            assertThat(semantic(event).has("input")).isFalse();
            assertThat(event.toString()).doesNotContain(sentinel);
            assertThat(audit.getEvent(event.path("event_id").asText())).isEqualTo(event);
        }
    }

    private static JsonNode semantic(JsonNode event) {
        return event.path("attributes").path("semantic");
    }
}
