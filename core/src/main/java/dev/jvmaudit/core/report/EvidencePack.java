package dev.jvmaudit.core.report;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.rules.RuleSet;
import dev.jvmaudit.core.rules.RulesLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A zip a company can hand to its lawyer, its consultant, or Oracle: what was on this host, when,
 * and exactly which rules said so.
 *
 * <p>The pack holds the scan in both machine and human form, a verbatim copy of the rule data the
 * verdicts came from, and a manifest of SHA-256 hashes.
 *
 * <p><b>What the manifest proves, and what it does not.</b> It proves the pack is internally
 * consistent: nobody edited {@code report.html} to remove a row after the pack was generated,
 * because the hash would no longer match. It proves nothing about <em>who</em> generated it or
 * <em>when</em>, because there is no signature and no trusted timestamp - anyone holding this tool
 * can produce a pack saying anything and recompute the hashes to match. Saying otherwise would be
 * the single most damaging thing this product could claim, so the limitation is written into the
 * manifest and into the README inside the pack, not only into the documentation.
 */
public final class EvidencePack {

  /** The version of the manifest schema. */
  public static final int MANIFEST_SCHEMA_VERSION = 1;

  /** The canonical scan output. */
  public static final String FINDINGS_JSON = "findings.json";

  /** The flat projection, for spreadsheets. */
  public static final String FINDINGS_CSV = "findings.csv";

  /** The human-readable report. */
  public static final String REPORT_HTML = "report.html";

  /** The licence rules, verbatim. */
  public static final String RULES_SNAPSHOT = "rules-snapshot.yaml";

  /** The product catalogue, verbatim. */
  public static final String VENDORS_SNAPSHOT = "rules-snapshot-vendors.yaml";

  /** The release-date catalogue, verbatim. */
  public static final String RELEASES_SNAPSHOT = "rules-snapshot-releases.json";

  /** What the pack is and what it does not prove. */
  public static final String README = "README.txt";

  /** The hashes. Not hashed itself, for the obvious reason. */
  public static final String MANIFEST_JSON = "manifest.json";

  private static final String LIMITATIONS =
      "This manifest proves that the files listed in it have not been altered since this pack was"
          + " generated: re-hash them and compare. It does NOT prove who generated the pack, on"
          + " which machine, or when. There is no digital signature and no trusted timestamp in"
          + " this version, so the host metadata and the timestamp below are simply what the"
          + " scanning machine reported about itself, and anyone with a copy of JVMAudit could"
          + " produce a pack containing different content with a matching manifest.";

  private EvidencePack() {}

  /**
   * One file inside the pack.
   *
   * @param name the entry name
   * @param sha256 the lowercase hex SHA-256 of its bytes
   * @param bytes its length
   */
  public record Entry(String name, String sha256, long bytes) {}

  /**
   * What was written.
   *
   * @param file the zip that was written
   * @param entries the hashed files, in pack order
   */
  public record Written(Path file, List<Entry> entries) {}

  /**
   * Writes an evidence pack.
   *
   * @param zipFile where to write it; parent directories are created
   * @param result the scan to preserve
   * @param rules the rule set the scan was classified with
   * @return what was written
   * @throws IOException if the file cannot be written
   */
  public static Written write(Path zipFile, ScanResult result, RuleSet rules) throws IOException {
    Map<String, byte[]> contents = new LinkedHashMap<>();
    contents.put(FINDINGS_JSON, utf8(JsonReport.render(result, rules.disclaimer())));
    contents.put(FINDINGS_CSV, utf8(CsvReport.render(result)));
    contents.put(REPORT_HTML, utf8(HtmlReport.render(result, rules.disclaimer())));
    contents.put(
        RULES_SNAPSHOT, utf8(RulesLoader.readClasspathFile(RulesLoader.LICENSE_RULES_FILE)));
    contents.put(VENDORS_SNAPSHOT, utf8(RulesLoader.readClasspathFile(RulesLoader.VENDORS_FILE)));
    contents.put(RELEASES_SNAPSHOT, utf8(RulesLoader.readClasspathFile(RulesLoader.RELEASES_FILE)));
    contents.put(README, utf8(readme(result, rules)));

    List<Entry> entries = new ArrayList<>(contents.size());
    for (Map.Entry<String, byte[]> file : contents.entrySet()) {
      entries.add(new Entry(file.getKey(), sha256(file.getValue()), file.getValue().length));
    }

    byte[] manifest = utf8(manifest(result, rules, entries));

    Path parent = zipFile.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    try (OutputStream out = Files.newOutputStream(zipFile);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.setComment("JVMAudit evidence pack. See " + README + " for what the manifest proves.");
      // The manifest goes first so that a reader unzipping by hand meets it before the data.
      writeEntry(zip, MANIFEST_JSON, manifest, result);
      for (Map.Entry<String, byte[]> file : contents.entrySet()) {
        writeEntry(zip, file.getKey(), file.getValue(), result);
      }
    }

    return new Written(zipFile, List.copyOf(entries));
  }

  /**
   * Entry timestamps are pinned to the scan's own start time rather than "now", so that writing two
   * packs from one scan produces byte-identical zips. A pack whose bytes change every time it is
   * written is harder to argue about, not easier.
   */
  private static void writeEntry(
      ZipOutputStream zip, String name, byte[] content, ScanResult result) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(result.startedAt().toEpochMilli());
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }

  private static String manifest(ScanResult result, RuleSet rules, List<Entry> entries) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();
    root.put("schemaVersion", MANIFEST_SCHEMA_VERSION);

    ObjectNode tool = root.putObject("tool");
    tool.put("name", "jvmaudit");
    tool.put("version", result.toolVersion());

    root.put("rulesVersion", rules.rulesVersion());
    root.put("productCatalogVersion", rules.products().catalogVersion());
    root.put("createdAtUtc", result.startedAt().toString());
    root.put("hashAlgorithm", "SHA-256");

    ObjectNode host = root.putObject("host");
    host.put("hostname", result.host());
    host.put("user", result.user());
    host.put("osName", result.osName());
    host.put("osVersion", result.osVersion());
    host.put("osArch", result.osArch());

    ArrayNode files = root.putArray("files");
    for (Entry entry : entries) {
      ObjectNode node = files.addObject();
      node.put("name", entry.name());
      node.put("sha256", entry.sha256());
      node.put("bytes", entry.bytes());
    }

    root.put("selfHashed", false);
    root.put("signed", false);
    root.put("limitations", LIMITATIONS);
    root.put("disclaimer", rules.disclaimer());

    try {
      DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
      DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
      printer.indentObjectsWith(indenter);
      printer.indentArraysWith(indenter);
      return mapper.writer(printer).writeValueAsString(root) + "\n";
    } catch (IOException e) {
      throw new IllegalStateException("Could not render the evidence manifest", e);
    }
  }

  private static String readme(ScanResult result, RuleSet rules) {
    return String.join(
            "\n",
            "JVMAudit evidence pack",
            "======================",
            "",
            "Host:            " + result.host(),
            "Operating system: "
                + result.osName()
                + " "
                + result.osVersion()
                + " ("
                + result.osArch()
                + ")",
            "Scanned as:      " + result.user(),
            "Scan started:    " + result.startedAt() + " (UTC)",
            "Deep sweep:      " + (result.deep() ? "yes" : "no"),
            "JVMAudit:        " + result.toolVersion(),
            "Licence rules:   " + rules.rulesVersion(),
            "",
            "Summary:         " + result.summaryLine(),
            "",
            "Contents",
            "--------",
            MANIFEST_JSON + "                SHA-256 of every other file, plus host metadata.",
            FINDINGS_JSON + "                the scan, in full, in the canonical machine format.",
            FINDINGS_CSV + "                 the same findings as one row per installation.",
            REPORT_HTML + "                  the human-readable report. Opens offline; it makes no",
            "                             network requests and contains no scripts.",
            RULES_SNAPSHOT + "           the licence rules used, verbatim, with their sources.",
            VENDORS_SNAPSHOT + "   the product catalogue used, verbatim.",
            RELEASES_SNAPSHOT + " the release-date data used, verbatim.",
            "",
            "Verifying this pack",
            "-------------------",
            "Unzip it, then for each file listed in " + MANIFEST_JSON + " compute its SHA-256 and",
            "compare. For example:",
            "",
            "    sha256sum " + FINDINGS_JSON + " " + REPORT_HTML,
            "    certutil -hashfile " + FINDINGS_JSON + " SHA256     (Windows)",
            "",
            "What this pack proves, and what it does not",
            "-------------------------------------------",
            LIMITATIONS,
            "",
            rules.disclaimer(),
            "")
        + "\n";
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The lowercase hex SHA-256 of some bytes.
   *
   * @param content the bytes to hash
   * @return 64 hex characters
   */
  public static String sha256(byte[] content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is required of every Java platform.
      throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
    }
  }
}
