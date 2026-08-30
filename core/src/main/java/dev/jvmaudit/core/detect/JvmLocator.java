package dev.jvmaudit.core.detect;

import java.util.List;
import java.util.function.Consumer;

/**
 * One way of finding Java installations. The scanner runs every applicable locator and merges what
 * they return, so a JVM that several of them find is reported once, with all of its provenance.
 */
public interface JvmLocator {

  /** A short name for this strategy, used in scan output. */
  String name();

  /** Whether this locator can do anything useful on this machine with these options. */
  default boolean isApplicable(ScanOptions options) {
    return true;
  }

  /**
   * Finds candidate JVM homes.
   *
   * @param options what the user asked for
   * @param issues receives anything that went wrong or that limits the result
   * @return candidate homes; duplicates are fine, the scanner deduplicates
   */
  List<JvmCandidate> locate(ScanOptions options, Consumer<ScanIssue> issues);
}
