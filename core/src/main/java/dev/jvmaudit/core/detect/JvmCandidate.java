package dev.jvmaudit.core.detect;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A directory a locator believes is a JVM home, before anything has been read out of it.
 *
 * @param home the candidate JVM home directory
 * @param source how it was found
 * @param detail extra provenance worth keeping, such as the registry key or the process id, or null
 */
public record JvmCandidate(Path home, DetectionSource source, String detail) {

  public JvmCandidate {
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(source, "source");
  }

  /** A candidate with no extra provenance. */
  public static JvmCandidate of(Path home, DetectionSource source) {
    return new JvmCandidate(home, source, null);
  }
}
