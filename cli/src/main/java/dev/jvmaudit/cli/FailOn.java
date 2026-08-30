package dev.jvmaudit.cli;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import picocli.CommandLine;

/**
 * What should make {@code jvmaudit scan} exit non-zero.
 *
 * <p>The published spellings are hyphenated - {@code oracle-paid}, {@code oracle-any} - which no
 * Java enum constant can be, so the names are carried explicitly and converted on the way in. The
 * underscore spellings are accepted too, because somebody will type them.
 */
public enum FailOn {

  /** Never fail on findings. The default. */
  NONE("none", "never fail on findings"),

  /** Fail if any installation most likely needs a paid Oracle licence. */
  ORACLE_PAID("oracle-paid", "an installation most likely needs a paid Oracle licence"),

  /** Fail if any Oracle-licensed installation is present at all, free or not. */
  ORACLE_ANY("oracle-any", "any Oracle-licensed installation is present, free or not");

  private final String spelling;
  private final String description;

  FailOn(String spelling, String description) {
    this.spelling = spelling;
    this.description = description;
  }

  /** The spelling used on the command line. */
  public String spelling() {
    return spelling;
  }

  /** What it means, for help text. */
  public String description() {
    return description;
  }

  /**
   * Parses a command-line value, accepting both the hyphenated and underscored spellings.
   *
   * @param value what the user typed
   * @return the matching value
   * @throws IllegalArgumentException if it matches nothing, naming the valid spellings
   */
  public static FailOn parse(String value) {
    String normalised =
        value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    for (FailOn candidate : values()) {
      if (candidate.spelling.equals(normalised)) {
        return candidate;
      }
    }
    List<String> valid = new ArrayList<>();
    for (FailOn candidate : values()) {
      valid.add(candidate.spelling);
    }
    throw new IllegalArgumentException(
        "'"
            + value
            + "' is not a valid --fail-on value. Valid values: "
            + String.join(", ", valid));
  }

  /** Converts the command-line value for picocli. */
  public static final class Converter implements CommandLine.ITypeConverter<FailOn> {
    @Override
    public FailOn convert(String value) {
      try {
        return parse(value);
      } catch (IllegalArgumentException e) {
        // Picocli wraps a plain IllegalArgumentException in "cannot convert ... to FailOn (...)".
        // This exception type is printed as-is, so the user sees only the sentence that helps.
        throw new CommandLine.TypeConversionException(e.getMessage());
      }
    }
  }

  /** Supplies the values shown by {@code ${COMPLETION-CANDIDATES}} and by shell completion. */
  public static final class Candidates implements Iterable<String> {
    @Override
    public Iterator<String> iterator() {
      List<String> spellings = new ArrayList<>();
      for (FailOn candidate : values()) {
        spellings.add(candidate.spelling);
      }
      return spellings.iterator();
    }
  }
}
