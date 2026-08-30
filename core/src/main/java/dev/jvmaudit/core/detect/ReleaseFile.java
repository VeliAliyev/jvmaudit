package dev.jvmaudit.core.detect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code release} file every modern JDK and JRE ships in its home directory.
 *
 * <p>Reading this is the cheap, safe, non-invasive way to identify an installation, and JVMAudit
 * prefers it to executing anything. The format is {@code KEY="value"} lines.
 */
public final class ReleaseFile {

  private final Map<String, String> entries;

  private ReleaseFile(Map<String, String> entries) {
    this.entries = Map.copyOf(entries);
  }

  /** An empty release file, for an installation that has none. */
  public static ReleaseFile empty() {
    return new ReleaseFile(Map.of());
  }

  /**
   * Reads the release file from a JVM home.
   *
   * @param home the JVM home directory
   * @return the parsed file, or empty if it is absent or unreadable
   */
  public static Optional<ReleaseFile> read(Path home) {
    Path file = home.resolve(JvmHomes.RELEASE_FILE);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(parse(Files.readAllLines(file, StandardCharsets.UTF_8)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Parses release file lines.
   *
   * @param lines the file's lines
   * @return the parsed file
   */
  public static ReleaseFile parse(List<String> lines) {
    Map<String, String> entries = new LinkedHashMap<>();
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int equals = trimmed.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String key = trimmed.substring(0, equals).trim();
      String value = trimmed.substring(equals + 1).trim();
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      entries.put(key, value);
    }
    return new ReleaseFile(entries);
  }

  /** Whether the file held nothing usable. */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** Every key/value pair, in file order. */
  public Map<String, String> entries() {
    return entries;
  }

  /**
   * One field's value.
   *
   * @param key the field name, for example {@code IMPLEMENTOR}
   * @return the value, or null if the field is absent or blank
   */
  public String get(String key) {
    String value = entries.get(key);
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** The {@code IMPLEMENTOR} field: who built this JVM. */
  public String implementor() {
    return get("IMPLEMENTOR");
  }

  /** The {@code IMPLEMENTOR_VERSION} field: the vendor's own build identifier. */
  public String implementorVersion() {
    return get("IMPLEMENTOR_VERSION");
  }

  /** The {@code JAVA_VERSION} field. */
  public String javaVersion() {
    return get("JAVA_VERSION");
  }

  /** The {@code JAVA_RUNTIME_VERSION} field. */
  public String javaRuntimeVersion() {
    return get("JAVA_RUNTIME_VERSION");
  }

  /** The {@code BUILD_TYPE} field. */
  public String buildType() {
    return get("BUILD_TYPE");
  }

  /** The {@code SOURCE} field: the source repositories the build came from. */
  public String source() {
    return get("SOURCE");
  }

  /**
   * The {@code JAVA_VERSION_DATE} field, which is the build's GA date. Absent on Java 8 and
   * earlier, which is why {@code rules/jdk-releases.json} exists.
   *
   * @return the date, or empty if absent or unparseable
   */
  public Optional<LocalDate> javaVersionDate() {
    String value = get("JAVA_VERSION_DATE");
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(LocalDate.parse(value));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
