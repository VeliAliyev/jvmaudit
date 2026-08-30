package dev.jvmaudit.core.rules;

import dev.jvmaudit.core.model.JavaVersion;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * General-availability dates of Oracle JDK releases, loaded from {@code rules/jdk-releases.json}.
 *
 * <p>Used only when an installation's {@code release} file carries no {@code JAVA_VERSION_DATE}. A
 * version that is not in the catalogue yields no date, which makes date-based rules fall through
 * and the engine report {@code UNKNOWN} - never a guessed date.
 */
public final class ReleaseCatalog {

  private final Map<JavaVersion, LocalDate> dates;

  /**
   * @param dates GA dates keyed by parsed version
   */
  public ReleaseCatalog(Map<JavaVersion, LocalDate> dates) {
    this.dates = Map.copyOf(Objects.requireNonNull(dates, "dates"));
  }

  /** An empty catalogue, for tests that want to exercise the unknown-date path. */
  public static ReleaseCatalog empty() {
    return new ReleaseCatalog(Map.of());
  }

  /**
   * The GA date of a release.
   *
   * @param version the version to look up, may be null
   * @return the GA date, or empty if the version is null or not in the catalogue
   */
  public Optional<LocalDate> gaDate(JavaVersion version) {
    return version == null ? Optional.empty() : Optional.ofNullable(dates.get(version));
  }

  /** How many releases the catalogue holds. */
  public int size() {
    return dates.size();
  }
}
