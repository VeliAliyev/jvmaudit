package dev.jvmaudit.core.detect;

import dev.jvmaudit.core.model.LicenseStatus;
import dev.jvmaudit.core.model.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything one scan produced: the installations, the problems, and enough context to reproduce
 * the run. This is the object the JSON, CSV and HTML outputs are projections of, and the thing an
 * evidence pack preserves.
 *
 * @param jvms the installations found, ordered most urgent first
 * @param issues what went wrong or what limited the scan's completeness
 * @param startedAt when the scan started, in UTC
 * @param duration how long it took
 * @param host the machine's host name, as far as it can be determined
 * @param osName the operating system name
 * @param osVersion the operating system version
 * @param osArch the machine architecture
 * @param user the account the scan ran as
 * @param toolVersion the JVMAudit version that produced it
 * @param rulesVersion the version of the licence rules it used
 * @param deep whether the deep filesystem sweep ran
 */
public record ScanResult(
    List<DetectedJvm> jvms,
    List<ScanIssue> issues,
    Instant startedAt,
    Duration duration,
    String host,
    String osName,
    String osVersion,
    String osArch,
    String user,
    String toolVersion,
    String rulesVersion,
    boolean deep) {

  public ScanResult {
    jvms = List.copyOf(Objects.requireNonNullElse(jvms, List.of()));
    issues = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
  }

  /** How many installations were found. */
  public int total() {
    return jvms.size();
  }

  /**
   * How many installations fall into each severity, including zeroes, in severity order.
   *
   * @return counts keyed by severity
   */
  public Map<Severity, Integer> countsBySeverity() {
    Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
    for (Severity severity : Severity.values()) {
      counts.put(severity, 0);
    }
    for (DetectedJvm jvm : jvms) {
      counts.merge(jvm.severity(), 1, Integer::sum);
    }
    return counts;
  }

  /**
   * How many installations carry each licence status.
   *
   * @return counts keyed by status
   */
  public Map<LicenseStatus, Integer> countsByStatus() {
    Map<LicenseStatus, Integer> counts = new EnumMap<>(LicenseStatus.class);
    for (LicenseStatus status : LicenseStatus.values()) {
      counts.put(status, 0);
    }
    for (DetectedJvm jvm : jvms) {
      counts.merge(jvm.classification().status(), 1, Integer::sum);
    }
    return counts;
  }

  /** Whether any installation is most likely to need a paid Oracle licence. */
  public boolean hasOraclePaidLikely() {
    return jvms.stream()
        .anyMatch(jvm -> jvm.classification().status() == LicenseStatus.ORACLE_PAID_LIKELY);
  }

  /** Whether any installation is an Oracle-licensed product of any kind, free or not. */
  public boolean hasAnyOracleLicensed() {
    return jvms.stream().anyMatch(jvm -> jvm.classification().status().isOracleLicensed());
  }

  /** Whether any part of the scan failed outright. */
  public boolean hasErrors() {
    return issues.stream().anyMatch(issue -> issue.level() == ScanIssue.Level.ERROR);
  }

  /** A one-line summary of the counts, for the end of the console report. */
  public String summaryLine() {
    Map<Severity, Integer> counts = countsBySeverity();
    return total()
        + (total() == 1 ? " JVM found: " : " JVMs found: ")
        + counts.get(Severity.OK)
        + " free, "
        + counts.get(Severity.REVIEW)
        + " review, "
        + counts.get(Severity.ACTION)
        + " Oracle-paid-likely, "
        + counts.get(Severity.UNKNOWN)
        + " unknown.";
  }
}
