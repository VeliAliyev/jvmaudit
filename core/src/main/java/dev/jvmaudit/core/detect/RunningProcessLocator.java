package dev.jvmaudit.core.detect;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Finds the JVMs that are running right now.
 *
 * <p>A JVM nobody can find on disk but that is serving traffic is the one that matters most, and
 * this is also the only locator that can catch a JVM launched from a deleted or unusual path.
 *
 * <p>Without elevated privileges an operating system will not show one user the command lines of
 * another user's processes. The scan therefore always records how complete this pass could be, so
 * the report can tell the reader to re-run as administrator or root rather than quietly
 * understating the estate.
 */
public final class RunningProcessLocator implements JvmLocator {

  private final Supplier<Stream<ProcessHandle>> processes;

  /**
   * @param processes supplies the processes to inspect
   */
  public RunningProcessLocator(Supplier<Stream<ProcessHandle>> processes) {
    this.processes = processes;
  }

  /** A locator over the processes on this machine. */
  public static RunningProcessLocator forCurrentMachine() {
    return new RunningProcessLocator(ProcessHandle::allProcesses);
  }

  @Override
  public String name() {
    return "running processes";
  }

  @Override
  public boolean isApplicable(ScanOptions options) {
    return options.includeRunningProcesses();
  }

  @Override
  public List<JvmCandidate> locate(ScanOptions options, Consumer<ScanIssue> issues) {
    List<JvmCandidate> found = new ArrayList<>();
    int total = 0;
    int visible = 0;

    try (Stream<ProcessHandle> stream = processes.get()) {
      for (ProcessHandle process : stream.toList()) {
        total++;
        Optional<String> command = process.info().command();
        if (command.isEmpty()) {
          continue;
        }
        visible++;
        String value = command.get();
        if (!looksLikeJavaLauncher(value)) {
          continue;
        }
        toPath(value)
            .flatMap(JvmHomes::toJvmHome)
            .ifPresent(
                home ->
                    found.add(
                        new JvmCandidate(
                            home, DetectionSource.RUNNING_PROCESS, "pid " + process.pid())));
      }
    } catch (RuntimeException e) {
      issues.accept(
          ScanIssue.warning(
              "Could not list running processes ("
                  + e.getMessage()
                  + "), so JVMs that are running"
                  + " but not installed in a conventional location may be missing.",
              null));
      return found;
    }

    if (total > 0 && visible < total) {
      issues.accept(
          ScanIssue.warning(
              "Only "
                  + visible
                  + " of "
                  + total
                  + " running processes exposed their command line to this user. Run JVMAudit as"
                  + " administrator (Windows) or root (Linux and macOS) for complete coverage of"
                  + " running JVMs.",
              null));
    }
    return found;
  }

  /**
   * Whether a process command looks like a Java launcher.
   *
   * @param command the executable path of a running process
   * @return true if it is a java or javaw launcher
   */
  static boolean looksLikeJavaLauncher(String command) {
    String lower = command.toLowerCase(Locale.ROOT).replace('\\', '/');
    int slash = lower.lastIndexOf('/');
    String name = slash < 0 ? lower : lower.substring(slash + 1);
    return name.equals("java")
        || name.equals("java.exe")
        || name.equals("javaw")
        || name.equals("javaw.exe");
  }

  private static Optional<Path> toPath(String value) {
    try {
      return Optional.of(Path.of(value));
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }
}
