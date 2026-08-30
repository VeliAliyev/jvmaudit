package dev.jvmaudit.core.model;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Everything JVMAudit knows about one Java installation, and nothing it had to guess.
 *
 * <p>Fields are nullable on purpose: an installation with an unreadable {@code release} file and a
 * {@code bin/java} that will not run yields a fingerprint that is mostly empty, and the rules
 * engine is expected to answer {@link LicenseStatus#UNKNOWN} for it rather than fill in blanks.
 *
 * @param path the JVM home directory
 * @param product the recognised distribution, or null if the vendor was not recognised
 * @param vendor the raw {@code IMPLEMENTOR} string, or null
 * @param implementorVersion the raw {@code IMPLEMENTOR_VERSION} string, or null
 * @param versionString the raw {@code JAVA_VERSION} string, or null
 * @param version {@code versionString} parsed, or null if it was absent or unparseable
 * @param runtimeVersion the raw {@code JAVA_RUNTIME_VERSION} string, or null
 * @param runtimeName the runtime line from {@code java -version}, or null
 * @param javaVersionDate the {@code JAVA_VERSION_DATE} field, or null
 * @param isJavaTm TRUE when the runtime identifies itself as {@code Java(TM)}, FALSE when it says
 *     {@code OpenJDK}, null when unknown - the discriminator between Oracle JDK and Oracle OpenJDK
 * @param buildType the raw {@code BUILD_TYPE} field, or null
 * @param sourceRepositories the raw {@code SOURCE} field, or null
 * @param licenseKind the licence the installation ships with itself, or null. With {@code
 *     sourceRepositories} this forms the static discriminator between Oracle JDK and Oracle
 *     OpenJDK, which the release file alone cannot separate.
 * @param source how this fingerprint was obtained
 * @param bundledInside the application directory this JVM appears to be bundled inside, or null
 */
public record JvmFingerprint(
    Path path,
    Product product,
    String vendor,
    String implementorVersion,
    String versionString,
    JavaVersion version,
    String runtimeVersion,
    String runtimeName,
    LocalDate javaVersionDate,
    Boolean isJavaTm,
    String buildType,
    String sourceRepositories,
    String licenseKind,
    FingerprintSource source,
    Path bundledInside) {

  public JvmFingerprint {
    source = Objects.requireNonNullElse(source, FingerprintSource.SUPPLIED);
  }

  /** A fresh builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Whether this JVM appears to live inside another application's installation directory. */
  public boolean isBundled() {
    return bundledInside != null;
  }

  /**
   * A copy that records the application directory this JVM sits inside. The detector works this out
   * after identification, because it needs to know where the JVM was found relative to the
   * conventional install roots.
   *
   * @param directory the application directory, or null for none
   * @return a copy carrying the value
   */
  public JvmFingerprint withBundledInside(Path directory) {
    return new JvmFingerprint(
        path,
        product,
        vendor,
        implementorVersion,
        versionString,
        version,
        runtimeVersion,
        runtimeName,
        javaVersionDate,
        isJavaTm,
        buildType,
        sourceRepositories,
        licenseKind,
        source,
        directory);
  }

  /** Builds a {@link JvmFingerprint}. Every field is optional. */
  public static final class Builder {
    private Path path;
    private Product product;
    private String vendor;
    private String implementorVersion;
    private String versionString;
    private JavaVersion version;
    private String runtimeVersion;
    private String runtimeName;
    private LocalDate javaVersionDate;
    private Boolean isJavaTm;
    private String buildType;
    private String sourceRepositories;
    private String licenseKind;
    private FingerprintSource source = FingerprintSource.SUPPLIED;
    private Path bundledInside;

    private Builder() {}

    /** Sets the JVM home directory. */
    public Builder path(Path value) {
      this.path = value;
      return this;
    }

    /** Sets the recognised distribution. */
    public Builder product(Product value) {
      this.product = value;
      return this;
    }

    /** Sets the raw {@code IMPLEMENTOR} string. */
    public Builder vendor(String value) {
      this.vendor = value;
      return this;
    }

    /** Sets the raw {@code IMPLEMENTOR_VERSION} string. */
    public Builder implementorVersion(String value) {
      this.implementorVersion = value;
      return this;
    }

    /** Sets the raw {@code JAVA_VERSION} string and parses it, ignoring anything unparseable. */
    public Builder versionString(String value) {
      this.versionString = value;
      this.version = JavaVersion.parseOrNull(value);
      return this;
    }

    /** Overrides the parsed version. */
    public Builder version(JavaVersion value) {
      this.version = value;
      return this;
    }

    /** Sets the raw {@code JAVA_RUNTIME_VERSION} string. */
    public Builder runtimeVersion(String value) {
      this.runtimeVersion = value;
      return this;
    }

    /** Sets the runtime line reported by {@code java -version}. */
    public Builder runtimeName(String value) {
      this.runtimeName = value;
      return this;
    }

    /** Sets the {@code JAVA_VERSION_DATE} field. */
    public Builder javaVersionDate(LocalDate value) {
      this.javaVersionDate = value;
      return this;
    }

    /** Sets whether the runtime calls itself {@code Java(TM)}; null means unknown. */
    public Builder javaTm(Boolean value) {
      this.isJavaTm = value;
      return this;
    }

    /** Sets the raw {@code BUILD_TYPE} field. */
    public Builder buildType(String value) {
      this.buildType = value;
      return this;
    }

    /** Sets the raw {@code SOURCE} field. */
    public Builder sourceRepositories(String value) {
      this.sourceRepositories = value;
      return this;
    }

    /** Sets the licence the installation ships with itself, by name. */
    public Builder licenseKind(String value) {
      this.licenseKind = value;
      return this;
    }

    /** Sets how this fingerprint was obtained. */
    public Builder source(FingerprintSource value) {
      this.source = value;
      return this;
    }

    /** Records the application directory this JVM is bundled inside. */
    public Builder bundledInside(Path value) {
      this.bundledInside = value;
      return this;
    }

    /** Builds the fingerprint. */
    public JvmFingerprint build() {
      return new JvmFingerprint(
          path,
          product,
          vendor,
          implementorVersion,
          versionString,
          version,
          runtimeVersion,
          runtimeName,
          javaVersionDate,
          isJavaTm,
          buildType,
          sourceRepositories,
          licenseKind,
          source,
          bundledInside);
    }
  }
}
