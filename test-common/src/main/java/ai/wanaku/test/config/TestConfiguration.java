package ai.wanaku.test.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import ai.wanaku.test.WanakuTestConstants;

public class TestConfiguration {

    private final Path routerJarPath;
    private final Path praxisBinaryPath;
    private final Path httpToolServiceJarPath;
    private final Path fileProviderJarPath;
    private final Path camelCapabilityJarPath;
    private final Path artifactsDir;
    private final Path tempDataDir;
    private final Duration defaultTimeout;

    private TestConfiguration(Builder builder) {
        this.routerJarPath = builder.routerJarPath;
        this.praxisBinaryPath = builder.praxisBinaryPath;
        this.httpToolServiceJarPath = builder.httpToolServiceJarPath;
        this.fileProviderJarPath = builder.fileProviderJarPath;
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
                .routerJarPath(findJar(artifactsDir, "wanaku-router"))
                .praxisBinaryPath(findPraxisBinary(artifactsDir))
                .httpToolServiceJarPath(findJar(artifactsDir, "wanaku-tool-service-http"))
                .fileProviderJarPath(findJar(artifactsDir, "wanaku-provider-file"))
                .camelCapabilityJarPath(findJar(artifactsDir, "camel-integration-capability"))
                .defaultTimeout(timeout)
                .build();
    }

    public boolean isPraxisMode() {
        return praxisBinaryPath != null && praxisBinaryPath.toFile().exists();
    }

    private static Path findPraxisBinary(Path artifactsDir) {
        String explicitPath = System.getProperty(WanakuTestConstants.PROP_PRAXIS_BINARY);
        if (explicitPath == null) {
            return null;
        }
        return Path.of(explicitPath).toAbsolutePath().normalize();
    }

    private static Path findJar(Path artifactsDir, String prefix) {
        String propKey;
        if (prefix.contains("router")) {
            propKey = WanakuTestConstants.PROP_ROUTER_JAR;
        } else if (prefix.contains("provider-file")) {
            propKey = WanakuTestConstants.PROP_FILE_PROVIDER_JAR;
        } else if (prefix.contains("camel-integration-capability")) {
            propKey = WanakuTestConstants.PROP_CAMEL_CAPABILITY_JAR;
        } else {
            propKey = WanakuTestConstants.PROP_HTTP_SERVICE_JAR;
        }
        String explicitPath = System.getProperty(propKey);
        if (explicitPath != null) {
            return Path.of(explicitPath).toAbsolutePath().normalize();
        }

        if (Files.exists(artifactsDir)) {
            try {
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

                    Path fatJar = Files.list(quarkusAppDir)
                            .filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .findFirst()
                            .orElse(null);
                    if (fatJar != null) {
                        return fatJar;
                    }
                }

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

    public Path getPraxisBinaryPath() {
        return praxisBinaryPath;
    }

    public Path getHttpToolServiceJarPath() {
        return httpToolServiceJarPath;
    }

    public Path getFileProviderJarPath() {
        return fileProviderJarPath;
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
        private Path routerJarPath;
        private Path praxisBinaryPath;
        private Path httpToolServiceJarPath;
        private Path fileProviderJarPath;
        private Path camelCapabilityJarPath;
        private Path artifactsDir;
        private Path tempDataDir;
        private Duration defaultTimeout = WanakuTestConstants.DEFAULT_TIMEOUT;

        public Builder routerJarPath(Path routerJarPath) {
            this.routerJarPath = routerJarPath;
            return this;
        }

        public Builder praxisBinaryPath(Path praxisBinaryPath) {
            this.praxisBinaryPath = praxisBinaryPath;
            return this;
        }

        public Builder httpToolServiceJarPath(Path httpToolServiceJarPath) {
            this.httpToolServiceJarPath = httpToolServiceJarPath;
            return this;
        }

        public Builder fileProviderJarPath(Path fileProviderJarPath) {
            this.fileProviderJarPath = fileProviderJarPath;
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
