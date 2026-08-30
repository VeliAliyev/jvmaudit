package dev.jvmaudit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmaudit.core.detect.JvmFixtures;
import dev.jvmaudit.core.detect.JvmFixtures.Fixture;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.report.SampleScans;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The command line as a user meets it: what it prints, what it writes, and what it exits with.
 *
 * <p>Every case runs against an estate this test plants, using {@code --paths}, so nothing here
 * depends on what Java happens to be installed on the machine or the CI runner.
 */
class CliTest {

  @TempDir Path estate;

  /** Everything the CLI wrote to standard output during the last {@link #run}. */
  private String out;

  /** Everything it wrote to standard error. */
  private String err;

  @BeforeEach
  void plantAnEstate() {
    for (Fixture fixture : JvmFixtures.catalogue()) {
      JvmFixtures.plant(estate, fixture);
    }
  }

  /** Runs the CLI with colour forced off, capturing both streams. */
  private int run(String... args) {
    StringWriter outBuffer = new StringWriter();
    StringWriter errBuffer = new StringWriter();
    int code;
    try (PrintWriter outWriter = new PrintWriter(outBuffer);
        PrintWriter errWriter = new PrintWriter(errBuffer)) {
      code = Main.run(args, outWriter, errWriter, CommandLine.Help.Ansi.OFF);
    }
    out = outBuffer.toString();
    err = errBuffer.toString();
    return code;
  }

  private String[] scanEstate(String... extra) {
    String[] args = new String[3 + extra.length];
    args[0] = "scan";
    args[1] = "--paths";
    args[2] = estate.toString();
    System.arraycopy(extra, 0, args, 3, extra.length);
    return args;
  }

  // ---------------------------------------------------------------- help and version

  @Test
  void printsHelpWhenGivenNoSubcommand() {
    assertThat(run()).isEqualTo(ExitCode.CLEAN);

    assertThat(out)
        .contains("Usage: jvmaudit")
        .contains("scan")
        .contains("diff")
        .contains("rules")
        .contains("runs entirely offline")
        .contains("not legal advice");
  }

  @Test
  void helpResolvesEveryPlaceholderItUses() {
    // A '${...}' left in the output means a picocli variable that does not resolve, which reads as
    // a bug to anyone running --help.
    run("--help");
    String topLevel = out;
    run("scan", "--help");
    String scan = out;
    run("diff", "--help");
    String diff = out;
    run("rules", "--help");
    String rules = out;

    for (String help : new String[] {topLevel, scan, diff, rules}) {
      assertThat(help).doesNotContain("${").doesNotContain("COMPLETION-CANDIDATES");
    }
  }

  @Test
  void scanHelpDocumentsTheExitCodesAndTheOfflinePromise() {
    assertThat(run("scan", "--help")).isEqualTo(ExitCode.CLEAN);

    assertThat(out)
        .contains("--deep")
        .contains("--paths")
        .contains("--exclude")
        .contains("--format")
        .contains("--out")
        .contains("--fail-on")
        .contains("oracle-paid")
        .contains("oracle-any")
        .contains("Exit codes:")
        .contains("Nothing here touches the network.");
  }

  @Test
  void printsItsVersionAndTheRulesVersion() {
    assertThat(run("--version")).isEqualTo(ExitCode.CLEAN);

    assertThat(out).contains("jvmaudit ").contains("licence rules ").contains("running on Java ");
  }

  // ---------------------------------------------------------------- scan output formats

  @Test
  void scansOnlyTheDirectoriesGivenWithPaths() {
    assertThat(run(scanEstate())).isEqualTo(ExitCode.CLEAN);

    assertThat(out)
        .contains(JvmFixtures.catalogue().size() + " JVMs found")
        .contains("Eclipse Temurin")
        .contains("not legal advice");
  }

  @Test
  void writesJsonThatTheDiffCommandCanReadBack() throws IOException {
    Path file = estate.resolve("out").resolve("scan.json");

    assertThat(run(scanEstate("--format", "json", "--out", file.toString())))
        .isEqualTo(ExitCode.CLEAN);

    assertThat(file).exists();
    String json = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(json).contains("\"schemaVersion\"").contains("\"jvms\"").contains("\"citations\"");
    assertThat(out).contains("Report written to").contains("JVMs found");

    assertThat(run("diff", file.toString(), file.toString())).isEqualTo(ExitCode.CLEAN);
    assertThat(out).contains("No change.");
  }

  @Test
  void writesCsvWithAHeaderRowPerInstallation() {
    assertThat(run(scanEstate("--format", "csv"))).isEqualTo(ExitCode.CLEAN);

    String[] lines = out.strip().split("\n");
    assertThat(lines[0]).startsWith("path,product,vendor,version,status");
    assertThat(lines).hasSize(JvmFixtures.catalogue().size() + 1);
  }

  @Test
  void writesSelfContainedHtml() {
    assertThat(run(scanEstate("--format", "html"))).isEqualTo(ExitCode.CLEAN);

    assertThat(out)
        .startsWith("<!DOCTYPE html>")
        .contains("<style>")
        .doesNotContain("<script")
        .doesNotContain("<link")
        .contains("not legal advice");
  }

  @Test
  void neverColoursOutputThatIsNotAConsole() {
    run(scanEstate());

    assertThat(out).doesNotContain("[");
  }

  // ---------------------------------------------------------------- exit codes

  @Test
  void exitsZeroByDefaultEvenWhenOracleIsFound() {
    assertThat(run(scanEstate("--format", "csv")))
        .as("a scan is not a failure; --fail-on is what makes it one")
        .isEqualTo(ExitCode.CLEAN);
  }

  @Test
  void doesNotCallAPlantedOracleBuildPaidWhenItCannotRunIt() {
    // The fixtures' bin/java is a text file, not a runnable launcher, so JVMAudit cannot get the
    // Java(TM) evidence that separates Oracle JDK from Oracle OpenJDK - and it reports UNKNOWN
    // rather than guessing. --fail-on oracle-paid therefore finds nothing, which is the correct and
    // deliberately conservative answer. The exit-code logic itself is checked below against a scan
    // that really does contain a paid installation.
    assertThat(run(scanEstate("--format", "csv", "--fail-on", "oracle-paid")))
        .isEqualTo(ExitCode.CLEAN);

    assertThat(run(scanEstate())).isEqualTo(ExitCode.CLEAN);
    assertThat(out).contains("unknown.").contains("will not guess");
  }

  @Test
  void mapsFindingsToExitCodesForEachFailOnValue() {
    // A scan holding one ORACLE_PAID_LIKELY, two Oracle-licensed-but-free, and one unknown.
    ScanResult withPaidOracle = SampleScans.deterministic();

    assertThat(ScanCommand.exitCodeFor(withPaidOracle, FailOn.NONE)).isEqualTo(ExitCode.CLEAN);
    assertThat(ScanCommand.exitCodeFor(withPaidOracle, FailOn.ORACLE_PAID))
        .isEqualTo(ExitCode.MATCHES_FOUND);
    assertThat(ScanCommand.exitCodeFor(withPaidOracle, FailOn.ORACLE_ANY))
        .isEqualTo(ExitCode.MATCHES_FOUND);

    ScanResult nothingFound = SampleScans.empty();
    assertThat(ScanCommand.exitCodeFor(nothingFound, FailOn.NONE)).isEqualTo(ExitCode.CLEAN);
    assertThat(ScanCommand.exitCodeFor(nothingFound, FailOn.ORACLE_PAID)).isEqualTo(ExitCode.CLEAN);
    assertThat(ScanCommand.exitCodeFor(nothingFound, FailOn.ORACLE_ANY)).isEqualTo(ExitCode.CLEAN);
  }

  @Test
  void exitsOneWhenAnyOracleInstallationIsFound() {
    assertThat(run(scanEstate("--format", "csv", "--fail-on", "oracle-any")))
        .isEqualTo(ExitCode.MATCHES_FOUND);
  }

  @Test
  void exitsZeroWhenTheEstateHoldsNoOracleBuild(@TempDir Path clean) {
    JvmFixtures.plant(
        clean,
        JvmFixtures.catalogue().stream()
            .filter(f -> f.id().equals("temurin-21"))
            .findFirst()
            .orElseThrow());

    assertThat(
            run("scan", "--paths", clean.toString(), "--format", "csv", "--fail-on", "oracle-any"))
        .isEqualTo(ExitCode.CLEAN);
  }

  @Test
  void acceptsBothSpellingsOfTheFailOnValues() {
    assertThat(run(scanEstate("--format", "csv", "--fail-on", "oracle_any")))
        .isEqualTo(ExitCode.MATCHES_FOUND);
    assertThat(run(scanEstate("--format", "csv", "--fail-on", "ORACLE-ANY")))
        .isEqualTo(ExitCode.MATCHES_FOUND);
  }

  @Test
  void rejectsAnUnknownFailOnValueWithAMessageThatNamesTheValidOnes() {
    assertThat(run(scanEstate("--fail-on", "whatever"))).isEqualTo(ExitCode.USAGE_ERROR);

    assertThat(err).contains("not a valid --fail-on value").contains("oracle-paid, oracle-any");
  }

  @Test
  void rejectsAnUnknownFormat() {
    assertThat(run(scanEstate("--format", "yaml"))).isEqualTo(ExitCode.USAGE_ERROR);
  }

  // ---------------------------------------------------------------- diff

  @Test
  void reportsWhatAppearedBetweenTwoScans(@TempDir Path work) throws IOException {
    Path before = work.resolve("before.json");
    Path after = work.resolve("after.json");

    Path smaller = work.resolve("estate-before");
    JvmFixtures.plant(
        smaller,
        JvmFixtures.catalogue().stream()
            .filter(f -> f.id().equals("temurin-21"))
            .findFirst()
            .orElseThrow());

    run("scan", "--paths", smaller.toString(), "--format", "json", "--out", before.toString());
    run(scanEstate("--format", "json", "--out", after.toString()));

    assertThat(run("diff", before.toString(), after.toString(), "--fail-on", "oracle"))
        .isEqualTo(ExitCode.MATCHES_FOUND);
    assertThat(out).contains("APPEARED").contains("An Oracle-licensed installation appeared");
  }

  @Test
  void refusesAFileThatIsNotAScan(@TempDir Path work) throws IOException {
    Path notAScan = work.resolve("notes.json");
    Files.writeString(notAScan, "{\"hello\":\"world\"}", StandardCharsets.UTF_8);

    assertThat(run("diff", notAScan.toString(), notAScan.toString()))
        .isEqualTo(ExitCode.USAGE_ERROR);
    assertThat(err).contains("is not a JVMAudit scan file").contains("jvmaudit scan --format json");
  }

  @Test
  void reportsAMissingScanFileWithoutAStackTrace(@TempDir Path work) {
    Path missing = work.resolve("nope.json");

    assertThat(run("diff", missing.toString(), missing.toString())).isEqualTo(ExitCode.SCAN_ERROR);
    assertThat(err).contains("Could not read").doesNotContain("\tat ");
  }

  // ---------------------------------------------------------------- rules

  @Test
  void printsTheRulesWithTheirSources() {
    assertThat(run("rules")).isEqualTo(ExitCode.CLEAN);

    assertThat(out)
        .contains("Licence rules version")
        .contains("oracle-jdk-8-otn")
        .contains("https://www.oracle.com/java/technologies/javase/jdk-faqs.html")
        .contains("Recognised distributions:")
        .contains("not legal advice");
  }

  @Test
  void singlesOutTheRulesThatAreInferences() {
    assertThat(run("rules", "--unverified-only")).isEqualTo(ExitCode.CLEAN);

    assertThat(out)
        .contains("oracle-jdk-12-16-otn")
        .contains("UNVERIFIED")
        .doesNotContain("oracle-jdk-8-otn");
  }

  @Test
  void printsTheRuleFileVerbatimOnRequest() {
    assertThat(run("rules", "--raw")).isEqualTo(ExitCode.CLEAN);

    assertThat(out).contains("schemaVersion:").contains("rulesVersion:").contains("citations:");
  }
}
