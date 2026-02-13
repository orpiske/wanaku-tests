package ai.wanaku.test.http;

import ai.wanaku.test.base.BaseIntegrationTest;

/**
 * Base class for HTTP capability integration tests.
 * Subclasses should be annotated with @QuarkusTest for Quarkus context.
 * Provides the log profile name for structured logging.
 */
public abstract class HttpCapabilityTestBase extends BaseIntegrationTest {

    @Override
    protected String getLogProfile() {
        return "http-capability";
    }
}
