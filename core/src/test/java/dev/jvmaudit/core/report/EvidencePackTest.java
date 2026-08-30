package dev.jvmaudit.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.rules.RuleSet;
import dev.jvmaudit.core.rules.RulesLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The M4 gate: write a pack, unzip it, re-hash every file, and check the manifest agrees.
 *
 * <p>These tests also police what the pack claims. An evidence pack that overstated what its hashes
 * prove would be worse than no pack at all, so the wording is asserted, not just the arithmetic.
 */
class EvidencePackTest {

  private static final RuleSet RULES = SampleScans.engine().ruleSet();

  @TempDir Path work;

  private Path zip;
  private Map<String, byte[]> unpacked;

  @BeforeEach
  void writeAndUnpack() throws IOException {
    zip = work.resolve("out").resolve("evidence.zip");
    EvidencePack.write(zip, SampleScans.deterministic(), RULES);
    unpacked = unzip(zip);
  }

  @Test
  void writesEveryFileThePackPromises() {
    assertThat(unpacked.keySet())
        .containsExactlyInAnyOrder(
            EvidencePack.MANIFEST_JSON,
            EvidencePack.FINDINGS_JSON,
            EvidencePack.FINDINGS_CSV,
            EvidencePack.REPORT_HTML,
            EvidencePack.RULES_SNAPSHOT,
            EvidencePack.VENDORS_SNAPSHOT,
            EvidencePack.RELEASES_SNAPSHOT,
            EvidencePack.README);
  }

  @Test
  void everyHashInTheManifestMatchesTheFileItNames() throws IOException {
    JsonNode manifest = manifest();

    List<String> hashed = new ArrayList<>();
    for (JsonNode file : manifest.get("files")) {
      String name = file.get("name").asText();
      byte[] content = unpacked.get(name);

      assertThat(content).as("manifest names %s, which is not in the pack", name).isNotNull();
      assertThat(EvidencePack.sha256(content))
          .as("hash mismatch for %s", name)
          .isEqualTo(file.get("sha256").asText());
      assertThat(content.length)
          .as("length mismatch for %s", name)
          .isEqualTo(file.get("bytes").asInt());
      hashed.add(name);
    }

    assertThat(hashed)
        .as("every file in the pack except the manifest itself must be hashed")
        .containsExactlyInAnyOrderElementsOf(
            unpacked.keySet().stream().filter(n -> !n.equals(EvidencePack.MANIFEST_JSON)).toList());
  }

  @Test
  void detectsAFileThatWasEditedAfterThePackWasGenerated() throws IOException {
    // Proves the check above can actually fail; a verification test that cannot detect tampering
    // proves nothing at all.
    JsonNode manifest = manifest();
    String recordedHash = null;
    for (JsonNode file : manifest.get("files")) {
      if (file.get("name").asText().equals(EvidencePack.REPORT_HTML)) {
        recordedHash = file.get("sha256").asText();
      }
    }

    byte[] tampered =
        new String(unpacked.get(EvidencePack.REPORT_HTML), StandardCharsets.UTF_8)
            .replace("ORACLE PAID LIKELY", "FREE")
            .getBytes(StandardCharsets.UTF_8);

    assertThat(EvidencePack.sha256(tampered)).isNotEqualTo(recordedHash);
  }

  @Test
  void carriesTheHostMetadataAndTheVersionsThatProducedIt() throws IOException {
    JsonNode manifest = manifest();

    assertThat(manifest.get("schemaVersion").asInt())
        .isEqualTo(EvidencePack.MANIFEST_SCHEMA_VERSION);
    assertThat(manifest.get("tool").get("name").asText()).isEqualTo("jvmaudit");
    assertThat(manifest.get("tool").get("version").asText()).isEqualTo("1.2.3-test");
    assertThat(manifest.get("rulesVersion").asText()).isEqualTo(RULES.rulesVersion());
    assertThat(manifest.get("productCatalogVersion").asText())
        .isEqualTo(RULES.products().catalogVersion());
    assertThat(manifest.get("hashAlgorithm").asText()).isEqualTo("SHA-256");
    assertThat(manifest.get("createdAtUtc").asText())
        .isEqualTo("2026-08-30T09:00:00Z")
        .endsWith("Z");

    JsonNode host = manifest.get("host");
    assertThat(host.get("hostname").asText()).isEqualTo("build-agent-07");
    assertThat(host.get("user").asText()).isEqualTo("svc-audit");
    assertThat(host.get("osName").asText()).isEqualTo("Linux");
    assertThat(host.get("osVersion").asText()).isEqualTo("6.8.0");
    assertThat(host.get("osArch").asText()).isEqualTo("amd64");
  }

  @Test
  void doesNotOverclaimWhatTheManifestProves() throws IOException {
    JsonNode manifest = manifest();
    String readme = text(EvidencePack.README);

    assertThat(manifest.get("signed").asBoolean()).isFalse();
    assertThat(manifest.get("selfHashed").asBoolean()).isFalse();

    for (String where : new String[] {manifest.get("limitations").asText(), readme}) {
      assertThat(where)
          .contains("does NOT prove who generated the pack")
          .contains("no digital signature")
          .contains("no trusted timestamp");
    }
    assertThat(readme).contains(RULES.disclaimer());
    assertThat(manifest.get("disclaimer").asText()).isEqualTo(RULES.disclaimer());
  }

  @Test
  void tellsTheReaderHowToVerifyItOnEitherPlatform() throws IOException {
    assertThat(text(EvidencePack.README))
        .contains("sha256sum")
        .contains("certutil -hashfile")
        .contains(EvidencePack.MANIFEST_JSON);
  }

  @Test
  void preservesTheRuleDataVerbatimSoAVerdictCanBeReproduced() throws IOException {
    assertThat(text(EvidencePack.RULES_SNAPSHOT))
        .isEqualTo(RulesLoader.readClasspathFile(RulesLoader.LICENSE_RULES_FILE));
    assertThat(text(EvidencePack.VENDORS_SNAPSHOT))
        .isEqualTo(RulesLoader.readClasspathFile(RulesLoader.VENDORS_FILE));
    assertThat(text(EvidencePack.RELEASES_SNAPSHOT))
        .isEqualTo(RulesLoader.readClasspathFile(RulesLoader.RELEASES_FILE));

    // The snapshot is a working rule set, not just bytes: it can be loaded and used again.
    Path extracted = work.resolve("rules");
    Files.createDirectories(extracted);
    Files.write(
        extracted.resolve(RulesLoader.LICENSE_RULES_FILE),
        unpacked.get(EvidencePack.RULES_SNAPSHOT));
    Files.write(
        extracted.resolve(RulesLoader.VENDORS_FILE), unpacked.get(EvidencePack.VENDORS_SNAPSHOT));
    Files.write(
        extracted.resolve(RulesLoader.RELEASES_FILE), unpacked.get(EvidencePack.RELEASES_SNAPSHOT));

    RuleSet reloaded = RulesLoader.fromDirectory(extracted);
    assertThat(reloaded.rulesVersion()).isEqualTo(RULES.rulesVersion());
    assertThat(reloaded.rules()).hasSameSizeAs(RULES.rules());
  }

  @Test
  void theReportInThePackRendersOffline() throws IOException {
    String html = text(EvidencePack.REPORT_HTML);

    assertThat(html)
        .startsWith("<!DOCTYPE html>")
        .contains("</html>")
        .contains("<style>")
        .doesNotContain("<script")
        .doesNotContain("<link")
        .doesNotContain("<img")
        .doesNotContain("@import")
        .doesNotContain("url(")
        .doesNotContain("http-equiv=\"refresh\"");

    // Nothing in the document causes the browser to fetch anything. Note what is deliberately
    // allowed: <a href="https://..."> citation links, which a reader may choose to follow, and URLs
    // appearing as plain text in an explanation. Neither is a request the page makes on its own.
    for (String forbidden :
        new String[] {
          "src=",
          "srcset=",
          "<iframe",
          "<object",
          "<embed",
          "<video",
          "<audio",
          "<source",
          "<base",
          "background=",
          "poster="
        }) {
      assertThat(html)
          .as("evidence report must not fetch anything: found %s", forbidden)
          .doesNotContain(forbidden);
    }
    for (String line : html.split("\n")) {
      if (line.contains("href=")) {
        assertThat(line)
            .as("the only hrefs allowed are anchors the reader may click: %s", line)
            .contains("<a href=");
      }
    }
  }

  @Test
  void thePackHoldsTheSameFindingsAsTheScan() throws IOException {
    ScanResult scan = SampleScans.deterministic();

    assertThat(text(EvidencePack.FINDINGS_JSON))
        .isEqualTo(JsonReport.render(scan, RULES.disclaimer()));
    assertThat(text(EvidencePack.FINDINGS_CSV)).isEqualTo(CsvReport.render(scan));
    assertThat(text(EvidencePack.README)).contains(scan.summaryLine());

    // And it reads back through the same path jvmaudit diff uses.
    ScanSnapshot snapshot = ScanSnapshot.parse(text(EvidencePack.FINDINGS_JSON), "pack");
    assertThat(snapshot.jvms()).hasSize(scan.total());
  }

  @Test
  void writingTheSameScanTwiceProducesAnIdenticalPack() throws IOException {
    Path second = work.resolve("second.zip");
    EvidencePack.write(second, SampleScans.deterministic(), RULES);

    assertThat(Files.readAllBytes(second))
        .as("a pack whose bytes change on every write is harder to argue about, not easier")
        .isEqualTo(Files.readAllBytes(zip));
  }

  @Test
  void reportsWhatItWrote() throws IOException {
    EvidencePack.Written written =
        EvidencePack.write(work.resolve("third.zip"), SampleScans.deterministic(), RULES);

    assertThat(written.file()).isEqualTo(work.resolve("third.zip"));
    assertThat(written.entries()).hasSize(7);
    assertThat(written.entries())
        .allSatisfy(
            entry -> {
              assertThat(entry.sha256()).hasSize(64).matches("[0-9a-f]+");
              assertThat(entry.bytes()).isPositive();
            });
  }

  @Test
  void createsThePackDirectoryIfItIsNotThere() {
    assertThat(zip).exists();
    assertThat(zip.getParent().getFileName().toString()).isEqualTo("out");
  }

  private JsonNode manifest() throws IOException {
    return new ObjectMapper().readTree(text(EvidencePack.MANIFEST_JSON));
  }

  private String text(String name) {
    return new String(unpacked.get(name), StandardCharsets.UTF_8);
  }

  private static Map<String, byte[]> unzip(Path file) throws IOException {
    Map<String, byte[]> contents = new LinkedHashMap<>();
    try (InputStream in = Files.newInputStream(file);
        ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        contents.put(entry.getName(), zip.readAllBytes());
      }
    }
    return contents;
  }
}
