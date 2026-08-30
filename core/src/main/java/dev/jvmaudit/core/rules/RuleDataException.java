package dev.jvmaudit.core.rules;

/**
 * Thrown when the rule data files are missing, malformed, or internally inconsistent. JVMAudit
 * fails loudly rather than scanning with a half-loaded rule set.
 */
public class RuleDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what is wrong, naming the file and the offending entry
   */
  public RuleDataException(String message) {
    super(message);
  }

  /**
   * @param message what is wrong, naming the file and the offending entry
   * @param cause the underlying parse or IO failure
   */
  public RuleDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
