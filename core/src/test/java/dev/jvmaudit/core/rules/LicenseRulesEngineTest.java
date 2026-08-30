package dev.jvmaudit.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.Classification;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.FingerprintSource;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.model.LicenseStatus;
import dev.jvmaudit.core.model.Product;
import dev.jvmaudit.core.model.Severity;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The table from the build plan, turned into tests. Each row is one licensing claim the product
 * makes, so a change in behaviour here is a change in what JVMAudit tells a customer.
 */
class LicenseRulesEngineTest {

  private static final LicenseRulesEngine ENGINE = LicenseRulesEngine.usingPackagedRules();

  /** An engine with no release-date data, to exercise the version-only fall-back rules. */
  private static final LicenseRulesEngine WITHOUT_DATES =
      new LicenseRulesEngine(
          new RuleSet(
              ENGINE.ruleSet().rulesVersion(),
              ENGINE.ruleSet().disclaimer(),
              ENGINE.ruleSet().sources(),
              ENGINE.ruleSet().rules(),
              ENGINE.ruleSet().products(),
              ReleaseCatalog.empty()));

  private static Product product(String id) {
    return ENGINE
        .ruleSet()
        .products()
        .byId(id)
        .orElseThrow(() -> new AssertionError("No such product in vendors.yaml: " + id));
  }

  private static JvmFingerprint jvm(String productId, String version) {
    return JvmFingerprint.builder()
        .path(Path.of("/opt/java", productId + "-" + version))
        .product(product(productId))
        .vendor(product(productId).vendor())
        .versionString(version)
        .source(FingerprintSource.RELEASE_FILE)
        .build();
  }

  private static JvmFingerprint jvm(String productId, String version, String releaseDate) {
    return JvmFingerprint.builder()
        .path(Path.of("/opt/java", productId + "-" + version))
        .product(product(productId))
        .vendor(product(productId).vendor())
        .versionString(version)
        .javaVersionDate(LocalDate.parse(releaseDate))
        .source(FingerprintSource.RELEASE_FILE)
        .build();
  }

  static Stream<Arguments> theRuleTable() {
    return Stream.of(
        // ---- Java 8: the 8u202 / 8u211 boundary of April 2019
        Arguments.of(
            "Oracle JDK 8u202 is the last BCL build",
            jvm("oracle-jdk", "1.8.0_202"),
            LicenseStatus.LEGACY_BCL,
            "oracle-jdk-8-bcl"),
        Arguments.of(
            "Oracle JDK 8u202 in Oracle's own spelling",
            jvm("oracle-jdk", "8u202"),
            LicenseStatus.LEGACY_BCL,
            "oracle-jdk-8-bcl"),
        Arguments.of(
            "Oracle JDK 8u211 is the first OTN build",
            jvm("oracle-jdk", "1.8.0_211"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-8-otn"),
        Arguments.of(
            "a current Oracle JDK 8 update is still OTN",
            jvm("oracle-jdk", "1.8.0_503"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-8-otn"),

        // ---- Java 11: OTN throughout
        Arguments.of(
            "Oracle JDK 11.0.22",
            jvm("oracle-jdk", "11.0.22"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-11-otn"),
        Arguments.of(
            "Oracle JDK 11.0.1, the first update",
            jvm("oracle-jdk", "11.0.1"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-11-otn"),

        // ---- Java 12 to 16: between the OTN switch and the NFTC, inferred rather than quoted
        Arguments.of(
            "Oracle JDK 13.0.2",
            jvm("oracle-jdk", "13.0.2"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-12-16-otn"),

        // ---- Java 17: the 17.0.12 / 17.0.13 boundary of October 2024
        Arguments.of(
            "Oracle JDK 17.0.12 is the last NFTC build",
            jvm("oracle-jdk", "17.0.12", "2024-07-16"),
            LicenseStatus.ORACLE_FREE_NFTC,
            "oracle-jdk-17-nftc-by-date"),
        Arguments.of(
            "Oracle JDK 17.0.13 is the first OTN build",
            jvm("oracle-jdk", "17.0.13", "2024-10-15"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-17-otn-by-date"),
        Arguments.of(
            "a real Oracle JDK 17.0.11 read off this machine",
            jvm("oracle-jdk", "17.0.11", "2024-04-16"),
            LicenseStatus.ORACLE_FREE_NFTC,
            "oracle-jdk-17-nftc-by-date"),
        Arguments.of(
            "a current Oracle JDK 17 update",
            jvm("oracle-jdk", "17.0.20", "2026-07-21"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-17-otn-by-date"),

        // ---- Java 21: inside and outside the NFTC window that closes in September 2026
        Arguments.of(
            "Oracle JDK 21.0.8 is inside the NFTC window",
            jvm("oracle-jdk", "21.0.8", "2025-07-15"),
            LicenseStatus.ORACLE_FREE_NFTC,
            "oracle-jdk-21-nftc-by-date"),
        Arguments.of(
            "Oracle JDK 21.0.12.1 is the last build inside the window",
            jvm("oracle-jdk", "21.0.12.1", "2026-08-18"),
            LicenseStatus.ORACLE_FREE_NFTC,
            "oracle-jdk-21-nftc-by-date"),
        Arguments.of(
            "an Oracle JDK 21 update from the October 2026 CPU is OTN",
            jvm("oracle-jdk", "21.0.13", "2026-10-20"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-jdk-21-otn-by-date"),

        // ---- Java 25 and the current non-LTS line
        Arguments.of(
            "Oracle JDK 25.0.4 is inside its NFTC window",
            jvm("oracle-jdk", "25.0.4", "2026-07-21"),
            LicenseStatus.ORACLE_FREE_NFTC,
            "oracle-jdk-25-nftc"),
        Arguments.of(
            "Oracle JDK 26 is a current NFTC release",
            jvm("oracle-jdk", "26.0.2", "2026-07-21"),
            LicenseStatus.ORACLE_FREE_NFTC,
            "oracle-jdk-nftc-current"),

        // ---- Oracle's own OpenJDK builds are free, despite the Oracle vendor string
        Arguments.of(
            "a real Oracle OpenJDK 21.0.2 read off this machine",
            jvm("oracle-openjdk", "21.0.2", "2024-01-16"),
            LicenseStatus.FREE,
            "oracle-openjdk-gpl"),
        Arguments.of(
            "Oracle OpenJDK 17.0.13, the same version as a paid Oracle JDK",
            jvm("oracle-openjdk", "17.0.13", "2024-10-15"),
            LicenseStatus.FREE,
            "oracle-openjdk-gpl"),

        // ---- GraalVM
        Arguments.of(
            "Oracle GraalVM is free with conditions",
            jvm("oracle-graalvm", "21.0.4", "2024-07-16"),
            LicenseStatus.ORACLE_FREE_GFTC,
            "oracle-graalvm-gftc"),
        Arguments.of(
            "Oracle GraalVM for JDK 21 flips to OTN in October 2026",
            jvm("oracle-graalvm", "21.0.13", "2026-10-20"),
            LicenseStatus.ORACLE_PAID_LIKELY,
            "oracle-graalvm-21-otn-planned"),
        Arguments.of(
            "GraalVM Community Edition is open source",
            jvm("graalvm-ce", "21.0.2", "2024-01-16"),
            LicenseStatus.FREE,
            "graalvm-ce-gpl"),

        // ---- the non-Oracle distributions, including at versions where Oracle's are paid
        Arguments.of(
            "Eclipse Temurin",
            jvm("temurin", "21.0.4", "2024-07-16"),
            LicenseStatus.FREE,
            "third-party-open-source"),
        Arguments.of(
            "a real Amazon Corretto 17.0.13 read off this machine",
            jvm("corretto", "17.0.13", "2024-10-15"),
            LicenseStatus.FREE,
            "third-party-open-source"),
        Arguments.of(
            "Azul Zulu on Java 8, where Oracle's build would be OTN",
            jvm("zulu", "1.8.0_422"),
            LicenseStatus.FREE,
            "third-party-open-source"),
        Arguments.of(
            "Microsoft Build of OpenJDK",
            jvm("microsoft", "17.0.13", "2024-10-15"),
            LicenseStatus.FREE,
            "third-party-open-source"),
        Arguments.of(
            "IBM Semeru",
            jvm("semeru", "21.0.4", "2024-07-16"),
            LicenseStatus.FREE,
            "third-party-open-source"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("theRuleTable")
  void classifiesTheRuleTable(
      String description, JvmFingerprint fingerprint, LicenseStatus status, String ruleId) {
    Classification result = ENGINE.classify(fingerprint);

    assertThat(result.status()).as(description).isEqualTo(status);
    assertThat(result.ruleId()).as(description).isEqualTo(ruleId);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("theRuleTable")
  void alwaysCitesAtLeastOneSource(
      String description, JvmFingerprint fingerprint, LicenseStatus status, String ruleId) {
    Classification result = ENGINE.classify(fingerprint);

    assertThat(result.citations()).as(description).isNotEmpty();
    assertThat(result.citations())
        .as(description)
        .allSatisfy(
            citation -> {
              assertThat(citation.url()).startsWith("https://");
              assertThat(citation.title()).isNotBlank();
            });
    assertThat(result.summary()).as(description).isNotBlank();
  }

  @Test
  void tellsOracleJdkAndOracleOpenJdkApartAtTheSameVersion() {
    // Both report IMPLEMENTOR="Oracle Corporation". Only the Java(TM) discriminator separates them,
    // and getting this wrong is the difference between "free" and "this one may cost money".
    ProductCatalog catalog = ENGINE.ruleSet().products();

    Product oracleJdk =
        catalog.resolve("Oracle Corporation", null, null, Boolean.TRUE).orElseThrow();
    Product oracleOpenJdk =
        catalog.resolve("Oracle Corporation", null, null, Boolean.FALSE).orElseThrow();

    assertThat(oracleJdk.id()).isEqualTo("oracle-jdk");
    assertThat(oracleOpenJdk.id()).isEqualTo("oracle-openjdk");

    JvmFingerprint paid =
        JvmFingerprint.builder()
            .product(oracleJdk)
            .versionString("17.0.13")
            .javaVersionDate(LocalDate.parse("2024-10-15"))
            .build();
    JvmFingerprint free =
        JvmFingerprint.builder()
            .product(oracleOpenJdk)
            .versionString("17.0.13")
            .javaVersionDate(LocalDate.parse("2024-10-15"))
            .build();

    assertThat(ENGINE.classify(paid).status()).isEqualTo(LicenseStatus.ORACLE_PAID_LIKELY);
    assertThat(ENGINE.classify(free).status()).isEqualTo(LicenseStatus.FREE);
  }

  @Test
  void refusesToGuessWhenTheOracleDiscriminatorIsMissing() {
    // An Oracle vendor string with no Java(TM) evidence could be either product, so the catalogue
    // recognises neither and the engine says so.
    assertThat(ENGINE.ruleSet().products().resolve("Oracle Corporation", null, null, null))
        .isEmpty();

    Classification result =
        ENGINE.classify(
            JvmFingerprint.builder()
                .vendor("Oracle Corporation")
                .versionString("17.0.13")
                .javaVersionDate(LocalDate.parse("2024-10-15"))
                .build());

    assertThat(result.status()).isEqualTo(LicenseStatus.UNKNOWN);
    assertThat(result.flags()).contains(ClassificationFlag.PRODUCT_UNIDENTIFIED);
    assertThat(result.citations()).isNotEmpty();
  }

  @Test
  void reportsUnknownForAnUnrecognisedVendor() {
    Classification result =
        ENGINE.classify(
            JvmFingerprint.builder()
                .path(Path.of("/opt/acme-java"))
                .vendor("Acme Java Systems Ltd.")
                .versionString("21.0.4")
                .build());

    assertThat(result.status()).isEqualTo(LicenseStatus.UNKNOWN);
    assertThat(result.severity()).isEqualTo(Severity.UNKNOWN);
    assertThat(result.ruleId()).isNull();
    assertThat(result.summary()).contains("Acme Java Systems Ltd.");
    assertThat(result.citations()).isNotEmpty();
  }

  @Test
  void reportsUnknownWhenThereIsNothingToGoOn() {
    Classification result = ENGINE.classify(JvmFingerprint.builder().build());

    assertThat(result.status()).isEqualTo(LicenseStatus.UNKNOWN);
    assertThat(result.citations()).isNotEmpty();
  }

  @Test
  void flagsTheClosingNftcWindowOnJava21() {
    Classification result = ENGINE.classify(jvm("oracle-jdk", "21.0.8", "2025-07-15"));

    assertThat(result.status()).isEqualTo(LicenseStatus.ORACLE_FREE_NFTC);
    assertThat(result.flags()).contains(ClassificationFlag.NFTC_WINDOW_CLOSING);
    assertThat(result.severity())
        .as("a closing free-licence window is worth a look even though the build is free")
        .isEqualTo(Severity.REVIEW);
    assertThat(result.summary()).contains("October 2026");
  }

  @Test
  void flagsThatTheFreeJava17LineIsFrozen() {
    Classification result = ENGINE.classify(jvm("oracle-jdk", "17.0.12", "2024-07-16"));

    assertThat(result.flags()).contains(ClassificationFlag.NO_FURTHER_FREE_UPDATES);
    assertThat(result.severity()).isEqualTo(Severity.REVIEW);
  }

  @Test
  void marksInferredRulesAsUnverified() {
    Classification result = ENGINE.classify(jvm("oracle-jdk", "13.0.2", "2020-01-14"));

    assertThat(result.confidence()).isEqualTo(Confidence.UNVERIFIED);
    assertThat(result.isUnverified()).isTrue();
    assertThat(result.flags()).contains(ClassificationFlag.UNVERIFIED_RULE);
    assertThat(result.summary()).contains("confirm against the LICENSE file");
  }

  @Test
  void resolvesTheReleaseDateFromTheCatalogueWhenTheInstallationDoesNotCarryOne() {
    // A Java 8 release file has no JAVA_VERSION_DATE, so the date has to come from the catalogue.
    Classification result = ENGINE.classify(jvm("oracle-jdk", "1.8.0_202"));

    assertThat(result.releaseDate()).isEqualTo(LocalDate.parse("2019-01-15"));
    assertThat(result.releaseDateSource())
        .isEqualTo(Classification.ReleaseDateSource.RELEASE_CATALOG);
  }

  @Test
  void prefersTheInstallationsOwnReleaseDateOverTheCatalogue() {
    Classification result = ENGINE.classify(jvm("oracle-jdk", "17.0.12", "2024-07-16"));

    assertThat(result.releaseDateSource()).isEqualTo(Classification.ReleaseDateSource.RELEASE_FILE);
  }

  @Test
  void fallsBackToVersionBoundsWhenNoReleaseDateIsAvailable() {
    JvmFingerprint free =
        JvmFingerprint.builder().product(product("oracle-jdk")).versionString("17.0.12").build();
    JvmFingerprint paid =
        JvmFingerprint.builder().product(product("oracle-jdk")).versionString("17.0.13").build();

    assertThat(WITHOUT_DATES.classify(free).ruleId()).isEqualTo("oracle-jdk-17-nftc-by-version");
    assertThat(WITHOUT_DATES.classify(free).status()).isEqualTo(LicenseStatus.ORACLE_FREE_NFTC);
    assertThat(WITHOUT_DATES.classify(paid).ruleId()).isEqualTo("oracle-jdk-17-otn-by-version");
    assertThat(WITHOUT_DATES.classify(paid).status()).isEqualTo(LicenseStatus.ORACLE_PAID_LIKELY);
  }

  @Test
  void marksTheJava21VersionFallbackAsAnInference() {
    // Oracle states the JDK 21 boundary as a date, not a version number, so the version mapping is
    // ours and must not be presented as Oracle's word.
    JvmFingerprint fingerprint =
        JvmFingerprint.builder().product(product("oracle-jdk")).versionString("21.0.8").build();

    Classification result = WITHOUT_DATES.classify(fingerprint);

    assertThat(result.ruleId()).isEqualTo("oracle-jdk-21-nftc-by-version");
    assertThat(result.confidence()).isEqualTo(Confidence.UNVERIFIED);
  }

  @Test
  void reportsUnknownRatherThanGuessingAnUndatedJava25Build() {
    JvmFingerprint fingerprint =
        JvmFingerprint.builder().product(product("oracle-jdk")).versionString("25.0.4").build();

    Classification result = WITHOUT_DATES.classify(fingerprint);

    assertThat(result.status()).isEqualTo(LicenseStatus.UNKNOWN);
    assertThat(result.ruleId()).isEqualTo("oracle-jdk-25-undetermined");
    assertThat(result.flags()).contains(ClassificationFlag.DATE_UNKNOWN);
  }

  @Test
  void carriesTheProductProvenanceAlongsideTheRuleCitations() {
    Classification result = ENGINE.classify(jvm("corretto", "17.0.13", "2024-10-15"));

    assertThat(result.citations())
        .extracting(Citation::url)
        .contains("https://aws.amazon.com/corretto/faqs/");
  }

  @Test
  void classifiesAListInOrder() {
    List<Classification> results =
        ENGINE.classifyAll(
            List.of(
                jvm("temurin", "21.0.4", "2024-07-16"),
                jvm("oracle-jdk", "17.0.13", "2024-10-15"),
                jvm("oracle-jdk", "1.8.0_202")));

    assertThat(results)
        .extracting(Classification::status)
        .containsExactly(
            LicenseStatus.FREE, LicenseStatus.ORACLE_PAID_LIKELY, LicenseStatus.LEGACY_BCL);
  }

  @Test
  void neverEmitsAClassificationWithoutACitation() {
    // Belt and braces over the whole rule set, not just the rows in the table above.
    assertThat(ENGINE.ruleSet().rules())
        .allSatisfy(rule -> assertThat(rule.citations()).as(rule.id()).isNotEmpty());
  }
}
