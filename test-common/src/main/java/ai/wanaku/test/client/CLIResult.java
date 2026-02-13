package ai.wanaku.test.client;

import java.time.Duration;

/**
 * Result of a CLI command execution.
 */
public class CLIResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final Duration duration;

    public CLIResult(int exitCode, String stdout, String stderr, Duration duration) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.duration = duration;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public String getCombinedOutput() {
        if (stderr == null || stderr.isEmpty()) {
            return stdout;
        }
        return stdout + "\n" + stderr;
    }

    @Override
    public String toString() {
        return "CLIResult{" +
                "exitCode=" + exitCode +
                ", duration=" + duration.toMillis() + "ms" +
                ", stdout='" + (stdout.length() > 100 ? stdout.substring(0, 100) + "..." : stdout) + '\'' +
                '}';
    }
}
