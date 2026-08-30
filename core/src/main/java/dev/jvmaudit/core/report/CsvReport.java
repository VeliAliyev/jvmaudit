package dev.jvmaudit.core.report;

import dev.jvmaudit.core.detect.DetectedJvm;
import dev.jvmaudit.core.detect.DetectionSource;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.ClassificationFlag;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A flat projection of a scan, one row per installation, for spreadsheets and for the ITAM tools a
 * customer already runs.
 *
 * <p>Deliberately lossless in the columns that matter for an audit conversation: the rule that
 * produced each verdict, its confidence, and its citations all travel with the row, so a
 * spreadsheet forwarded to a lawyer is still traceable.
 */
public final class CsvReport {

  /** LF, not the platform separator, so the same estate yields the same bytes anywhere. */
  private static final String LF = "\n";

  private static final List<String> HEADERS =
      List.of(
          "path",
          "product",
          "vendor",
          "version",
          "status",
          "severity",
          "confidence",
          "summary",
          "remediation",
          "flags",
          "ruleId",
          "citations",
          "releaseDate",
          "identifiedFrom",
          "detectedBy",
          "bundledInside");

  private CsvReport() {}

  /** The column headers, in order. */
  public static List<String> headers() {
    return HEADERS;
  }

  /**
   * Renders a scan as CSV, with a header row.
   *
   * @param result the scan to render
   * @return the CSV document, ending in a newline
   */
  public static String render(ScanResult result) {
    StringBuilder csv = new StringBuilder();
    csv.append(String.join(",", HEADERS)).append(LF);
    for (DetectedJvm jvm : result.jvms()) {
      csv.append(
              String.join(
                  ",",
                  quote(jvm.path().toString()),
                  quote(jvm.productName()),
                  quote(jvm.fingerprint().vendor()),
                  quote(jvm.versionLabel()),
                  quote(jvm.classification().status().name()),
                  quote(jvm.severity().name()),
                  quote(jvm.classification().confidence().name()),
                  quote(jvm.classification().summary()),
                  quote(jvm.classification().remediation()),
                  quote(
                      jvm.classification().flags().stream()
                          .map(ClassificationFlag::name)
                          .sorted()
                          .collect(Collectors.joining(" "))),
                  quote(jvm.classification().ruleId()),
                  quote(
                      jvm.classification().citations().stream()
                          .map(Citation::url)
                          .collect(Collectors.joining(" "))),
                  quote(
                      jvm.classification().releaseDate() == null
                          ? null
                          : jvm.classification().releaseDate().toString()),
                  quote(jvm.fingerprint().source().name()),
                  quote(
                      jvm.sources().stream()
                          .map(DetectionSource::name)
                          .sorted()
                          .collect(Collectors.joining(" "))),
                  quote(
                      jvm.fingerprint().bundledInside() == null
                          ? null
                          : jvm.fingerprint().bundledInside().toString())))
          .append(LF);
    }
    return csv.toString();
  }

  /**
   * Quotes one field per RFC 4180: always quoted, embedded quotes doubled, and newlines flattened
   * so that one installation is always one line.
   */
  private static String quote(String value) {
    if (value == null) {
      return "\"\"";
    }
    String flattened = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    return "\"" + flattened.replace("\"", "\"\"") + "\"";
  }
}
