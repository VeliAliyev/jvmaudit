package dev.jvmaudit.core.detect;

import dev.jvmaudit.core.model.LicenseStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plants fake JVM installations on disk.
 *
 * <p>Real temporary directories, real {@code release} files, real {@code bin/java} - nothing about
 * the filesystem is mocked. What tests here must never do is depend on what happens to be installed
 * on the machine or the CI runner: the estate under test is the one this class builds.
 *
 * <p>The catalogue below deliberately spans Java 8 and 11 as well as the modern releases. JVMAudit
 * itself needs Java 17 to run, but the installations it has to find and classify are dominated by
 * Java 8 and 11, and those are also where the commercially important licence boundaries sit.
 */
public final class JvmFixtures {

  private JvmFixtures() {}

  /**
   * One planted installation and what JVMAudit is expected to make of it.
   *
   * @param id short name, also used as the directory name
   * @param implementor the {@code IMPLEMENTOR} field, or null to omit it
   * @param implementorVersion the {@code IMPLEMENTOR_VERSION} field, or null to omit it
   * @param javaVersion the {@code JAVA_VERSION} field
   * @param javaVersionDate the {@code JAVA_VERSION_DATE} field, or null (Java 8 has none)
   * @param runtimeVersion the {@code JAVA_RUNTIME_VERSION} field, or null
   * @param sourceField the {@code SOURCE} field, or null
   * @param versionOutput what {@code java -version} would print
   * @param expectedProductId the product the catalogue should recognise, or null for none
   * @param expectedStatus the licence status expected once the product is known
   * @param statusWithoutExec the licence status expected when JVMAudit may not run anything
   */
  public record Fixture(
      String id,
      String implementor,
      String implementorVersion,
      String javaVersion,
      String javaVersionDate,
      String runtimeVersion,
      String sourceField,
      String versionOutput,
      String expectedProductId,
      LicenseStatus expectedStatus,
      LicenseStatus statusWithoutExec) {}

  /**
   * The fixture catalogue: every vendor JVMAudit claims to recognise, at versions that span the
   * licence boundaries that matter.
   */
  public static List<Fixture> catalogue() {
    List<Fixture> all = new ArrayList<>();

    // ---- Oracle JDK, across all four licence regimes. The release file cannot identify these,
    // so without exec they are UNKNOWN and with exec they classify.
    all.add(
        oracleJdk(
            "oracle-jdk-8u202", "1.8.0_202", null, "1.8.0_202-b08", LicenseStatus.LEGACY_BCL));
    all.add(
        oracleJdk(
            "oracle-jdk-8u211",
            "1.8.0_211",
            null,
            "1.8.0_211-b12",
            LicenseStatus.ORACLE_PAID_LIKELY));
    all.add(
        oracleJdk(
            "oracle-jdk-11",
            "11.0.22",
            "2024-01-16",
            "11.0.22+9-LTS-219",
            LicenseStatus.ORACLE_PAID_LIKELY));
    all.add(
        oracleJdk(
            "oracle-jdk-17-nftc",
            "17.0.12",
            "2024-07-16",
            "17.0.12+8-LTS-286",
            LicenseStatus.ORACLE_FREE_NFTC));
    all.add(
        oracleJdk(
            "oracle-jdk-17-otn",
            "17.0.13",
            "2024-10-15",
            "17.0.13+10-LTS-58",
            LicenseStatus.ORACLE_PAID_LIKELY));
    all.add(
        oracleJdk(
            "oracle-jdk-21-nftc",
            "21.0.8",
            "2025-07-15",
            "21.0.8+12-LTS-250",
            LicenseStatus.ORACLE_FREE_NFTC));
    all.add(
        oracleJdk(
            "oracle-jdk-25-nftc",
            "25.0.4",
            "2026-07-21",
            "25.0.4+8-LTS-30",
            LicenseStatus.ORACLE_FREE_NFTC));

    // ---- Oracle's own OpenJDK builds: the same vendor string, a free licence.
    all.add(
        new Fixture(
            "oracle-openjdk-21",
            "Oracle Corporation",
            null,
            "21.0.2",
            "2024-01-16",
            "21.0.2+13-58",
            ".:git:289f7a7ab6f5",
            """
            openjdk version "21.0.2" 2024-01-16
            OpenJDK Runtime Environment (build 21.0.2+13-58)
            OpenJDK 64-Bit Server VM (build 21.0.2+13-58, mixed mode, sharing)
            """,
            "oracle-openjdk",
            LicenseStatus.FREE,
            LicenseStatus.UNKNOWN));
    all.add(
        new Fixture(
            "oracle-openjdk-17",
            "Oracle Corporation",
            null,
            "17.0.13",
            "2024-10-15",
            "17.0.13+10-58",
            ".:git:aaaabbbbcccc",
            """
            openjdk version "17.0.13" 2024-10-15
            OpenJDK Runtime Environment (build 17.0.13+10-58)
            OpenJDK 64-Bit Server VM (build 17.0.13+10-58, mixed mode, sharing)
            """,
            "oracle-openjdk",
            LicenseStatus.FREE,
            LicenseStatus.UNKNOWN));

    // ---- The non-Oracle distributions. These identify themselves from the release file alone,
    // so exec changes nothing: free either way.
    all.add(
        free(
            "temurin-21",
            "Eclipse Adoptium",
            "Temurin-21.0.4+7",
            "21.0.4",
            "2024-07-16",
            "21.0.4+7-LTS",
            "temurin"));
    all.add(
        free(
            "temurin-11",
            "Eclipse Adoptium",
            "Temurin-11.0.24+8",
            "11.0.24",
            "2024-07-16",
            "11.0.24+8-LTS",
            "temurin"));
    all.add(
        free(
            "temurin-8",
            "Eclipse Adoptium",
            "Temurin-8.0.422+5",
            "1.8.0_422",
            null,
            "1.8.0_422-b05",
            "temurin"));
    all.add(
        free(
            "adoptopenjdk-8",
            "AdoptOpenJDK",
            "AdoptOpenJDK",
            "1.8.0_292",
            null,
            "1.8.0_292-b10",
            "adoptopenjdk"));
    all.add(
        free(
            "corretto-17",
            "Amazon.com Inc.",
            "Corretto-17.0.13.11.1",
            "17.0.13",
            "2024-10-15",
            "17.0.13+11-LTS",
            "corretto"));
    all.add(
        free(
            "corretto-8",
            "Amazon.com Inc.",
            "Corretto-8.422.05.1",
            "1.8.0_422",
            null,
            "1.8.0_422-b05",
            "corretto"));
    all.add(
        free(
            "zulu-11",
            "Azul Systems, Inc.",
            "Zulu11.74+15-CA",
            "11.0.24",
            "2024-07-16",
            "11.0.24+8-LTS",
            "zulu"));
    all.add(
        free(
            "zulu-8",
            "Azul Systems, Inc.",
            "Zulu8.78.0.19-CA",
            "1.8.0_412",
            null,
            "1.8.0_412-b08",
            "zulu"));
    all.add(
        free(
            "microsoft-21",
            "Microsoft",
            "Microsoft-9889599",
            "21.0.4",
            "2024-07-16",
            "21.0.4+7-LTS",
            "microsoft"));
    all.add(
        free(
            "redhat-11",
            "Red Hat, Inc.",
            "11.0.24.0.8-1",
            "11.0.24",
            "2024-07-16",
            "11.0.24+8-LTS",
            "redhat"));
    all.add(
        free(
            "liberica-17",
            "BellSoft",
            "17.0.13+12-LTS",
            "17.0.13",
            "2024-10-15",
            "17.0.13+12-LTS",
            "liberica"));
    all.add(
        free(
            "sapmachine-21",
            "SAP SE",
            "SapMachine",
            "21.0.4",
            "2024-07-16",
            "21.0.4+7-LTS",
            "sapmachine"));
    all.add(
        free(
            "semeru-11",
            "IBM Corporation",
            "11.0.24.0",
            "11.0.24",
            "2024-07-16",
            "11.0.24+8-LTS",
            "semeru"));
    all.add(
        free(
            "dragonwell-8",
            "Alibaba",
            "Alibaba Dragonwell 8.18.20",
            "1.8.0_412",
            null,
            "1.8.0_412-b08",
            "dragonwell"));
    all.add(
        free(
            "kona-17",
            "Tencent",
            "TencentKonaJDK17.0.13",
            "17.0.13",
            "2024-10-15",
            "17.0.13+11",
            "kona"));

    // ---- GraalVM, both editions.
    all.add(
        new Fixture(
            "oracle-graalvm-21",
            "Oracle Corporation",
            "Oracle GraalVM 21.0.4+8.1",
            "21.0.4",
            "2024-07-16",
            "21.0.4+8-LTS-jvmci-23.1-b41",
            null,
            """
            java version "21.0.4" 2024-07-16 LTS
            Java(TM) SE Runtime Environment Oracle GraalVM 21.0.4+8.1 (build 21.0.4+8-LTS-jvmci-23.1-b41)
            Java HotSpot(TM) 64-Bit Server VM Oracle GraalVM 21.0.4+8.1 (build 21.0.4+8-LTS-jvmci-23.1-b41, mixed mode, sharing)
            """,
            "oracle-graalvm",
            LicenseStatus.ORACLE_FREE_GFTC,
            LicenseStatus.ORACLE_FREE_GFTC));
    all.add(
        new Fixture(
            "graalvm-ce-21",
            "GraalVM Community",
            "GraalVM CE 21.0.2+13.1",
            "21.0.2",
            "2024-01-16",
            "21.0.2+13-jvmci-23.1-b30",
            null,
            """
            openjdk version "21.0.2" 2024-01-16
            OpenJDK Runtime Environment GraalVM CE 21.0.2+13.1 (build 21.0.2+13-jvmci-23.1-b30)
            OpenJDK 64-Bit Server VM GraalVM CE 21.0.2+13.1 (build 21.0.2+13-jvmci-23.1-b30, mixed mode, sharing)
            """,
            "graalvm-ce",
            LicenseStatus.FREE,
            LicenseStatus.FREE));

    // ---- A vendor nobody has heard of: must stay UNKNOWN in both directions.
    all.add(
        new Fixture(
            "unknown-vendor",
            "Acme Java Systems Ltd.",
            "Acme-21.0.4",
            "21.0.4",
            "2024-07-16",
            "21.0.4+7",
            null,
            """
            openjdk version "21.0.4" 2024-07-16
            OpenJDK Runtime Environment Acme-21.0.4 (build 21.0.4+7)
            """,
            null,
            LicenseStatus.UNKNOWN,
            LicenseStatus.UNKNOWN));

    return List.copyOf(all);
  }

  private static Fixture oracleJdk(
      String id, String version, String versionDate, String runtimeVersion, LicenseStatus status) {
    String header =
        versionDate == null
            ? "java version \"" + version + "\""
            : "java version \"" + version + "\" " + versionDate + " LTS";
    String output =
        header
            + System.lineSeparator()
            + "Java(TM) SE Runtime Environment (build "
            + runtimeVersion
            + ")"
            + System.lineSeparator()
            + "Java HotSpot(TM) 64-Bit Server VM (build "
            + runtimeVersion
            + ", mixed mode, sharing)"
            + System.lineSeparator();
    return new Fixture(
        id,
        "Oracle Corporation",
        null,
        version,
        versionDate,
        runtimeVersion,
        ".:git:0531bcd287a8 open:git:38d1cef19db8",
        output,
        "oracle-jdk",
        status,
        LicenseStatus.UNKNOWN);
  }

  private static Fixture free(
      String id,
      String implementor,
      String implementorVersion,
      String version,
      String versionDate,
      String runtimeVersion,
      String productId) {
    String header =
        versionDate == null
            ? "openjdk version \"" + version + "\""
            : "openjdk version \"" + version + "\" " + versionDate;
    String output =
        header
            + System.lineSeparator()
            + "OpenJDK Runtime Environment "
            + implementorVersion
            + " (build "
            + runtimeVersion
            + ")"
            + System.lineSeparator();
    return new Fixture(
        id,
        implementor,
        implementorVersion,
        version,
        versionDate,
        runtimeVersion,
        null,
        output,
        productId,
        LicenseStatus.FREE,
        LicenseStatus.FREE);
  }

  /**
   * Plants one fixture as a directory tree.
   *
   * @param parent the directory to create the installation in
   * @param fixture what to plant
   * @return the planted JVM home
   */
  public static Path plant(Path parent, Fixture fixture) {
    return plant(parent, fixture, fixture.id());
  }

  /**
   * Plants one fixture under a chosen directory name.
   *
   * @param parent the directory to create the installation in
   * @param fixture what to plant
   * @param directoryName the name to give the JVM home
   * @return the planted JVM home
   */
  public static Path plant(Path parent, Fixture fixture, String directoryName) {
    Map<String, String> entries = new LinkedHashMap<>();
    putIfPresent(entries, "IMPLEMENTOR", fixture.implementor());
    putIfPresent(entries, "IMPLEMENTOR_VERSION", fixture.implementorVersion());
    putIfPresent(entries, "JAVA_RUNTIME_VERSION", fixture.runtimeVersion());
    putIfPresent(entries, "JAVA_VERSION", fixture.javaVersion());
    putIfPresent(entries, "JAVA_VERSION_DATE", fixture.javaVersionDate());
    entries.put("OS_ARCH", "x86_64");
    entries.put("OS_NAME", "Linux");
    putIfPresent(entries, "SOURCE", fixture.sourceField());
    return plantJvm(parent.resolve(directoryName), entries);
  }

  /**
   * Plants a JVM home with an arbitrary release file.
   *
   * @param home where to create it
   * @param releaseEntries the release file contents
   * @return the home
   */
  public static Path plantJvm(Path home, Map<String, String> releaseEntries) {
    try {
      Files.createDirectories(home.resolve("bin"));
      Files.createDirectories(home.resolve("lib"));
      StringBuilder release = new StringBuilder();
      releaseEntries.forEach(
          (key, value) -> release.append(key).append("=\"").append(value).append("\"\n"));
      Files.writeString(
          home.resolve(JvmHomes.RELEASE_FILE), release.toString(), StandardCharsets.UTF_8);
      writeLauncher(home.resolve("bin").resolve("java"));
      writeLauncher(home.resolve("bin").resolve("java.exe"));
      writeLauncher(home.resolve("bin").resolve("javac"));
      return home;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Plants a JVM home with no release file at all, which forces identification by execution.
   *
   * @param home where to create it
   * @return the home
   */
  public static Path plantWithoutReleaseFile(Path home) {
    try {
      Files.createDirectories(home.resolve("bin"));
      writeLauncher(home.resolve("bin").resolve("java"));
      writeLauncher(home.resolve("bin").resolve("java.exe"));
      return home;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeLauncher(Path launcher) throws IOException {
    Files.writeString(launcher, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
    launcher.toFile().setExecutable(true);
  }

  private static void putIfPresent(Map<String, String> entries, String key, String value) {
    if (value != null) {
      entries.put(key, value);
    }
  }

  /**
   * A {@link ProcessRunner} that answers {@code java -version} from the fixture catalogue, keyed by
   * the launcher path it is asked to run. Anything it does not recognise fails the way a file that
   * is not really an executable fails.
   *
   * @param homes planted homes, by the fixture that produced them
   * @return a runner that stands in for really executing the launchers
   */
  public static ProcessRunner versionRunner(Map<Path, Fixture> homes) {
    return (command, timeout) -> {
      if (command.size() < 2 || !"-version".equals(command.get(1))) {
        return new ProcessRunner.Result(-1, "", "", false, "unexpected command " + command);
      }
      Path launcher = Path.of(command.get(0));
      Path home = launcher.getParent() == null ? null : launcher.getParent().getParent();
      Fixture fixture = home == null ? null : homes.get(home);
      if (fixture == null) {
        return new ProcessRunner.Result(-1, "", "", false, "Cannot run program " + command.get(0));
      }
      return new ProcessRunner.Result(0, fixture.versionOutput(), "", false, null);
    };
  }

  /** A runner that always times out, for testing the timeout path. */
  public static ProcessRunner timingOutRunner() {
    return (command, timeout) -> new ProcessRunner.Result(-1, "", "", true, null);
  }

  /** The exec timeout the fixtures assume. */
  public static Duration execTimeout() {
    return Duration.ofSeconds(5);
  }
}
