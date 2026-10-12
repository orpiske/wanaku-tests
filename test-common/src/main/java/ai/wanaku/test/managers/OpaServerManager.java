package ai.wanaku.test.managers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.HealthCheckUtils;
import ai.wanaku.test.utils.PortUtils;

/** Per-scenario native OPA process, with policies and data loaded through its REST API. */
public class OpaServerManager extends ProcessManager {

    private final Path binary;
    private final TestConfiguration config;
    private int port;

    public OpaServerManager(Path binary, TestConfiguration config) {
        this.binary = binary;
        this.config = config;
    }

    public void prepare() {
        port = PortUtils.findAvailablePort();
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Override
    protected String getProcessName() {
        return "opa-server";
    }

    @Override
    protected Path getExecutablePath() {
        return binary;
    }

    @Override
    protected List<String> getProcessArguments() {
        return List.of("run", "--server", "--addr=127.0.0.1:" + port);
    }

    @Override
    protected List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(binary.toAbsolutePath().toString());
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
    protected boolean performHealthCheck() {
        return HealthCheckUtils.waitForHealthy(getBaseUrl() + "/health", config.getDefaultTimeout());
    }
}
