package dev.jvmaudit.core.detect;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Something that went wrong, or something the user should know about the completeness of a scan.
 *
 * <p>Issues are first-class output, not log noise. A scan that could not read half the filesystem,
 * or that could only see this user's own processes, has produced an incomplete inventory, and an
 * inventory that quietly understates the estate is worse than no inventory at all.
 *
 * @param level how much it matters
 * @param message what happened, in plain English
 * @param path the path it concerns, or null
 */
public record ScanIssue(Level level, String message, Path path) {

  /** How much an issue matters. */
  public enum Level {
    /** The scan is complete; this is context worth mentioning. */
    INFO,
    /** The scan may be incomplete because of this. */
    WARNING,
    /** Part of the scan failed outright. Drives exit code 2. */
    ERROR
  }

  public ScanIssue {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(message, "message");
  }

  /** An informational note. */
  public static ScanIssue info(String message) {
    return new ScanIssue(Level.INFO, message, null);
  }

  /** A warning that the scan may be incomplete. */
  public static ScanIssue warning(String message, Path path) {
    return new ScanIssue(Level.WARNING, message, path);
  }

  /** A failure of part of the scan. */
  public static ScanIssue error(String message, Path path) {
    return new ScanIssue(Level.ERROR, message, path);
  }

  @Override
  public String toString() {
    return path == null ? level + ": " + message : level + ": " + message + " (" + path + ")";
  }
}
