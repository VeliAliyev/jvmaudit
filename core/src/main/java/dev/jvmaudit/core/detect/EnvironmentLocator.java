package dev.jvmaudit.core.detect;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Finds the JVMs this shell would actually use: {@code JAVA_HOME}, and every {@code java} launcher
 * on {@code PATH}.
 *
 * <p>These matter out of proportion to their number. A machine can hold a dozen JDKs, but the one
 * on {@code PATH} is the one that runs in production, so if exactly one Oracle JDK on a host is
 * worth arguing about, it is usually this one.
 */
public final class EnvironmentLocator implements JvmLocator {

  private final Map<String, String> environment;
  private final String pathSeparator;
  private final OsFamily os;

  /**
   * @param environment the process environment
   * @param pathSeparator the separator between PATH entries
   * @param os the operating system family, which decides the launcher's file name
   */
  public EnvironmentLocator(Map<String, String> environment, String pathSeparator, OsFamily os) {
    this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    this.pathSeparator = Objects.requireNonNull(pathSeparator, "pathSeparator");
    this.os = Objects.requireNonNull(os, "os");
  }

  /** A locator over this process's own environment. */
  public static EnvironmentLocator forCurrentMachine() {
    return new EnvironmentLocator(System.getenv(), java.io.File.pathSeparator, OsFamily.current());
  }

  @Override
  public String name() {
    return "JAVA_HOME and PATH";
  }

  @Override
  public boolean isApplicable(ScanOptions options) {
    return options.includeEnvironment();
  }

  @Override
  public List<JvmCandidate> locate(ScanOptions options, Consumer<ScanIssue> issues) {
    List<JvmCandidate> found = new ArrayList<>();

    for (String variable : List.of("JAVA_HOME", "JDK_HOME", "JRE_HOME")) {
      String value = environment.get(variable);
      if (value == null || value.isBlank()) {
        continue;
      }
      Optional<Path> home = toPath(value).flatMap(JvmHomes::toJvmHome);
      if (home.isPresent()) {
        found.add(new JvmCandidate(home.get(), DetectionSource.JAVA_HOME, variable));
      } else {
        issues.accept(
            ScanIssue.warning(
                variable + " is set to something that is not a JVM home: " + value + ".", null));
      }
    }

    String path = environment.get("PATH");
    if (path == null) {
      path = environment.get("Path");
    }
    if (path == null || path.isBlank()) {
      return found;
    }

    for (String entry : path.split(java.util.regex.Pattern.quote(pathSeparator))) {
      if (entry.isBlank()) {
        continue;
      }
      Optional<Path> directory = toPath(entry.trim());
      if (directory.isEmpty()) {
        continue;
      }
      Path launcher = directory.get().resolve(os.javaExecutableName());
      JvmHomes.toJvmHome(launcher)
          .ifPresent(home -> found.add(new JvmCandidate(home, DetectionSource.PATH, entry.trim())));
    }
    return found;
  }

  private static Optional<Path> toPath(String value) {
    try {
      return Optional.of(Path.of(value));
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }
}
