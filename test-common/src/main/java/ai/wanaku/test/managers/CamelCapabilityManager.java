package ai.wanaku.test.managers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.HealthCheckUtils;
import ai.wanaku.test.utils.PortUtils;

public class CamelCapabilityManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(CamelCapabilityManager.class);

    private final TestConfiguration config;
    private int httpPort;

    private String name;
    private String routesRef;
    private String dependenciesRef;
    private String registrationUrl;

    public CamelCapabilityManager(TestConfiguration config) {
        this.config = config;
    }

    public void prepare(String serviceName, String routesRef, String dependenciesRef) {
        this.httpPort = PortUtils.findAvailablePort();
        this.name = serviceName;
        this.routesRef = routesRef;
        this.dependenciesRef = dependenciesRef;

        LOG.debug("Camel Capability '{}' prepared with MCP port {}", name, httpPort);
    }

    public void setRegistrationUrl(String registrationUrl) {
        this.registrationUrl = registrationUrl;
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
    protected void configureDataIsolation() {}

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

        args.add("--mcp-port");
        args.add(String.valueOf(httpPort));

        if (dependenciesRef != null) {
            args.add("--dependencies");
            args.add(dependenciesRef);
        }

        if (registrationUrl != null) {
            args.add("--registration-url");
            args.add(registrationUrl);
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
}
