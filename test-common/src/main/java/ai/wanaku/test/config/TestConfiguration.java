package ai.wanaku.test.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import ai.wanaku.test.WanakuTestConstants;

public class TestConfiguration {

    private final Path praxisBinaryPath;
    private final Path camelCapabilityJarPath;
    private final Path artifactsDir;
    private final Path tempDataDir;
    private final Duration defaultTimeout;

    private TestConfiguration(Builder builder) {
        this.praxisBinaryPath = builder.praxisBinaryPath;
        this.camelCapabilityJarPath = builder.camelCapabilityJarPath;
        this.artifactsDir = builder.artifactsDir;
        this.tempDataDir = builder.tempDataDir;
        this.defaultTimeout = builder.defaultTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TestConfiguration fromSystemProperties() {
        String artifactsDirStr =
                System.getProperty(WanakuTestConstants.PROP_ARTIFACTS_DIR, WanakuTestConstants.DEFAULT_ARTIFACTS_DIR);
        Path artifactsDir = Path.of(artifactsDirStr).toAbsolutePath().normalize();

        String timeoutStr = System.getProperty(WanakuTestConstants.PROP_TIMEOUT, "60");
        Duration timeout = Duration.ofSeconds(Long.parseLong(timeoutStr.replaceAll("[^0-9]", "")));

        return builder()
                .artifactsDir(artifactsDir)
                .praxisBinaryPath(findPraxisBinary())
                .camelCapabilityJarPath(findCicJar(artifactsDir))
                .defaultTimeout(timeout)
                .build();
    }

    private static Path findPraxisBinary() {
        String explicitPath = System.getProperty(WanakuTestConstants.PROP_PRAXIS_BINARY);
        if (explicitPath == null) {
            return null;
        }
        return Path.of(explicitPath).toAbsolutePath().normalize();
    }

    private static Path findCicJar(Path artifactsDir) {
        String explicitPath = System.getProperty(WanakuTestConstants.PROP_CAMEL_CAPABILITY_JAR);
        if (explicitPath != null) {
            return Path.of(explicitPath).toAbsolutePath().normalize();
        }

        if (Files.exists(artifactsDir)) {
            try (var stream = Files.list(artifactsDir)) {
                Path cicDir = stream.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("camel-integration-capability"))
                        .findFirst()
                        .orElse(null);

                if (cicDir != null) {
                    try (var jarStream = Files.list(cicDir)) {
                        return jarStream
                                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                .findFirst()
                                .orElse(null);
                    }
                }
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public Path getPraxisBinaryPath() {
        return praxisBinaryPath;
    }

    public Path getCamelCapabilityJarPath() {
        return camelCapabilityJarPath;
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
        private Path praxisBinaryPath;
        private Path camelCapabilityJarPath;
        private Path artifactsDir;
        private Path tempDataDir;
        private Duration defaultTimeout = WanakuTestConstants.DEFAULT_TIMEOUT;

        public Builder praxisBinaryPath(Path praxisBinaryPath) {
            this.praxisBinaryPath = praxisBinaryPath;
            return this;
        }

        public Builder camelCapabilityJarPath(Path camelCapabilityJarPath) {
            this.camelCapabilityJarPath = camelCapabilityJarPath;
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
