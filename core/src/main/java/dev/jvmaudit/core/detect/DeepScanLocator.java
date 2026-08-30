package dev.jvmaudit.core.detect;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Walks the filesystem looking for JVM homes.
 *
 * <p>This is the opt-in {@code --deep} pass, and it is the one that finds what the others cannot: a
 * JRE shipped inside some application's install directory. That is exactly where a surprise Oracle
 * JDK lives, because nobody installed it deliberately and nobody remembers it is there.
 *
 * <p>It is opt-in because it is the only expensive locator. Four things keep it survivable on a
 * real server: symbolic links are never followed, so there are no loops; a depth limit; a default
 * exclusion list covering the pseudo-filesystems and the directories that are large and never hold
 * a JDK; and a deadline, after which the walk stops and says it was cut short rather than
 * pretending the result is complete.
 */
public final class DeepScanLocator implements JvmLocator {

  /**
   * Directory names skipped everywhere. Pseudo-filesystems, package caches, and the two build
   * directories that dwarf everything else on a developer machine.
   */
  static final Set<String> EXCLUDED_NAMES =
      Set.of(
          "proc",
          "sys",
          "dev",
          "run",
          "node_modules",
          ".git",
          ".svn",
          ".hg",
          "$recycle.bin",
          "system volume information",
          "winsxs",
          "windows.old",
          ".trash",
          ".trashes",
          "lost+found");

  /** Absolute paths skipped entirely: pseudo-filesystems and the macOS network mount point. */
  static final Set<String> EXCLUDED_ABSOLUTE =
      Set.of("/proc", "/sys", "/dev", "/run", "/net", "/Network", "/Volumes", "/private/var/vm");

  private final List<Path> roots;

  /**
   * @param roots the directories to walk
   */
  public DeepScanLocator(List<Path> roots) {
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
  }

  /** A deep scan over every filesystem root on this machine. */
  public static DeepScanLocator forCurrentMachine() {
    List<Path> roots = new ArrayList<>();
    FileSystems.getDefault().getRootDirectories().forEach(roots::add);
    return new DeepScanLocator(roots);
  }

  /** The directories this locator will walk. */
  public List<Path> roots() {
    return roots;
  }

  @Override
  public String name() {
    return "deep filesystem scan";
  }

  @Override
  public boolean isApplicable(ScanOptions options) {
    return options.deep();
  }

  @Override
  public List<JvmCandidate> locate(ScanOptions options, Consumer<ScanIssue> issues) {
    Instant deadline = Instant.now().plus(options.timeout());
    List<PathMatcher> excludes = compile(options.excludeGlobs(), issues);
    List<JvmCandidate> found = new ArrayList<>();
    Set<Path> visited = new HashSet<>();
    boolean[] cutShort = {false};

    // --paths narrows the sweep; without it the sweep covers every filesystem root.
    List<Path> targets = options.paths().isEmpty() ? roots : options.paths();

    for (Path root : targets) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try {
        Files.walkFileTree(
            root,
            Set.of(),
            options.maxDepth(),
            new Visitor(found, excludes, deadline, visited, cutShort));
      } catch (IOException | RuntimeException e) {
        issues.accept(
            ScanIssue.warning(
                "Deep scan could not finish under " + root + ": " + e.getMessage(), root));
      }
    }

    if (cutShort[0]) {
      issues.accept(
          ScanIssue.warning(
              "The deep scan hit its "
                  + options.timeout().toSeconds()
                  + "s time limit and stopped early, so this inventory may be incomplete. Re-run"
                  + " with a longer --timeout, or narrow it with --paths.",
              null));
    }
    return found;
  }

  private static List<PathMatcher> compile(List<String> globs, Consumer<ScanIssue> issues) {
    List<PathMatcher> matchers = new ArrayList<>(globs.size());
    for (String glob : globs) {
      try {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
      } catch (IllegalArgumentException | UnsupportedOperationException e) {
        issues.accept(ScanIssue.warning("Ignoring unusable --exclude pattern: " + glob, null));
      }
    }
    return matchers;
  }

  /** Records a JVM home and skips its subtree; a JDK contains no second JDK. */
  private static final class Visitor implements FileVisitor<Path> {

    private final List<JvmCandidate> found;
    private final List<PathMatcher> excludes;
    private final Instant deadline;
    private final Set<Path> visited;
    private final boolean[] cutShort;
    private int sinceClockCheck;

    Visitor(
        List<JvmCandidate> found,
        List<PathMatcher> excludes,
        Instant deadline,
        Set<Path> visited,
        boolean[] cutShort) {
      this.found = found;
      this.excludes = excludes;
      this.deadline = deadline;
      this.visited = visited;
      this.cutShort = cutShort;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
      if (outOfTime()) {
        cutShort[0] = true;
        return FileVisitResult.TERMINATE;
      }
      if (isExcluded(directory)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      // Belt and braces against a filesystem that reports a cycle even without following links.
      if (!visited.add(JvmHomes.canonical(directory))) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      if (JvmHomes.looksLikeJvmHome(directory)) {
        found.add(JvmCandidate.of(directory, DetectionSource.DEEP_SCAN));
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
      return outOfTime() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException failure) {
      // Unreadable directories are the normal case on a real machine; the scan-level warning about
      // privileges already tells the user what that means.
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
      return FileVisitResult.CONTINUE;
    }

    private boolean isExcluded(Path directory) {
      Path name = directory.getFileName();
      if (name != null && EXCLUDED_NAMES.contains(name.toString().toLowerCase(Locale.ROOT))) {
        return true;
      }
      String absolute = directory.toAbsolutePath().toString().replace('\\', '/');
      if (EXCLUDED_ABSOLUTE.contains(absolute)) {
        return true;
      }
      for (PathMatcher matcher : excludes) {
        if (matcher.matches(directory) || (name != null && matcher.matches(name))) {
          return true;
        }
      }
      return false;
    }

    /**
     * Checks the clock on the first directory and then every 64th, so an already-expired budget
     * stops the walk immediately instead of after a burst of work.
     */
    private boolean outOfTime() {
      if (sinceClockCheck-- > 0) {
        return false;
      }
      sinceClockCheck = 63;
      // >= so that a budget of zero, or one already spent, stops immediately.
      return Instant.now().compareTo(deadline) >= 0;
    }
  }

  /** The default overall budget for a deep scan, if the caller sets none. */
  public static Duration defaultTimeout() {
    return Duration.ofMinutes(2);
  }
}
