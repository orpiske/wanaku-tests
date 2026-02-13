package ai.wanaku.test.utils;

import ai.wanaku.test.WanakuTestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for process output logging and log file management.
 *
 * Log structure:
 * <pre>
 * logs/
 * ├── test-framework.log
 * ├── router/
 * │   ├── wanaku-router-HttpToolCliITCase-2026-02-04_15-35-09.log
 * │   └── wanaku-router-HttpToolRegistrationITCase-2026-02-04_15-36-01.log
 * └── http-capability/
 *     ├── HttpToolCliITCase/
 *     │   ├── shouldRegisterHttpToolViaCli-2026-02-04_15-35-12.log
 *     │   └── shouldListToolsViaCli-2026-02-04_15-35-18.log
 *     └── HttpToolRegistrationITCase/
 *         └── shouldRegisterHttpToolViaRestApi-2026-02-04_15-36-05.log
 * </pre>
 */
public final class LogUtils {

    private static final Logger LOG = LoggerFactory.getLogger(LogUtils.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());

    private LogUtils() {
        // Utility class
    }

    /**
     * Creates a log file for the router.
     *
     * @param testClassName the test class name (e.g., "HttpToolCliITCase")
     * @return the created log file in logs/router/
     * @throws IOException if the log file cannot be created
     */
    public static File createRouterLogFile(String testClassName) throws IOException {
        Path logDir = ensureDirectory("router");
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        String filename = String.format("wanaku-router-%s-%s.log", testClassName, timestamp);
        return createFile(logDir, filename);
    }

    /**
     * Creates a log file for a capability test.
     *
     * @param profile    the capability profile (e.g., "http-capability")
     * @param testClass  the test class name (e.g., "HttpToolCliITCase")
     * @param testMethod the test method name (e.g., "shouldRegisterHttpToolViaCli")
     * @param component  the component name (e.g., "http-tool-service")
     * @return the created log file
     * @throws IOException if the log file cannot be created
     */
    public static File createCapabilityLogFile(String profile, String testClass, String testMethod, String component)
            throws IOException {
        Path logDir = ensureDirectory(profile, testClass);
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        String filename = String.format("%s-%s.log", sanitizeFilename(testMethod), timestamp);
        return createFile(logDir, filename);
    }

    /**
     * Creates a log file for a test component (fallback).
     *
     * @param testName   the name of the test class or method
     * @param component  the component name (e.g., "router", "http-tool-service")
     * @return the created log file
     * @throws IOException if the log file cannot be created
     */
    public static File createLogFile(String testName, String component) throws IOException {
        // Legacy behavior - flat structure
        Path logDir = ensureLogDirectory();
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        String filename = String.format("%s-%s-%s.log", sanitizeFilename(testName), sanitizeFilename(component), timestamp);
        return createFile(logDir, filename);
    }

    /**
     * Ensures the base log directory exists.
     *
     * @return the path to the log directory
     * @throws IOException if the directory cannot be created
     */
    public static Path ensureLogDirectory() throws IOException {
        Path logDir = Path.of(WanakuTestConstants.LOG_DIR);
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
            LOG.debug("Created log directory: {}", logDir.toAbsolutePath());
        }
        return logDir;
    }

    /**
     * Ensures a subdirectory exists within the log directory.
     *
     * @param subdirs the subdirectory path components
     * @return the path to the subdirectory
     * @throws IOException if the directory cannot be created
     */
    public static Path ensureDirectory(String... subdirs) throws IOException {
        Path logDir = ensureLogDirectory();
        for (String subdir : subdirs) {
            logDir = logDir.resolve(sanitizeFilename(subdir));
        }
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
            LOG.debug("Created log directory: {}", logDir.toAbsolutePath());
        }
        return logDir;
    }

    /**
     * Creates a file in the specified directory.
     */
    private static File createFile(Path directory, String filename) throws IOException {
        File logFile = directory.resolve(filename).toFile();
        logFile.createNewFile();
        LOG.debug("Created log file: {}", logFile.getAbsolutePath());
        return logFile;
    }

    /**
     * Sanitizes a string for use in a filename.
     *
     * @param input the input string
     * @return sanitized string safe for filenames
     */
    private static String sanitizeFilename(String input) {
        if (input == null) {
            return "unknown";
        }
        return input.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

}
