package dev.jvmaudit.core.model;

/** How JVMAudit learned what an installation is. Reported so the reader can judge the evidence. */
public enum FingerprintSource {

  /** Read from the {@code release} file in the JVM home. Cheap, safe, and authoritative. */
  RELEASE_FILE("release file"),

  /**
   * Read from the output of {@code bin/java -version}. Used when there is no readable release file.
   */
  EXEC("java -version"),

  /** Inferred from the shape of the installation, for example its directory layout. */
  HEURISTIC("heuristic"),

  /** Supplied directly, as tests and fixtures do. */
  SUPPLIED("supplied");

  private final String label;

  FingerprintSource(String label) {
    this.label = label;
  }

  /** A short label for the report. */
  public String label() {
    return label;
  }
}
