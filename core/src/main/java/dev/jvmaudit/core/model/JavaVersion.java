package dev.jvmaudit.core.model;

import java.util.Objects;

/**
 * A Java version, parsed from either version scheme and comparable across both.
 *
 * <p>Two schemes exist in the wild and JVMAudit meets both of them:
 *
 * <ul>
 *   <li>the legacy scheme used up to Java 8 - {@code 1.8.0_202}, {@code 1.8.0_202-b08}, and the
 *       {@code 8u202} spelling Oracle uses in its release notes;
 *   <li>the modern JEP 322 scheme - {@code 17.0.11}, {@code 17.0.11+7-LTS-207}, {@code 21.0.12.1}.
 * </ul>
 *
 * <p>Both are normalised onto the same four numbers - feature, interim, update, patch - so that
 * {@code 8u202} and {@code 1.8.0_202} compare equal and {@code 17.0.12} sorts before {@code
 * 17.0.13}. Build numbers and qualifiers ({@code +7}, {@code -LTS-207}, {@code -b08}, {@code -ea})
 * are recorded but never affect ordering.
 */
public final class JavaVersion implements Comparable<JavaVersion> {

  private final int feature;
  private final int interim;
  private final int update;
  private final int patch;
  private final String qualifier;
  private final String raw;

  private JavaVersion(
      int feature, int interim, int update, int patch, String qualifier, String raw) {
    this.feature = feature;
    this.interim = interim;
    this.update = update;
    this.patch = patch;
    this.qualifier = qualifier;
    this.raw = raw;
  }

  /**
   * Parses a version string in either scheme.
   *
   * @param raw for example {@code 1.8.0_202}, {@code 8u202}, {@code 17.0.11+7-LTS-207}
   * @return the parsed version
   * @throws IllegalArgumentException if {@code raw} is null, blank, or holds no leading number
   */
  public static JavaVersion parse(String raw) {
    JavaVersion parsed = parseOrNull(raw);
    if (parsed == null) {
      throw new IllegalArgumentException("Not a recognisable Java version: " + raw);
    }
    return parsed;
  }

  /**
   * Parses a version string in either scheme, returning null instead of throwing.
   *
   * @param raw the version string, may be null
   * @return the parsed version, or null if it could not be parsed
   */
  public static JavaVersion parseOrNull(String raw) {
    if (raw == null) {
      return null;
    }
    String text = raw.trim();
    if (text.isEmpty()) {
      return null;
    }

    // Split off the build/qualifier tail: everything from the first '+' or, failing that, from the
    // first '-' that is not part of the legacy "_202-b08" update tail.
    String core = text;
    String qualifier = "";
    int plus = core.indexOf('+');
    if (plus >= 0) {
      qualifier = core.substring(plus);
      core = core.substring(0, plus);
    }

    int update = 0;
    int underscore = core.indexOf('_');
    if (underscore >= 0) {
      String tail = core.substring(underscore + 1);
      core = core.substring(0, underscore);
      int dash = tail.indexOf('-');
      if (dash >= 0) {
        qualifier = tail.substring(dash) + qualifier;
        tail = tail.substring(0, dash);
      }
      update = leadingInt(tail, 0);
    }

    int dash = core.indexOf('-');
    if (dash >= 0) {
      qualifier = core.substring(dash) + qualifier;
      core = core.substring(0, dash);
    }

    // The "8u202" spelling from Oracle's release notes.
    int u = core.indexOf('u');
    if (u > 0 && isAllDigits(core.substring(0, u))) {
      int feature = Integer.parseInt(core.substring(0, u));
      int fromU = leadingInt(core.substring(u + 1), 0);
      return new JavaVersion(feature, 0, fromU, 0, qualifier, text);
    }

    String[] parts = core.split("\\.");
    int[] numbers = new int[parts.length];
    int count = 0;
    for (String part : parts) {
      if (part.isEmpty() || !isAllDigits(part)) {
        break;
      }
      numbers[count++] = Integer.parseInt(part);
    }
    if (count == 0) {
      return null;
    }

    // Legacy "1.8.0_202": the leading 1 is not a feature version.
    if (numbers[0] == 1 && count >= 2) {
      int feature = numbers[1];
      int interim = count >= 3 ? numbers[2] : 0;
      return new JavaVersion(feature, interim, update, 0, qualifier, text);
    }

    int feature = numbers[0];
    int interim = count >= 2 ? numbers[1] : 0;
    int third = count >= 3 ? numbers[2] : 0;
    int fourth = count >= 4 ? numbers[3] : 0;
    // A modern version carries its update in the third position; an "_" tail cannot occur here.
    return new JavaVersion(feature, interim, third, fourth, qualifier, text);
  }

  private static boolean isAllDigits(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static int leadingInt(String value, int fallback) {
    int end = 0;
    while (end < value.length() && Character.isDigit(value.charAt(end))) {
      end++;
    }
    return end == 0 ? fallback : Integer.parseInt(value.substring(0, end));
  }

  /** The feature version: 8 for {@code 1.8.0_202}, 17 for {@code 17.0.11}. */
  public int feature() {
    return feature;
  }

  /** The interim version: the second number of the modern scheme, 0 for the legacy scheme. */
  public int interim() {
    return interim;
  }

  /** The update version: 202 for {@code 8u202}, 11 for {@code 17.0.11}. */
  public int update() {
    return update;
  }

  /** The patch version: 1 for {@code 21.0.12.1}, 0 when absent. */
  public int patch() {
    return patch;
  }

  /** The build number and qualifiers, for example {@code +7-LTS-207}; empty when absent. */
  public String qualifier() {
    return qualifier;
  }

  /** The string this version was parsed from, unchanged. */
  public String raw() {
    return raw;
  }

  /**
   * The version in the spelling Oracle uses in its release notes: {@code 8u202} for Java 8 and
   * earlier, {@code 17.0.11} or {@code 21.0.12.1} for later versions.
   */
  public String canonical() {
    if (feature <= 8) {
      return update == 0 ? String.valueOf(feature) : feature + "u" + update;
    }
    StringBuilder text = new StringBuilder();
    text.append(feature).append('.').append(interim).append('.').append(update);
    if (patch != 0) {
      text.append('.').append(patch);
    }
    return text.toString();
  }

  @Override
  public int compareTo(JavaVersion other) {
    int result = Integer.compare(feature, other.feature);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(interim, other.interim);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(update, other.update);
    if (result != 0) {
      return result;
    }
    return Integer.compare(patch, other.patch);
  }

  /** Whether this version is at least {@code other}, comparing the four numbers only. */
  public boolean isAtLeast(JavaVersion other) {
    return compareTo(other) >= 0;
  }

  /** Whether this version is at most {@code other}, comparing the four numbers only. */
  public boolean isAtMost(JavaVersion other) {
    return compareTo(other) <= 0;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof JavaVersion that)) {
      return false;
    }
    return feature == that.feature
        && interim == that.interim
        && update == that.update
        && patch == that.patch;
  }

  @Override
  public int hashCode() {
    return Objects.hash(feature, interim, update, patch);
  }

  @Override
  public String toString() {
    return canonical();
  }
}
