package ai.wanaku.test.managers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.HealthCheckUtils;
import ai.wanaku.test.utils.PortUtils;

/**
 * Manages the Camel Integration Capability (CIC) process lifecycle.
 * CIC is the single capability provider — different capabilities are CIC instances
 * launched with different Camel route files. CIC exposes an MCP endpoint on its
 * HTTP port, which praxis registers as a forward.
 */
public class CamelCapabilityManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(CamelCapabilityManager.class);

    private final TestConfiguration config;
    private int httpPort;

    private String name;
    private String routesRef;
    private String rulesRef;
    private String dependenciesRef;

    public CamelCapabilityManager(TestConfiguration config) {
        this.config = config;
    }

    /**
     * Prepares CIC with route and config references.
     *
     * @param serviceName the service name (used as --name)
     * @param routesRef routes reference (e.g., "file:///path/to/routes.yaml")
     * @param rulesRef rules reference (can be null)
     * @param dependenciesRef dependencies reference (can be null)
     */
    public void prepare(String serviceName, String routesRef, String rulesRef, String dependenciesRef) {
        this.httpPort = PortUtils.findAvailablePort();
        this.name = serviceName;
        this.routesRef = routesRef;
        this.rulesRef = rulesRef;
        this.dependenciesRef = dependenciesRef;

        addSystemProperty("quarkus.http.port", String.valueOf(httpPort));

        LOG.debug("Camel Capability '{}' prepared with HTTP port {}", name, httpPort);
    }

    @Override
    protected List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(getExecutablePath().toAbsolutePath().toString());
        command.addAll(getProcessArguments());
        return command;
    }

    @Override
    protected Path getWorkingDirectory() {
        return null;
    }

    @Override
    protected void configureDataIsolation() {
        // CIC does not use Infinispan or service-home directories
    }

    @Override
    protected String getProcessName() {
        return "camel-capability";
    }

    @Override
    protected Path getExecutablePath() {
        return config.getCamelCapabilityJarPath();
    }

    @Override
    protected List<String> getProcessArguments() {
        List<String> args = new ArrayList<>();

        args.add("--name");
        args.add(name);

        args.add("--routes-ref");
        args.add(routesRef);

        if (rulesRef != null) {
            args.add("--rules-ref");
            args.add(rulesRef);
        }

        args.add("--dependencies");
        if (dependenciesRef != null) {
            args.add(dependenciesRef);
        } else {
            args.add("file://" + getOrCreateEmptyDepsFile().toAbsolutePath());
        }

        return args;
    }

    @Override
    protected boolean performHealthCheck() {
        return HealthCheckUtils.waitForPort("localhost", httpPort, config.getDefaultTimeout());
    }

    public String getName() {
        return name;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public String getMcpUrl() {
        return "http://localhost:" + httpPort + "/mcp";
    }

    private static Path getOrCreateEmptyDepsFile() {
        Path emptyDeps = Path.of("target", "empty-dependencies.txt");
        if (!emptyDeps.toFile().exists()) {
            try {
                Files.createDirectories(emptyDeps.getParent());
                Files.createFile(emptyDeps);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create empty dependencies file", e);
            }
        }
        return emptyDeps;
    }
}
