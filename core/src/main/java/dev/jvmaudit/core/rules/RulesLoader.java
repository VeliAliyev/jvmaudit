package dev.jvmaudit.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.JavaVersion;
import dev.jvmaudit.core.model.LicenseStatus;
import dev.jvmaudit.core.model.Product;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads the three rule data files into a {@link RuleSet}.
 *
 * <p>The files ship inside the jar under {@code /rules} and can also be read from a directory,
 * which is how {@code jvmaudit rules} shows what it is using and how the evidence pack snapshots
 * the exact rules a scan ran with. Anything malformed raises {@link RuleDataException} rather than
 * being skipped: a silently half-loaded rule set would produce confidently wrong licence claims.
 */
public final class RulesLoader {

  /** File name of the licence rules. */
  public static final String LICENSE_RULES_FILE = "oracle-license-rules.yaml";

  /** File name of the product catalogue. */
  public static final String VENDORS_FILE = "vendors.yaml";

  /** File name of the release-date catalogue. */
  public static final String RELEASES_FILE = "jdk-releases.json";

  private static final String CLASSPATH_ROOT = "/rules/";

  /**
   * The licence names a match condition may use. Kept as strings rather than importing the detect
   * package, so that the rules layer does not depend on the detection layer.
   */
  private static final List<String> KNOWN_LICENSE_KINDS =
      List.of("NFTC", "OTN", "GFTC", "GPLV2", "UNRECOGNISED");

  private RulesLoader() {}

  /**
   * Loads the rule set packaged inside the jar.
   *
   * @return the loaded rule set
   * @throws RuleDataException if a file is missing or malformed
   */
  public static RuleSet fromClasspath() {
    return load(
        name -> {
          try (InputStream in = RulesLoader.class.getResourceAsStream(CLASSPATH_ROOT + name)) {
            if (in == null) {
              throw new RuleDataException("Rule data file is missing from the jar: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new RuleDataException("Could not read rule data file " + name, e);
          }
        });
  }

  /**
   * Loads the rule set from a directory of data files.
   *
   * @param directory a directory holding the three rule data files
   * @return the loaded rule set
   * @throws RuleDataException if a file is missing or malformed
   */
  public static RuleSet fromDirectory(Path directory) {
    return load(
        name -> {
          Path file = directory.resolve(name);
          try {
            return Files.readString(file, StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new RuleDataException("Could not read rule data file " + file, e);
          }
        });
  }

  /**
   * Reads one rule data file verbatim from the jar, for snapshotting into an evidence pack.
   *
   * @param name one of {@link #LICENSE_RULES_FILE}, {@link #VENDORS_FILE}, {@link #RELEASES_FILE}
   * @return the file's exact text
   * @throws RuleDataException if the file is missing
   */
  public static String readClasspathFile(String name) {
    try (InputStream in = RulesLoader.class.getResourceAsStream(CLASSPATH_ROOT + name)) {
      if (in == null) {
        throw new RuleDataException("Rule data file is missing from the jar: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuleDataException("Could not read rule data file " + name, e);
    }
  }

  /** Supplies the text of a named rule data file. */
  @FunctionalInterface
  private interface FileReader {
    String read(String name);
  }

  private static RuleSet load(FileReader reader) {
    ProductCatalog products = parseVendors(reader.read(VENDORS_FILE));
    ReleaseCatalog releases = parseReleases(reader.read(RELEASES_FILE));
    return parseLicenseRules(reader.read(LICENSE_RULES_FILE), products, releases);
  }

  // ------------------------------------------------------------------ vendors.yaml

  private static ProductCatalog parseVendors(String yaml) {
    Map<String, Object> root = loadYaml(yaml, VENDORS_FILE);
    String catalogVersion = string(root, "catalogVersion");
    List<Object> raw = list(root, "products", VENDORS_FILE);
    List<ProductCatalog.Entry> entries = new ArrayList<>(raw.size());
    List<String> ids = new ArrayList<>();
    for (Object item : raw) {
      Map<String, Object> node = map(item, VENDORS_FILE + " products entry");
      String id = required(node, "id", VENDORS_FILE);
      if (ids.contains(id)) {
        throw new RuleDataException("Duplicate product id in " + VENDORS_FILE + ": " + id);
      }
      ids.add(id);
      Product product =
          new Product(
              id,
              required(node, "displayName", VENDORS_FILE),
              required(node, "vendor", VENDORS_FILE),
              Boolean.TRUE.equals(node.get("oracle")),
              citations(node.get("citations"), VENDORS_FILE, id),
              Confidence.parse(string(node, "matchConfidence")));
      entries.add(new ProductCatalog.Entry(product, conditions(node.get("match"), id)));
    }
    if (entries.isEmpty()) {
      throw new RuleDataException(VENDORS_FILE + " declares no products.");
    }
    return new ProductCatalog(catalogVersion, entries);
  }

  /**
   * Parses a product's match conditions. A product may state several alternatives, because the
   * evidence available differs: a readable release file gives a vendor string, while an
   * installation identified by running it gives only the java -version banner.
   */
  private static List<ProductCatalog.Condition> conditions(Object node, String productId) {
    if (node == null) {
      throw new RuleDataException("Product '" + productId + "' states no match conditions.");
    }
    List<Object> raw = node instanceof List ? asList(node, "match of " + productId) : List.of(node);
    List<ProductCatalog.Condition> conditions = new ArrayList<>(raw.size());
    for (Object item : raw) {
      Map<String, Object> entry = map(item, VENDORS_FILE + " match for " + productId);
      ProductCatalog.Condition condition =
          new ProductCatalog.Condition(
              string(entry, "implementor"),
              string(entry, "implementorVersion"),
              string(entry, "runtimeName"),
              (Boolean) entry.get("requiresJavaTm"),
              string(entry, "sourceContains"),
              string(entry, "sourceExcludes"),
              licenseKinds(entry.get("licenseKind"), productId));
      if (condition.isEmpty()) {
        throw new RuleDataException(
            "Product '"
                + productId
                + "' has a match alternative with no conditions, which would"
                + " recognise every installation on earth.");
      }
      conditions.add(condition);
    }
    if (conditions.isEmpty()) {
      throw new RuleDataException("Product '" + productId + "' states no match conditions.");
    }
    return conditions;
  }

  /**
   * Parses the {@code licenseKind} condition, which accepts either one licence name or a list of
   * them. The names must be ones {@code LicenseText.Kind} knows, so a typo in the data file is
   * rejected at load time rather than silently matching nothing.
   */
  private static List<String> licenseKinds(Object node, String productId) {
    if (node == null) {
      return List.of();
    }
    List<Object> raw =
        node instanceof List ? asList(node, "licenseKind of " + productId) : List.of(node);
    List<String> kinds = new ArrayList<>(raw.size());
    for (Object item : raw) {
      String name = String.valueOf(item).trim().toUpperCase(Locale.ROOT);
      if (!KNOWN_LICENSE_KINDS.contains(name)) {
        throw new RuleDataException(
            "Product '"
                + productId
                + "' names an unknown licenseKind '"
                + item
                + "'. Known: "
                + String.join(", ", KNOWN_LICENSE_KINDS));
      }
      kinds.add(name);
    }
    return kinds;
  }

  private static List<Citation> citations(Object node, String file, String owner) {
    if (node == null) {
      return List.of();
    }
    List<Citation> result = new ArrayList<>();
    for (Object item : asList(node, file + " citations for " + owner)) {
      Map<String, Object> entry = map(item, file + " citation for " + owner);
      String url = required(entry, "url", file);
      result.add(new Citation(url, required(entry, "title", file), url));
    }
    return result;
  }

  // ------------------------------------------------------------------ jdk-releases.json

  private static ReleaseCatalog parseReleases(String json) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(json);
    } catch (IOException e) {
      throw new RuleDataException("Could not parse " + RELEASES_FILE, e);
    }
    JsonNode releases = root.get("releases");
    if (releases == null || !releases.isObject()) {
      throw new RuleDataException(RELEASES_FILE + " has no 'releases' object.");
    }
    Map<JavaVersion, LocalDate> dates = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = releases.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JavaVersion version = JavaVersion.parseOrNull(field.getKey());
      if (version == null) {
        throw new RuleDataException(
            RELEASES_FILE + " has an unparseable version key: " + field.getKey());
      }
      LocalDate date;
      try {
        date = LocalDate.parse(field.getValue().asText());
      } catch (DateTimeParseException e) {
        throw new RuleDataException(
            RELEASES_FILE + " has an unparseable date for " + field.getKey(), e);
      }
      dates.put(version, date);
    }
    return new ReleaseCatalog(dates);
  }

  // ------------------------------------------------------------------ oracle-license-rules.yaml

  private static RuleSet parseLicenseRules(
      String yaml, ProductCatalog products, ReleaseCatalog releases) {
    Map<String, Object> root = loadYaml(yaml, LICENSE_RULES_FILE);
    String rulesVersion = required(root, "rulesVersion", LICENSE_RULES_FILE);
    String disclaimer = required(root, "disclaimer", LICENSE_RULES_FILE);

    Map<String, Citation> sources = new LinkedHashMap<>();
    for (Object item : list(root, "sources", LICENSE_RULES_FILE)) {
      Map<String, Object> node = map(item, LICENSE_RULES_FILE + " sources entry");
      String id = required(node, "id", LICENSE_RULES_FILE);
      sources.put(
          id,
          new Citation(
              id,
              required(node, "title", LICENSE_RULES_FILE),
              required(node, "url", LICENSE_RULES_FILE)));
    }

    List<LicenseRule> rules = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (Object item : list(root, "rules", LICENSE_RULES_FILE)) {
      Map<String, Object> node = map(item, LICENSE_RULES_FILE + " rules entry");
      String id = required(node, "id", LICENSE_RULES_FILE);
      if (ids.contains(id)) {
        throw new RuleDataException("Duplicate rule id in " + LICENSE_RULES_FILE + ": " + id);
      }
      ids.add(id);

      RuleMatch match = parseMatch(map(node.get("match"), "match of rule " + id), id, products);
      if (match.isEmpty()) {
        throw new RuleDataException("Rule '" + id + "' states no match conditions.");
      }

      List<Citation> ruleCitations = new ArrayList<>();
      for (Object citationId : asList(node.get("citations"), "citations of rule " + id)) {
        Citation citation = sources.get(String.valueOf(citationId));
        if (citation == null) {
          throw new RuleDataException(
              "Rule '" + id + "' cites unknown source '" + citationId + "'.");
        }
        ruleCitations.add(citation);
      }

      rules.add(
          new LicenseRule(
              id,
              match,
              status(required(node, "status", LICENSE_RULES_FILE), id),
              flags(node.get("flags"), id),
              collapse(required(node, "summary", LICENSE_RULES_FILE)),
              ruleCitations,
              Confidence.parse(string(node, "confidence")),
              date(node.get("effectiveFrom"), "effectiveFrom of rule " + id),
              date(node.get("effectiveTo"), "effectiveTo of rule " + id),
              collapseOrNull(string(node, "remediation"))));
    }
    if (rules.isEmpty()) {
      throw new RuleDataException(LICENSE_RULES_FILE + " declares no rules.");
    }
    return new RuleSet(rulesVersion, collapse(disclaimer), sources, rules, products, releases);
  }

  private static RuleMatch parseMatch(
      Map<String, Object> node, String ruleId, ProductCatalog products) {
    String productId = string(node, "product");
    if (productId != null && products.byId(productId).isEmpty()) {
      throw new RuleDataException(
          "Rule '" + ruleId + "' matches unknown product '" + productId + "'.");
    }
    return new RuleMatch(
        productId,
        (Boolean) node.get("oracleProduct"),
        integer(node.get("feature"), "feature of rule " + ruleId),
        integer(node.get("featureMin"), "featureMin of rule " + ruleId),
        integer(node.get("featureMax"), "featureMax of rule " + ruleId),
        version(node.get("versionMin"), "versionMin of rule " + ruleId),
        version(node.get("versionMax"), "versionMax of rule " + ruleId),
        date(node.get("releasedOnOrAfter"), "releasedOnOrAfter of rule " + ruleId),
        date(node.get("releasedOnOrBefore"), "releasedOnOrBefore of rule " + ruleId));
  }

  private static LicenseStatus status(String text, String ruleId) {
    try {
      return LicenseStatus.valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new RuleDataException("Rule '" + ruleId + "' has unknown status '" + text + "'.", e);
    }
  }

  private static Set<ClassificationFlag> flags(Object node, String ruleId) {
    if (node == null) {
      return Set.of();
    }
    Set<ClassificationFlag> result = EnumSet.noneOf(ClassificationFlag.class);
    for (Object item : asList(node, "flags of rule " + ruleId)) {
      try {
        result.add(
            ClassificationFlag.valueOf(String.valueOf(item).trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new RuleDataException("Rule '" + ruleId + "' has unknown flag '" + item + "'.", e);
      }
    }
    return result;
  }

  // ------------------------------------------------------------------ small helpers

  private static Map<String, Object> loadYaml(String yaml, String file) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object loaded;
    try {
      loaded = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (RuntimeException e) {
      throw new RuleDataException("Could not parse " + file, e);
    }
    return map(loaded, file);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object node, String what) {
    if (!(node instanceof Map)) {
      throw new RuleDataException("Expected a mapping for " + what + " but found: " + node);
    }
    return (Map<String, Object>) node;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object node, String what) {
    if (node == null) {
      return List.of();
    }
    if (!(node instanceof List)) {
      throw new RuleDataException("Expected a list for " + what + " but found: " + node);
    }
    return (List<Object>) node;
  }

  private static List<Object> list(Map<String, Object> node, String key, String file) {
    Object value = node.get(key);
    if (value == null) {
      throw new RuleDataException(file + " has no '" + key + "' section.");
    }
    return asList(value, file + " '" + key + "'");
  }

  private static String string(Map<String, Object> node, String key) {
    Object value = node.get(key);
    return value == null ? null : String.valueOf(value).trim();
  }

  private static String required(Map<String, Object> node, String key, String file) {
    String value = string(node, key);
    if (value == null || value.isEmpty()) {
      throw new RuleDataException(file + " has an entry with no '" + key + "'.");
    }
    return value;
  }

  private static Integer integer(Object node, String what) {
    if (node == null) {
      return null;
    }
    if (node instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.valueOf(String.valueOf(node).trim());
    } catch (NumberFormatException e) {
      throw new RuleDataException("Expected a number for " + what + " but found: " + node, e);
    }
  }

  private static JavaVersion version(Object node, String what) {
    if (node == null) {
      return null;
    }
    JavaVersion parsed = JavaVersion.parseOrNull(String.valueOf(node));
    if (parsed == null) {
      throw new RuleDataException("Expected a version for " + what + " but found: " + node);
    }
    return parsed;
  }

  private static LocalDate date(Object node, String what) {
    if (node == null) {
      return null;
    }
    if (node instanceof Date date) {
      return date.toInstant().atZone(TimeZone.getTimeZone("UTC").toZoneId()).toLocalDate();
    }
    try {
      return LocalDate.parse(String.valueOf(node).trim());
    } catch (DateTimeParseException e) {
      throw new RuleDataException(
          "Expected an ISO-8601 date for " + what + " but found: " + node, e);
    }
  }

  private static String collapseOrNull(String text) {
    return text == null ? null : collapse(text);
  }

  /** Collapses the line breaks YAML folded scalars leave behind into single spaces. */
  private static String collapse(String text) {
    return text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
  }
}
