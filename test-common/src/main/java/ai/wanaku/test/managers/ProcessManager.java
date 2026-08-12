package ai.wanaku.test.managers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.utils.LogUtils;

/**
 * Base class for managing Java processes (Router, HTTP Tool Service).
 * Provides start/stop lifecycle management with graceful shutdown.
 */
public abstract class ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessManager.class);

    protected Process process;
    protected File logFile;
    protected ProcessState state = ProcessState.STOPPED;
    protected final Map<String, String> environment = new HashMap<>();
    protected final List<String> jvmArgs = new ArrayList<>();

    // Log context for structured logging
    protected String logProfile;
    protected String logTestClass;
    protected String logTestMethod;

    public enum ProcessState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    /**
     * Gets the name of this process manager for logging.
     */
    protected abstract String getProcessName();

    /**
     * Gets the path to the executable (JAR or native binary) to run.
     */
    protected abstract Path getExecutablePath();

    /**
     * Gets the command line arguments for the process.
     */
    protected abstract List<String> getProcessArguments();

    /**
     * Builds the full command to start the process.
     * Default implementation launches a Java JAR. Override for native binaries.
     *
     * @return the command components
     */
    protected List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(getExecutablePath().getFileName().toString());
        command.addAll(getProcessArguments());
        return command;
    }

    /**
     * Gets the working directory for the process.
     * Default returns the parent of the executable path (for Quarkus fast-jar).
     * Override for processes that don't need a specific working directory.
     *
     * @return the working directory, or null to use the current directory
     */
    protected Path getWorkingDirectory() {
        Path execPath = getExecutablePath();
        return execPath != null ? execPath.getParent().toAbsolutePath().normalize() : null;
    }

    /**
     * Performs health check after process starts.
     * @return true if the process is healthy
     */
    protected abstract boolean performHealthCheck();

    /**
     * Sets the log context for structured log file creation.
     *
     * @param profile    the capability profile (e.g., "http-capability")
     * @param testClass  the test class name
     * @param testMethod the test method name
     */
    public void setLogContext(String profile, String testClass, String testMethod) {
        this.logProfile = profile;
        this.logTestClass = testClass;
        this.logTestMethod = testMethod;
    }

    /**
     * Starts the process.
     *
     * @param testName the name of the test for log file naming
     * @throws IOException if the process cannot be started
     */
    public void start(String testName) throws IOException {
        if (state != ProcessState.STOPPED) {
            throw new IllegalStateException("Process is already running: " + getProcessName());
        }

        Path execPath = getExecutablePath();
        if (execPath == null || !execPath.toFile().exists()) {
            throw new IllegalStateException("Executable not found: " + execPath);
        }

        state = ProcessState.STARTING;

        configureDataIsolation();

        LOG.debug("Starting {}", getProcessName());

        logFile = createLogFile(testName);

        List<String> command = buildCommand();
        Path workingDir = getWorkingDirectory();

        LOG.debug("Working directory: {}", workingDir);
        LOG.debug("Command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }

        if (!environment.isEmpty()) {
            pb.environment().putAll(environment);
        }

        pb.redirectOutput(logFile);
        pb.redirectErrorStream(true);

        process = pb.start();
        LOG.debug("{} started with PID: {}", getProcessName(), process.pid());

        if (performHealthCheck()) {
            state = ProcessState.RUNNING;
            LOG.debug("{} is healthy", getProcessName());
        } else {
            stop();
            throw new IllegalStateException(
                    getProcessName() + " failed health check. Check logs: " + logFile.getAbsolutePath());
        }
    }

    /**
     * Sets up isolated data directories. Override to change or skip for non-Java processes.
     */
    protected void configureDataIsolation() {
        Path dataDir = Path.of("target", "wanaku-data", getProcessName() + "-" + System.nanoTime());
        addSystemProperty(
                "wanaku.persistence.infinispan.base-folder",
                dataDir.resolve("router").toAbsolutePath().toString());
        addSystemProperty(
                "wanaku.service.service-home",
                dataDir.resolve("services").toAbsolutePath().toString());
    }

    /**
     * Stops the process with graceful shutdown.
     */
    public void stop() {
        if (process == null || !process.isAlive()) {
            state = ProcessState.STOPPED;
            return;
        }

        state = ProcessState.STOPPING;
        LOG.debug("Stopping {}", getProcessName());

        try {
            // Try graceful shutdown first (SIGTERM)
            process.destroy();

            boolean terminated =
                    process.waitFor(WanakuTestConstants.GRACEFUL_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            if (!terminated) {
                LOG.warn("{} did not stop gracefully, forcing shutdown", getProcessName());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            LOG.debug("{} stopped", getProcessName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("{} stop interrupted", getProcessName());
            process.destroyForcibly();
        } finally {
            state = ProcessState.STOPPED;
            process = null;
        }
    }

    /**
     * Checks if the process is running.
     */
    public boolean isRunning() {
        return process != null && process.isAlive() && state == ProcessState.RUNNING;
    }

    /**
     * Adds a system property as a JVM argument.
     */
    public void addSystemProperty(String key, String value) {
        jvmArgs.add("-D" + key + "=" + value);
    }

    /**
     * Adds an environment variable for the process.
     */
    public void addEnvironmentVariable(String key, String value) {
        environment.put(key, value);
    }

    /**
     * Gets the log file for this process.
     */
    public File getLogFile() {
        return logFile;
    }

    /**
     * Gets the process exit code, or -1 if the process is still running or hasn't started.
     */
    public int getExitCode() {
        if (process == null || process.isAlive()) {
            return -1;
        }
        return process.exitValue();
    }

    /**
     * Creates a log file for this process.
     * Override in subclasses for custom log file locations.
     *
     * @param testName the test name
     * @return the log file
     * @throws IOException if file creation fails
     */
    protected File createLogFile(String testName) throws IOException {
        if (logProfile != null && logTestClass != null && logTestMethod != null) {
            return LogUtils.createCapabilityLogFile(logProfile, logTestClass, logTestMethod);
        }
        return LogUtils.createLogFile(testName, getProcessName());
    }
}
