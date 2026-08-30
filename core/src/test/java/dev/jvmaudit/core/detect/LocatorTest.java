package dev.jvmaudit.core.detect;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmaudit.core.detect.WellKnownRootLocator.Root;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The individual locators, each against a real directory tree rather than a mocked filesystem. */
class LocatorTest {

  @TempDir Path temp;

  private static final Map<String, String> TEMURIN =
      Map.of("IMPLEMENTOR", "Eclipse Adoptium", "JAVA_VERSION", "21.0.4");

  private final List<ScanIssue> issues = new ArrayList<>();

  private Path plant(Path where) {
    return JvmFixtures.plantJvm(where, TEMURIN);
  }

  // ---------------------------------------------------------------- well-known roots

  @Test
  void findsAJdkOneLevelBelowAConventionalRoot() {
    Path root = temp.resolve("Program Files").resolve("Java");
    Path jdk = plant(root.resolve("jdk-21"));

    List<JvmCandidate> found =
        new WellKnownRootLocator(List.of(Root.of(root)))
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsExactly(jdk);
    assertThat(found)
        .extracting(JvmCandidate::source)
        .containsOnly(DetectionSource.WELL_KNOWN_ROOT);
  }

  @Test
  void findsAMacOsBundleBelowAConventionalRoot() {
    Path root = temp.resolve("JavaVirtualMachines");
    Path home = plant(root.resolve("temurin-21.jdk").resolve("Contents").resolve("Home"));

    List<JvmCandidate> found =
        new WellKnownRootLocator(List.of(new Root(root, 3)))
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsExactly(home);
  }

  @Test
  void findsAHomebrewStyleJdkSeveralLevelsDown() {
    Path cellar = temp.resolve("Cellar");
    Path home =
        plant(
            cellar
                .resolve("openjdk")
                .resolve("21.0.4")
                .resolve("libexec")
                .resolve("openjdk.jdk")
                .resolve("Contents")
                .resolve("Home"));

    List<JvmCandidate> found =
        new WellKnownRootLocator(List.of(new Root(cellar, 6)))
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsExactly(home);
  }

  @Test
  void doesNotDescendIntoAJdkLookingForMoreJdks() {
    Path root = temp.resolve("jvm");
    Path jdk = plant(root.resolve("jdk-21"));
    plant(jdk.resolve("nested"));

    List<JvmCandidate> found =
        new WellKnownRootLocator(List.of(new Root(root, 5)))
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsExactly(jdk);
  }

  @Test
  void skipsRootsThatDoNotExist() {
    List<JvmCandidate> found =
        new WellKnownRootLocator(List.of(Root.of(temp.resolve("nowhere"))))
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).isEmpty();
    assertThat(issues).isEmpty();
  }

  @Test
  void listsTheConventionalRootsForEachOperatingSystem() {
    Path home = Path.of("/home/dev");

    List<String> windows =
        WellKnownRootLocator.rootsFor(
                OsFamily.WINDOWS, home, Map.of("ProgramFiles", "C:\\Program Files"))
            .stream()
            .map(root -> root.path().toString().replace('\\', '/'))
            .toList();
    List<String> linux =
        WellKnownRootLocator.rootsFor(OsFamily.LINUX, home, Map.of()).stream()
            .map(root -> root.path().toString().replace('\\', '/'))
            .toList();
    List<String> macos =
        WellKnownRootLocator.rootsFor(OsFamily.MACOS, home, Map.of()).stream()
            .map(root -> root.path().toString().replace('\\', '/'))
            .toList();

    assertThat(windows)
        .anySatisfy(path -> assertThat(path).endsWith("Program Files/Java"))
        .anySatisfy(path -> assertThat(path).endsWith("Eclipse Adoptium"))
        .anySatisfy(path -> assertThat(path).endsWith("Amazon Corretto"));
    assertThat(linux)
        .anySatisfy(path -> assertThat(path).endsWith("/usr/lib/jvm"))
        .anySatisfy(path -> assertThat(path).endsWith("/usr/java"))
        .anySatisfy(path -> assertThat(path).endsWith(".sdkman/candidates/java"))
        .anySatisfy(path -> assertThat(path).endsWith(".asdf/installs/java"));
    assertThat(macos)
        .anySatisfy(path -> assertThat(path).endsWith("/Library/Java/JavaVirtualMachines"))
        .anySatisfy(path -> assertThat(path).contains("Cellar"));
  }

  @Test
  void honoursTheProgramFilesEnvironmentVariable() {
    List<Root> roots =
        WellKnownRootLocator.rootsFor(
            OsFamily.WINDOWS, Path.of("C:\\Users\\dev"), Map.of("ProgramFiles", "D:\\Apps"));

    assertThat(roots)
        .extracting(root -> root.path().toString())
        .anySatisfy(path -> assertThat(path).startsWith("D:\\Apps"));
  }

  // ---------------------------------------------------------------- environment

  @Test
  void findsJavaHomeAndEveryJavaOnThePath() {
    Path fromJavaHome = plant(temp.resolve("home-jdk"));
    Path fromPath = plant(temp.resolve("path-jdk"));

    List<JvmCandidate> found =
        new EnvironmentLocator(
                Map.of(
                    "JAVA_HOME",
                    fromJavaHome.toString(),
                    "PATH",
                    fromPath.resolve("bin") + ";" + temp.resolve("not-java")),
                ";",
                OsFamily.WINDOWS)
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found)
        .extracting(JvmCandidate::home)
        .containsExactlyInAnyOrder(fromJavaHome, fromPath);
    assertThat(found)
        .extracting(JvmCandidate::source)
        .containsExactlyInAnyOrder(DetectionSource.JAVA_HOME, DetectionSource.PATH);
  }

  @Test
  void warnsWhenJavaHomePointsAtSomethingThatIsNotAJvm() {
    List<JvmCandidate> found =
        new EnvironmentLocator(
                Map.of("JAVA_HOME", temp.resolve("wrong").toString()), ";", OsFamily.WINDOWS)
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).isEmpty();
    assertThat(issues)
        .singleElement()
        .satisfies(issue -> assertThat(issue.message()).contains("JAVA_HOME is set to something"));
  }

  @Test
  void copesWithAnEnvironmentThatSaysNothingAboutJava() {
    assertThat(
            new EnvironmentLocator(Map.of(), ":", OsFamily.LINUX)
                .locate(ScanOptions.defaults(), issues::add))
        .isEmpty();
    assertThat(issues).isEmpty();
  }

  // ---------------------------------------------------------------- Windows registry

  @Test
  void parsesJavaHomeValuesOutOfRegQueryOutput() {
    String output =
        """
        HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK\\17.0.11
            JavaHome    REG_SZ    C:\\Program Files\\Java\\jdk-17
            RuntimeLib    REG_SZ    C:\\Program Files\\Java\\jdk-17\\bin\\server\\jvm.dll

        HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\Java Runtime Environment\\1.8
            JavaHome    REG_EXPAND_SZ    C:\\Program Files (x86)\\Java\\jre1.8.0_202
        """;

    assertThat(WindowsRegistryLocator.parseJavaHomeValues(output))
        .containsExactly(
            "C:\\Program Files\\Java\\jdk-17", "C:\\Program Files (x86)\\Java\\jre1.8.0_202");
  }

  @Test
  void keepsSpacesInsideRegistryPaths() {
    assertThat(
            WindowsRegistryLocator.parseJavaHomeValues(
                "    JavaHome    REG_SZ    C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.4.7-hotspot"))
        .containsExactly("C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.4.7-hotspot");
  }

  @Test
  void findsAJvmTheRegistryPointsAt() {
    Path jdk = plant(temp.resolve("registry-jdk"));
    ProcessRunner reg =
        (command, timeout) ->
            new ProcessRunner.Result(
                0, "    JavaHome    REG_SZ    " + jdk + System.lineSeparator(), "", false, null);

    List<JvmCandidate> found =
        new WindowsRegistryLocator(reg, OsFamily.WINDOWS)
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsOnly(jdk);
    assertThat(found)
        .extracting(JvmCandidate::source)
        .containsOnly(DetectionSource.WINDOWS_REGISTRY);
  }

  @Test
  void degradesGracefullyWhenTheRegToolIsMissing() {
    ProcessRunner missing =
        (command, timeout) -> new ProcessRunner.Result(-1, "", "", false, "reg not found");

    List<JvmCandidate> found =
        new WindowsRegistryLocator(missing, OsFamily.WINDOWS)
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).isEmpty();
    assertThat(issues)
        .anySatisfy(issue -> assertThat(issue.message()).contains("Could not run the Windows reg"))
        .allSatisfy(issue -> assertThat(issue.level()).isEqualTo(ScanIssue.Level.WARNING));
  }

  @Test
  void doesNothingOffWindows() {
    WindowsRegistryLocator locator =
        new WindowsRegistryLocator(ProcessRunner.system(), OsFamily.LINUX);

    assertThat(locator.isApplicable(ScanOptions.defaults())).isFalse();
  }

  @Test
  void staysOutOfTheWayWhenTheUserTurnsItOff() {
    assertThat(
            new WindowsRegistryLocator(ProcessRunner.system(), OsFamily.WINDOWS)
                .isApplicable(ScanOptions.builder().includeRegistry(false).build()))
        .isFalse();
  }

  // ---------------------------------------------------------------- running processes

  @Test
  void findsTheJvmBehindARunningJavaProcess() {
    Path jdk = plant(temp.resolve("running-jdk"));
    ProcessHandle handle = fakeProcess(4242, jdk.resolve("bin").resolve("java").toString());

    List<JvmCandidate> found =
        new RunningProcessLocator(() -> Stream.of(handle))
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsExactly(jdk);
    assertThat(found).extracting(JvmCandidate::detail).containsExactly("pid 4242");
  }

  @Test
  void tellsTheUserWhenItCouldNotSeeEveryProcess() {
    ProcessHandle visible = fakeProcess(1, "/usr/bin/bash");
    ProcessHandle hidden = fakeProcess(2, null);

    new RunningProcessLocator(() -> Stream.of(visible, hidden))
        .locate(ScanOptions.defaults(), issues::add);

    assertThat(issues)
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.message()).contains("1 of 2 running processes");
              assertThat(issue.message()).contains("administrator");
              assertThat(issue.message()).contains("root");
            });
  }

  @Test
  void ignoresProcessesThatAreNotJavaLaunchers() {
    assertThat(RunningProcessLocator.looksLikeJavaLauncher("/usr/lib/jvm/jdk-21/bin/java"))
        .isTrue();
    assertThat(RunningProcessLocator.looksLikeJavaLauncher("C:\\jdk\\bin\\javaw.exe")).isTrue();
    assertThat(RunningProcessLocator.looksLikeJavaLauncher("/usr/bin/javac")).isFalse();
    assertThat(RunningProcessLocator.looksLikeJavaLauncher("/usr/bin/javascript-runner")).isFalse();
    assertThat(RunningProcessLocator.looksLikeJavaLauncher("/opt/myjavaapp")).isFalse();
  }

  @Test
  void reportsRatherThanFailsWhenProcessesCannotBeListed() {
    List<JvmCandidate> found =
        new RunningProcessLocator(
                () -> {
                  throw new UnsupportedOperationException("not permitted");
                })
            .locate(ScanOptions.defaults(), issues::add);

    assertThat(found).isEmpty();
    assertThat(issues)
        .singleElement()
        .satisfies(
            issue -> assertThat(issue.message()).contains("Could not list running processes"));
  }

  // ---------------------------------------------------------------- deep scan

  @Test
  void deepScanFindsAJvmBuriedInsideAnApplication() {
    Path buried = plant(temp.resolve("apps").resolve("SomeProduct").resolve("jre"));

    List<JvmCandidate> found =
        new DeepScanLocator(List.of(temp))
            .locate(ScanOptions.builder().deep(true).build(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsExactly(buried);
    assertThat(found).extracting(JvmCandidate::source).containsOnly(DetectionSource.DEEP_SCAN);
  }

  @Test
  void deepScanSkipsTheDirectoriesNobodyWantsWalked() throws IOException {
    plant(temp.resolve("node_modules").resolve("weird-jdk"));
    plant(temp.resolve(".git").resolve("weirder-jdk"));
    Path wanted = plant(temp.resolve("apps").resolve("jre"));
    Files.createDirectories(temp.resolve("node_modules"));

    List<JvmCandidate> found =
        new DeepScanLocator(List.of(temp))
            .locate(ScanOptions.builder().deep(true).build(), issues::add);

    assertThat(found).extracting(JvmCandidate::home).containsExactly(wanted);
  }

  @Test
  void deepScanReportsAnUnusableExcludePatternRatherThanFailing() {
    new DeepScanLocator(List.of(temp))
        .locate(
            ScanOptions.builder().deep(true).excludeGlobs(List.of("[unclosed")).build(),
            issues::add);

    assertThat(issues)
        .anySatisfy(issue -> assertThat(issue.message()).contains("unusable --exclude pattern"));
  }

  @Test
  void deepScanOnlyRunsWhenAskedFor() {
    assertThat(new DeepScanLocator(List.of(temp)).isApplicable(ScanOptions.defaults())).isFalse();
    assertThat(
            new DeepScanLocator(List.of(temp))
                .isApplicable(ScanOptions.builder().deep(true).build()))
        .isTrue();
    assertThat(DeepScanLocator.defaultTimeout()).isEqualTo(Duration.ofMinutes(2));
  }

  // ---------------------------------------------------------------- explicit paths

  @Test
  void explicitPathsSayWhenTheyHeldNothing() {
    new ExplicitPathLocator()
        .locate(ScanOptions.builder().paths(List.of(temp)).build(), issues::add);

    assertThat(issues)
        .anySatisfy(
            issue -> assertThat(issue.message()).contains("No Java installation was found"));
  }

  @Test
  void explicitPathsComplainAboutADirectoryThatIsNotThere() {
    new ExplicitPathLocator()
        .locate(ScanOptions.builder().paths(List.of(temp.resolve("nope"))).build(), issues::add);

    assertThat(issues).anySatisfy(issue -> assertThat(issue.message()).contains("does not exist"));
  }

  /** A minimal ProcessHandle standing in for a real process, since one cannot be constructed. */
  private static ProcessHandle fakeProcess(long pid, String command) {
    ProcessHandle.Info info =
        (ProcessHandle.Info)
            java.lang.reflect.Proxy.newProxyInstance(
                LocatorTest.class.getClassLoader(),
                new Class<?>[] {ProcessHandle.Info.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "command" -> Optional.ofNullable(command);
                      case "toString" -> "Info[" + command + "]";
                      case "equals" -> proxy == args[0];
                      case "hashCode" -> System.identityHashCode(proxy);
                      default -> Optional.empty();
                    });
    return (ProcessHandle)
        java.lang.reflect.Proxy.newProxyInstance(
            LocatorTest.class.getClassLoader(),
            new Class<?>[] {ProcessHandle.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "pid" -> pid;
                  case "info" -> info;
                  case "toString" -> "Process[" + pid + "]";
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> Long.hashCode(pid);
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }
}
