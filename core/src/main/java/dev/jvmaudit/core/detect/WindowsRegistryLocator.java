package dev.jvmaudit.core.detect;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads the {@code JavaHome} values Oracle's Windows installers write under {@code
 * HKLM\SOFTWARE\JavaSoft}, including the 32-bit view under {@code Wow6432Node}.
 *
 * <p>This is worth doing even though the well-known roots usually find the same JDKs: the registry
 * records installations that were put somewhere unconventional, and an Oracle JRE 8 installed by an
 * MSI years ago is exactly the kind of thing an audit turns up.
 *
 * <p>Shelling out to {@code reg} rather than using a JNI registry library keeps {@code core}
 * dependency-free. If {@code reg} is missing or refuses, the scan carries on and says so.
 */
public final class WindowsRegistryLocator implements JvmLocator {

  private static final List<String> KEYS =
      List.of(
          "HKLM\\SOFTWARE\\JavaSoft",
          "HKLM\\SOFTWARE\\Wow6432Node\\JavaSoft",
          "HKCU\\SOFTWARE\\JavaSoft");

  private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(20);

  private final ProcessRunner runner;
  private final OsFamily os;

  /**
   * @param runner how to run the {@code reg} tool
   * @param os the operating system family; the locator does nothing off Windows
   */
  public WindowsRegistryLocator(ProcessRunner runner, OsFamily os) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.os = Objects.requireNonNull(os, "os");
  }

  /** A locator that runs the real {@code reg} tool on this machine. */
  public static WindowsRegistryLocator forCurrentMachine() {
    return new WindowsRegistryLocator(ProcessRunner.system(), OsFamily.current());
  }

  @Override
  public String name() {
    return "Windows registry";
  }

  @Override
  public boolean isApplicable(ScanOptions options) {
    return os == OsFamily.WINDOWS && options.includeRegistry();
  }

  @Override
  public List<JvmCandidate> locate(ScanOptions options, Consumer<ScanIssue> issues) {
    List<JvmCandidate> found = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    boolean anyQuerySucceeded = false;

    for (String key : KEYS) {
      ProcessRunner.Result result = runner.run(List.of("reg", "query", key, "/s"), QUERY_TIMEOUT);

      if (result.failure() != null) {
        issues.accept(
            ScanIssue.warning(
                "Could not run the Windows reg tool ("
                    + result.failure()
                    + "), so registry-only"
                    + " Java installations may be missing from this scan.",
                null));
        continue;
      }
      if (result.timedOut()) {
        issues.accept(ScanIssue.warning("Querying the registry key " + key + " timed out.", null));
        continue;
      }
      anyQuerySucceeded = true;
      if (!result.succeeded()) {
        // A missing key is the normal case on a machine with no Oracle installer history.
        continue;
      }

      for (String home : parseJavaHomeValues(result.output())) {
        if (seen.add(home)) {
          toPath(home)
              .flatMap(JvmHomes::toJvmHome)
              .ifPresent(
                  path -> found.add(new JvmCandidate(path, DetectionSource.WINDOWS_REGISTRY, key)));
        }
      }
    }

    if (!anyQuerySucceeded) {
      issues.accept(
          ScanIssue.warning(
              "No registry key could be read, so Java installations recorded only in the registry"
                  + " may be missing from this scan.",
              null));
    }
    return found;
  }

  /**
   * Pulls the {@code JavaHome} values out of {@code reg query /s} output.
   *
   * <p>The format is {@code JavaHome REG_SZ C:\Program Files\Java\jre1.8.0_202}, with the value
   * itself allowed to contain spaces, so the split is on the type token rather than on whitespace.
   *
   * @param output what {@code reg} printed
   * @return the paths it named, in order, without duplicates
   */
  public static List<String> parseJavaHomeValues(String output) {
    List<String> values = new ArrayList<>();
    if (output == null) {
      return values;
    }
    for (String rawLine : output.split("\\R")) {
      String line = rawLine.trim();
      int name = line.indexOf("JavaHome");
      if (name != 0) {
        continue;
      }
      int type = line.indexOf("REG_SZ");
      if (type < 0) {
        type = line.indexOf("REG_EXPAND_SZ");
        if (type < 0) {
          continue;
        }
        type += "REG_EXPAND_SZ".length();
      } else {
        type += "REG_SZ".length();
      }
      String value = line.substring(type).trim();
      if (!value.isEmpty() && !values.contains(value)) {
        values.add(value);
      }
    }
    return values;
  }

  private static java.util.Optional<Path> toPath(String value) {
    try {
      return java.util.Optional.of(Path.of(value));
    } catch (InvalidPathException e) {
      return java.util.Optional.empty();
    }
  }
}
