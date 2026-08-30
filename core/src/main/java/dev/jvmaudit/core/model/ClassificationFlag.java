package dev.jvmaudit.core.model;

/**
 * A qualifier attached to a classification. Flags never change the licence status - they explain
 * why an otherwise clear result still deserves a look, and some of them raise its severity.
 */
public enum ClassificationFlag {

  /** How the installation is used decides whether the licence is satisfied. */
  REVIEW_REQUIRED(Severity.REVIEW, "How this installation is used decides whether it is licensed"),

  /** The licence permits free use only under conditions that JVMAudit cannot check. */
  CONDITIONS_APPLY(
      Severity.REVIEW, "Free use is conditional - read the licence against your usage"),

  /** Oracle has announced a licence change that has not taken effect yet. */
  FUTURE_ANNOUNCED_CHANGE(Severity.REVIEW, "Oracle has announced a licence change for this line"),

  /** The free licence window for this line is closing. */
  NFTC_WINDOW_CLOSING(Severity.REVIEW, "The free NFTC window for this line is closing"),

  /** Staying on this build keeps the free licence but forgoes further security updates. */
  NO_FURTHER_FREE_UPDATES(
      Severity.REVIEW, "This line receives no further updates under the free licence"),

  /** The build's release date could not be determined, and the rule needed it. */
  DATE_UNKNOWN(Severity.UNKNOWN, "The build's release date could not be determined"),

  /** The rule that produced this result is an inference, not a quotation from a primary source. */
  UNVERIFIED_RULE(
      Severity.REVIEW, "This rule is JVMAudit's inference, not a quoted Oracle statement"),

  /**
   * The JVM sits inside another product's installation directory, so that product's vendor may
   * already license it. JVMAudit says "verify with the application vendor", never "you owe money".
   */
  POSSIBLY_VENDOR_BUNDLED(
      Severity.REVIEW,
      "Bundled inside another application - verify with that application's vendor before acting"),

  /** The product could not be identified, so no rule could apply. */
  PRODUCT_UNIDENTIFIED(Severity.UNKNOWN, "The vendor of this installation was not recognised");

  private final Severity severity;
  private final String description;

  ClassificationFlag(Severity severity, String description) {
    this.severity = severity;
    this.description = description;
  }

  /** The severity this flag contributes. */
  public Severity severity() {
    return severity;
  }

  /** One line explaining what the flag means, for the report. */
  public String description() {
    return description;
  }
}
