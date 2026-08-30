package dev.jvmaudit.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build-time metadata about this copy of JVMAudit, baked in by Maven resource filtering. */
public final class BuildInfo {

  private static final String RESOURCE = "/jvmaudit-build.properties";
  private static final String VERSION = load();

  private BuildInfo() {}

  /** The tool version, e.g. {@code 0.1.0}. Never null; falls back to {@code unknown}. */
  public static String version() {
    return VERSION;
  }

  private static String load() {
    try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return "unknown";
      }
      Properties props = new Properties();
      props.load(in);
      String value = props.getProperty("version");
      return value == null || value.isBlank() ? "unknown" : value.trim();
    } catch (IOException e) {
      return "unknown";
    }
  }
}
