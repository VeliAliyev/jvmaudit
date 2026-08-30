package dev.jvmaudit.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmaudit.core.detect.ScanResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Golden-file tests for all four output formats.
 *
 * <p>These exist to make output changes deliberate. The JSON schema is a published interface that
 * {@code jvmaudit diff} and every downstream consumer depends on, and the summaries in the console
 * and HTML reports are the sentences a customer forwards to their lawyer. Neither should ever move
 * because somebody refactored a class.
 *
 * <p>Run with {@code -Djvmaudit.golden.update=true} to rewrite the expected files after an
 * intentional change, then read the diff before committing it.
 */
class ReportGoldenTest {

  private static final Path GOLDEN_DIRECTORY = Path.of("src", "test", "resources", "golden");

  @Test
  void tableReportMatchesItsGoldenFile() {
    assertMatchesGolden(
        "scan.txt", TableReport.plain().render(SampleScans.deterministic(), disclaimer()));
  }

  @Test
  void jsonReportMatchesItsGoldenFile() {
    assertMatchesGolden("scan.json", JsonReport.render(SampleScans.deterministic(), disclaimer()));
  }

  @Test
  void csvReportMatchesItsGoldenFile() {
    assertMatchesGolden("scan.csv", CsvReport.render(SampleScans.deterministic()));
  }

  @Test
  void htmlReportMatchesItsGoldenFile() {
    assertMatchesGolden("scan.html", HtmlReport.render(SampleScans.deterministic(), disclaimer()));
  }

  @Test
  void diffMatchesItsGoldenFile() {
    ScanDiff diff =
        ScanDiff.between(
            ScanSnapshot.parse(
                JsonReport.render(SampleScans.weekEarlier(), disclaimer()), "before"),
            ScanSnapshot.parse(
                JsonReport.render(SampleScans.deterministic(), disclaimer()), "after"));

    assertMatchesGolden("diff.txt", diff.render());
  }

  @Test
  void anEmptyScanStillProducesEveryFormat() {
    ScanResult empty = SampleScans.empty();

    assertThat(TableReport.plain().render(empty, disclaimer()))
        .contains("No Java installation was found")
        .contains("--deep");
    assertThat(JsonReport.render(empty, disclaimer())).contains("\"total\" : 0");
    assertThat(CsvReport.render(empty)).isEqualTo(String.join(",", CsvReport.headers()) + "\n");
    assertThat(HtmlReport.render(empty, disclaimer()))
        .contains("No Java installation was found on this machine");
  }

  @Test
  void everyReportCarriesTheDisclaimer() {
    ScanResult result = SampleScans.deterministic();

    assertThat(TableReport.plain().render(result, disclaimer())).contains("not legal advice");
    assertThat(JsonReport.render(result, disclaimer())).contains("not legal advice");
    assertThat(HtmlReport.render(result, disclaimer())).contains("not legal advice");
  }

  @Test
  void everyReportCarriesTheCitations() {
    ScanResult result = SampleScans.deterministic();
    String faq = "https://www.oracle.com/java/technologies/javase/jdk-faqs.html";

    assertThat(TableReport.plain().render(result, disclaimer())).contains(faq);
    assertThat(JsonReport.render(result, disclaimer())).contains(faq);
    assertThat(CsvReport.render(result)).contains(faq);
    assertThat(HtmlReport.render(result, disclaimer())).contains(faq);
  }

  @Test
  void theHtmlReportRequestsNothingFromTheNetwork() {
    String html = HtmlReport.render(SampleScans.deterministic(), disclaimer());

    // Links to the citations are anchors the reader may click; nothing is fetched to render the
    // page. No script, no stylesheet, no image, no font.
    assertThat(html)
        .doesNotContain("<script")
        .doesNotContain("<link")
        .doesNotContain("<img")
        .doesNotContain("@import")
        .doesNotContain("url(");
  }

  @Test
  void theHtmlReportEscapesEverythingItPrints() {
    assertThat(HtmlReport.escape("<script>alert('x')</script>"))
        .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
    assertThat(HtmlReport.escape("a & b \"c\"")).isEqualTo("a &amp; b &quot;c&quot;");
    assertThat(HtmlReport.escape(null)).isEmpty();
  }

  @Test
  void everyOutputEndsWithASingleNewlineAndUsesUnixLineEndings() {
    // Byte-stability across operating systems: an evidence pack hashes these files.
    for (String rendered :
        new String[] {
          TableReport.plain().render(SampleScans.deterministic(), disclaimer()),
          JsonReport.render(SampleScans.deterministic(), disclaimer()),
          CsvReport.render(SampleScans.deterministic()),
          HtmlReport.render(SampleScans.deterministic(), disclaimer())
        }) {
      assertThat(rendered).doesNotContain("\r").endsWith("\n");
    }
  }

  @Test
  void theJsonSchemaKeepsItsPromisedShape() {
    String json = JsonReport.render(SampleScans.deterministic(), disclaimer());

    int previous = -1;
    for (String field : JsonReport.topLevelFields()) {
      int at = json.indexOf("\"" + field + "\"");
      assertThat(at).as("top-level field '%s' is missing from the JSON", field).isGreaterThan(-1);
      assertThat(at).as("top-level field '%s' moved out of order", field).isGreaterThan(previous);
      previous = at;
    }
  }

  @Test
  void theDiffKeepsAStableOrderAcrossRuns() {
    // Regression: ScanSnapshot once stored its installations in an unordered map, so the same two
    // files produced differently ordered reports from one run to the next.
    String before = JsonReport.render(SampleScans.weekEarlier(), disclaimer());
    String after = JsonReport.render(SampleScans.deterministic(), disclaimer());

    String first =
        ScanDiff.between(ScanSnapshot.parse(before, "before"), ScanSnapshot.parse(after, "after"))
            .render();
    for (int run = 0; run < 20; run++) {
      String again =
          ScanDiff.between(ScanSnapshot.parse(before, "before"), ScanSnapshot.parse(after, "after"))
              .render();
      assertThat(again).isEqualTo(first);
    }

    assertThat(ScanSnapshot.parse(after, "after").paths())
        .as("the reading order of the file is the order of the report")
        .containsExactly(
            "/opt/java/oracle-jdk-8u211".replace('/', java.io.File.separatorChar),
            "/opt/vendor-app/AcmeSuite/jre".replace('/', java.io.File.separatorChar),
            "/opt/java/oracle-jdk-17.0.12".replace('/', java.io.File.separatorChar),
            "/opt/java/mystery-jdk".replace('/', java.io.File.separatorChar),
            "/opt/java/temurin-21".replace('/', java.io.File.separatorChar));
  }

  @Test
  void normalisesBothSpellingsOfAWindowsPathToTheSameThing() {
    // Regression: this passed on Windows and failed on Linux and macOS. JSON escapes a Windows
    // path as "\\\\opt\\\\java", and collapsing it character by character produced "//opt//java",
    // which no other platform could ever match. Both spellings must land on the same text.
    // One real backslash per separator: what Path.toString() gives on Windows.
    String asWindowsRendersIt = "\\opt\\java\\jdk";
    // Two real backslashes per separator: what JSON escaping turns that into.
    String asJsonEscapesIt = "\\\\opt\\\\java\\\\jdk";
    String asEveryoneElseRendersIt = "/opt/java/jdk";

    assertThat(asWindowsRendersIt).hasSize(13);
    assertThat(asJsonEscapesIt).hasSize(16);

    assertThat(normalisePathSeparators(asWindowsRendersIt)).isEqualTo(asEveryoneElseRendersIt);
    assertThat(normalisePathSeparators(asJsonEscapesIt)).isEqualTo(asEveryoneElseRendersIt);
    assertThat(normalisePathSeparators(asEveryoneElseRendersIt)).isEqualTo(asEveryoneElseRendersIt);
  }

  private static String disclaimer() {
    return SampleScans.engine().ruleSet().disclaimer();
  }

  /**
   * Compares rendered output against its golden file.
   *
   * <p>Backslashes are normalised to forward slashes on both sides, because the sample paths render
   * as {@code \opt\java\...} on Windows and {@code /opt/java/...} elsewhere. That is a property of
   * {@link Path}, not of the report, and it is the only thing here allowed to differ by platform.
   */
  private static String normalisePathSeparators(String text) {
    // The escaped pair is collapsed first. JSON renders a Windows path as "\\opt\\java", so
    // replacing character by character would turn each of the two backslashes into a slash and
    // produce "//opt//java" - which passed on Windows and failed on every other platform.
    return text.replace("\\\\", "/").replace('\\', '/');
  }

  private static void assertMatchesGolden(String name, String actual) {
    String normalised = normalisePathSeparators(actual);

    if (Boolean.getBoolean("jvmaudit.golden.update")) {
      write(name, normalised);
      return;
    }

    String expected = read(name);
    assertThat(normalised)
        .as(
            "Output changed. If that was deliberate, re-run with"
                + " -Djvmaudit.golden.update=true and read the diff before committing it.")
        .isEqualTo(expected);
  }

  private static String read(String name) {
    try (InputStream in = ReportGoldenTest.class.getResourceAsStream("/golden/" + name)) {
      if (in == null) {
        throw new AssertionError(
            "Missing golden file golden/"
                + name
                + ". Create it with -Djvmaudit.golden.update=true.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(String name, String content) {
    try {
      Files.createDirectories(GOLDEN_DIRECTORY);
      Files.writeString(GOLDEN_DIRECTORY.resolve(name), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
