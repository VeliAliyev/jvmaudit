package dev.jvmaudit.cli;

import dev.jvmaudit.core.report.ScanDiff;
import dev.jvmaudit.core.report.ScanSnapshot;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code jvmaudit diff} - what changed between two scans of the same host. */
@Command(
    name = "diff",
    mixinStandardHelpOptions = true,
    description = "Compare two scan JSON files and show what appeared, disappeared or changed.",
    footerHeading = "%n",
    footer = {
      "Exit codes:",
      "  0  nothing appeared or changed, or --fail-on was not given",
      "  1  --fail-on matched",
      "",
      "The point of this command is catching an Oracle JDK that creeps back onto a host.",
      "Run a scan on a schedule, keep the JSON, and diff it against the last one:",
      "",
      "  # cron, weekly",
      "  jvmaudit scan --format json --out /var/lib/jvmaudit/today.json",
      "  jvmaudit diff /var/lib/jvmaudit/last.json /var/lib/jvmaudit/today.json --fail-on oracle",
      "  mv /var/lib/jvmaudit/today.json /var/lib/jvmaudit/last.json"
    })
public final class DiffCommand implements Callable<Integer> {

  /** What should make the command exit non-zero. */
  public enum FailOn {
    /** Never fail. The default. */
    NONE,
    /** Fail when an Oracle-licensed installation appears, or one moves to a paid licence. */
    ORACLE,
    /** Fail on any difference at all. */
    ANY
  }

  @Parameters(index = "0", paramLabel = "<old.json>", description = "The earlier scan.")
  Path before;

  @Parameters(index = "1", paramLabel = "<new.json>", description = "The later scan.")
  Path after;

  @Option(
      names = "--fail-on",
      paramLabel = "<what>",
      description = "Exit with code 1 on: none, oracle, any. Default: none.")
  FailOn failOn = FailOn.NONE;

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public Integer call() {
    ScanDiff diff = ScanDiff.between(ScanSnapshot.read(before), ScanSnapshot.read(after));

    spec.commandLine().getOut().print(diff.render());
    spec.commandLine().getOut().flush();

    boolean matched =
        switch (failOn) {
          case NONE -> false;
          case ORACLE -> diff.hasNewOracleExposure();
          case ANY -> !diff.isEmpty();
        };
    return matched ? ExitCode.MATCHES_FOUND : ExitCode.CLEAN;
  }
}
