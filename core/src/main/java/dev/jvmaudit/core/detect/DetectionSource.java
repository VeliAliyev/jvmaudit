package dev.jvmaudit.core.detect;

/**
 * How an installation was found. Reported per installation, and more than one can apply to the same
 * JVM - a JDK that is both on the PATH and in a well-known root lists both.
 */
public enum DetectionSource {

  /** A conventional install directory for this operating system. */
  WELL_KNOWN_ROOT("well-known install root"),

  /** The {@code JAVA_HOME} environment variable. */
  JAVA_HOME("JAVA_HOME"),

  /** A directory on {@code PATH} holding a {@code java} launcher. */
  PATH("PATH"),

  /** The Windows registry under {@code HKLM\\SOFTWARE\\JavaSoft}. */
  WINDOWS_REGISTRY("Windows registry"),

  /** A process running on this machine right now. */
  RUNNING_PROCESS("running process"),

  /** The opt-in deep filesystem sweep. This is what finds JVMs bundled inside applications. */
  DEEP_SCAN("deep scan"),

  /** A path the user named explicitly with {@code --paths}. */
  EXPLICIT_PATH("explicit path");

  private final String label;

  DetectionSource(String label) {
    this.label = label;
  }

  /** A short label for the report. */
  public String label() {
    return label;
  }
}
