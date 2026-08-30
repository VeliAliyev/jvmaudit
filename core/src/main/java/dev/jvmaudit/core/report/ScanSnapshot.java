package dev.jvmaudit.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scan read back from its JSON, reduced to what {@code jvmaudit diff} needs to compare two points
 * in time.
 *
 * <p>Reading back rather than re-scanning is the whole point: the weekly cron recipe in the README
 * writes a JSON file and diffs it against last week's, which is how a customer notices that an
 * Oracle JDK reappeared on a host. That makes this reader a compatibility surface, so it is
 * tolerant of missing fields and refuses only on a schema version it does not know.
 */
public record ScanSnapshot(
    int schemaVersion,
    String toolVersion,
    String rulesVersion,
    String host,
    String startedAt,
    Map<String, Entry> jvms) {

  /**
   * One installation as recorded in a scan file.
   *
   * @param path the JVM home, which is the identity used for comparison
   * @param product the product display name, or a placeholder
   * @param version the version as reported
   * @param status the licence status name
   * @param severity the severity name
   * @param ruleId the rule that produced the verdict, or null
   * @param summary the one-line explanation
   */
  public record Entry(
      String path,
      String product,
      String version,
      String status,
      String severity,
      String ruleId,
      String summary) {}

  public ScanSnapshot {
    // Insertion order is preserved deliberately. Map.copyOf returns an unordered map, which made
    // the order of a diff report vary between runs of the same two files - unacceptable for output
    // people store, compare and attach to an audit response.
    jvms =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNullElse(jvms, Map.of())));
  }

  /**
   * Reads a scan file written by {@link JsonReport}.
   *
   * @param file the JSON file
   * @return the snapshot
   * @throws UncheckedIOException if the file cannot be read
   * @throws IllegalArgumentException if it is not a JVMAudit scan, or its schema is from the future
   */
  public static ScanSnapshot read(Path file) {
    String json;
    try {
      json = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file, e);
    }
    return parse(json, file.toString());
  }

  /**
   * Parses a scan document.
   *
   * @param json the document
   * @param origin what to call it in error messages
   * @return the snapshot
   */
  public static ScanSnapshot parse(String json, String origin) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(json);
    } catch (IOException e) {
      throw new IllegalArgumentException(origin + " is not valid JSON.", e);
    }
    if (root == null || !root.isObject() || !root.has("jvms")) {
      throw new IllegalArgumentException(
          origin
              + " is not a JVMAudit scan file (no 'jvms' array). Produce one with:"
              + " jvmaudit scan --format json --out scan.json");
    }
    int schemaVersion = root.path("schemaVersion").asInt(0);
    if (schemaVersion > JsonReport.SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          origin
              + " was written with schema version "
              + schemaVersion
              + ", which this build of JVMAudit ("
              + JsonReport.SCHEMA_VERSION
              + ") does not understand. Upgrade JVMAudit.");
    }

    Map<String, Entry> jvms = new LinkedHashMap<>();
    for (JsonNode jvm : root.path("jvms")) {
      JsonNode classification = jvm.path("classification");
      String path = jvm.path("path").asText("");
      if (path.isEmpty()) {
        continue;
      }
      jvms.put(
          path,
          new Entry(
              path,
              jvm.path("product")
                  .path("displayName")
                  .asText(jvm.path("vendorString").asText("unidentified")),
              jvm.path("version").asText(jvm.path("versionString").asText("unknown")),
              classification.path("status").asText("UNKNOWN"),
              classification.path("severity").asText("UNKNOWN"),
              classification.path("ruleId").isNull()
                  ? null
                  : classification.path("ruleId").asText(null),
              classification.path("summary").asText("")));
    }

    return new ScanSnapshot(
        schemaVersion,
        root.path("tool").path("version").asText("unknown"),
        root.path("rulesVersion").asText("unknown"),
        root.path("host").path("hostname").asText("unknown"),
        root.path("scan").path("startedAt").asText("unknown"),
        jvms);
  }

  /** The paths in this snapshot, in file order. */
  public List<String> paths() {
    return new ArrayList<>(jvms.keySet());
  }
}
