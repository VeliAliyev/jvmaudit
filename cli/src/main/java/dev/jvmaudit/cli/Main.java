package dev.jvmaudit.cli;

import dev.jvmaudit.core.rules.RuleDataException;
import java.io.PrintWriter;
import picocli.CommandLine;

/** Entry point. */
public final class Main {

  /** Wide enough that the example commands in the footer fit on one line each. */
  private static final int HELP_WIDTH = 100;

  private Main() {}

  /**
   * Runs the CLI and exits with its code.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    System.exit(run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
  }

  /**
   * Runs the CLI against explicit streams, so tests can drive it without touching the real ones.
   *
   * @param args the command line
   * @param out where normal output goes
   * @param err where errors go
   * @return the process exit code
   */
  public static int run(String[] args, PrintWriter out, PrintWriter err) {
    return run(args, out, err, ansi());
  }

  /**
   * Runs the CLI with an explicit colour setting, so tests get byte-stable output.
   *
   * @param args the command line
   * @param out where normal output goes
   * @param err where errors go
   * @param ansi whether to emit ANSI escapes
   * @return the process exit code
   */
  static int run(String[] args, PrintWriter out, PrintWriter err, CommandLine.Help.Ansi ansi) {
    CommandLine commandLine =
        new CommandLine(new JvmAuditCommand())
            .setOut(out)
            .setErr(err)
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setUsageHelpWidth(HELP_WIDTH)
            .setUsageHelpAutoWidth(true)
            .setColorScheme(CommandLine.Help.defaultColorScheme(ansi))
            .setExecutionExceptionHandler(Main::handleException);
    return commandLine.execute(args);
  }

  /**
   * Colour in help text follows the same rule as colour in scan output: only on a real terminal,
   * and never when the environment asks for plain text. Help that is redirected into a file or a CI
   * log has to stay readable.
   */
  private static CommandLine.Help.Ansi ansi() {
    return System.getenv("NO_COLOR") != null
        ? CommandLine.Help.Ansi.OFF
        : CommandLine.Help.Ansi.AUTO;
  }

  /**
   * Turns an exception into a message a user can act on.
   *
   * <p>A stack trace is the wrong answer for every failure this tool has: a malformed rule file, an
   * unreadable scan file, a path that is not there. Each of those has a specific thing the user
   * should do about it, and the trace is only noise on top.
   */
  private static int handleException(
      Exception exception, CommandLine commandLine, CommandLine.ParseResult parseResult) {
    PrintWriter err = commandLine.getErr();

    if (exception instanceof RuleDataException) {
      err.println("jvmaudit: the licence rule data is unusable, so no verdict can be trusted.");
      err.println("  " + exception.getMessage());
      err.println(
          "  This is a bug in this build of JVMAudit. Please report it at"
              + " https://github.com/VeliAliyev/jvmaudit/issues");
      return ExitCode.SCAN_ERROR;
    }
    if (exception instanceof IllegalArgumentException) {
      err.println("jvmaudit: " + exception.getMessage());
      return ExitCode.USAGE_ERROR;
    }
    if (exception instanceof java.io.UncheckedIOException
        || exception instanceof java.io.IOException) {
      err.println("jvmaudit: " + exception.getMessage());
      return ExitCode.SCAN_ERROR;
    }

    err.println("jvmaudit: unexpected failure: " + exception);
    err.println(
        "  Please report this at https://github.com/VeliAliyev/jvmaudit/issues with the command"
            + " you ran.");
    exception.printStackTrace(err);
    return ExitCode.SCAN_ERROR;
  }
}
