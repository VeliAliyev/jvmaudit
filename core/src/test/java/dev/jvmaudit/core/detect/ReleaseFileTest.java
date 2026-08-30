package dev.jvmaudit.core.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseFileTest {

  @TempDir Path temp;

  /**
   * The real release file of the Oracle JDK 17.0.11 installed on the machine this was written on.
   */
  private static final List<String> REAL_ORACLE_JDK =
      List.of(
          "IMPLEMENTOR=\"Oracle Corporation\"",
          "JAVA_RUNTIME_VERSION=\"17.0.11+7-LTS-207\"",
          "JAVA_VERSION=\"17.0.11\"",
          "JAVA_VERSION_DATE=\"2024-04-16\"",
          "LIBC=\"default\"",
          "OS_ARCH=\"x86_64\"",
          "OS_NAME=\"Windows\"",
          "SOURCE=\".:git:0531bcd287a8 open:git:38d1cef19db8\"");

  /** The real release file of the Amazon Corretto 17.0.13 installed on the same machine. */
  private static final List<String> REAL_CORRETTO =
      List.of(
          "IMPLEMENTOR=\"Amazon.com Inc.\"",
          "IMPLEMENTOR_VERSION=\"Corretto-17.0.13.11.1\"",
          "JAVA_RUNTIME_VERSION=\"17.0.13+11-LTS\"",
          "JAVA_VERSION=\"17.0.13\"",
          "JAVA_VERSION_DATE=\"2024-10-15\"",
          "OS_ARCH=\"x86_64\"",
          "OS_NAME=\"Windows\"",
          "SOURCE=\"\"");

  @Test
  void parsesARealOracleJdkReleaseFile() {
    ReleaseFile release = ReleaseFile.parse(REAL_ORACLE_JDK);

    assertThat(release.implementor()).isEqualTo("Oracle Corporation");
    assertThat(release.implementorVersion()).as("Oracle sets none").isNull();
    assertThat(release.javaVersion()).isEqualTo("17.0.11");
    assertThat(release.javaVersionDate()).contains(LocalDate.parse("2024-04-16"));
    assertThat(release.javaRuntimeVersion()).isEqualTo("17.0.11+7-LTS-207");
    assertThat(release.source()).contains("open:git:");
    assertThat(release.isEmpty()).isFalse();
  }

  @Test
  void parsesARealCorrettoReleaseFile() {
    ReleaseFile release = ReleaseFile.parse(REAL_CORRETTO);

    assertThat(release.implementor()).isEqualTo("Amazon.com Inc.");
    assertThat(release.implementorVersion()).isEqualTo("Corretto-17.0.13.11.1");
    assertThat(release.javaVersionDate()).contains(LocalDate.parse("2024-10-15"));
    assertThat(release.source()).as("an empty quoted value counts as absent").isNull();
  }

  @Test
  void handlesAJava8ReleaseFileWithNoVersionDate() {
    ReleaseFile release =
        ReleaseFile.parse(
            List.of("JAVA_VERSION=\"1.8.0_202\"", "OS_NAME=\"Windows\"", "OS_ARCH=\"amd64\""));

    assertThat(release.javaVersion()).isEqualTo("1.8.0_202");
    assertThat(release.javaVersionDate()).isEmpty();
    assertThat(release.implementor()).isNull();
  }

  @Test
  void ignoresBlankLinesCommentsAndMalformedEntries() {
    ReleaseFile release =
        ReleaseFile.parse(
            List.of(
                "",
                "# a comment",
                "   ",
                "NOT_A_PAIR",
                "=novalue",
                "JAVA_VERSION=\"21.0.4\"",
                "UNQUOTED=plain"));

    assertThat(release.javaVersion()).isEqualTo("21.0.4");
    assertThat(release.get("UNQUOTED")).isEqualTo("plain");
    assertThat(release.entries()).hasSize(2);
  }

  @Test
  void survivesAnUnparseableVersionDate() {
    ReleaseFile release = ReleaseFile.parse(List.of("JAVA_VERSION_DATE=\"not-a-date\""));

    assertThat(release.javaVersionDate()).isEmpty();
  }

  @Test
  void readsTheFileFromAJvmHome() {
    Path home = temp.resolve("jdk");
    JvmFixtures.plantJvm(
        home, java.util.Map.of("IMPLEMENTOR", "Eclipse Adoptium", "JAVA_VERSION", "21.0.4"));

    assertThat(ReleaseFile.read(home)).isPresent();
    assertThat(ReleaseFile.read(home).orElseThrow().implementor()).isEqualTo("Eclipse Adoptium");
  }

  @Test
  void reportsAbsenceRatherThanFailingWhenThereIsNoReleaseFile() throws IOException {
    Path home = Files.createDirectories(temp.resolve("bare"));

    assertThat(ReleaseFile.read(home)).isEmpty();
    assertThat(ReleaseFile.empty().isEmpty()).isTrue();
    assertThat(ReleaseFile.empty().implementor()).isNull();
  }
}
