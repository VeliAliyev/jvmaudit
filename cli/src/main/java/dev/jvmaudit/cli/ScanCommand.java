package dev.jvmaudit.cli;

import dev.jvmaudit.core.detect.JvmScanner;
import dev.jvmaudit.core.detect.ScanOptions;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.report.CsvReport;
import dev.jvmaudit.core.report.EvidencePack;
import dev.jvmaudit.core.report.HtmlReport;
import dev.jvmaudit.core.report.JsonReport;
import dev.jvmaudit.core.report.TableReport;
import dev.jvmaudit.core.rules.LicenseRulesEngine;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code jvmaudit scan} - the command the whole tool exists for. */
@Command(
    name = "scan",
    mixinStandardHelpOptions = true,
    description = "Inventory the Java installations on this machine and classify their licences.",
    sortOptions = false,
    footerHeading = "%n",
    footer = {
      "Exit codes:",
      "  0  the scan completed and nothing matched --fail-on",
      "  1  the scan completed and something matched --fail-on",
      "  2  part of the scan failed, so the inventory may be incomplete",
      "",
      "Nothing here touches the network."
    })
public final class ScanCommand implements Callable<Integer> {

  /** Output formats. */
  public enum Format {
    /** Colour-coded console table. The default. */
    TABLE,
    /** The canonical machine format; what {@code jvmaudit diff} reads. */
    JSON,
    /** A flat projection, one row per installation. */
    CSV,
    /** A single self-contained page, presentable enough to forward. */
    HTML
  }

  @Option(
      names = "--deep",
      description =
          "Also sweep the filesystem for JVMs bundled inside other applications. Slower, and the"
              + " only way to find a JRE shipped inside somebody else's product.")
  boolean deep;

  @Option(
      names = "--paths",
      paramLabel = "<dir>",
      split = ",",
      description =
          "Scan ONLY these directories. The usual places, JAVA_HOME, PATH, the registry and"
              + " running processes are all skipped, so this is how you audit one directory.")
  List<Path> paths = List.of();

  @Option(
      names = "--exclude",
      paramLabel = "<glob>",
      split = ",",
      description = "Skip paths matching these globs during --deep.")
  List<String> exclude = List.of();

  @Option(
      names = "--max-depth",
      paramLabel = "<n>",
      description = "How deep --deep may descend below each root. Default: ${DEFAULT-VALUE}.")
  int maxDepth = 12;

  @Option(
      names = "--timeout",
      paramLabel = "<seconds>",
      description =
          "Overall budget for the scan. A scan cut short says so rather than looking complete."
              + " Default: ${DEFAULT-VALUE}.")
  long timeoutSeconds = 120;

  @Option(
      names = "--probe",
      negatable = true,
      description =
          "Run 'java -version' on installations JVMAudit cannot identify from their release file."
              + " On by default, because Oracle JDK and Oracle OpenJDK are indistinguishable"
              + " without it. With --no-probe those are reported as unknown rather than guessed.")
  Boolean probe;

  @Option(names = "--no-processes", description = "Do not look at running processes.")
  boolean noProcesses;

  @Option(names = "--no-registry", description = "Do not read the Windows registry.")
  boolean noRegistry;

  @Option(
      names = "--format",
      paramLabel = "<format>",
      description = "Output format: table, json, csv, html. Default: table.")
  Format format = Format.TABLE;

  @Option(
      names = "--out",
      paramLabel = "<file>",
      description = "Write the report to a file instead of standard output.")
  Path out;

  @Option(
      names = "--fail-on",
      paramLabel = "<what>",
      converter = FailOn.Converter.class,
      completionCandidates = FailOn.Candidates.class,
      description =
          "Exit with code 1 when the scan finds: ${COMPLETION-CANDIDATES}. Default: none.")
  FailOn failOn = FailOn.NONE;

  @Option(
      names = "--evidence",
      paramLabel = "<file.zip>",
      description =
          "Also write a zipped evidence pack: the findings, the human-readable report, the exact"
              + " licence rules used, and a SHA-256 manifest of all of them.")
  Path evidence;

  @Option(
      names = "--color",
      negatable = true,
      description =
          "Force colour on or off. By default JVMAudit colours only when it is writing to a"
              + " terminal, so redirected output and CI logs stay clean. --color is useful when"
              + " piping into a pager that understands escapes.")
  Boolean color;

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public Integer call() throws IOException {
    LicenseRulesEngine engine = LicenseRulesEngine.usingPackagedRules();
    ScanResult result = JvmScanner.forCurrentMachine(engine).scan(options());

    if (evidence != null) {
      EvidencePack.Written written = EvidencePack.write(evidence, result, engine.ruleSet());
      PrintWriter stdout = spec.commandLine().getOut();
      stdout.println(
          "Evidence pack written to "
              + written.file().toAbsolutePath()
              + " ("
              + written.entries().size()
              + " files, SHA-256 manifest)");
    }

    String rendered = render(result, engine.ruleSet().disclaimer());
    if (out == null) {
      spec.commandLine().getOut().print(rendered);
      spec.commandLine().getOut().flush();
    } else {
      Path parent = out.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(out, rendered, StandardCharsets.UTF_8);
      PrintWriter stdout = spec.commandLine().getOut();
      stdout.println(result.summaryLine());
      stdout.println("Report written to " + out.toAbsolutePath());
      stdout.flush();
    }

    return exitCode(result);
  }

  private ScanOptions options() {
    ScanOptions.ExecPolicy execPolicy =
        probe == null
            ? ScanOptions.ExecPolicy.WHEN_NEEDED
            : (probe ? ScanOptions.ExecPolicy.ALWAYS : ScanOptions.ExecPolicy.NEVER);

    // --paths means "look here and nowhere else". Anything else would make the option useless for
    // auditing one directory, and would make its output depend on what the host happens to have.
    boolean scoped = !paths.isEmpty();

    return ScanOptions.builder()
        .deep(deep)
        .paths(paths)
        .excludeGlobs(exclude)
        .maxDepth(maxDepth)
        .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
        .execPolicy(execPolicy)
        .includeWellKnownRoots(!scoped)
        .includeEnvironment(!scoped)
        .includeRunningProcesses(!scoped && !noProcesses)
        .includeRegistry(!scoped && !noRegistry)
        .build();
  }

  private String render(ScanResult result, String disclaimer) {
    return switch (format) {
      case TABLE ->
          new TableReport(useColour(), useGlyphs(), terminalWidth()).render(result, disclaimer);
      case JSON -> JsonReport.render(result, disclaimer);
      case CSV -> CsvReport.render(result);
      case HTML -> HtmlReport.render(result, disclaimer);
    };
  }

  /**
   * Colour goes to a terminal, never to a file or a pipe, and never when the environment asks for
   * plain output. {@code NO_COLOR} is honoured because CI logs are unreadable with escape codes in
   * them.
   */
  private boolean useColour() {
    if (format != Format.TABLE) {
      return false;
    }
    if (color != null) {
      return color;
    }
    if (out != null || System.getenv("NO_COLOR") != null) {
      return false;
    }
    if ("dumb".equals(System.getenv("TERM"))) {
      return false;
    }
    return System.console() != null;
  }

  /**
   * Status glyphs are suppressed on Windows consoles, which frequently cannot render them - unless
   * the user asked for colour explicitly, in which case they have opted into decoration and know
   * what their terminal can do.
   */
  private boolean useGlyphs() {
    if (!useColour()) {
      return false;
    }
    return color != null ? color : !isWindowsConsole();
  }

  private static boolean isWindowsConsole() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }

  private static int terminalWidth() {
    String columns = System.getenv("COLUMNS");
    if (columns != null) {
      try {
        return Integer.parseInt(columns.trim());
      } catch (NumberFormatException e) {
        // fall through to the default
      }
    }
    return 100;
  }

  private int exitCode(ScanResult result) {
    if (result.hasErrors()) {
      return ExitCode.SCAN_ERROR;
    }
    boolean matched =
        switch (failOn) {
          case NONE -> false;
          case ORACLE_PAID -> result.hasOraclePaidLikely();
          case ORACLE_ANY -> result.hasAnyOracleLicensed();
        };
    return matched ? ExitCode.MATCHES_FOUND : ExitCode.CLEAN;
  }

  /** Visible for tests: what a given result and failure policy should exit with. */
  static int exitCodeFor(ScanResult result, FailOn failOn) {
    ScanCommand command = new ScanCommand();
    command.failOn = failOn;
    return command.exitCode(result);
  }
}
