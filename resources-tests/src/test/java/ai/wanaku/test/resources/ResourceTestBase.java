package ai.wanaku.test.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;

public abstract class ResourceTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceTestBase.class);

    @BeforeAll
    static void startFileProvider(TestInfo testInfo) throws Exception {
        LOG.info("File provider via ResourceProviderManager no longer supported — will be replaced by CIC");
    }

    @AfterEach
    void clearResources() {
        if (routerClient != null) {
            try {
                routerClient.clearAllResources();
            } catch (Exception e) {
                LOG.warn("Failed to clear resources: {}", e.getMessage());
            }
        }
    }

    @Override
    protected String getLogProfile() {
        return "file-provider";
    }

    protected Path createTestFile(String filename, String content) throws IOException {
        Path file = tempDataDir.resolve(filename);
        Files.writeString(file, content);
        LOG.debug("Created test file: {}", file);
        return file;
    }

    protected static boolean isFileAvailable(Path path) {
        if (path == null) {
            LOG.warn("Couldn't determine the path to the jar (config returned null)");
            return false;
        }

        final boolean exists = path.toFile().exists();
        if (!exists) {
            LOG.warn("The expected file doesn't exist at the location {}", path);
            return false;
        }

        return true;
    }
}
