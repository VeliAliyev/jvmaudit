package dev.jvmaudit.core.detect;

import dev.jvmaudit.core.model.Classification;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.model.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One Java installation, as found, identified and classified.
 *
 * @param path the canonical JVM home, which is also the identity used for deduplication
 * @param fingerprint what was read out of it
 * @param classification what the licence rules made of that
 * @param sources every way it was found; more than one is normal
 * @param aliases other paths that resolve to this same installation, such as a symlink that points
 *     at it
 */
public record DetectedJvm(
    Path path,
    JvmFingerprint fingerprint,
    Classification classification,
    Set<DetectionSource> sources,
    List<Path> aliases) {

  public DetectedJvm {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(classification, "classification");
    sources = Set.copyOf(Objects.requireNonNullElse(sources, Set.of()));
    aliases = List.copyOf(Objects.requireNonNullElse(aliases, List.of()));
  }

  /** How urgently the reader should look at this one. */
  public Severity severity() {
    return classification.severity();
  }

  /** The product's display name, or a placeholder when the vendor was not recognised. */
  public String productName() {
    return fingerprint.product() == null
        ? (fingerprint.vendor() == null ? "unidentified" : fingerprint.vendor())
        : fingerprint.product().displayName();
  }

  /** The version as reported by the installation, or a placeholder. */
  public String versionLabel() {
    if (fingerprint.version() != null) {
      return fingerprint.version().canonical();
    }
    return fingerprint.versionString() == null ? "unknown" : fingerprint.versionString();
  }
}
