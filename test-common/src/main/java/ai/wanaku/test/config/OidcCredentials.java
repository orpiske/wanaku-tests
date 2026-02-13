package ai.wanaku.test.config;

/**
 * OIDC credentials for capability authentication.
 * Used by capabilities to obtain tokens for registering with the Router.
 */
public record OidcCredentials(
        String tokenEndpoint,   // e.g., http://localhost:8543/realms/wanaku
        String clientId,        // e.g., wanaku-service
        String clientSecret     // the actual secret value
) {
    /**
     * Gets the auth server URL (token endpoint without /protocol/openid-connect/token).
     * Some frameworks need the base realm URL, not the full token endpoint.
     */
    public String getAuthServerUrl() {
        // tokenEndpoint is like: http://localhost:8543/realms/wanaku
        return tokenEndpoint;
    }
}
