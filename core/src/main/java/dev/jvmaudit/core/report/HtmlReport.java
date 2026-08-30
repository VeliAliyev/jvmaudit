package dev.jvmaudit.core.report;

import dev.jvmaudit.core.detect.DetectedJvm;
import dev.jvmaudit.core.detect.DetectionSource;
import dev.jvmaudit.core.detect.ScanIssue;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.Severity;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A single self-contained HTML file: inline CSS, no scripts, no external requests of any kind.
 *
 * <p>That is not only an aesthetic choice. This file is meant to be forwarded to a manager, a
 * lawyer or an auditor, and often opened from a network share or an email attachment. A report that
 * fetches a font or a stylesheet would leak the fact that it was opened, would look different
 * offline, and would undercut the promise the rest of the tool makes.
 */
public final class HtmlReport {

  private HtmlReport() {}

  /**
   * Renders a scan as a standalone HTML document.
   *
   * @param result the scan to render
   * @param disclaimer the not-legal-advice disclaimer from the rule set
   * @return the complete HTML document
   */
  public static String render(ScanResult result, String disclaimer) {
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
    html.append("<meta charset=\"utf-8\">\n");
    html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
    html.append("<title>JVMAudit report - ").append(escape(result.host())).append("</title>\n");
    html.append("<style>\n").append(css()).append("</style>\n");
    html.append("</head>\n<body>\n");

    html.append("<header>\n");
    html.append("<h1>Java inventory and licence review</h1>\n");
    html.append("<p class=\"summary\">").append(escape(result.summaryLine())).append("</p>\n");
    html.append("</header>\n");

    html.append("<section class=\"meta\">\n<dl>\n");
    meta(html, "Host", result.host());
    meta(
        html,
        "Operating system",
        result.osName() + " " + result.osVersion() + " (" + result.osArch() + ")");
    meta(html, "Scanned as", result.user());
    meta(html, "Scan started", result.startedAt().toString());
    meta(html, "Scan took", result.duration().toMillis() + " ms");
    meta(html, "Deep filesystem sweep", result.deep() ? "yes" : "no");
    meta(html, "JVMAudit version", result.toolVersion());
    meta(html, "Licence rules version", result.rulesVersion());
    html.append("</dl>\n</section>\n");

    html.append("<section class=\"counts\">\n");
    for (Map.Entry<Severity, Integer> entry : result.countsBySeverity().entrySet()) {
      html.append("<div class=\"count ")
          .append(cssClass(entry.getKey()))
          .append("\"><span class=\"n\">")
          .append(entry.getValue())
          .append("</span><span class=\"l\">")
          .append(escape(entry.getKey().label()))
          .append("</span></div>\n");
    }
    html.append("</section>\n");

    if (result.jvms().isEmpty()) {
      html.append("<p class=\"empty\">No Java installation was found on this machine.</p>\n");
    } else {
      html.append("<table>\n<thead><tr>");
      for (String header :
          new String[] {"Status", "Product", "Version", "Path", "Why", "Sources"}) {
        html.append("<th>").append(header).append("</th>");
      }
      html.append("</tr></thead>\n<tbody>\n");
      for (DetectedJvm jvm : result.jvms()) {
        renderRow(html, jvm);
      }
      html.append("</tbody>\n</table>\n");
    }

    if (!result.issues().isEmpty()) {
      html.append("<section class=\"issues\">\n<h2>Scan completeness</h2>\n<ul>\n");
      for (ScanIssue issue : result.issues()) {
        html.append("<li class=\"")
            .append(issue.level().name().toLowerCase(java.util.Locale.ROOT))
            .append("\"><strong>")
            .append(escape(issue.level().name().toLowerCase(java.util.Locale.ROOT)))
            .append("</strong> ")
            .append(escape(issue.message()));
        if (issue.path() != null) {
          html.append(" <code>").append(escape(issue.path().toString())).append("</code>");
        }
        html.append("</li>\n");
      }
      html.append("</ul>\n</section>\n");
    }

    html.append("<footer>\n<p class=\"disclaimer\">")
        .append(escape(disclaimer))
        .append("</p>\n<p class=\"offline\">Produced offline by JVMAudit ")
        .append(escape(result.toolVersion()))
        .append(". This file makes no network requests and contains no scripts.</p>\n</footer>\n");

    html.append("</body>\n</html>\n");
    return html.toString();
  }

  private static void renderRow(StringBuilder html, DetectedJvm jvm) {
    Severity severity = jvm.severity();
    html.append("<tr class=\"").append(cssClass(severity)).append("\">\n");
    html.append("<td class=\"status\"><span class=\"pill\">")
        .append(escape(severity.label()))
        .append("</span></td>\n");
    html.append("<td>").append(escape(jvm.productName())).append("</td>\n");
    html.append("<td class=\"version\">").append(escape(jvm.versionLabel())).append("</td>\n");
    html.append("<td class=\"path\"><code>")
        .append(escape(jvm.path().toString()))
        .append("</code>");
    if (jvm.fingerprint().bundledInside() != null) {
      html.append("<div class=\"bundled\">bundled inside <code>")
          .append(escape(jvm.fingerprint().bundledInside().toString()))
          .append("</code></div>");
    }
    html.append("</td>\n");

    html.append("<td class=\"why\"><p>")
        .append(escape(jvm.classification().summary()))
        .append("</p>\n");
    if (jvm.classification().remediation() != null) {
      html.append("<p class=\"remediation\">")
          .append(escape(jvm.classification().remediation()))
          .append("</p>\n");
    }
    if (jvm.classification().confidence() == Confidence.UNVERIFIED) {
      html.append(
          "<p class=\"unverified\">This rule is JVMAudit's inference, not a statement Oracle"
              + " publishes. Check the citation before acting on it.</p>\n");
    }
    if (!jvm.classification().flags().isEmpty()) {
      html.append("<ul class=\"flags\">\n");
      for (ClassificationFlag flag : ClassificationFlag.values()) {
        if (jvm.classification().flags().contains(flag)) {
          html.append("<li>").append(escape(flag.description())).append("</li>\n");
        }
      }
      html.append("</ul>\n");
    }
    html.append("<ul class=\"citations\">\n");
    for (Citation citation : jvm.classification().citations()) {
      html.append("<li><a href=\"")
          .append(escape(citation.url()))
          .append("\" rel=\"noreferrer noopener\">")
          .append(escape(citation.title()))
          .append("</a></li>\n");
    }
    html.append("</ul>\n");
    if (jvm.classification().ruleId() != null) {
      html.append("<p class=\"rule\">rule: <code>")
          .append(escape(jvm.classification().ruleId()))
          .append("</code></p>\n");
    }
    html.append("</td>\n");

    html.append("<td class=\"sources\">")
        .append(
            escape(
                jvm.sources().stream()
                    .map(DetectionSource::label)
                    .sorted()
                    .collect(Collectors.joining(", "))))
        .append("</td>\n");
    html.append("</tr>\n");
  }

  private static void meta(StringBuilder html, String term, String value) {
    html.append("<dt>")
        .append(escape(term))
        .append("</dt><dd>")
        .append(escape(value))
        .append("</dd>\n");
  }

  private static String cssClass(Severity severity) {
    return switch (severity) {
      case OK -> "ok";
      case REVIEW -> "review";
      case ACTION -> "action";
      case UNKNOWN -> "unknown";
    };
  }

  /**
   * Escapes text for HTML. Applied to every value that reaches the document, including file paths
   * and vendor strings read off disk, which are attacker-influenceable on a shared machine.
   */
  static String escape(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder escaped = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static String css() {
    return """
        :root { color-scheme: light dark; }
        * { box-sizing: border-box; }
        body { margin: 0; padding: 2rem 1.5rem 4rem; background: #fbfbfa; color: #1a1a18;
               font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica,
               Arial, sans-serif; }
        header { border-bottom: 2px solid #1a1a18; padding-bottom: 1rem; margin-bottom: 1.5rem; }
        h1 { font-size: 1.5rem; margin: 0 0 .35rem; letter-spacing: -.01em; }
        h2 { font-size: 1.05rem; margin: 2rem 0 .6rem; }
        .summary { margin: 0; font-size: 1.05rem; font-weight: 600; }
        .meta dl { display: grid; grid-template-columns: max-content 1fr; gap: .2rem 1.25rem;
                   margin: 0 0 1.5rem; font-size: .85rem; }
        .meta dt { color: #6b6b66; }
        .meta dd { margin: 0; }
        .counts { display: flex; flex-wrap: wrap; gap: .75rem; margin-bottom: 1.75rem; }
        .count { border: 1px solid #d9d9d4; border-left-width: 5px; border-radius: 4px;
                 padding: .5rem .9rem; background: #fff; min-width: 8.5rem; }
        .count .n { display: block; font-size: 1.5rem; font-weight: 700; line-height: 1.1; }
        .count .l { display: block; font-size: .72rem; text-transform: uppercase;
                    letter-spacing: .06em; color: #6b6b66; }
        .count.ok { border-left-color: #2f7d32; }
        .count.review { border-left-color: #b26a00; }
        .count.action { border-left-color: #b3261e; }
        .count.unknown { border-left-color: #8a8a84; }
        table { border-collapse: collapse; width: 100%; background: #fff;
                border: 1px solid #d9d9d4; }
        th { text-align: left; font-size: .72rem; text-transform: uppercase;
             letter-spacing: .06em; color: #6b6b66; padding: .6rem .7rem;
             border-bottom: 1px solid #d9d9d4; background: #f4f4f1; }
        td { padding: .7rem; border-bottom: 1px solid #ececE7; vertical-align: top;
             font-size: .88rem; }
        tr.ok td.status .pill { background: #e6f2e6; color: #1e5c21; }
        tr.review td.status .pill { background: #fdf0dc; color: #8a5200; }
        tr.action td.status .pill { background: #fbe4e2; color: #8c1d18; }
        tr.unknown td.status .pill { background: #eceCE9; color: #55554f; }
        .pill { display: inline-block; padding: .18rem .5rem; border-radius: 3px;
                font-size: .72rem; font-weight: 700; text-transform: uppercase;
                letter-spacing: .04em; white-space: nowrap; }
        code { font: .82em ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
               background: #f4f4f1; padding: .08em .3em; border-radius: 3px; word-break: break-all; }
        td.why { max-width: 34rem; }
        td.why p { margin: 0 0 .45rem; }
        .remediation { color: #1a4f8a; }
        .unverified { color: #8a5200; font-weight: 600; }
        .bundled { font-size: .78rem; color: #6b6b66; margin-top: .3rem; }
        ul.flags, ul.citations { margin: .2rem 0 .45rem; padding-left: 1.1rem;
                                 font-size: .8rem; color: #55554f; }
        ul.citations a { color: #1a4f8a; }
        .rule { font-size: .75rem; color: #8a8a84; margin: .2rem 0 0; }
        td.sources { font-size: .78rem; color: #55554f; white-space: nowrap; }
        .issues ul { padding-left: 1.1rem; font-size: .85rem; }
        .issues li.warning { color: #8a5200; }
        .issues li.error { color: #8c1d18; }
        .empty { padding: 2rem; text-align: center; color: #6b6b66; background: #fff;
                 border: 1px dashed #d9d9d4; }
        footer { margin-top: 2.5rem; border-top: 1px solid #d9d9d4; padding-top: 1rem; }
        .disclaimer { font-weight: 600; }
        .offline, .disclaimer { font-size: .82rem; color: #55554f; margin: .3rem 0; }
        @media (prefers-color-scheme: dark) {
          body { background: #16161a; color: #e8e8e4; }
          header { border-bottom-color: #e8e8e4; }
          .count, table { background: #1f1f24; border-color: #34343c; }
          th { background: #26262c; border-bottom-color: #34343c; color: #a3a39c; }
          td { border-bottom-color: #2b2b32; }
          code { background: #26262c; }
          .meta dt, td.sources, .rule, .bundled, .offline, .disclaimer { color: #a3a39c; }
          ul.flags, ul.citations { color: #a3a39c; }
          ul.citations a, .remediation { color: #8ab4f8; }
          tr.ok td.status .pill { background: #1d3a1f; color: #a8dcab; }
          tr.review td.status .pill { background: #3d2c10; color: #f0c07a; }
          tr.action td.status .pill { background: #43201d; color: #f2b8b5; }
          tr.unknown td.status .pill { background: #2b2b32; color: #c6c6bf; }
          .empty { background: #1f1f24; border-color: #34343c; }
          footer { border-top-color: #34343c; }
        }
        @media print {
          body { background: #fff; padding: 0; }
          .count, table { border-color: #999; }
        }
        """;
  }
}
