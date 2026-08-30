package dev.jvmaudit.core.detect;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmaudit.core.model.FingerprintSource;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.model.Product;
import dev.jvmaudit.core.rules.LicenseRulesEngine;
import dev.jvmaudit.core.rules.ProductCatalog;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmIdentifierTest {

  private static final ProductCatalog CATALOG =
      LicenseRulesEngine.usingPackagedRules().ruleSet().products();

  @TempDir Path temp;

  private final List<ScanIssue> issues = new ArrayList<>();

  private static ProcessRunner saying(String output) {
    return (command, timeout) -> new ProcessRunner.Result(0, output, "", false, null);
  }

  private static ProcessRunner refusing() {
    return (command, timeout) ->
        new ProcessRunner.Result(-1, "", "", false, "Cannot run program: not an executable");
  }

  @Test
  void readsEverythingItNeedsFromTheReleaseFileOfANonOracleBuild() {
    Path home =
        JvmFixtures.plantJvm(
            temp.resolve("temurin"),
            Map.of(
                "IMPLEMENTOR", "Eclipse Adoptium",
                "IMPLEMENTOR_VERSION", "Temurin-21.0.4+7",
                "JAVA_VERSION", "21.0.4",
                "JAVA_VERSION_DATE", "2024-07-16",
                "JAVA_RUNTIME_VERSION", "21.0.4+7-LTS"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(CATALOG, refusing()).identify(home, ScanOptions.defaults(), issues::add);

    assertThat(fingerprint.source()).isEqualTo(FingerprintSource.RELEASE_FILE);
    assertThat(fingerprint.product()).extracting(Product::id).isEqualTo("temurin");
    assertThat(fingerprint.versionString()).isEqualTo("21.0.4");
    assertThat(fingerprint.javaVersionDate()).isEqualTo(LocalDate.parse("2024-07-16"));
    assertThat(fingerprint.version().feature()).isEqualTo(21);
    assertThat(issues).as("no need to run anything").isEmpty();
  }

  @Test
  void runsTheLauncherWhenTheReleaseFileLeavesTheProductAmbiguous() {
    // The Oracle case: the release file says "Oracle Corporation" and nothing more.
    Path home =
        JvmFixtures.plantJvm(
            temp.resolve("oracle"),
            Map.of("IMPLEMENTOR", "Oracle Corporation", "JAVA_VERSION", "17.0.13"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(
                CATALOG,
                saying(
                    """
                    java version "17.0.13" 2024-10-15 LTS
                    Java(TM) SE Runtime Environment (build 17.0.13+10-LTS-58)
                    """))
            .identify(home, ScanOptions.defaults(), issues::add);

    assertThat(fingerprint.product()).extracting(Product::id).isEqualTo("oracle-jdk");
    assertThat(fingerprint.isJavaTm()).isTrue();
    assertThat(fingerprint.source())
        .as("the release file still supplied the version")
        .isEqualTo(FingerprintSource.RELEASE_FILE);
  }

  @Test
  void distinguishesOracleOpenJdkFromOracleJdkByRunningIt() {
    Path home =
        JvmFixtures.plantJvm(
            temp.resolve("oracle-openjdk"),
            Map.of("IMPLEMENTOR", "Oracle Corporation", "JAVA_VERSION", "21.0.2"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(
                CATALOG,
                saying(
                    """
                    openjdk version "21.0.2" 2024-01-16
                    OpenJDK Runtime Environment (build 21.0.2+13-58)
                    """))
            .identify(home, ScanOptions.defaults(), issues::add);

    assertThat(fingerprint.product()).extracting(Product::id).isEqualTo("oracle-openjdk");
    assertThat(fingerprint.isJavaTm()).isFalse();
  }

  @Test
  void leavesTheProductUnresolvedWhenExecutionIsForbidden() {
    Path home =
        JvmFixtures.plantJvm(
            temp.resolve("oracle"),
            Map.of("IMPLEMENTOR", "Oracle Corporation", "JAVA_VERSION", "17.0.13"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(CATALOG, saying("Java(TM) SE Runtime Environment"))
            .identify(
                home,
                ScanOptions.builder().execPolicy(ScanOptions.ExecPolicy.NEVER).build(),
                issues::add);

    assertThat(fingerprint.product()).isNull();
    assertThat(fingerprint.isJavaTm()).isNull();
    assertThat(fingerprint.vendor()).isEqualTo("Oracle Corporation");
  }

  @Test
  void runsTheLauncherEvenForAnUnambiguousBuildWhenToldAlways() {
    Path home =
        JvmFixtures.plantJvm(
            temp.resolve("temurin"),
            Map.of("IMPLEMENTOR", "Eclipse Adoptium", "JAVA_VERSION", "21.0.4"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(
                CATALOG,
                saying(
                    """
                    openjdk version "21.0.4" 2024-07-16
                    OpenJDK Runtime Environment Temurin-21.0.4+7 (build 21.0.4+7-LTS)
                    """))
            .identify(
                home,
                ScanOptions.builder().execPolicy(ScanOptions.ExecPolicy.ALWAYS).build(),
                issues::add);

    assertThat(fingerprint.runtimeName()).contains("Temurin");
    assertThat(fingerprint.isJavaTm()).isFalse();
  }

  @Test
  void identifiesAVendorFromTheVersionBannerWhenThereIsNoReleaseFile() {
    Path home = JvmFixtures.plantWithoutReleaseFile(temp.resolve("bare"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(
                CATALOG,
                saying(
                    """
                    openjdk version "17.0.13" 2024-10-15 LTS
                    OpenJDK Runtime Environment Corretto-17.0.13.11.1 (build 17.0.13+11-LTS)
                    """))
            .identify(home, ScanOptions.defaults(), issues::add);

    assertThat(fingerprint.source()).isEqualTo(FingerprintSource.EXEC);
    assertThat(fingerprint.product()).extracting(Product::id).isEqualTo("corretto");
    assertThat(fingerprint.versionString()).isEqualTo("17.0.13");
    assertThat(fingerprint.javaVersionDate()).isEqualTo(LocalDate.parse("2024-10-15"));
  }

  @Test
  void reportsAnInstallationItCanNeitherReadNorRun() {
    Path home = JvmFixtures.plantWithoutReleaseFile(temp.resolve("broken"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(CATALOG, refusing()).identify(home, ScanOptions.defaults(), issues::add);

    assertThat(fingerprint.source()).isEqualTo(FingerprintSource.HEURISTIC);
    assertThat(fingerprint.product()).isNull();
    assertThat(issues)
        .anySatisfy(issue -> assertThat(issue.message()).contains("Could not run java -version"));
  }

  @Test
  void reportsALauncherThatHangs() {
    Path home = JvmFixtures.plantWithoutReleaseFile(temp.resolve("hangs"));

    new JvmIdentifier(CATALOG, JvmFixtures.timingOutRunner())
        .identify(home, ScanOptions.defaults(), issues::add);

    assertThat(issues)
        .anySatisfy(issue -> assertThat(issue.message()).contains("did not finish within"));
  }

  @Test
  void reportsAnInstallationWithNoLauncherAtAll() {
    Path home = JvmFixtures.plantJvm(temp.resolve("no-bin"), Map.of("JAVA_VERSION", "21.0.4"));
    home.resolve("bin").resolve("java").toFile().delete();
    home.resolve("bin").resolve("java.exe").toFile().delete();
    home.resolve("bin").resolve("javac").toFile().delete();

    new JvmIdentifier(CATALOG, refusing())
        .identify(
            home,
            ScanOptions.builder().execPolicy(ScanOptions.ExecPolicy.ALWAYS).build(),
            issues::add);

    assertThat(issues)
        .anySatisfy(issue -> assertThat(issue.message()).contains("No java launcher under bin/"));
  }

  @Test
  void tellsOracleJdkFromOracleOpenJdkWithoutRunningAnything() {
    // The static discriminator, validated by the JDK artifact survey of 2026-08-30 across three
    // Oracle JDK and four Oracle OpenJDK releases: an Oracle JDK's SOURCE carries a second
    // "open:git:" component AND it ships the NFTC or OTN text; an Oracle OpenJDK build has neither.
    Path oracleJdk =
        JvmFixtures.plantJvm(
            temp.resolve("oracle-jdk"),
            Map.of(
                "IMPLEMENTOR", "Oracle Corporation",
                "JAVA_VERSION", "21.0.8",
                "JAVA_VERSION_DATE", "2025-07-15",
                "SOURCE", ".:git:86c757ff111e open:git:916e4b3a9b29"));
    JvmFixtures.plantLicense(oracleJdk, "legal", JvmFixtures.NFTC_TEXT);

    Path oracleOpenJdk =
        JvmFixtures.plantJvm(
            temp.resolve("oracle-openjdk"),
            Map.of(
                "IMPLEMENTOR", "Oracle Corporation",
                "JAVA_VERSION", "21.0.2",
                "JAVA_VERSION_DATE", "2024-01-16",
                "SOURCE", ".:git:289f7a7ab6f5"));
    JvmFixtures.plantLicense(oracleOpenJdk, "legal", JvmFixtures.GPLV2_TEXT);

    ScanOptions noExec = ScanOptions.builder().execPolicy(ScanOptions.ExecPolicy.NEVER).build();

    JvmFingerprint paid =
        new JvmIdentifier(CATALOG, refusing()).identify(oracleJdk, noExec, issues::add);
    JvmFingerprint free =
        new JvmIdentifier(CATALOG, refusing()).identify(oracleOpenJdk, noExec, issues::add);

    assertThat(paid.product()).extracting(Product::id).isEqualTo("oracle-jdk");
    assertThat(paid.licenseKind()).isEqualTo("NFTC");
    assertThat(paid.isJavaTm()).as("nothing was executed, so this stays unknown").isNull();

    assertThat(free.product()).extracting(Product::id).isEqualTo("oracle-openjdk");
    assertThat(free.licenseKind()).isEqualTo("GPLV2");
    assertThat(issues).as("no launcher was run, so there is nothing to report").isEmpty();
  }

  @Test
  void needsBothHalvesOfTheStaticDiscriminatorBeforeItNamesAnOracleBuild() {
    // Either half alone is not enough. This is the combined rule the directive asked for.
    Path sourceOnly =
        JvmFixtures.plantJvm(
            temp.resolve("source-only"),
            Map.of(
                "IMPLEMENTOR", "Oracle Corporation",
                "JAVA_VERSION", "21.0.8",
                "SOURCE", ".:git:aaaa open:git:bbbb"));

    Path licenceOnly =
        JvmFixtures.plantJvm(
            temp.resolve("licence-only"),
            Map.of("IMPLEMENTOR", "Oracle Corporation", "JAVA_VERSION", "21.0.8"));
    JvmFixtures.plantLicense(licenceOnly, "legal", JvmFixtures.NFTC_TEXT);

    ScanOptions noExec = ScanOptions.builder().execPolicy(ScanOptions.ExecPolicy.NEVER).build();

    assertThat(
            new JvmIdentifier(CATALOG, refusing())
                .identify(sourceOnly, noExec, issues::add)
                .product())
        .as("an open:git: SOURCE with no licence evidence is not enough")
        .isNull();
    assertThat(
            new JvmIdentifier(CATALOG, refusing())
                .identify(licenceOnly, noExec, issues::add)
                .product())
        .as("NFTC text with no SOURCE field is not enough")
        .isNull();
  }

  @Test
  void readsALicenceFromTheInstallationRootAsWellAsFromLegal() {
    // Oracle's Windows installer puts LICENSE in the root; the tar.gz builds only use legal/.
    Path home =
        JvmFixtures.plantJvm(
            temp.resolve("windows-style"),
            Map.of(
                "IMPLEMENTOR", "Oracle Corporation",
                "JAVA_VERSION", "17.0.11",
                "SOURCE", ".:git:0531bcd287a8 open:git:38d1cef19db8"));
    JvmFixtures.plantLicense(home, "root", JvmFixtures.NFTC_TEXT);

    JvmFingerprint fingerprint =
        new JvmIdentifier(CATALOG, refusing())
            .identify(
                home,
                ScanOptions.builder().execPolicy(ScanOptions.ExecPolicy.NEVER).build(),
                issues::add);

    assertThat(fingerprint.licenseKind()).isEqualTo("NFTC");
    assertThat(fingerprint.product()).extracting(Product::id).isEqualTo("oracle-jdk");
  }

  @Test
  void keepsTheRawFieldsThatMayLaterIdentifyOracleBuildsWithoutRunningThem() {
    // The SOURCE field is the candidate static discriminator; it is recorded but not yet acted on.
    Path home =
        JvmFixtures.plantJvm(
            temp.resolve("oracle"),
            Map.of(
                "IMPLEMENTOR", "Oracle Corporation",
                "JAVA_VERSION", "17.0.11",
                "JAVA_RUNTIME_VERSION", "17.0.11+7-LTS-207",
                "SOURCE", ".:git:0531bcd287a8 open:git:38d1cef19db8"));

    JvmFingerprint fingerprint =
        new JvmIdentifier(CATALOG, refusing())
            .identify(
                home,
                ScanOptions.builder().execPolicy(ScanOptions.ExecPolicy.NEVER).build(),
                issues::add);

    assertThat(fingerprint.sourceRepositories()).contains("open:git:");
    assertThat(fingerprint.runtimeVersion()).isEqualTo("17.0.11+7-LTS-207");
    assertThat(fingerprint.product())
        .as("recorded, but deliberately not used to decide the product yet")
        .isNull();
  }
}
