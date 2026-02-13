package ai.wanaku.test.config;

import ai.wanaku.test.WanakuTestConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuration holder for the entire test framework.
 * Created once per test suite and immutable after initialization.
 */
public class TestConfiguration {

    private final Path routerJarPath;
    private final Path httpToolServiceJarPath;
    private final Path cliPath;
    private final Path artifactsDir;
    private final Path tempDataDir;
    private final Duration defaultTimeout;

    private TestConfiguration(Builder builder) {
        this.routerJarPath = builder.routerJarPath;
        this.httpToolServiceJarPath = builder.httpToolServiceJarPath;
        this.cliPath = builder.cliPath;
        this.artifactsDir = builder.artifactsDir;
        this.tempDataDir = builder.tempDataDir;
        this.defaultTimeout = builder.defaultTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a configuration with sensible defaults from system properties.
     */
    public static TestConfiguration fromSystemProperties() {
        String artifactsDirStr = System.getProperty(WanakuTestConstants.PROP_ARTIFACTS_DIR,
                WanakuTestConstants.DEFAULT_ARTIFACTS_DIR);
        Path artifactsDir = Path.of(artifactsDirStr);

        String timeoutStr = System.getProperty(WanakuTestConstants.PROP_TIMEOUT, "60");
        Duration timeout = Duration.ofSeconds(Long.parseLong(timeoutStr.replaceAll("[^0-9]", "")));

        return builder()
                .artifactsDir(artifactsDir)
                .routerJarPath(findJar(artifactsDir, "wanaku-router"))
                .httpToolServiceJarPath(findJar(artifactsDir, "wanaku-tool-service-http"))
                .cliPath(Optional.ofNullable(System.getProperty(WanakuTestConstants.PROP_CLI_PATH))
                        .map(Path::of)
                        .orElse(null))
                .defaultTimeout(timeout)
                .build();
    }

    private static Path findJar(Path artifactsDir, String prefix) {
        // Check system property first
        String propKey = prefix.contains("router") ?
                WanakuTestConstants.PROP_ROUTER_JAR :
                WanakuTestConstants.PROP_HTTP_SERVICE_JAR;
        String explicitPath = System.getProperty(propKey);
        if (explicitPath != null) {
            return Path.of(explicitPath);
        }

        // Search in artifacts directory
        if (Files.exists(artifactsDir)) {
            try {
                // First try: look for Quarkus app directory (fast-jar format)
                Path quarkusAppDir = Files.list(artifactsDir)
                        .filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .findFirst()
                        .orElse(null);

                if (quarkusAppDir != null) {
                    Path quarkusRunJar = quarkusAppDir.resolve("quarkus-run.jar");
                    if (Files.exists(quarkusRunJar)) {
                        return quarkusRunJar;
                    }
                }

                // Second try: look for standalone JAR file
                return Files.list(artifactsDir)
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .findFirst()
                        .orElse(null);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public Path getRouterJarPath() {
        return routerJarPath;
    }

    public Path getHttpToolServiceJarPath() {
        return httpToolServiceJarPath;
    }

    public Path getCliPath() {
        return cliPath;
    }

    public Path getArtifactsDir() {
        return artifactsDir;
    }

    public Path getTempDataDir() {
        return tempDataDir;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public static class Builder {
        private Path routerJarPath;
        private Path httpToolServiceJarPath;
        private Path cliPath;
        private Path artifactsDir;
        private Path tempDataDir;
        private Duration defaultTimeout = WanakuTestConstants.DEFAULT_TIMEOUT;

        public Builder routerJarPath(Path routerJarPath) {
            this.routerJarPath = routerJarPath;
            return this;
        }

        public Builder httpToolServiceJarPath(Path httpToolServiceJarPath) {
            this.httpToolServiceJarPath = httpToolServiceJarPath;
            return this;
        }

        public Builder cliPath(Path cliPath) {
            this.cliPath = cliPath;
            return this;
        }

        public Builder artifactsDir(Path artifactsDir) {
            this.artifactsDir = artifactsDir;
            return this;
        }

        public Builder tempDataDir(Path tempDataDir) {
            this.tempDataDir = tempDataDir;
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public TestConfiguration build() {
            return new TestConfiguration(this);
        }
    }
}
