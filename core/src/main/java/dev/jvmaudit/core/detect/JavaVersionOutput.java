package dev.jvmaudit.core.detect;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * What {@code java -version} said, parsed.
 *
 * <p>This is where the Oracle discriminator comes from. Oracle JDK prints {@code Java(TM) SE
 * Runtime Environment}; every OpenJDK build, Oracle's own included, prints {@code OpenJDK Runtime
 * Environment}. Nothing else in an installation separates the two reliably, and the difference is
 * "free" versus "this may cost money".
 *
 * @param versionString the quoted version, for example {@code 17.0.11}, or null
 * @param releaseDate the date printed after the version, or null
 * @param runtimeLine the whole Runtime Environment line, or null
 * @param isJavaTm TRUE for {@code Java(TM)}, FALSE for {@code OpenJDK}, null if neither was said
 * @param raw everything the command printed
 */
public record JavaVersionOutput(
    String versionString, LocalDate releaseDate, String runtimeLine, Boolean isJavaTm, String raw) {

  private static final String JAVA_TM_RUNTIME = "Java(TM) SE Runtime Environment";
  private static final String OPENJDK_RUNTIME = "OpenJDK Runtime Environment";

  public JavaVersionOutput {
    raw = Objects.requireNonNullElse(raw, "");
  }

  /** Whether anything usable was found. */
  public boolean isEmpty() {
    return versionString == null && runtimeLine == null && isJavaTm == null;
  }

  /**
   * Parses the output of {@code java -version}.
   *
   * @param output everything the command printed, on either stream; may be null
   * @return the parsed result, possibly {@linkplain #isEmpty() empty}
   */
  public static JavaVersionOutput parse(String output) {
    if (output == null || output.isBlank()) {
      return new JavaVersionOutput(null, null, null, null, "");
    }

    String versionString = null;
    LocalDate releaseDate = null;
    String runtimeLine = null;
    Boolean isJavaTm = null;

    for (String rawLine : output.split("\\R")) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }

      if (versionString == null && isVersionLine(line)) {
        versionString = between(line, '"', '"');
        releaseDate = trailingDate(line);
      }
      // "Runtime", not "Runtime Environment": IBM Semeru prints "IBM Semeru Runtime Open Edition",
      // and a survey of real builds found that spelling is not unique to IBM's older releases.
      if (runtimeLine == null && line.contains("Runtime")) {
        runtimeLine = line;
        if (line.contains(JAVA_TM_RUNTIME)) {
          isJavaTm = Boolean.TRUE;
        } else if (line.contains(OPENJDK_RUNTIME)) {
          isJavaTm = Boolean.FALSE;
        }
      }
    }

    // Some builds name themselves in a way that identifies neither family on the runtime line -
    // "IBM Semeru Runtime Open Edition" says neither Java(TM) nor OpenJDK. Fall back to the whole
    // output, on the exact "Java(TM)" spelling only, never on "Java HotSpot(TM)", which free builds
    // print too. The OpenJDK check is case-insensitive because the first line is lowercase
    // ("openjdk version \"1.8.0_504\"") while the runtime line is not.
    if (isJavaTm == null) {
      if (output.contains("Java(TM)")) {
        isJavaTm = Boolean.TRUE;
      } else if (output.toLowerCase(java.util.Locale.ROOT).contains("openjdk")) {
        isJavaTm = Boolean.FALSE;
      }
    }

    return new JavaVersionOutput(versionString, releaseDate, runtimeLine, isJavaTm, output);
  }

  private static boolean isVersionLine(String line) {
    int quote = line.indexOf('"');
    if (quote < 0) {
      return false;
    }
    String head = line.substring(0, quote).trim();
    return head.endsWith("version");
  }

  private static String between(String line, char open, char close) {
    int start = line.indexOf(open);
    if (start < 0) {
      return null;
    }
    int end = line.indexOf(close, start + 1);
    if (end <= start + 1) {
      return null;
    }
    return line.substring(start + 1, end);
  }

  private static LocalDate trailingDate(String line) {
    int closing = line.indexOf('"', line.indexOf('"') + 1);
    if (closing < 0) {
      return null;
    }
    for (String token : line.substring(closing + 1).trim().split("\\s+")) {
      if (token.length() == 10 && token.charAt(4) == '-' && token.charAt(7) == '-') {
        try {
          return LocalDate.parse(token);
        } catch (DateTimeParseException e) {
          return null;
        }
      }
    }
    return null;
  }
}
