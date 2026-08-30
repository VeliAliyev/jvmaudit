package dev.jvmaudit.core.detect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Shared rules for recognising and normalising JVM home directories. */
public final class JvmHomes {

  /** The name of the metadata file every modern JDK and JRE ships in its home directory. */
  public static final String RELEASE_FILE = "release";

  private JvmHomes() {}

  /**
   * Whether a directory looks like a JVM home: it has a {@code release} file, or a {@code java}
   * launcher under {@code bin}.
   *
   * @param directory the directory to test, may be null
   * @return true if this looks like a JVM home
   */
  public static boolean looksLikeJvmHome(Path directory) {
    if (directory == null || !Files.isDirectory(directory)) {
      return false;
    }
    return Files.isRegularFile(directory.resolve(RELEASE_FILE))
        || javaLauncher(directory).isPresent();
  }

  /**
   * The {@code java} launcher inside a JVM home, under either name.
   *
   * @param home the JVM home directory, may be null
   * @return the launcher, or empty if there is none
   */
  public static Optional<Path> javaLauncher(Path home) {
    if (home == null) {
      return Optional.empty();
    }
    Path bin = home.resolve("bin");
    for (String name : new String[] {"java", "java.exe"}) {
      Path candidate = bin.resolve(name);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether the installation is a full JDK rather than a JRE, judged by the compiler's presence.
   */
  public static boolean isJdk(Path home) {
    if (home == null) {
      return false;
    }
    Path bin = home.resolve("bin");
    return Files.isRegularFile(bin.resolve("javac"))
        || Files.isRegularFile(bin.resolve("javac.exe"));
  }

  /**
   * Turns a path that points at, into, or near a JVM home into the JVM home itself.
   *
   * <p>Handles the three shapes that turn up in the wild: the home directory itself, a macOS bundle
   * ({@code Foo.jdk/Contents/Home}), and a path to the launcher or its {@code bin} directory.
   *
   * @param path any of the above, may be null
   * @return the JVM home, or empty if this is not one
   */
  public static Optional<Path> toJvmHome(Path path) {
    if (path == null) {
      return Optional.empty();
    }
    if (Files.isRegularFile(path)) {
      // A java launcher: <home>/bin/java
      Path bin = path.getParent();
      if (bin != null && bin.getFileName() != null && "bin".equals(bin.getFileName().toString())) {
        return present(bin.getParent());
      }
      return Optional.empty();
    }
    if (!Files.isDirectory(path)) {
      return Optional.empty();
    }
    if (looksLikeJvmHome(path)) {
      return Optional.of(path);
    }
    if (path.getFileName() != null && "bin".equals(path.getFileName().toString())) {
      return present(path.getParent());
    }
    // macOS: /Library/Java/JavaVirtualMachines/temurin-21.jdk -> .../Contents/Home
    Path bundleHome = path.resolve("Contents").resolve("Home");
    if (looksLikeJvmHome(bundleHome)) {
      return Optional.of(bundleHome);
    }
    return Optional.empty();
  }

  private static Optional<Path> present(Path home) {
    return looksLikeJvmHome(home) ? Optional.of(home) : Optional.empty();
  }

  /**
   * The canonical form of a path, used to recognise that two discoveries are the same installation.
   *
   * <p>Resolves symbolic links where the filesystem allows it - {@code /usr/lib/jvm/default-java}
   * and the directory it points at are one JVM, not two - and falls back to normalisation when it
   * does not.
   *
   * @param path the path to canonicalise
   * @return the canonical path
   */
  public static Path canonical(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException | RuntimeException e) {
      return path.toAbsolutePath().normalize();
    }
  }
}
