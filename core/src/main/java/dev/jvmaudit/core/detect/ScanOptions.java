package dev.jvmaudit.core.detect;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a scan should do. Defaults are chosen so that {@code jvmaudit scan} with no arguments is
 * fast, safe on a production server, and complete enough to be useful.
 */
public final class ScanOptions {

  /**
   * When JVMAudit is allowed to execute a found {@code bin/java -version}.
   *
   * <p>The default is {@link #WHEN_NEEDED}, not "never": Oracle JDK and Oracle OpenJDK are
   * indistinguishable from the {@code release} file alone, and that is exactly the distinction
   * between "free" and "this may cost money". Refusing to run anything would turn every Oracle
   * installation into an UNKNOWN.
   */
  public enum ExecPolicy {
    /** Never execute anything. Some installations will be reported UNKNOWN as a result. */
    NEVER,
    /** Execute only when the release file is missing or leaves the product ambiguous. */
    WHEN_NEEDED,
    /** Always execute, even when the release file already answers the question. */
    ALWAYS
  }

  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration DEFAULT_EXEC_TIMEOUT = Duration.ofSeconds(5);
  private static final int DEFAULT_MAX_DEPTH = 12;

  private final boolean deep;
  private final List<Path> paths;
  private final List<String> excludeGlobs;
  private final int maxDepth;
  private final Duration timeout;
  private final Duration execTimeout;
  private final ExecPolicy execPolicy;
  private final boolean includeRunningProcesses;
  private final boolean includeRegistry;
  private final boolean includeWellKnownRoots;
  private final boolean includeEnvironment;

  private ScanOptions(Builder builder) {
    this.deep = builder.deep;
    this.paths = List.copyOf(builder.paths);
    this.excludeGlobs = List.copyOf(builder.excludeGlobs);
    this.maxDepth = builder.maxDepth;
    this.timeout = builder.timeout;
    this.execTimeout = builder.execTimeout;
    this.execPolicy = builder.execPolicy;
    this.includeRunningProcesses = builder.includeRunningProcesses;
    this.includeRegistry = builder.includeRegistry;
    this.includeWellKnownRoots = builder.includeWellKnownRoots;
    this.includeEnvironment = builder.includeEnvironment;
  }

  /** The defaults: every cheap locator, no deep sweep, exec only when it is needed. */
  public static ScanOptions defaults() {
    return builder().build();
  }

  /** A fresh builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Whether to run the opt-in deep filesystem sweep. */
  public boolean deep() {
    return deep;
  }

  /** Directories to scan instead of the defaults, or empty to use the defaults. */
  public List<Path> paths() {
    return paths;
  }

  /** Glob patterns of paths to skip during the deep sweep. */
  public List<String> excludeGlobs() {
    return excludeGlobs;
  }

  /** How deep the deep sweep may descend below each root. */
  public int maxDepth() {
    return maxDepth;
  }

  /** The overall budget for the scan. Locators stop early rather than overrun it. */
  public Duration timeout() {
    return timeout;
  }

  /** The budget for one {@code java -version} call. */
  public Duration execTimeout() {
    return execTimeout;
  }

  /** When JVMAudit may execute a found java launcher. */
  public ExecPolicy execPolicy() {
    return execPolicy;
  }

  /** Whether to inspect running processes. */
  public boolean includeRunningProcesses() {
    return includeRunningProcesses;
  }

  /** Whether to query the Windows registry. */
  public boolean includeRegistry() {
    return includeRegistry;
  }

  /** Whether to look in the conventional install roots for this operating system. */
  public boolean includeWellKnownRoots() {
    return includeWellKnownRoots;
  }

  /** Whether to look at {@code JAVA_HOME} and {@code PATH}. */
  public boolean includeEnvironment() {
    return includeEnvironment;
  }

  /** Builds {@link ScanOptions}. */
  public static final class Builder {
    private boolean deep;
    private List<Path> paths = new ArrayList<>();
    private List<String> excludeGlobs = new ArrayList<>();
    private int maxDepth = DEFAULT_MAX_DEPTH;
    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration execTimeout = DEFAULT_EXEC_TIMEOUT;
    private ExecPolicy execPolicy = ExecPolicy.WHEN_NEEDED;
    private boolean includeRunningProcesses = true;
    private boolean includeRegistry = true;
    private boolean includeWellKnownRoots = true;
    private boolean includeEnvironment = true;

    private Builder() {}

    /** Turns the deep filesystem sweep on or off. */
    public Builder deep(boolean value) {
      this.deep = value;
      return this;
    }

    /** Scans these directories instead of the defaults. */
    public Builder paths(List<Path> value) {
      this.paths = new ArrayList<>(Objects.requireNonNullElse(value, List.of()));
      return this;
    }

    /** Skips paths matching these glob patterns during the deep sweep. */
    public Builder excludeGlobs(List<String> value) {
      this.excludeGlobs = new ArrayList<>(Objects.requireNonNullElse(value, List.of()));
      return this;
    }

    /** Limits how deep the deep sweep descends. */
    public Builder maxDepth(int value) {
      if (value < 1) {
        throw new IllegalArgumentException("maxDepth must be at least 1, got " + value);
      }
      this.maxDepth = value;
      return this;
    }

    /** Sets the overall scan budget. */
    public Builder timeout(Duration value) {
      this.timeout = Objects.requireNonNull(value, "timeout");
      return this;
    }

    /** Sets the budget for one java -version call. */
    public Builder execTimeout(Duration value) {
      this.execTimeout = Objects.requireNonNull(value, "execTimeout");
      return this;
    }

    /** Sets when JVMAudit may execute a found java launcher. */
    public Builder execPolicy(ExecPolicy value) {
      this.execPolicy = Objects.requireNonNull(value, "execPolicy");
      return this;
    }

    /** Turns the running-process locator on or off. */
    public Builder includeRunningProcesses(boolean value) {
      this.includeRunningProcesses = value;
      return this;
    }

    /** Turns the Windows registry locator on or off. */
    public Builder includeRegistry(boolean value) {
      this.includeRegistry = value;
      return this;
    }

    /** Turns the well-known roots locator on or off. */
    public Builder includeWellKnownRoots(boolean value) {
      this.includeWellKnownRoots = value;
      return this;
    }

    /** Turns the JAVA_HOME and PATH locator on or off. */
    public Builder includeEnvironment(boolean value) {
      this.includeEnvironment = value;
      return this;
    }

    /** Builds the options. */
    public ScanOptions build() {
      return new ScanOptions(this);
    }
  }
}
