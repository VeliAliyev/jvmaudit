package dev.jvmaudit.core.detect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command. The two places JVMAudit shells out - {@code java -version} and the
 * Windows {@code reg} tool - go through this, so both can be exercised in tests without a real
 * Oracle JDK or a real Windows registry, and so every external call has a timeout.
 */
@FunctionalInterface
public interface ProcessRunner {

  /**
   * Runs a command and waits for it, up to a timeout.
   *
   * @param command the command and its arguments
   * @param timeout how long to wait before giving up and destroying the process
   * @return what the command produced
   */
  Result run(List<String> command, Duration timeout);

  /**
   * What a command produced.
   *
   * @param exitCode the exit code, or -1 if the command timed out or could not be started
   * @param stdout standard output, with standard error merged in
   * @param stderr kept for callers that separate the two; empty from {@link #system()}
   * @param timedOut whether the timeout was hit
   * @param failure the reason the command could not be started at all, or null
   */
  record Result(int exitCode, String stdout, String stderr, boolean timedOut, String failure) {

    /** Whether the command ran to completion and reported success. */
    public boolean succeeded() {
      return exitCode == 0 && !timedOut && failure == null;
    }

    /** Everything the command printed, on either stream. */
    public String output() {
      if (stderr == null || stderr.isBlank()) {
        return stdout == null ? "" : stdout;
      }
      return stdout == null || stdout.isBlank() ? stderr : stdout + System.lineSeparator() + stderr;
    }
  }

  /** The real thing: {@link ProcessBuilder}, with the timeout enforced. */
  static ProcessRunner system() {
    return (command, timeout) -> {
      Process process = null;
      try {
        // Standard error is merged into standard output on purpose: `java -version` writes to
        // stderr and `reg query` writes to stdout, and reading two pipes in sequence can deadlock
        // if either fills its buffer. One stream cannot.
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String combined = drain(process.getInputStream());
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
          process.destroyForcibly();
          return new Result(-1, combined, "", true, null);
        }
        return new Result(process.exitValue(), combined, "", false, null);
      } catch (IOException e) {
        return new Result(-1, "", "", false, e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        if (process != null) {
          process.destroyForcibly();
        }
        return new Result(-1, "", "", true, "interrupted");
      }
    };
  }

  private static String drain(InputStream stream) throws IOException {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
