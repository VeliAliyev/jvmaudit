package dev.jvmaudit.core.rules;

import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.LicenseStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One licensing fact, as loaded from {@code rules/oracle-license-rules.yaml}.
 *
 * @param id stable rule id, reported with every classification so a result can be traced back
 * @param match the conditions under which the rule applies
 * @param status the licence the rule asserts
 * @param flags qualifiers to attach to the classification
 * @param summary one sentence of plain English
 * @param citations the primary sources for the statement; never empty
 * @param confidence whether a cited source states this directly
 * @param effectiveFrom the date the licence term the rule describes started, or null
 * @param effectiveTo the date it ends, or null
 */
public record LicenseRule(
    String id,
    RuleMatch match,
    LicenseStatus status,
    Set<ClassificationFlag> flags,
    String summary,
    List<Citation> citations,
    Confidence confidence,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {

  public LicenseRule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(match, "match");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(confidence, "confidence");
    flags = Set.copyOf(Objects.requireNonNullElse(flags, Set.of()));
    citations = List.copyOf(Objects.requireNonNull(citations, "citations"));
    if (citations.isEmpty()) {
      throw new RuleDataException("Rule '" + id + "' has no citation; every rule must cite one.");
    }
  }
}
