package dev.jvmaudit.core.rules;

import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.Classification;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.JavaVersion;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.model.LicenseStatus;
import dev.jvmaudit.core.model.Product;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a {@link JvmFingerprint} into a {@link Classification} by applying the licence rules.
 *
 * <p>Rules are evaluated in file order and the first match wins. Nothing is inferred beyond what
 * the rules state: an installation whose product is unrecognised, or whose version or build date a
 * matching rule needed and did not have, is reported as {@link LicenseStatus#UNKNOWN}.
 */
public final class LicenseRulesEngine {

  private final RuleSet ruleSet;

  /**
   * @param ruleSet the rule data to classify against
   */
  public LicenseRulesEngine(RuleSet ruleSet) {
    this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet");
  }

  /** An engine over the rule data packaged in the jar. */
  public static LicenseRulesEngine usingPackagedRules() {
    return new LicenseRulesEngine(RulesLoader.fromClasspath());
  }

  /** The rule data this engine reasons with. */
  public RuleSet ruleSet() {
    return ruleSet;
  }

  /**
   * Classifies one installation.
   *
   * @param fingerprint what is known about the installation; never null, but its fields may be
   * @return the classification, always carrying at least one citation
   */
  public Classification classify(JvmFingerprint fingerprint) {
    Objects.requireNonNull(fingerprint, "fingerprint");

    Product product = fingerprint.product();
    JavaVersion version = fingerprint.version();
    ResolvedDate resolved = resolveReleaseDate(fingerprint);

    if (product == null) {
      return unidentified(fingerprint, resolved);
    }

    for (LicenseRule rule : ruleSet.rules()) {
      if (rule.match().matches(product, version, resolved.date())) {
        return build(rule, product, resolved);
      }
    }
    return unmatched(product, resolved);
  }

  /**
   * Classifies every installation in a list, preserving order.
   *
   * @param fingerprints the installations
   * @return one classification per fingerprint
   */
  public List<Classification> classifyAll(List<JvmFingerprint> fingerprints) {
    List<Classification> results = new ArrayList<>(fingerprints.size());
    for (JvmFingerprint fingerprint : fingerprints) {
      results.add(classify(fingerprint));
    }
    return results;
  }

  private Classification build(LicenseRule rule, Product product, ResolvedDate resolved) {
    Set<ClassificationFlag> flags = EnumSet.noneOf(ClassificationFlag.class);
    flags.addAll(rule.flags());
    if (rule.confidence() == Confidence.UNVERIFIED) {
      flags.add(ClassificationFlag.UNVERIFIED_RULE);
    }

    // The rule's own sources come first; the product's provenance is appended so the reader can
    // also check why JVMAudit decided this installation is that vendor's build.
    Set<Citation> citations = new LinkedHashSet<>(rule.citations());
    citations.addAll(product.citations());

    return new Classification(
        rule.status(),
        flags,
        rule.summary(),
        List.copyOf(citations),
        Confidence.min(rule.confidence(), product.matchConfidence()),
        rule.id(),
        resolved.date(),
        resolved.source());
  }

  private Classification unidentified(JvmFingerprint fingerprint, ResolvedDate resolved) {
    String vendor = fingerprint.vendor();
    String summary =
        vendor == null || vendor.isBlank()
            ? "This installation does not say who built it, so JVMAudit cannot tell which licence"
                + " applies - open its release file or run its bin/java -version by hand."
            : "JVMAudit does not recognise the vendor string '"
                + vendor
                + "', so it will not guess at the licence - please open an issue so the vendor can"
                + " be added.";
    return new Classification(
        LicenseStatus.UNKNOWN,
        Set.of(ClassificationFlag.PRODUCT_UNIDENTIFIED),
        summary,
        List.of(faqCitation()),
        Confidence.VERIFIED,
        null,
        resolved.date(),
        resolved.source());
  }

  private Classification unmatched(Product product, ResolvedDate resolved) {
    return new Classification(
        LicenseStatus.UNKNOWN,
        Set.of(),
        "No licence rule covers this "
            + product.displayName()
            + " build, so JVMAudit will not guess - please open an issue with its release file.",
        List.of(faqCitation()),
        Confidence.VERIFIED,
        null,
        resolved.date(),
        resolved.source());
  }

  private Citation faqCitation() {
    Citation faq = ruleSet.sources().get("oracle-faq");
    return faq != null
        ? faq
        : new Citation(
            "oracle-faq",
            "Oracle Java SE Licensing FAQ",
            "https://www.oracle.com/java/technologies/javase/jdk-faqs.html");
  }

  private ResolvedDate resolveReleaseDate(JvmFingerprint fingerprint) {
    if (fingerprint.javaVersionDate() != null) {
      return new ResolvedDate(
          fingerprint.javaVersionDate(), Classification.ReleaseDateSource.RELEASE_FILE);
    }
    Optional<LocalDate> fromCatalog = ruleSet.releases().gaDate(fingerprint.version());
    return fromCatalog
        .map(date -> new ResolvedDate(date, Classification.ReleaseDateSource.RELEASE_CATALOG))
        .orElseGet(() -> new ResolvedDate(null, null));
  }

  private record ResolvedDate(LocalDate date, Classification.ReleaseDateSource source) {}
}
