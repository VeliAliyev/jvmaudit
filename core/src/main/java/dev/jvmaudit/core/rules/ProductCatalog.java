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
 */
public final class ProductCatalog {

  /**
   * One catalogue entry: a product and the strings that identify it.
   *
   * @param product the product this entry recognises
   * @param implementor required exact {@code IMPLEMENTOR} value, or null
   * @param implementorVersion required substring of {@code IMPLEMENTOR_VERSION}, or null
   * @param runtimeName required substring of the {@code java -version} runtime line, or null
   * @param requiresJavaTm required value of the {@code Java(TM)} discriminator, or null
   */
  public record Entry(
      Product product,
      String implementor,
      String implementorVersion,
      String runtimeName,
      Boolean requiresJavaTm) {

    /**
     * Whether this entry recognises the given installation.
     *
     * @param actualImplementor the raw {@code IMPLEMENTOR} field, may be null
     * @param actualImplementorVersion the raw {@code IMPLEMENTOR_VERSION} field, may be null
     * @param actualRuntimeName the {@code java -version} runtime line, may be null
     * @param isJavaTm whether the runtime calls itself {@code Java(TM)}, null when unknown
     * @return true when every condition this entry states holds
     */
    public boolean matches(
        String actualImplementor,
        String actualImplementorVersion,
        String actualRuntimeName,
        Boolean isJavaTm) {
      if (implementor != null && !equalsIgnoringCase(implementor, actualImplementor)) {
        return false;
      }
      if (implementorVersion != null
          && !containsIgnoringCase(actualImplementorVersion, implementorVersion)) {
        return false;
      }
      if (runtimeName != null && !containsIgnoringCase(actualRuntimeName, runtimeName)) {
        return false;
      }
      return requiresJavaTm == null || requiresJavaTm.equals(isJavaTm);
    }

    private static boolean equalsIgnoringCase(String expected, String actual) {
      return actual != null && expected.equalsIgnoreCase(actual.trim());
    }

    private static boolean containsIgnoringCase(String actual, String needle) {
      return actual != null
          && actual.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
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
   * @param implementor the raw {@code IMPLEMENTOR} field, may be null
   * @param implementorVersion the raw {@code IMPLEMENTOR_VERSION} field, may be null
   * @param runtimeName the runtime line from {@code java -version}, may be null
   * @param isJavaTm whether the runtime calls itself {@code Java(TM)}; null when unknown, which
   *     deliberately prevents the Oracle JDK and Oracle OpenJDK entries from matching, since those
   *     two share a vendor string and differ only here
   * @return the recognised product, or empty
   */
  public Optional<Product> resolve(
      String implementor, String implementorVersion, String runtimeName, Boolean isJavaTm) {
    return entries.stream()
        .filter(e -> e.matches(implementor, implementorVersion, runtimeName, isJavaTm))
        .map(Entry::product)
        .findFirst();
  }
}
