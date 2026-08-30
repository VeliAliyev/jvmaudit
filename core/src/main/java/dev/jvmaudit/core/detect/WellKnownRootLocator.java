package dev.jvmaudit.core.detect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Looks in the directories Java is conventionally installed into on each operating system,
 * including the ones the version managers use.
 *
 * <p>Each root carries its own depth budget rather than sharing one, because the layouts differ by
 * an order of magnitude: a Windows install root holds the JDK one level down, while a Homebrew
 * cellar buries it five levels down inside a macOS bundle. Bounded walks keep this locator cheap
 * enough to run by default on a production server.
 */
public final class WellKnownRootLocator implements JvmLocator {

  /**
   * A directory to look in and how far below it to look.
   *
   * @param path the directory
   * @param depth how many levels below it to descend
   */
  public record Root(Path path, int depth) {
    public Root {
      Objects.requireNonNull(path, "path");
      if (depth < 1) {
        throw new IllegalArgumentException("depth must be at least 1, got " + depth);
      }
    }

    /** A root searched one level down, which is the usual layout. */
    public static Root of(Path path) {
      return new Root(path, 2);
    }
  }

  private final List<Root> roots;

  /**
   * @param roots the directories to search
   */
  public WellKnownRootLocator(List<Root> roots) {
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
  }

  /** The roots for the machine this is running on. */
  public static WellKnownRootLocator forCurrentMachine() {
    return new WellKnownRootLocator(
        rootsFor(
            OsFamily.current(), Path.of(System.getProperty("user.home", ".")), System.getenv()));
  }

  /** The directories this locator will search. */
  public List<Root> roots() {
    return roots;
  }

  /**
   * The conventional install roots for one operating system.
   *
   * @param os the operating system family
   * @param userHome the current user's home directory
   * @param env the process environment, consulted for the Windows Program Files locations
   * @return the roots, including ones that do not exist on this machine
   */
  public static List<Root> rootsFor(OsFamily os, Path userHome, Map<String, String> env) {
    List<Root> roots = new ArrayList<>();
    switch (os) {
      case WINDOWS -> {
        Path programFiles = fromEnv(env, "ProgramFiles", "C:\\Program Files");
        Path programFilesX86 = fromEnv(env, "ProgramFiles(x86)", "C:\\Program Files (x86)");
        for (Path base : List.of(programFiles, programFilesX86)) {
          roots.add(Root.of(base.resolve("Java")));
          roots.add(Root.of(base.resolve("Eclipse Adoptium")));
          roots.add(Root.of(base.resolve("Amazon Corretto")));
          roots.add(Root.of(base.resolve("Zulu")));
          roots.add(Root.of(base.resolve("Microsoft")));
          roots.add(Root.of(base.resolve("BellSoft")));
          roots.add(Root.of(base.resolve("RedHat")));
          roots.add(Root.of(base.resolve("SapMachine")));
          roots.add(Root.of(base.resolve("Semeru")));
          roots.add(Root.of(base.resolve("AdoptOpenJDK")));
          roots.add(new Root(base.resolve("Common Files").resolve("Oracle").resolve("Java"), 3));
        }
        roots.add(Root.of(userHome.resolve(".jdks")));
        roots.add(new Root(userHome.resolve(".gradle").resolve("jdks"), 3));
        roots.add(new Root(userHome.resolve("scoop").resolve("apps"), 3));
      }
      case MACOS -> {
        roots.add(new Root(Path.of("/Library/Java/JavaVirtualMachines"), 3));
        roots.add(new Root(userHome.resolve("Library/Java/JavaVirtualMachines"), 3));
        roots.add(new Root(Path.of("/System/Library/Java/JavaVirtualMachines"), 3));
        roots.add(
            new Root(
                Path.of("/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home"), 1));
        // Homebrew keeps the JVM inside a bundle inside a versioned cellar directory.
        roots.add(new Root(Path.of("/opt/homebrew/Cellar"), 6));
        roots.add(new Root(Path.of("/usr/local/Cellar"), 6));
        roots.add(new Root(Path.of("/opt/homebrew/opt"), 6));
        addVersionManagerRoots(roots, userHome);
      }
      case LINUX, OTHER -> {
        roots.add(new Root(Path.of("/usr/lib/jvm"), 3));
        roots.add(new Root(Path.of("/usr/lib64/jvm"), 3));
        roots.add(Root.of(Path.of("/usr/java")));
        roots.add(Root.of(Path.of("/opt")));
        roots.add(new Root(Path.of("/opt/java"), 3));
        roots.add(Root.of(Path.of("/usr/local")));
        roots.add(new Root(Path.of("/usr/local/lib/jvm"), 3));
        roots.add(new Root(Path.of("/snap"), 3));
        addVersionManagerRoots(roots, userHome);
      }
    }
    return List.copyOf(roots);
  }

  private static void addVersionManagerRoots(List<Root> roots, Path userHome) {
    roots.add(Root.of(userHome.resolve(".sdkman/candidates/java")));
    roots.add(Root.of(userHome.resolve(".asdf/installs/java")));
    roots.add(Root.of(userHome.resolve(".jdks")));
    roots.add(new Root(userHome.resolve(".gradle/jdks"), 3));
    roots.add(new Root(userHome.resolve(".jenv/versions"), 3));
    roots.add(new Root(userHome.resolve(".local/share/mise/installs/java"), 3));
  }

  private static Path fromEnv(Map<String, String> env, String key, String fallback) {
    String value = env.get(key);
    return Path.of(value == null || value.isBlank() ? fallback : value);
  }

  @Override
  public String name() {
    return "well-known roots";
  }

  @Override
  public boolean isApplicable(ScanOptions options) {
    return options.includeWellKnownRoots();
  }

  @Override
  public List<JvmCandidate> locate(ScanOptions options, Consumer<ScanIssue> issues) {
    List<JvmCandidate> found = new ArrayList<>();
    for (Root root : roots) {
      if (!Files.isDirectory(root.path())) {
        continue;
      }
      try {
        for (Path home : search(root)) {
          found.add(JvmCandidate.of(home, DetectionSource.WELL_KNOWN_ROOT));
        }
      } catch (IOException | RuntimeException e) {
        issues.accept(
            ScanIssue.warning(
                "Could not read a conventional install root: " + e.getMessage() + ".",
                root.path()));
      }
    }
    return found;
  }

  /** Breadth-first walk of one root, stopping at each JVM home rather than descending into it. */
  private static List<Path> search(Root root) throws IOException {
    List<Path> homes = new ArrayList<>();
    Deque<Path> queue = new ArrayDeque<>();
    Deque<Integer> depths = new ArrayDeque<>();
    queue.add(root.path());
    depths.add(1);

    while (!queue.isEmpty()) {
      Path directory = queue.removeFirst();
      int depth = depths.removeFirst();

      Path home = JvmHomes.toJvmHome(directory).orElse(null);
      if (home != null) {
        homes.add(home);
        continue;
      }
      if (depth >= root.depth()) {
        continue;
      }
      try (Stream<Path> children = Files.list(directory)) {
        for (Path child : children.toList()) {
          if (Files.isDirectory(child)) {
            queue.add(child);
            depths.add(depth + 1);
          }
        }
      } catch (IOException e) {
        // An unreadable subdirectory is normal (permissions); keep going with the rest.
      }
    }
    return homes;
  }
}
