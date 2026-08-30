package dev.jvmaudit.core.detect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Searches the directories the user named with {@code --paths}.
 *
 * <p>Each is searched a few levels down, so both "here is a JDK" and "here is a directory full of
 * JDKs" do what the user meant. A path that holds no JVM is reported rather than ignored: silently
 * finding nothing under a directory the user explicitly asked about is a bug from their side of the
 * screen.
 */
public final class ExplicitPathLocator implements JvmLocator {

  private static final int DEPTH = 4;

  @Override
  public String name() {
    return "explicit paths";
  }

  @Override
  public boolean isApplicable(ScanOptions options) {
    return !options.paths().isEmpty();
  }

  @Override
  public List<JvmCandidate> locate(ScanOptions options, Consumer<ScanIssue> issues) {
    List<JvmCandidate> found = new ArrayList<>();
    WellKnownRootLocator search =
        new WellKnownRootLocator(
            options.paths().stream()
                .map(path -> new WellKnownRootLocator.Root(path, DEPTH))
                .toList());

    for (Path path : options.paths()) {
      if (!Files.exists(path)) {
        issues.accept(ScanIssue.warning("--paths named a directory that does not exist.", path));
      } else if (!Files.isDirectory(path)) {
        issues.accept(ScanIssue.warning("--paths named something that is not a directory.", path));
      }
    }

    for (JvmCandidate candidate : search.locate(options, issues)) {
      found.add(JvmCandidate.of(candidate.home(), DetectionSource.EXPLICIT_PATH));
    }

    if (found.isEmpty() && !options.paths().isEmpty()) {
      issues.accept(
          ScanIssue.info(
              "No Java installation was found under the paths given with --paths (searched "
                  + DEPTH
                  + " levels down)."));
    }
    return found;
  }
}
