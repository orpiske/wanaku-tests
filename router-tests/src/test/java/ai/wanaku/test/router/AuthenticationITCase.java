package ai.wanaku.test.router;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Disabled("Authentication not built into wanaku-server — handled by external goauth_proxy")
class AuthenticationITCase extends RouterTestBase {

    @DisplayName("Reject unauthenticated tools list request")
    @Test
    void shouldRejectUnauthenticatedToolsRequest() {}

    @DisplayName("Reject unauthenticated resources list request")
    @Test
    void shouldRejectUnauthenticatedResourcesRequest() {}

    @DisplayName("Accept authenticated tools list request with valid MCP token")
    @Test
    void shouldAcceptAuthenticatedToolsRequest() {}

    @DisplayName("Reject tools list request with invalid bearer token")
    @Test
    void shouldRejectInvalidToken() {}

    @DisplayName("Service credentials are available and valid for capability authentication")
    @Test
    void shouldAcceptServiceCredentials() {}
}
