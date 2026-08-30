package dev.jvmaudit.core.model;

/** The licence an installation is under, as far as JVMAudit can establish it. */
public enum LicenseStatus {

  /** An open source licence - GPLv2 with the Classpath Exception in practice. No Oracle cost. */
  FREE(Severity.OK, "Free (open source)"),

  /** Oracle's No-Fee Terms and Conditions: free for all uses, but only for a stated window. */
  ORACLE_FREE_NFTC(Severity.OK, "Oracle NFTC (free, time-boxed)"),

  /** GraalVM Free Terms and Conditions: free, with conditions on how it may be used. */
  ORACLE_FREE_GFTC(Severity.REVIEW, "Oracle GFTC (free, conditions apply)"),

  /** The pre-2019 Binary Code Licence: free for some uses, restricted for others. */
  LEGACY_BCL(Severity.REVIEW, "Oracle BCL (legacy, review)"),

  /** Oracle's OTN licence: free only for personal, development, test and demo use. */
  ORACLE_PAID_LIKELY(Severity.ACTION, "Oracle OTN (paid for commercial use)"),

  /** Not determined. JVMAudit says so rather than guessing. */
  UNKNOWN(Severity.UNKNOWN, "Unknown");

  private final Severity severity;
  private final String label;

  LicenseStatus(Severity severity, String label) {
    this.severity = severity;
    this.label = label;
  }

  /** The baseline severity for this status, before any flags are taken into account. */
  public Severity severity() {
    return severity;
  }

  /** A short human-readable label. */
  public String label() {
    return label;
  }

  /** Whether this status means the installation is an Oracle-licensed product. */
  public boolean isOracleLicensed() {
    return this == ORACLE_FREE_NFTC
        || this == ORACLE_FREE_GFTC
        || this == LEGACY_BCL
        || this == ORACLE_PAID_LIKELY;
  }
}
