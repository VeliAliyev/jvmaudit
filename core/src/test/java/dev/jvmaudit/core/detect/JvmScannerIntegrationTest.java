package dev.jvmaudit.core.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.jvmaudit.core.detect.JvmFixtures.Fixture;
import dev.jvmaudit.core.model.Classification;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.LicenseStatus;
import dev.jvmaudit.core.model.Product;
import dev.jvmaudit.core.rules.LicenseRulesEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The M2 gate, end to end: plant a known estate on disk, scan it, and check that every installation
 * is found exactly once and classified correctly.
 *
 * <p>Nothing here depends on what is installed on the machine or the CI runner. The one test that
 * does look at the real machine only asserts that scanning it does not blow up.
 */
class JvmScannerIntegrationTest {

  private static final LicenseRulesEngine ENGINE = LicenseRulesEngine.usingPackagedRules();

  @TempDir Path estate;

  private Map<Path, Fixture> planted;

  @BeforeEach
  void plantTheEstate() {
    planted = new HashMap<>();
    for (Fixture fixture : JvmFixtures.catalogue()) {
      planted.put(JvmFixtures.plant(estate, fixture), fixture);
    }
  }

  /** A scanner that only looks where the test planted things, and that can "run" the launchers. */
  private JvmScanner scanner() {
    return new JvmScanner(
        List.of(new ExplicitPathLocator()),
        new JvmIdentifier(ENGINE.ruleSet().products(), JvmFixtures.versionRunner(planted)),
        ENGINE);
  }

  private ScanOptions options() {
    return ScanOptions.builder()
        .paths(List.of(estate))
        .includeWellKnownRoots(false)
        .includeEnvironment(false)
        .includeRegistry(false)
        .includeRunningProcesses(false)
        .execTimeout(JvmFixtures.execTimeout())
        .build();
  }

  @Test
  void findsEveryPlantedInstallation() {
    ScanResult result = scanner().scan(options());

    assertThat(result.total())
        .as("every planted JVM must be found; the catalogue has %d", planted.size())
        .isEqualTo(planted.size());
    assertThat(result.jvms())
        .extracting(DetectedJvm::path)
        .containsExactlyInAnyOrderElementsOf(
            planted.keySet().stream().map(JvmHomes::canonical).toList());
  }

  @Test
  void classifiesEveryPlantedInstallationAsExpected() {
    ScanResult result = scanner().scan(options());
    Map<Path, DetectedJvm> byPath =
        result.jvms().stream().collect(Collectors.toMap(DetectedJvm::path, jvm -> jvm));

    planted.forEach(
        (home, fixture) -> {
          DetectedJvm found = byPath.get(JvmHomes.canonical(home));
          assertThat(found).as("%s was not found", fixture.id()).isNotNull();
          assertThat(found.classification().status())
              .as("%s classified wrongly", fixture.id())
              .isEqualTo(fixture.expectedStatus());
          String productId =
              found.fingerprint().product() == null ? null : found.fingerprint().product().id();
          assertThat(productId)
              .as("%s product", fixture.id())
              .isEqualTo(fixture.expectedProductId());
        });
  }

  @Test
  void everyClassificationInAScanCarriesACitation() {
    ScanResult result = scanner().scan(options());

    assertThat(result.jvms())
        .isNotEmpty()
        .allSatisfy(
            jvm -> assertThat(jvm.classification().citations()).as("%s", jvm.path()).isNotEmpty());
  }

  @Test
  void everyUnknownSaysHowToResolveIt() {
    // Directive from M2: an UNKNOWN the reader cannot act on is a dead end.
    ScanResult result = scanner().scan(options());

    assertThat(result.jvms())
        .filteredOn(jvm -> jvm.classification().status() == LicenseStatus.UNKNOWN)
        .isNotEmpty()
        .allSatisfy(
            jvm -> {
              assertThat(jvm.classification().remediation()).as("%s", jvm.path()).isNotBlank();
              assertThat(jvm.classification().summary()).as("%s", jvm.path()).isNotBlank();
            });
  }

  @Test
  void withoutExecTheOracleBuildsBecomeUnknownRatherThanAGuess() {
    // The rule that must not be weakened: Oracle JDK and Oracle OpenJDK are indistinguishable from
    // the release file, so with execution forbidden JVMAudit reports UNKNOWN for both.
    ScanResult result =
        scanner()
            .scan(
                ScanOptions.builder()
                    .paths(List.of(estate))
                    .includeWellKnownRoots(false)
                    .includeEnvironment(false)
                    .includeRegistry(false)
                    .includeRunningProcesses(false)
                    .execPolicy(ScanOptions.ExecPolicy.NEVER)
                    .build());

    Map<Path, DetectedJvm> byPath =
        result.jvms().stream().collect(Collectors.toMap(DetectedJvm::path, jvm -> jvm));

    planted.forEach(
        (home, fixture) -> {
          DetectedJvm found = byPath.get(JvmHomes.canonical(home));
          assertThat(found.classification().status())
              .as("%s without exec", fixture.id())
              .isEqualTo(fixture.statusWithoutExec());
        });

    DetectedJvm oracle = byPath.get(JvmHomes.canonical(estate.resolve("oracle-jdk-17-otn")));
    assertThat(oracle.classification().status()).isEqualTo(LicenseStatus.UNKNOWN);
    assertThat(oracle.classification().remediation())
        .contains("Java(TM)")
        .contains("OpenJDK")
        .contains("LICENSE");
  }

  @Test
  void identifiesAnInstallationWithNoReleaseFileByRunningIt() {
    Path bare = JvmFixtures.plantWithoutReleaseFile(estate.resolve("no-release-file"));
    Fixture temurin =
        JvmFixtures.catalogue().stream()
            .filter(f -> f.id().equals("temurin-21"))
            .findFirst()
            .orElseThrow();
    planted.put(bare, temurin);

    ScanResult result = scanner().scan(options());
    DetectedJvm found =
        result.jvms().stream()
            .filter(jvm -> jvm.path().equals(JvmHomes.canonical(bare)))
            .findFirst()
            .orElseThrow();

    assertThat(found.fingerprint().source())
        .isEqualTo(dev.jvmaudit.core.model.FingerprintSource.EXEC);
    assertThat(found.fingerprint().versionString()).isEqualTo("21.0.4");
    assertThat(found.classification().status()).isEqualTo(LicenseStatus.FREE);
  }

  @Test
  void countsOneInstallationOnceEvenWhenTwoLocatorsFindIt() {
    JvmScanner twoWays =
        new JvmScanner(
            List.of(
                new ExplicitPathLocator(),
                new EnvironmentLocator(
                    Map.of("JAVA_HOME", estate.resolve("temurin-21").toString()),
                    java.io.File.pathSeparator,
                    OsFamily.current())),
            new JvmIdentifier(ENGINE.ruleSet().products(), JvmFixtures.versionRunner(planted)),
            ENGINE);

    ScanResult result =
        twoWays.scan(
            ScanOptions.builder()
                .paths(List.of(estate))
                .includeWellKnownRoots(false)
                .includeEnvironment(true)
                .includeRegistry(false)
                .includeRunningProcesses(false)
                .execTimeout(JvmFixtures.execTimeout())
                .build());

    assertThat(result.total()).isEqualTo(planted.size());
    DetectedJvm temurin =
        result.jvms().stream()
            .filter(jvm -> jvm.path().equals(JvmHomes.canonical(estate.resolve("temurin-21"))))
            .findFirst()
            .orElseThrow();
    assertThat(temurin.sources())
        .containsExactlyInAnyOrder(DetectionSource.EXPLICIT_PATH, DetectionSource.JAVA_HOME);
  }

  @Test
  void deduplicatesAJvmReachedThroughASymbolicLink() throws IOException {
    Path target = estate.resolve("temurin-21");
    Path link = estate.resolve("current-java");
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      // Windows needs Developer Mode or elevation to create symlinks; nothing to prove here then.
      assumeThat(false)
          .as("this machine cannot create symbolic links: %s", e.getMessage())
          .isTrue();
      return;
    }

    ScanResult result = scanner().scan(options());

    assertThat(result.total())
        .as("the symlink and its target are one installation, not two")
        .isEqualTo(planted.size());
    DetectedJvm temurin =
        result.jvms().stream()
            .filter(jvm -> jvm.path().equals(JvmHomes.canonical(target)))
            .findFirst()
            .orElseThrow();
    assertThat(temurin.aliases()).isNotEmpty();
  }

  @Test
  void ordersTheMostUrgentFindingsFirst() {
    ScanResult result = scanner().scan(options());

    List<dev.jvmaudit.core.model.Severity> severities =
        result.jvms().stream().map(DetectedJvm::severity).toList();

    assertThat(severities).isSortedAccordingTo((a, b) -> Integer.compare(order(a), order(b)));
    assertThat(result.jvms().get(0).severity()).isEqualTo(dev.jvmaudit.core.model.Severity.ACTION);
  }

  private static int order(dev.jvmaudit.core.model.Severity severity) {
    return switch (severity) {
      case ACTION -> 0;
      case REVIEW -> 1;
      case UNKNOWN -> 2;
      case OK -> 3;
    };
  }

  @Test
  void summarisesTheEstate() {
    ScanResult result = scanner().scan(options());

    assertThat(result.summaryLine())
        .startsWith(planted.size() + " JVMs found:")
        .contains("free")
        .contains("Oracle-paid-likely");
    assertThat(result.countsBySeverity().values().stream().mapToInt(Integer::intValue).sum())
        .isEqualTo(planted.size());
    assertThat(result.hasOraclePaidLikely()).isTrue();
    assertThat(result.hasAnyOracleLicensed()).isTrue();
    assertThat(result.rulesVersion()).isNotBlank();
    assertThat(result.toolVersion()).isNotBlank();
    assertThat(result.host()).isNotBlank();
  }

  @Test
  void flagsAJvmBundledInsideAnotherApplication() {
    // The whole point of --deep: a JRE nobody installed on purpose, sitting inside a product.
    Fixture oracle =
        JvmFixtures.catalogue().stream()
            .filter(f -> f.id().equals("oracle-jdk-8u211"))
            .findFirst()
            .orElseThrow();
    Path appDirectory = estate.resolve("vendor-app").resolve("SomeProduct");
    Path bundled = JvmFixtures.plant(appDirectory, oracle, "jre");
    planted.put(bundled, oracle);

    JvmScanner deepOnly =
        new JvmScanner(
            List.of(new DeepScanLocator(List.of(estate))),
            new JvmIdentifier(ENGINE.ruleSet().products(), JvmFixtures.versionRunner(planted)),
            ENGINE);

    ScanResult result =
        deepOnly.scan(
            ScanOptions.builder()
                .deep(true)
                .paths(List.of(estate))
                .includeWellKnownRoots(false)
                .includeEnvironment(false)
                .includeRegistry(false)
                .includeRunningProcesses(false)
                .execTimeout(JvmFixtures.execTimeout())
                .build());

    DetectedJvm found =
        result.jvms().stream()
            .filter(jvm -> jvm.path().equals(JvmHomes.canonical(bundled)))
            .findFirst()
            .orElseThrow();

    assertThat(found.fingerprint().isBundled()).isTrue();
    assertThat(found.fingerprint().bundledInside()).isEqualTo(appDirectory);
    assertThat(found.classification().flags()).contains(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED);
    assertThat(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED.description())
        .as("the wording must never assert that money is owed")
        .contains("verify with that application's vendor")
        .doesNotContainIgnoringCase("you owe");
    assertThat(found.classification().status())
        .as("bundling explains a finding, it does not change the licence")
        .isEqualTo(LicenseStatus.ORACLE_PAID_LIKELY);
  }

  @Test
  void deepScanRespectsExclusions() {
    Path hidden = estate.resolve("skip-me");
    Fixture temurin =
        JvmFixtures.catalogue().stream()
            .filter(f -> f.id().equals("temurin-21"))
            .findFirst()
            .orElseThrow();
    JvmFixtures.plant(hidden, temurin, "hidden-jdk");

    ScanResult withExclusion =
        new JvmScanner(
                List.of(new DeepScanLocator(List.of(estate))),
                new JvmIdentifier(ENGINE.ruleSet().products(), JvmFixtures.versionRunner(planted)),
                ENGINE)
            .scan(
                ScanOptions.builder()
                    .deep(true)
                    .paths(List.of(estate))
                    .excludeGlobs(List.of("skip-me"))
                    .includeWellKnownRoots(false)
                    .includeEnvironment(false)
                    .includeRegistry(false)
                    .includeRunningProcesses(false)
                    .execTimeout(JvmFixtures.execTimeout())
                    .build());

    assertThat(withExclusion.jvms())
        .extracting(DetectedJvm::path)
        .doesNotContain(JvmHomes.canonical(hidden.resolve("hidden-jdk")));
    assertThat(withExclusion.total()).isEqualTo(planted.size());
  }

  @Test
  void deepScanStopsAtItsTimeoutAndSaysSo() {
    ScanResult result =
        new JvmScanner(
                List.of(new DeepScanLocator(List.of(estate))),
                new JvmIdentifier(ENGINE.ruleSet().products(), JvmFixtures.versionRunner(planted)),
                ENGINE)
            .scan(
                ScanOptions.builder()
                    .deep(true)
                    .paths(List.of(estate))
                    .timeout(Duration.ZERO)
                    .includeWellKnownRoots(false)
                    .includeEnvironment(false)
                    .includeRegistry(false)
                    .includeRunningProcesses(false)
                    .build());

    assertThat(result.issues())
        .as("a scan cut short must say so rather than look complete")
        .anySatisfy(issue -> assertThat(issue.message()).contains("time limit"));
  }

  @Test
  void deepScanRespectsMaxDepth() {
    Fixture temurin =
        JvmFixtures.catalogue().stream()
            .filter(f -> f.id().equals("temurin-21"))
            .findFirst()
            .orElseThrow();
    Path deep = estate.resolve("a").resolve("b").resolve("c").resolve("d");
    JvmFixtures.plant(deep, temurin, "buried-jdk");

    ScanResult shallow =
        new JvmScanner(
                List.of(new DeepScanLocator(List.of(estate))),
                new JvmIdentifier(ENGINE.ruleSet().products(), JvmFixtures.versionRunner(planted)),
                ENGINE)
            .scan(
                ScanOptions.builder()
                    .deep(true)
                    .paths(List.of(estate))
                    .maxDepth(2)
                    .includeWellKnownRoots(false)
                    .includeEnvironment(false)
                    .includeRegistry(false)
                    .includeRunningProcesses(false)
                    .execTimeout(JvmFixtures.execTimeout())
                    .build());

    assertThat(shallow.jvms())
        .extracting(DetectedJvm::path)
        .doesNotContain(JvmHomes.canonical(deep.resolve("buried-jdk")));
  }

  @Test
  void scanningTheRealMachineDoesNotCrash() {
    // The one test that touches the host. It asserts nothing about what is installed, because that
    // differs on every developer machine and every CI runner.
    ScanResult result =
        JvmScanner.forCurrentMachine(ENGINE)
            .scan(
                ScanOptions.builder()
                    .deep(false)
                    .includeRunningProcesses(true)
                    .timeout(Duration.ofSeconds(60))
                    .build());

    assertThat(result.jvms()).allSatisfy(jvm -> assertThat(jvm.path()).isNotNull());
    assertThat(result.summaryLine()).isNotBlank();
    assertThat(result.duration().isNegative()).isFalse();
    assertThat(result.jvms())
        .allSatisfy(
            jvm -> {
              Classification classification = jvm.classification();
              assertThat(classification.citations()).isNotEmpty();
              if (classification.status() == LicenseStatus.UNKNOWN) {
                assertThat(classification.remediation()).isNotBlank();
              }
            });
  }

  @Test
  void findsTheJvmRunningThisTest() {
    // A real installation, on whatever machine this happens to be, identified for real.
    Path javaHome = Path.of(System.getProperty("java.home"));

    ScanResult result =
        new JvmScanner(
                List.of(new ExplicitPathLocator()),
                JvmIdentifier.using(ENGINE.ruleSet().products()),
                ENGINE)
            .scan(
                ScanOptions.builder()
                    .paths(List.of(javaHome))
                    .includeWellKnownRoots(false)
                    .includeEnvironment(false)
                    .includeRegistry(false)
                    .includeRunningProcesses(false)
                    .build());

    assertThat(result.jvms()).hasSize(1);
    DetectedJvm self = result.jvms().get(0);
    assertThat(self.fingerprint().versionString()).isNotBlank();
    assertThat(self.fingerprint().vendor()).isNotBlank();
    assertThat(self.fingerprint().product())
        .as("the JDK running these tests should be a recognised distribution")
        .isNotNull();
    assertThat(self.fingerprint().product()).extracting(Product::id).isNotNull();
  }
}
