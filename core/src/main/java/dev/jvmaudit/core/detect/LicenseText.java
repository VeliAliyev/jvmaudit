package dev.jvmaudit.core.detect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads the licence text an installation ships with itself, and says which licence it is.
 *
 * <p>This is half of the static discriminator between Oracle JDK and Oracle OpenJDK. Both report
 * {@code IMPLEMENTOR="Oracle Corporation"}; only one of them ships the No-Fee Terms and Conditions
 * or the OTN text, and the other ships GPLv2 with the Classpath Exception.
 *
 * <p>Where the text lives depends on how the build was packaged, not on which product it is: the
 * Windows installer puts a {@code LICENSE} in the installation root, while the tar.gz builds put
 * nothing there and keep it under {@code legal/<module>/LICENSE}. Both are checked, in that order.
 * Judging on the root alone was the first attempt and it separated nothing, because the tarballs of
 * both families look identical there.
 */
public final class LicenseText {

  /** Which licence an installation ships. */
  public enum Kind {
    /** Oracle No-Fee Terms and Conditions. */
    NFTC,
    /** Oracle Technology Network licence. */
    OTN,
    /** GraalVM Free Terms and Conditions. */
    GFTC,
    /** GNU General Public License v2, in practice with the Classpath Exception. */
    GPLV2,
    /** Text was found but matched nothing known. */
    UNRECOGNISED;

    /**
     * Parses the spelling used in the rule data files.
     *
     * @param text the value from {@code vendors.yaml}
     * @return the matching kind
     * @throws IllegalArgumentException if it names no known licence
     */
    public static Kind parse(String text) {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
  }

  /** How much of a licence file to read. The distinguishing wording is in the first few lines. */
  private static final int MAX_BYTES = 64 * 1024;

  private static final List<String> ROOT_CANDIDATES =
      List.of("LICENSE", "LICENSE.txt", "license", "LICENSE.md", "COPYRIGHT");

  private LicenseText() {}

  /**
   * Reads and classifies the licence an installation ships.
   *
   * @param home the JVM home directory
   * @return the licence kind, or empty if no licence text could be found or read
   */
  public static Optional<Kind> of(Path home) {
    if (home == null) {
      return Optional.empty();
    }
    for (String name : ROOT_CANDIDATES) {
      Optional<Kind> kind = read(home.resolve(name));
      if (kind.isPresent() && kind.get() != Kind.UNRECOGNISED) {
        return kind;
      }
    }
    return fromLegalDirectory(home);
  }

  /**
   * The licence under {@code legal/<module>/LICENSE}, which every distribution ships regardless of
   * packaging. Only the first module found is read; they are the same licence.
   */
  private static Optional<Kind> fromLegalDirectory(Path home) {
    Path legal = home.resolve("legal");
    if (!Files.isDirectory(legal)) {
      return Optional.empty();
    }
    try (Stream<Path> modules = Files.list(legal)) {
      for (Path module : modules.sorted().toList()) {
        if (!Files.isDirectory(module)) {
          continue;
        }
        Optional<Kind> kind = read(module.resolve("LICENSE"));
        if (kind.isPresent() && kind.get() != Kind.UNRECOGNISED) {
          return kind;
        }
      }
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static Optional<Kind> read(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try (var in = Files.newInputStream(file)) {
      byte[] head = in.readNBytes(MAX_BYTES);
      return Optional.of(classify(new String(head, StandardCharsets.UTF_8)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Classifies licence text.
   *
   * @param text the licence text, or the first part of it
   * @return which licence it is
   */
  public static Kind classify(String text) {
    if (text == null) {
      return Kind.UNRECOGNISED;
    }
    String lowered = text.toLowerCase(Locale.ROOT);
    if (lowered.contains("no-fee terms and conditions")) {
      return Kind.NFTC;
    }
    if (lowered.contains("graalvm free terms")) {
      return Kind.GFTC;
    }
    if (lowered.contains("oracle technology network") || lowered.contains("otn license")) {
      return Kind.OTN;
    }
    if (lowered.contains("gnu general public license")) {
      return Kind.GPLV2;
    }
    return Kind.UNRECOGNISED;
  }
}
