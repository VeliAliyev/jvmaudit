package dev.jvmaudit.core.model;

/**
 * How much attention a finding deserves. This is the column the console table colours, and it is
 * deliberately separate from {@link LicenseStatus} so that a flag can raise the urgency of an
 * otherwise free installation without misstating its licence.
 */
public enum Severity {

  /** Nothing to do. */
  OK("FREE", "OK"),

  /** Free today, but conditional, time-boxed, or dependent on how the installation is used. */
  REVIEW("REVIEW", "REVIEW"),

  /** Commercial or production use of this installation most likely needs a paid Oracle licence. */
  ACTION("ORACLE PAID LIKELY", "ACTION"),

  /** JVMAudit could not determine the licence and refuses to guess. */
  UNKNOWN("UNKNOWN", "UNKNOWN");

  private final String label;
  private final String shortLabel;

  Severity(String label, String shortLabel) {
    this.label = label;
    this.shortLabel = shortLabel;
  }

  /** The label shown in the status column of the report. */
  public String label() {
    return label;
  }

  /** A short label for narrow output such as CSV. */
  public String shortLabel() {
    return shortLabel;
  }

  /**
   * The more urgent of two severities. {@link #UNKNOWN} never overrides a known severity, because
   * "we could not tell" is less actionable than "this one costs money".
   *
   * @param a first severity, never null
   * @param b second severity, never null
   * @return whichever of the two the user should look at first
   */
  public static Severity max(Severity a, Severity b) {
    if (a == UNKNOWN) {
      return b == UNKNOWN ? UNKNOWN : b;
    }
    if (b == UNKNOWN) {
      return a;
    }
    return a.ordinal() >= b.ordinal() ? a : b;
  }
}
