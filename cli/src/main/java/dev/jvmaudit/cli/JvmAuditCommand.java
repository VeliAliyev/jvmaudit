package dev.jvmaudit.cli;

import dev.jvmaudit.core.BuildInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The top-level {@code jvmaudit} command.
 *
 * <p>Running it with no subcommand prints the help rather than scanning: this tool exists to make
 * statements about software licensing, and it should never do anything consequential because
 * somebody typed its name to see what it was.
 */
@Command(
    name = "jvmaudit",
    versionProvider = JvmAuditCommand.VersionProvider.class,
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = {ScanCommand.class, DiffCommand.class, RulesCommand.class},
    description =
        "Find every Java installation on this machine and show which ones are Oracle-licensed.",
    footerHeading = "%n",
    footer = {
      "JVMAudit runs entirely offline. It makes no network requests and sends no telemetry.",
      "",
      "It is an inventory tool, not legal advice: licence conclusions depend on your contracts",
      "with Oracle. Every verdict it prints carries the source it came from - follow them.",
      "",
      "Examples:",
      "  jvmaudit scan                                 look in the usual places",
      "  jvmaudit scan --deep                          also sweep the filesystem for bundled JVMs",
      "  jvmaudit scan --format html --out report.html a report to forward to a manager",
      "  jvmaudit scan --format json --out today.json  a snapshot to keep",
      "  jvmaudit diff last-week.json today.json       what changed since last week",
      "  jvmaudit rules                                the licence rules and their sources"
    })
public final class JvmAuditCommand implements Runnable {

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** Supplies {@code --version} output. */
  public static final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {
        "jvmaudit " + BuildInfo.version(),
        "licence rules " + RulesCommand.rulesVersionQuietly(),
        "running on Java "
            + System.getProperty("java.version", "unknown")
            + " ("
            + System.getProperty("java.vendor", "unknown")
            + ")"
      };
    }
  }
}
