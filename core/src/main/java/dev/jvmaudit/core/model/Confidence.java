package dev.jvmaudit.core.model;

import java.util.Locale;

/**
 * How well founded a statement in the rule data is. This is displayed to the user: a licence claim
 * JVMAudit cannot trace to a primary source must never look like one it can.
 */
public enum Confidence {

  /** A cited primary source makes this statement directly. */
  VERIFIED,

  /** A reasonable inference that no cited source states outright. Shown as such in the report. */
  UNVERIFIED;

  /**
   * Parses the spelling used in the rule data files.
   *
   * @param text {@code verified} or {@code unverified}, case-insensitive; null yields {@link
   *     #UNVERIFIED}
   * @return the parsed confidence, defaulting to {@link #UNVERIFIED} for anything unrecognised so
   *     that a typo in the data files understates rather than overstates certainty
   */
  public static Confidence parse(String text) {
    if (text == null) {
      return UNVERIFIED;
    }
    return "verified".equals(text.trim().toLowerCase(Locale.ROOT)) ? VERIFIED : UNVERIFIED;
  }

  /** The lower of two confidences: any unverified input makes the whole result unverified. */
  public static Confidence min(Confidence a, Confidence b) {
    return a == UNVERIFIED || b == UNVERIFIED ? UNVERIFIED : VERIFIED;
  }
}
