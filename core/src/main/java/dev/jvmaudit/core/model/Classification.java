package dev.jvmaudit.core.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What JVMAudit concluded about one installation, and why.
 *
 * <p>A classification always carries at least one citation. That is enforced by the constructor: an
 * unsourced licence claim is the one thing this tool must never emit.
 *
 * @param status the licence, or {@link LicenseStatus#UNKNOWN}
 * @param flags qualifiers that explain or escalate the result
 * @param summary one sentence of plain English the user can act on
 * @param citations where the claim comes from; never empty
 * @param confidence how well founded the claim is
 * @param ruleId the id of the rule that produced this result, or null when no rule matched
 * @param releaseDate the build's GA date as resolved during classification, or null
 * @param releaseDateSource where {@code releaseDate} came from, or null when it is null
 */
public record Classification(
    LicenseStatus status,
    Set<ClassificationFlag> flags,
    String summary,
    List<Citation> citations,
    Confidence confidence,
    String ruleId,
    LocalDate releaseDate,
    ReleaseDateSource releaseDateSource) {

  /** Where the release date used during classification came from. */
  public enum ReleaseDateSource {
    /** The {@code JAVA_VERSION_DATE} field of the installation's own release file. */
    RELEASE_FILE,
    /** Looked up by version in {@code rules/jdk-releases.json}. */
    RELEASE_CATALOG
  }

  public Classification {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(confidence, "confidence");
    citations = List.copyOf(Objects.requireNonNull(citations, "citations"));
    if (citations.isEmpty()) {
      throw new IllegalArgumentException(
          "A classification must cite at least one source; offending rule: " + ruleId);
    }
    flags = flags == null || flags.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(flags));
  }

  /**
   * How urgently the user should look at this installation: the status severity, raised by any flag
   * that carries a higher one.
   */
  public Severity severity() {
    Severity severity = status.severity();
    for (ClassificationFlag flag : flags) {
      severity = Severity.max(severity, flag.severity());
    }
    return severity;
  }

  /** Whether the result rests on a rule JVMAudit could not trace to a primary source. */
  public boolean isUnverified() {
    return confidence == Confidence.UNVERIFIED;
  }

  /**
   * A copy with one more flag, used by the detector to add findings the rules cannot know about,
   * such as {@link ClassificationFlag#POSSIBLY_VENDOR_BUNDLED}.
   *
   * @param flag the flag to add
   * @return a copy carrying the flag, or this classification if it already had it
   */
  public Classification withFlag(ClassificationFlag flag) {
    if (flags.contains(flag)) {
      return this;
    }
    List<ClassificationFlag> combined = new ArrayList<>(flags);
    combined.add(flag);
    return new Classification(
        status,
        EnumSet.copyOf(combined),
        summary,
        citations,
        confidence,
        ruleId,
        releaseDate,
        releaseDateSource);
  }
}
