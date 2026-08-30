package dev.jvmaudit.core.detect;

import java.util.Locale;

/** The operating system family, which decides where JVMs are conventionally installed. */
public enum OsFamily {
  WINDOWS,
  MACOS,
  LINUX,
  OTHER;

  /** The family of the machine this JVM is running on. */
  public static OsFamily current() {
    return detect(System.getProperty("os.name", ""));
  }

  /**
   * Classifies an {@code os.name} string.
   *
   * @param osName the value of the {@code os.name} system property, may be null
   * @return the matching family, or {@link #OTHER}
   */
  public static OsFamily detect(String osName) {
    String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (name.contains("win")) {
      return WINDOWS;
    }
    if (name.contains("mac") || name.contains("darwin")) {
      return MACOS;
    }
    if (name.contains("nux")
        || name.contains("nix")
        || name.contains("aix")
        || name.contains("sunos")
        || name.contains("bsd")) {
      return LINUX;
    }
    return OTHER;
  }

  /** The file name of the {@code java} launcher on this family. */
  public String javaExecutableName() {
    return this == WINDOWS ? "java.exe" : "java";
  }
}
