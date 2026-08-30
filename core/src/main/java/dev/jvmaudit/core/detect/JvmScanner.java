package dev.jvmaudit.core.detect;

import dev.jvmaudit.core.BuildInfo;
import dev.jvmaudit.core.model.Classification;
import dev.jvmaudit.core.model.ClassificationFlag;
import dev.jvmaudit.core.model.JvmFingerprint;
import dev.jvmaudit.core.rules.LicenseRulesEngine;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs every applicable locator, identifies what they found, classifies it, and returns one
 * inventory.
 *
 * <p>Deduplication is by canonical path, so a JDK reached through {@code JAVA_HOME}, through a
 * symlink on {@code PATH} and through its real directory is one row with three sources, not three
 * rows. That matters more than it sounds: an inventory that triple-counts is one a customer cannot
 * hand to a lawyer.
 *
 * <p>Nothing in this class, or anything it calls, touches the network.
 */
public final class JvmScanner {

  /** Sources that mean an installation sits somewhere Java is meant to be installed. */
  private static final Set<DetectionSource> CONVENTIONAL =
      EnumSet.of(
          DetectionSource.WELL_KNOWN_ROOT,
          DetectionSource.JAVA_HOME,
          DetectionSource.PATH,
          DetectionSource.WINDOWS_REGISTRY,
          DetectionSource.EXPLICIT_PATH);

  private final List<JvmLocator> locators;
  private final JvmIdentifier identifier;
  private final LicenseRulesEngine engine;

  /**
   * @param locators the strategies to find installations with
   * @param identifier turns a directory into a fingerprint
   * @param engine turns a fingerprint into a classification
   */
  public JvmScanner(
      List<JvmLocator> locators, JvmIdentifier identifier, LicenseRulesEngine engine) {
    this.locators = List.copyOf(Objects.requireNonNull(locators, "locators"));
    this.identifier = Objects.requireNonNull(identifier, "identifier");
    this.engine = Objects.requireNonNull(engine, "engine");
  }

  /** A scanner wired for the machine it is running on, using the rules packaged in the jar. */
  public static JvmScanner forCurrentMachine() {
    return forCurrentMachine(LicenseRulesEngine.usingPackagedRules());
  }

  /**
   * A scanner wired for the machine it is running on.
   *
   * @param engine the rules engine to classify with
   * @return the scanner
   */
  public static JvmScanner forCurrentMachine(LicenseRulesEngine engine) {
    return new JvmScanner(
        List.of(
            new ExplicitPathLocator(),
            WellKnownRootLocator.forCurrentMachine(),
            EnvironmentLocator.forCurrentMachine(),
            WindowsRegistryLocator.forCurrentMachine(),
            RunningProcessLocator.forCurrentMachine(),
            DeepScanLocator.forCurrentMachine()),
        JvmIdentifier.using(engine.ruleSet().products()),
        engine);
  }

  /**
   * Scans this machine.
   *
   * @param options what to look at and what JVMAudit is allowed to do
   * @return the inventory
   */
  public ScanResult scan(ScanOptions options) {
    Objects.requireNonNull(options, "options");
    Instant startedAt = Instant.now();
    List<ScanIssue> issues = new ArrayList<>();

    Map<Path, Discovery> discoveries = new LinkedHashMap<>();
    for (JvmLocator locator : locators) {
      if (!locator.isApplicable(options)) {
        continue;
      }
      List<JvmCandidate> candidates;
      try {
        candidates = locator.locate(options, issues::add);
      } catch (RuntimeException e) {
        issues.add(
            ScanIssue.error(
                "The "
                    + locator.name()
                    + " locator failed: "
                    + e
                    + ". The inventory below is"
                    + " incomplete.",
                null));
        continue;
      }
      for (JvmCandidate candidate : candidates) {
        Path canonical = JvmHomes.canonical(candidate.home());
        discoveries.computeIfAbsent(canonical, Discovery::new).add(candidate, canonical);
      }
    }

    List<DetectedJvm> jvms = new ArrayList<>(discoveries.size());
    for (Discovery discovery : discoveries.values()) {
      jvms.add(describe(discovery, options, issues));
    }

    jvms.sort(
        Comparator.comparing((DetectedJvm jvm) -> severityOrder(jvm))
            .thenComparing(jvm -> jvm.path().toString()));

    return new ScanResult(
        jvms,
        issues,
        startedAt,
        Duration.between(startedAt, Instant.now()),
        hostName(),
        System.getProperty("os.name", "unknown"),
        System.getProperty("os.version", "unknown"),
        System.getProperty("os.arch", "unknown"),
        System.getProperty("user.name", "unknown"),
        BuildInfo.version(),
        engine.ruleSet().rulesVersion(),
        options.deep());
  }

  private DetectedJvm describe(Discovery discovery, ScanOptions options, List<ScanIssue> issues) {
    JvmFingerprint fingerprint = identifier.identify(discovery.path, options, issues::add);

    Path bundledInside = bundledInside(discovery, fingerprint);
    if (bundledInside != null) {
      fingerprint = fingerprint.withBundledInside(bundledInside);
    }

    Classification classification = engine.classify(fingerprint);
    if (bundledInside != null) {
      classification = classification.withFlag(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED);
    }

    return new DetectedJvm(
        discovery.path, fingerprint, classification, discovery.sources, discovery.aliases);
  }

  /**
   * The application directory a JVM appears to be bundled inside, or null.
   *
   * <p>The test is where it was found, not what it contains: a JVM that no conventional locator
   * turned up - not a standard install root, not JAVA_HOME, not PATH, not the registry - but which
   * the deep sweep or a running process found, is sitting inside somebody else's product. That
   * vendor's own Oracle agreement may already cover it, which is why the flag says "verify with the
   * application vendor" and never "you owe money".
   */
  private static Path bundledInside(Discovery discovery, JvmFingerprint fingerprint) {
    for (DetectionSource source : discovery.sources) {
      if (CONVENTIONAL.contains(source)) {
        return null;
      }
    }
    Path home = fingerprint.path() == null ? discovery.path : fingerprint.path();
    Path parent = home.getParent();
    if (parent == null) {
      return null;
    }
    // macOS bundle: <app>/Foo.jdk/Contents/Home -> report <app>
    if (endsWith(home, "Contents", "Home")) {
      Path bundle = parent.getParent();
      Path outer = bundle == null ? null : bundle.getParent();
      return outer == null ? parent : outer;
    }
    return parent;
  }

  private static boolean endsWith(Path path, String parentName, String leafName) {
    Path leaf = path.getFileName();
    Path parent = path.getParent();
    Path parentLeaf = parent == null ? null : parent.getFileName();
    return leaf != null
        && parentLeaf != null
        && leafName.equals(leaf.toString())
        && parentName.equals(parentLeaf.toString());
  }

  private static int severityOrder(DetectedJvm jvm) {
    return switch (jvm.severity()) {
      case ACTION -> 0;
      case REVIEW -> 1;
      case UNKNOWN -> 2;
      case OK -> 3;
    };
  }

  private static String hostName() {
    String fromEnv = System.getenv("COMPUTERNAME");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    fromEnv = System.getenv("HOSTNAME");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    try {
      // Reads the local host's own name. This resolves against the local resolver configuration and
      // is the only name lookup JVMAudit performs; it sends nothing anywhere.
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException | RuntimeException e) {
      return "unknown";
    }
  }

  /** One installation and everything that found it, accumulated during the scan. */
  private static final class Discovery {
    private final Path path;
    private final Set<DetectionSource> sources = EnumSet.noneOf(DetectionSource.class);
    private final List<Path> aliases = new ArrayList<>();

    Discovery(Path path) {
      this.path = path;
    }

    void add(JvmCandidate candidate, Path canonical) {
      sources.add(candidate.source());
      Path reported = candidate.home();
      if (!reported.equals(canonical) && !aliases.contains(reported)) {
        aliases.add(reported);
      }
    }
  }
}
