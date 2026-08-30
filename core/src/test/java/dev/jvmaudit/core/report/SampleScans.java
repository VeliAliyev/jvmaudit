package dev.jvmaudit.core.report;

import dev.jvmaudit.core.detect.DetectedJvm;
import dev.jvmaudit.core.detect.DetectionSource;
import dev.jvmaudit.core.detect.ScanIssue;
import dev.jvmaudit.core.detect.ScanResult;
import dev.jvmaudit.core.model.Classification;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.FingerprintSource;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.model.Product;
import dev.jvmaudit.core.rules.LicenseRulesEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A fixed, fully deterministic scan result, so that the four output formats can be compared against
 * golden files byte for byte.
 *
 * <p>Everything that would otherwise vary - the timestamp, the host name, the operating system, the
 * tool version, the duration - is hard-coded. The classifications are not: they come from the real
 * rules engine, so a golden file also pins what JVMAudit actually says about each of these
 * installations, and a change to a rule summary shows up as a failing golden test rather than
 * slipping out in a release.
 */
public final class SampleScans {

  private static final LicenseRulesEngine ENGINE = LicenseRulesEngine.usingPackagedRules();

  private SampleScans() {}

  /** The rule set the sample is classified against. */
  public static LicenseRulesEngine engine() {
    return ENGINE;
  }

  /** A scan covering every severity, a bundled JVM, and an unrecognised vendor. */
  public static ScanResult deterministic() {
    List<DetectedJvm> jvms = new ArrayList<>();

    jvms.add(
        jvm(
            "/opt/java/oracle-jdk-8u211",
            product("oracle-jdk"),
            "1.8.0_211",
            null,
            Boolean.TRUE,
            Set.of(DetectionSource.WELL_KNOWN_ROOT, DetectionSource.PATH),
            null));

    jvms.add(
        jvm(
            "/opt/vendor-app/AcmeSuite/jre",
            product("oracle-jdk"),
            "1.8.0_202",
            null,
            Boolean.TRUE,
            Set.of(DetectionSource.DEEP_SCAN),
            "/opt/vendor-app/AcmeSuite"));

    jvms.add(
        jvm(
            "/opt/java/oracle-jdk-17.0.12",
            product("oracle-jdk"),
            "17.0.12",
            LocalDate.parse("2024-07-16"),
            Boolean.TRUE,
            Set.of(DetectionSource.WELL_KNOWN_ROOT),
            null));

    jvms.add(
        jvm(
            "/opt/java/mystery-jdk",
            null,
            "21.0.4",
            LocalDate.parse("2024-07-16"),
            null,
            Set.of(DetectionSource.DEEP_SCAN),
            null));

    jvms.add(
        jvm(
            "/opt/java/temurin-21",
            product("temurin"),
            "21.0.4",
            LocalDate.parse("2024-07-16"),
            Boolean.FALSE,
            Set.of(DetectionSource.WELL_KNOWN_ROOT, DetectionSource.JAVA_HOME),
            null));

    List<ScanIssue> issues =
        List.of(
            ScanIssue.warning(
                "Only 12 of 340 running processes exposed their command line to this user. Run"
                    + " JVMAudit as administrator (Windows) or root (Linux and macOS) for complete"
                    + " coverage of running JVMs.",
                null));

    return new ScanResult(
        jvms,
        issues,
        Instant.parse("2026-08-30T09:00:00Z"),
        Duration.ofMillis(1234),
        "build-agent-07",
        "Linux",
        "6.8.0",
        "amd64",
        "svc-audit",
        "1.2.3-test",
        ENGINE.ruleSet().rulesVersion(),
        true);
  }

  /** The same estate a week earlier: no Oracle JDK 17, and Temurin at an older version. */
  public static ScanResult weekEarlier() {
    List<DetectedJvm> jvms = new ArrayList<>();
    jvms.add(
        jvm(
            "/opt/java/oracle-jdk-8u211",
            product("oracle-jdk"),
            "1.8.0_211",
            null,
            Boolean.TRUE,
            Set.of(DetectionSource.WELL_KNOWN_ROOT, DetectionSource.PATH),
            null));
    jvms.add(
        jvm(
            "/opt/java/temurin-21",
            product("temurin"),
            "21.0.3",
            LocalDate.parse("2024-04-16"),
            Boolean.FALSE,
            Set.of(DetectionSource.WELL_KNOWN_ROOT, DetectionSource.JAVA_HOME),
            null));

    return new ScanResult(
        jvms,
        List.of(),
        Instant.parse("2026-08-23T09:00:00Z"),
        Duration.ofMillis(900),
        "build-agent-07",
        "Linux",
        "6.8.0",
        "amd64",
        "svc-audit",
        "1.2.3-test",
        ENGINE.ruleSet().rulesVersion(),
        true);
  }

  /** A scan that found nothing, which the report has to handle gracefully. */
  public static ScanResult empty() {
    return new ScanResult(
        List.of(),
        List.of(),
        Instant.parse("2026-08-30T09:00:00Z"),
        Duration.ofMillis(40),
        "build-agent-07",
        "Linux",
        "6.8.0",
        "amd64",
        "svc-audit",
        "1.2.3-test",
        ENGINE.ruleSet().rulesVersion(),
        false);
  }

  private static Product product(String id) {
    return ENGINE.ruleSet().products().byId(id).orElseThrow();
  }

  private static DetectedJvm jvm(
      String path,
      Product product,
      String version,
      LocalDate versionDate,
      Boolean javaTm,
      Set<DetectionSource> sources,
      String bundledInside) {

    JvmFingerprint fingerprint =
        JvmFingerprint.builder()
            .path(Path.of(path))
            .product(product)
            .vendor(product == null ? "Acme Java Systems Ltd." : product.vendor())
            .implementorVersion(product == null ? "Acme-21.0.4" : null)
            .versionString(version)
            .javaVersionDate(versionDate)
            .javaTm(javaTm)
            .source(FingerprintSource.RELEASE_FILE)
            .bundledInside(bundledInside == null ? null : Path.of(bundledInside))
            .build();

    Classification classification = ENGINE.classify(fingerprint);
    if (bundledInside != null) {
      classification = classification.withFlag(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED);
    }
    return new DetectedJvm(Path.of(path), fingerprint, classification, sources, List.of());
  }
}
