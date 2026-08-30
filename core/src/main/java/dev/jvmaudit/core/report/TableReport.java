package dev.jvmaudit.core.report;

import dev.jvmaudit.core.detect.DetectedJvm;
import dev.jvmaudit.core.detect.ScanIssue;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.Severity;
import java.util.ArrayList;
import java.util.List;

/**
 * The default console output: a colour-coded table, one row per installation, with the reason and
 * the citation underneath.
 *
 * <p>Colour and the status glyphs are optional, because this output is as likely to be piped into a
 * file or a CI log as read on a terminal. Nothing is conveyed by colour alone - the status word is
 * always spelled out - so the report is as readable in a redirected file as on screen.
 */
public final class TableReport {

  private static final String RESET = "\u001B[0m";
  private static final String BOLD = "\u001B[1m";
  private static final String DIM = "\u001B[2m";

  private final boolean colour;
  private final boolean glyphs;
  private final int width;

  /**
   * @param colour whether to emit ANSI colour
   * @param glyphs whether to emit the status emoji, which many Windows consoles cannot render
   * @param width the terminal width to wrap explanatory text to
   */
  public TableReport(boolean colour, boolean glyphs, int width) {
    this.colour = colour;
    this.glyphs = glyphs;
    this.width = Math.max(60, width);
  }

  /** A plain report, for pipes, files and golden-file tests. */
  public static TableReport plain() {
    return new TableReport(false, false, 100);
  }

  /**
   * Renders a scan as a console report.
   *
   * @param result the scan to render
   * @param disclaimer the not-legal-advice disclaimer from the rule set
   * @return the report, ending in a newline
   */
  public String render(ScanResult result, String disclaimer) {
    StringBuilder out = new StringBuilder();

    if (result.jvms().isEmpty()) {
      out.append("No Java installation was found.").append(nl());
      out.append(
              "If you expected one, try --deep to sweep the filesystem, or --paths to point"
                  + " JVMAudit at a directory.")
          .append(nl());
    } else {
      int productWidth = widest(result.jvms(), DetectedJvm::productName, 30);
      int versionWidth = widest(result.jvms(), DetectedJvm::versionLabel, 11);
      for (DetectedJvm jvm : result.jvms()) {
        renderRow(out, jvm, productWidth, versionWidth);
      }
    }

    out.append(nl());
    out.append(bold(result.summaryLine())).append(nl());

    if (!result.issues().isEmpty()) {
      out.append(nl());
      for (ScanIssue issue : result.issues()) {
        out.append(issueMarker(issue)).append(' ').append(issue.message());
        if (issue.path() != null) {
          out.append(" (").append(issue.path()).append(')');
        }
        out.append(nl());
      }
    }

    out.append(nl());
    out.append(dim(wrap(disclaimer, width, ""))).append(nl());
    return out.toString();
  }

  private void renderRow(StringBuilder out, DetectedJvm jvm, int productWidth, int versionWidth) {
    Severity severity = jvm.severity();
    out.append(colour(severity, pad(marker(severity) + severity.label(), statusWidth())))
        .append("  ")
        .append(pad(jvm.productName(), productWidth))
        .append("  ")
        .append(pad(jvm.versionLabel(), versionWidth))
        .append("  ")
        .append(jvm.path())
        .append(nl());

    String indent = " ".repeat(4);
    out.append(dim(wrap(jvm.classification().summary(), width, indent))).append(nl());

    if (jvm.classification().remediation() != null) {
      out.append(dim(wrap("-> " + jvm.classification().remediation(), width, indent))).append(nl());
    }
    if (jvm.classification().confidence() == Confidence.UNVERIFIED) {
      out.append(
              dim(
                  wrap(
                      "! This rule is JVMAudit's inference, not a statement Oracle publishes."
                          + " Check the citation before acting on it.",
                      width,
                      indent)))
          .append(nl());
    }
    if (jvm.classification().flags().contains(ClassificationFlag.VENDOR_MATCH_UNCONFIRMED)) {
      out.append(
              dim(
                  wrap(
                      "note: "
                          + ClassificationFlag.VENDOR_MATCH_UNCONFIRMED.description()
                          + ". The licence conclusion above is unaffected.",
                      width,
                      indent)))
          .append(nl());
    }
    for (Citation citation : jvm.classification().citations()) {
      out.append(dim(indent + "source: " + citation.url())).append(nl());
    }
    out.append(nl());
  }

  /**
   * The widest value in a column, so nothing overflows and nothing is padded further than needed.
   */
  private static int widest(
      List<DetectedJvm> jvms,
      java.util.function.Function<DetectedJvm, String> column,
      int minimum) {
    int widest = minimum;
    for (DetectedJvm jvm : jvms) {
      widest = Math.max(widest, column.apply(jvm).length());
    }
    return widest;
  }

  private int statusWidth() {
    int widest = 0;
    for (Severity severity : Severity.values()) {
      widest = Math.max(widest, severity.label().length());
    }
    return widest + (glyphs ? 3 : 0);
  }

  private String marker(Severity severity) {
    if (!glyphs) {
      return "";
    }
    return switch (severity) {
      case OK -> "✅ ";
      case REVIEW -> "🟡 ";
      case ACTION -> "❌ ";
      case UNKNOWN -> "⚪ ";
    };
  }

  private String issueMarker(ScanIssue issue) {
    String text =
        switch (issue.level()) {
          case INFO -> "note:";
          case WARNING -> "warning:";
          case ERROR -> "error:";
        };
    if (!colour) {
      return text;
    }
    return switch (issue.level()) {
      case INFO -> "\u001B[36m" + text + RESET;
      case WARNING -> "\u001B[33m" + text + RESET;
      case ERROR -> "\u001B[31m" + text + RESET;
    };
  }

  private String colour(Severity severity, String text) {
    if (!colour) {
      return text;
    }
    String code =
        switch (severity) {
          case OK -> "\u001B[32m";
          case REVIEW -> "\u001B[33m";
          case ACTION -> "\u001B[31m";
          case UNKNOWN -> "\u001B[37m";
        };
    return code + text + RESET;
  }

  private String bold(String text) {
    return colour ? BOLD + text + RESET : text;
  }

  private String dim(String text) {
    return colour ? DIM + text + RESET : text;
  }

  private static String pad(String text, int width) {
    return text.length() >= width ? text : text + " ".repeat(width - text.length());
  }

  /**
   * LF, not the platform separator. Windows terminals render it correctly, and pinning it means a
   * redirected report is byte-identical wherever the scan ran.
   */
  private static String nl() {
    return "\n";
  }

  /**
   * Wraps text to a width, prefixing every line with an indent. Never splits inside a word.
   *
   * @param text the text to wrap
   * @param width the target line width
   * @param indent the prefix for every line
   * @return the wrapped text
   */
  public static String wrap(String text, int width, String indent) {
    int room = Math.max(20, width - indent.length());
    List<String> lines = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : text.split("\\s+")) {
      if (word.isEmpty()) {
        continue;
      }
      if (line.length() > 0 && line.length() + 1 + word.length() > room) {
        lines.add(indent + line);
        line.setLength(0);
      }
      if (line.length() > 0) {
        line.append(' ');
      }
      line.append(word);
    }
    if (line.length() > 0) {
      lines.add(indent + line);
    }
    return String.join(nl(), lines);
  }
}
