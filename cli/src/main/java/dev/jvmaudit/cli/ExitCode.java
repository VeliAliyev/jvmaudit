package dev.jvmaudit.cli;

/**
 * The process exit codes, which are a published interface.
 *
 * <p>{@code --fail-on} is what lets a scan sit in a CI pipeline or a cron job and fail the build
 * when an Oracle JDK turns up. That is worth getting exactly right and never changing: a pipeline
 * that starts passing because an exit code was renumbered fails silently, which is the worst way
 * for a compliance check to fail.
 */
public final class ExitCode {

  /** Nothing matched {@code --fail-on}, and the scan completed. */
  public static final int CLEAN = 0;

  /** The scan completed and something matched {@code --fail-on}. */
  public static final int MATCHES_FOUND = 1;

  /** Part of the scan failed, so the inventory cannot be trusted to be complete. */
  public static final int SCAN_ERROR = 2;

  /** The command line itself was wrong. Picocli's own convention. */
  public static final int USAGE_ERROR = 2;

  private ExitCode() {}
}
