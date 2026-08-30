package dev.jvmaudit.core.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmaudit.core.detect.DetectedJvm;
import dev.jvmaudit.core.detect.DetectionSource;
import dev.jvmaudit.core.detect.ScanIssue;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.Classification;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.model.LicenseStatus;
import dev.jvmaudit.core.model.Severity;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The canonical machine-readable output.
 *
 * <p>JSON is the format everything else is a projection of, the format {@code jvmaudit diff} reads
 * back, and the format an evidence pack preserves, so its schema is a promise. It is built field by
 * field rather than by reflecting over the model classes, so that renaming a Java field cannot
 * silently change the published shape. {@link #SCHEMA_VERSION} goes up if it ever has to.
 *
 * <p>Keys are ordered and the output is pretty-printed with a trailing newline, so two scans of the
 * same machine produce a diffable file.
 */
public final class JsonReport {

  /** The version of the JSON schema this class writes. */
  public static final int SCHEMA_VERSION = 1;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Line endings are pinned to LF rather than the platform's, so that scanning the same estate on
   * Windows and on Linux produces byte-identical JSON. An evidence pack hashes this file, and a
   * hash that changes with the operating system would be worthless as evidence.
   */
  private static final ObjectWriter WRITER = writer();

  private static ObjectWriter writer() {
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    return MAPPER.writer(printer);
  }

  private JsonReport() {}

  /**
   * Renders a scan as JSON.
   *
   * @param result the scan to render
   * @param disclaimer the not-legal-advice disclaimer from the rule set
   * @return the JSON document, ending in a newline
   */
  public static String render(ScanResult result, String disclaimer) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schemaVersion", SCHEMA_VERSION);

    ObjectNode tool = root.putObject("tool");
    tool.put("name", "jvmaudit");
    tool.put("version", result.toolVersion());

    root.put("rulesVersion", result.rulesVersion());
    root.put("disclaimer", disclaimer);

    ObjectNode scan = root.putObject("scan");
    scan.put("startedAt", result.startedAt().toString());
    scan.put("durationMillis", result.duration().toMillis());
    scan.put("deep", result.deep());

    ObjectNode host = root.putObject("host");
    host.put("hostname", result.host());
    host.put("user", result.user());
    ObjectNode os = host.putObject("os");
    os.put("name", result.osName());
    os.put("version", result.osVersion());
    os.put("arch", result.osArch());

    ObjectNode summary = root.putObject("summary");
    summary.put("total", result.total());
    ObjectNode bySeverity = summary.putObject("bySeverity");
    for (Map.Entry<Severity, Integer> entry : result.countsBySeverity().entrySet()) {
      bySeverity.put(entry.getKey().name(), entry.getValue());
    }
    ObjectNode byStatus = summary.putObject("byStatus");
    for (Map.Entry<LicenseStatus, Integer> entry : result.countsByStatus().entrySet()) {
      byStatus.put(entry.getKey().name(), entry.getValue());
    }

    ArrayNode jvms = root.putArray("jvms");
    for (DetectedJvm jvm : result.jvms()) {
      jvms.add(renderJvm(jvm));
    }

    ArrayNode issues = root.putArray("issues");
    for (ScanIssue issue : result.issues()) {
      ObjectNode node = issues.addObject();
      node.put("level", issue.level().name());
      node.put("message", issue.message());
      putPath(node, "path", issue.path());
    }

    return write(root);
  }

  private static ObjectNode renderJvm(DetectedJvm jvm) {
    JvmFingerprint fingerprint = jvm.fingerprint();
    ObjectNode node = MAPPER.createObjectNode();

    putPath(node, "path", jvm.path());
    ArrayNode aliases = node.putArray("aliases");
    for (Path alias : jvm.aliases()) {
      aliases.add(alias.toString());
    }
    ArrayNode sources = node.putArray("sources");
    for (DetectionSource source : DetectionSource.values()) {
      if (jvm.sources().contains(source)) {
        sources.add(source.name());
      }
    }

    if (fingerprint.product() == null) {
      node.putNull("product");
    } else {
      ObjectNode product = node.putObject("product");
      product.put("id", fingerprint.product().id());
      product.put("displayName", fingerprint.product().displayName());
      product.put("vendor", fingerprint.product().vendor());
      product.put("oracle", fingerprint.product().oracle());
    }

    node.put("vendorString", fingerprint.vendor());
    node.put("implementorVersion", fingerprint.implementorVersion());
    node.put("version", fingerprint.version() == null ? null : fingerprint.version().canonical());
    node.put("versionString", fingerprint.versionString());
    node.put("runtimeVersion", fingerprint.runtimeVersion());
    node.put(
        "javaVersionDate",
        fingerprint.javaVersionDate() == null ? null : fingerprint.javaVersionDate().toString());
    node.put("buildType", fingerprint.buildType());
    node.put("javaTm", fingerprint.isJavaTm());
    node.put("identifiedFrom", fingerprint.source().name());
    putPath(node, "bundledInside", fingerprint.bundledInside());

    node.set("classification", renderClassification(jvm.classification(), jvm.severity()));
    return node;
  }

  private static ObjectNode renderClassification(Classification classification, Severity severity) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("status", classification.status().name());
    node.put("severity", severity.name());
    node.put("confidence", classification.confidence().name());
    node.put("ruleId", classification.ruleId());
    node.put("summary", classification.summary());
    node.put("remediation", classification.remediation());

    ArrayNode flags = node.putArray("flags");
    for (ClassificationFlag flag : ClassificationFlag.values()) {
      if (classification.flags().contains(flag)) {
        flags.add(flag.name());
      }
    }

    node.put(
        "releaseDate",
        classification.releaseDate() == null ? null : classification.releaseDate().toString());
    node.put(
        "releaseDateSource",
        classification.releaseDateSource() == null
            ? null
            : classification.releaseDateSource().name());

    ArrayNode citations = node.putArray("citations");
    for (Citation citation : classification.citations()) {
      ObjectNode entry = citations.addObject();
      entry.put("id", citation.id());
      entry.put("title", citation.title());
      entry.put("url", citation.url());
    }
    return node;
  }

  private static void putPath(ObjectNode node, String field, Path path) {
    if (path == null) {
      node.putNull(field);
    } else {
      node.put(field, path.toString());
    }
  }

  private static String write(ObjectNode root) {
    try {
      return WRITER.writeValueAsString(root) + "\n";
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("Could not render the scan as JSON", e);
    }
  }

  /** The field order the schema promises, for tests that guard against accidental reshaping. */
  public static List<String> topLevelFields() {
    return List.of(
        "schemaVersion",
        "tool",
        "rulesVersion",
        "disclaimer",
        "scan",
        "host",
        "summary",
        "jvms",
        "issues");
  }
}
