package dev.jvmaudit.core.rules;

import dev.jvmaudit.core.model.Product;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Recognises which Java distribution an installation is, from what the installation says about
 * itself. Loaded from {@code rules/vendors.yaml}.
 *
 * <p>Entries are tried in file order and the first match wins, so the specific ones come first. An
 * installation that matches nothing yields an empty result, and the rules engine reports it as
 * unknown - JVMAudit does not fall back on "probably Oracle" or "probably fine".
 *
 * <p>Each entry offers several alternative ways to recognise the same product, because the evidence
 * available differs: a readable {@code release} file gives a vendor string, an installation that
 * has to be identified by running it gives only the {@code java -version} banner, and the licence
 * text plus the {@code SOURCE} field give a static answer that needs neither.
 */
public final class ProductCatalog {

  /**
   * What is known about one installation, gathered from its release file, from the licence text it
   * ships, and optionally from running it.
   *
   * @param implementor the raw {@code IMPLEMENTOR} field, may be null
   * @param implementorVersion the raw {@code IMPLEMENTOR_VERSION} field, may be null
   * @param runtimeName the runtime line from {@code java -version}, may be null
   * @param isJavaTm whether the runtime calls itself {@code Java(TM)}, null when unknown
   * @param sourceRepositories the raw {@code SOURCE} field, may be null
   * @param licenseKind the licence the installation ships, by name, may be null
   */
  public record Evidence(
      String implementor,
      String implementorVersion,
      String runtimeName,
      Boolean isJavaTm,
      String sourceRepositories,
      String licenseKind) {

    /** Evidence from a release file alone: nothing executed, no licence read. */
    public static Evidence of(String implementor, String implementorVersion) {
      return new Evidence(implementor, implementorVersion, null, null, null, null);
    }
  }

  /**
   * One way of recognising a product. Every condition stated must hold; a condition about evidence
   * that is missing does not hold.
   *
   * @param implementor required exact {@code IMPLEMENTOR} value, or null
   * @param implementorVersion required substring of {@code IMPLEMENTOR_VERSION}, or null
   * @param runtimeName required substring of the {@code java -version} runtime line, or null
   * @param requiresJavaTm required value of the {@code Java(TM)} discriminator, or null
   * @param sourceContains required substring of the {@code SOURCE} field, or null
   * @param sourceExcludes substring the {@code SOURCE} field must not contain, or null. The field
   *     must still be present: a missing {@code SOURCE} proves nothing, so it never satisfies an
   *     exclusion.
   * @param licenseKinds licences the installation may ship, any one of which satisfies this; empty
   *     when the condition says nothing about the licence
   */
  public record Condition(
      String implementor,
      String implementorVersion,
      String runtimeName,
      Boolean requiresJavaTm,
      String sourceContains,
      String sourceExcludes,
      List<String> licenseKinds) {

    public Condition {
      licenseKinds = List.copyOf(Objects.requireNonNullElse(licenseKinds, List.of()));
    }

    /** Whether this condition states nothing at all, which the loader rejects. */
    public boolean isEmpty() {
      return implementor == null
          && implementorVersion == null
          && runtimeName == null
          && requiresJavaTm == null
          && sourceContains == null
          && sourceExcludes == null
          && licenseKinds.isEmpty();
    }

    /** Whether this condition needs nothing that only executing the installation can provide. */
    public boolean isStatic() {
      return requiresJavaTm == null && runtimeName == null;
    }

    /**
     * Whether this condition holds for the given evidence.
     *
     * @param evidence what is known about the installation
     * @return true when every condition stated holds
     */
    public boolean matches(Evidence evidence) {
      if (implementor != null && !equalsIgnoringCase(implementor, evidence.implementor())) {
        return false;
      }
      if (implementorVersion != null
          && !containsIgnoringCase(evidence.implementorVersion(), implementorVersion)) {
        return false;
      }
      if (runtimeName != null && !containsIgnoringCase(evidence.runtimeName(), runtimeName)) {
        return false;
      }
      if (requiresJavaTm != null && !requiresJavaTm.equals(evidence.isJavaTm())) {
        return false;
      }
      if (sourceContains != null
          && !containsIgnoringCase(evidence.sourceRepositories(), sourceContains)) {
        return false;
      }
      if (sourceExcludes != null) {
        String source = evidence.sourceRepositories();
        if (source == null || source.isBlank() || containsIgnoringCase(source, sourceExcludes)) {
          return false;
        }
      }
      if (licenseKinds.isEmpty()) {
        return true;
      }
      // An installation whose licence could not be read satisfies no licence condition. The null
      // check is not optional: List.of(...).contains(null) throws.
      String actual = evidence.licenseKind();
      return actual != null && licenseKinds.contains(actual);
    }

    private static boolean equalsIgnoringCase(String expected, String actual) {
      return actual != null && expected.equalsIgnoreCase(actual.trim());
    }

    private static boolean containsIgnoringCase(String actual, String needle) {
      return actual != null
          && actual.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
  }

  /**
   * One catalogue entry: a product and the alternative ways of recognising it.
   *
   * @param product the product this entry recognises
   * @param conditions alternatives; the entry matches when any one of them does
   */
  public record Entry(Product product, List<Condition> conditions) {

    public Entry {
      Objects.requireNonNull(product, "product");
      conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
    }

    /**
     * Whether this entry recognises the given installation.
     *
     * @param evidence what is known about the installation
     * @return true when any alternative matches
     */
    public boolean matches(Evidence evidence) {
      for (Condition condition : conditions) {
        if (condition.matches(evidence)) {
          return true;
        }
      }
      return false;
    }
  }

  private final String catalogVersion;
  private final List<Entry> entries;

  /**
   * @param catalogVersion the {@code catalogVersion} field of the data file
   * @param entries the entries, in the order they should be tried
   */
  public ProductCatalog(String catalogVersion, List<Entry> entries) {
    this.catalogVersion = Objects.requireNonNullElse(catalogVersion, "unknown");
    this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
  }

  /** The version stamped on the catalogue data file. */
  public String catalogVersion() {
    return catalogVersion;
  }

  /** The entries, in the order they are tried. */
  public List<Entry> entries() {
    return entries;
  }

  /** Every product the catalogue knows, in file order. */
  public List<Product> products() {
    return entries.stream().map(Entry::product).toList();
  }

  /**
   * Looks a product up by its id.
   *
   * @param id the product id, for example {@code oracle-jdk}
   * @return the product, or empty if the catalogue has no such id
   */
  public Optional<Product> byId(String id) {
    return entries.stream().map(Entry::product).filter(p -> p.id().equals(id)).findFirst();
  }

  /**
   * Recognises an installation.
   *
   * @param evidence what is known about the installation
   * @return the recognised product, or empty
   */
  public Optional<Product> resolve(Evidence evidence) {
    return entries.stream().filter(e -> e.matches(evidence)).map(Entry::product).findFirst();
  }

  /**
   * Recognises an installation from the release file and an optional {@code java -version}, with no
   * licence evidence.
   *
   * @param implementor the raw {@code IMPLEMENTOR} field, may be null
   * @param implementorVersion the raw {@code IMPLEMENTOR_VERSION} field, may be null
   * @param runtimeName the runtime line from {@code java -version}, may be null
   * @param isJavaTm whether the runtime calls itself {@code Java(TM)}; null when unknown
   * @return the recognised product, or empty
   */
  public Optional<Product> resolve(
      String implementor, String implementorVersion, String runtimeName, Boolean isJavaTm) {
    return resolve(
        new Evidence(implementor, implementorVersion, runtimeName, isJavaTm, null, null));
  }
}
