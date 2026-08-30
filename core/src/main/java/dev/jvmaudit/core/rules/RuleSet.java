package dev.jvmaudit.core.rules;

import dev.jvmaudit.core.model.Citation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The complete rule data JVMAudit reasons with: the licence rules, the product catalogue, the
 * release-date catalogue, and the versions of each. Reported in every scan output so a finding can
 * be reproduced against the exact rules that produced it.
 *
 * @param rulesVersion the {@code rulesVersion} stamped on the licence rules file
 * @param disclaimer the not-legal-advice disclaimer shown wherever a classification is shown
 * @param sources every cited source, by id
 * @param rules the licence rules, in evaluation order
 * @param products the product catalogue
 * @param releases the release-date catalogue
 */
public record RuleSet(
    String rulesVersion,
    String disclaimer,
    Map<String, Citation> sources,
    List<LicenseRule> rules,
    ProductCatalog products,
    ReleaseCatalog releases) {

  public RuleSet {
    Objects.requireNonNull(rulesVersion, "rulesVersion");
    Objects.requireNonNull(disclaimer, "disclaimer");
    sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
    rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    Objects.requireNonNull(products, "products");
    Objects.requireNonNull(releases, "releases");
  }

  /**
   * Looks a rule up by id.
   *
   * @param id the rule id
   * @return the rule, or empty
   */
  public Optional<LicenseRule> rule(String id) {
    return rules.stream().filter(r -> r.id().equals(id)).findFirst();
  }
}
