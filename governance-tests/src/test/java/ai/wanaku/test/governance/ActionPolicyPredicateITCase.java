package ai.wanaku.test.governance;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.McpTestClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Typed predicates operate on the MCP arguments, preserving missing values and JSON types. */
class ActionPolicyPredicateITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "predicate-tests";
    private static final String TYPED_TOOL = "capture_typed_tool";

    @BeforeEach
    void startGovernedInfrastructure() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        waitForToolDiscovery(NAMESPACE, TYPED_TOOL);
    }

    @DisplayName("Every operator distinguishes omitted arguments from present typed JSON values")
    @ParameterizedTest(name = "{0}")
    @MethodSource("typedValues")
    void matchesTypedValues(String name, String argumentsJson, String expectedJson, boolean present) throws Exception {
        JsonNode arguments = MAPPER.readTree(argumentsJson);
        JsonNode expected = MAPPER.readTree(expectedJson);
        McpTestClient client = connect(NAMESPACE);
        assertPredicate(
                client,
                arguments,
                predicate("exists", "/arguments/value", MAPPER.valueToTree(true)),
                present,
                name + " exists true");
        assertPredicate(
                client,
                arguments,
                predicate("exists", "/arguments/value", MAPPER.valueToTree(false)),
                !present,
                name + " exists false");
        for (String operator : List.of("equals", "not_equals", "one_of", "not_one_of")) {
            boolean positive = operator.equals("equals") || operator.equals("one_of");
            assertPredicate(
                    client,
                    arguments,
                    predicate(operator, "/arguments/value", expected),
                    present && positive,
                    name + " " + operator + " same type");
            // This sentinel cannot equal any matrix value, including the string representation of zero.
            assertPredicate(
                    client,
                    arguments,
                    predicate(operator, "/arguments/value", MAPPER.valueToTree("different")),
                    present && !positive,
                    name + " " + operator + " different value");
        }
        for (String operator : List.of("one_of", "not_one_of")) {
            ObjectNode membership = predicate(operator, "/arguments/value", expected);
            membership.putArray("values").add("another-candidate").add(expected);
            assertPredicate(
                    client,
                    arguments,
                    membership,
                    present && operator.equals("one_of"),
                    name + " " + operator + " later matching candidate");
        }
        if (name.equals("number zero") || name.equals("string zero")) {
            JsonNode otherType = MAPPER.readTree(name.equals("number zero") ? "\"0\"" : "0");
            for (String operator : List.of("equals", "not_equals", "one_of", "not_one_of")) {
                assertPredicate(
                        client,
                        arguments,
                        predicate(operator, "/arguments/value", otherType),
                        operator.equals("not_equals") || operator.equals("not_one_of"),
                        name + " " + operator + " string versus number");
            }
        }
    }

    static Stream<Arguments> typedValues() {
        return Stream.of(
                Arguments.of("omitted", "{}", "null", false),
                Arguments.of("null", "{\"value\":null}", "null", true),
                Arguments.of("false", "{\"value\":false}", "false", true),
                Arguments.of("number zero", "{\"value\":0}", "0", true),
                Arguments.of("empty string", "{\"value\":\"\"}", "\"\"", true),
                Arguments.of("string zero", "{\"value\":\"0\"}", "\"0\"", true),
                Arguments.of("empty object", "{\"value\":{}}", "{}", true),
                Arguments.of("empty array", "{\"value\":[]}", "[]", true),
                Arguments.of(
                        "nested object",
                        "{\"value\":{\"nested\":{\"flag\":false}}}",
                        "{\"nested\":{\"flag\":false}}",
                        true),
                Arguments.of("array", "{\"value\":[null,false,0,\"0\"]}", "[null,false,0,\"0\"]", true));
    }

    @DisplayName("JSON Pointer resolves root, nested fields, array indexes and escaped keys")
    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonPointers")
    void resolvesJsonPointers(String name, String argumentsJson, String pointer, String expectedJson, boolean matches)
            throws Exception {
        assertPredicate(
                connect(NAMESPACE),
                MAPPER.readTree(argumentsJson),
                predicate("equals", pointer, MAPPER.readTree(expectedJson)),
                matches,
                name);
    }

    static Stream<Arguments> jsonPointers() {
        String document = "{\"value\":{\"nested\":{\"items\":[0,false]},\"a~b\":\"tilde\",\"a/b\":\"slash\"}}";
        return Stream.of(
                Arguments.of(
                        "root document",
                        document,
                        "",
                        "{\"name\":\"capture_typed_tool\",\"arguments\":" + document + "}",
                        true),
                Arguments.of("nested field", document, "/arguments/value/nested/items", "[0,false]", true),
                Arguments.of("array index", document, "/arguments/value/nested/items/1", "false", true),
                Arguments.of("escaped tilde", document, "/arguments/value/a~0b", "\"tilde\"", true),
                Arguments.of("escaped slash", document, "/arguments/value/a~1b", "\"slash\"", true),
                Arguments.of("array out of bounds", document, "/arguments/value/nested/items/2", "null", false),
                Arguments.of("missing nested field", document, "/arguments/value/nested/absent", "null", false));
    }

    @DisplayName("All supplied predicates must match before a deny rule applies")
    @ParameterizedTest(name = "second predicate matches: {0}")
    @MethodSource("predicateConjunctions")
    void combinesPredicatesWithAnd(boolean secondMatches) throws Exception {
        JsonNode arguments = MAPPER.readTree("{\"value\":{\"first\":false,\"second\":0}}");
        List<ObjectNode> predicates = List.of(
                predicate("equals", "/arguments/value/first", MAPPER.valueToTree(false)),
                predicate("equals", "/arguments/value/second", MAPPER.valueToTree(secondMatches ? 0 : 1)));
        assertDecision(connect(NAMESPACE), arguments, predicates, secondMatches, "predicates AND " + secondMatches);
    }

    static Stream<Boolean> predicateConjunctions() {
        return Stream.of(true, false);
    }

    private static ObjectNode predicate(String operator, String pointer, JsonNode expected) {
        ObjectNode predicate =
                MAPPER.createObjectNode().put("operator", operator).put("pointer", pointer);
        if (operator.equals("one_of") || operator.equals("not_one_of")) {
            // More than one candidate ensures set membership is exercised, rather than scalar equality alone.
            predicate.putArray("values").add(expected).add("another-candidate");
        } else {
            predicate.set("value", expected);
        }
        return predicate;
    }

    private void assertPredicate(
            McpTestClient client, JsonNode arguments, ObjectNode predicate, boolean denied, String message)
            throws Exception {
        assertDecision(client, arguments, List.of(predicate), denied, message);
    }

    private void assertDecision(
            McpTestClient client, JsonNode arguments, List<ObjectNode> predicates, boolean denied, String message)
            throws Exception {
        ObjectNode rule = GovernancePolicies.denyTool("typed-predicate", TYPED_TOOL, message);
        rule.set("predicates", MAPPER.valueToTree(predicates));
        JsonNode policy = MAPPER.readTree(GovernancePolicies.policy(rule));
        try (ActionPolicyClient policyClient = new ActionPolicyClient(server.getBaseUrl())) {
            var response = policyClient.updatePolicy(policy);
            assertThat(response.statusCode())
                    .as("Activating predicate policy: %s", response.body())
                    .isEqualTo(200);
        }
        int before = captureCounts().toolCalls();
        Map<String, Object> suppliedArguments = MAPPER.convertValue(arguments, new TypeReference<>() {});
        if (denied) {
            client.when()
                    .toolsCall(TYPED_TOOL)
                    .withArguments(suppliedArguments)
                    .withErrorAssert(error -> {
                        assertThat(error.code()).isEqualTo(-32003);
                        assertThat(error.message()).contains(message);
                    })
                    .send()
                    .thenAssertResults();
        } else {
            client.when()
                    .toolsCall(TYPED_TOOL)
                    .withArguments(suppliedArguments)
                    .withAssert(result -> assertThat(result.isError()).isFalse())
                    .send()
                    .thenAssertResults();
        }
        assertThat(captureCounts().toolCalls())
                .as("Upstream count for %s", message)
                .isEqualTo(before + (denied ? 0 : 1));
    }
}
