package dev.jvmaudit.core.detect;

import dev.jvmaudit.core.model.FingerprintSource;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.model.Product;
import dev.jvmaudit.core.rules.ProductCatalog;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Turns a JVM home directory into a {@link JvmFingerprint}.
 *
 * <p>Order of preference, and the reason for it:
 *
 * <ol>
 *   <li>the {@code release} file - cheap, safe, and readable on a locked-down production host;
 *   <li>{@code bin/java -version} - needed when there is no release file, and needed even when
 *       there is one if the release file leaves the product ambiguous. Oracle JDK and Oracle
 *       OpenJDK both report {@code IMPLEMENTOR="Oracle Corporation"} and differ only in whether the
 *       runtime calls itself {@code Java(TM)}.
 * </ol>
 *
 * <p>When neither settles it, the fingerprint carries no product and the rules engine reports
 * UNKNOWN with an explanation. Guessing here would be the worst possible failure mode for this
 * tool, so it does not.
 */
public final class JvmIdentifier {

  private final ProductCatalog catalog;
  private final ProcessRunner runner;

  /**
   * @param catalog the product catalogue to recognise vendors with
   * @param runner how to execute {@code java -version}
   */
  public JvmIdentifier(ProductCatalog catalog, ProcessRunner runner) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  /** An identifier that really executes java launchers. */
  public static JvmIdentifier using(ProductCatalog catalog) {
    return new JvmIdentifier(catalog, ProcessRunner.system());
  }

  /**
   * Identifies one installation.
   *
   * @param home the JVM home directory
   * @param options controls whether and when a java launcher may be executed
   * @param issues receives anything that went wrong along the way
   * @return the fingerprint; never null, but its fields may be
   */
  public JvmFingerprint identify(Path home, ScanOptions options, Consumer<ScanIssue> issues) {
    ReleaseFile release = ReleaseFile.read(home).orElseGet(ReleaseFile::empty);

    String implementor = release.implementor();
    String implementorVersion = release.implementorVersion();
    String versionString = release.javaVersion();
    LocalDate versionDate = release.javaVersionDate().orElse(null);
    String runtimeVersion = release.javaRuntimeVersion();

    // The licence the installation ships with itself, read straight off disk. Together with the
    // SOURCE field this identifies an Oracle build without running anything - validated against
    // three Oracle JDK and four Oracle OpenJDK releases before being relied on.
    String licenseKind = LicenseText.of(home).map(Enum::name).orElse(null);

    Optional<Product> product =
        catalog.resolve(
            new ProductCatalog.Evidence(
                implementor, implementorVersion, null, null, release.source(), licenseKind));

    String runtimeName = null;
    Boolean isJavaTm = null;
    boolean execUsed = false;

    if (shouldExec(options, release, product)) {
      Optional<JavaVersionOutput> probed = probe(home, options, issues);
      if (probed.isPresent()) {
        JavaVersionOutput output = probed.get();
        execUsed = true;
        runtimeName = output.runtimeLine();
        isJavaTm = output.isJavaTm();
        if (versionString == null) {
          versionString = output.versionString();
        }
        if (versionDate == null) {
          versionDate = output.releaseDate();
        }
        product =
            catalog.resolve(
                new ProductCatalog.Evidence(
                    implementor,
                    implementorVersion,
                    runtimeName,
                    isJavaTm,
                    release.source(),
                    licenseKind));
      }
    }

    FingerprintSource source;
    if (!release.isEmpty()) {
      source = FingerprintSource.RELEASE_FILE;
    } else if (execUsed) {
      source = FingerprintSource.EXEC;
    } else {
      source = FingerprintSource.HEURISTIC;
    }

    return JvmFingerprint.builder()
        .path(home)
        .product(product.orElse(null))
        .vendor(implementor)
        .implementorVersion(implementorVersion)
        .versionString(versionString)
        .runtimeVersion(runtimeVersion)
        .runtimeName(runtimeName)
        .javaVersionDate(versionDate)
        .javaTm(isJavaTm)
        .buildType(release.buildType())
        .sourceRepositories(release.source())
        .licenseKind(licenseKind)
        .source(source)
        .build();
  }

  /**
   * Whether to run the launcher. "When needed" means: there is nothing to read, or what there is to
   * read does not identify the product - which is the Oracle JDK versus Oracle OpenJDK case.
   */
  private static boolean shouldExec(
      ScanOptions options, ReleaseFile release, Optional<Product> product) {
    return switch (options.execPolicy()) {
      case NEVER -> false;
      case ALWAYS -> true;
      case WHEN_NEEDED -> release.isEmpty() || product.isEmpty();
    };
  }

  private Optional<JavaVersionOutput> probe(
      Path home, ScanOptions options, Consumer<ScanIssue> issues) {
    Optional<Path> launcher = JvmHomes.javaLauncher(home);
    if (launcher.isEmpty()) {
      issues.accept(
          ScanIssue.warning(
              "No java launcher under bin/, so this installation could not be identified by"
                  + " running it.",
              home));
      return Optional.empty();
    }

    ProcessRunner.Result result =
        runner.run(List.of(launcher.get().toString(), "-version"), options.execTimeout());

    if (result.timedOut()) {
      issues.accept(
          ScanIssue.warning(
              "java -version did not finish within " + options.execTimeout().toSeconds() + "s.",
              home));
      return Optional.empty();
    }
    if (result.failure() != null) {
      issues.accept(
          ScanIssue.warning("Could not run java -version: " + result.failure() + ".", home));
      return Optional.empty();
    }

    JavaVersionOutput output = JavaVersionOutput.parse(result.output());
    if (output.isEmpty()) {
      issues.accept(
          ScanIssue.warning("java -version produced nothing JVMAudit could parse.", home));
      return Optional.empty();
    }
    return Optional.of(output);
  }
}
