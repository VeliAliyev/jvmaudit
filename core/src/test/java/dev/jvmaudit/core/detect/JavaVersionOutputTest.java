package dev.jvmaudit.core.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The {@code Java(TM)} discriminator lives here, so these are the tests that decide whether an
 * installation is called free or possibly paid. Every sample below is real output.
 */
class JavaVersionOutputTest {

  @Test
  void readsRealOracleJdk17Output() {
    JavaVersionOutput output =
        JavaVersionOutput.parse(
            """
            java version "17.0.11" 2024-04-16 LTS
            Java(TM) SE Runtime Environment (build 17.0.11+7-LTS-207)
            Java HotSpot(TM) 64-Bit Server VM (build 17.0.11+7-LTS-207, mixed mode, sharing)
            """);

    assertThat(output.isJavaTm()).isTrue();
    assertThat(output.versionString()).isEqualTo("17.0.11");
    assertThat(output.releaseDate()).isEqualTo(LocalDate.parse("2024-04-16"));
    assertThat(output.runtimeLine()).startsWith("Java(TM) SE Runtime Environment");
  }

  @Test
  void readsRealOracleOpenJdk21Output() {
    JavaVersionOutput output =
        JavaVersionOutput.parse(
            """
            openjdk version "21.0.2" 2024-01-16
            OpenJDK Runtime Environment (build 21.0.2+13-58)
            OpenJDK 64-Bit Server VM (build 21.0.2+13-58, mixed mode, sharing)
            """);

    assertThat(output.isJavaTm()).isFalse();
    assertThat(output.versionString()).isEqualTo("21.0.2");
    assertThat(output.releaseDate()).isEqualTo(LocalDate.parse("2024-01-16"));
  }

  @Test
  void readsRealCorrettoOutput() {
    JavaVersionOutput output =
        JavaVersionOutput.parse(
            """
            openjdk version "17.0.13" 2024-10-15 LTS
            OpenJDK Runtime Environment Corretto-17.0.13.11.1 (build 17.0.13+11-LTS)
            OpenJDK 64-Bit Server VM Corretto-17.0.13.11.1 (build 17.0.13+11-LTS, mixed mode, sharing)
            """);

    assertThat(output.isJavaTm()).isFalse();
    assertThat(output.runtimeLine()).contains("Corretto-17.0.13.11.1");
  }

  @Test
  void doesNotMistakeJavaHotSpotTmForJavaTm() {
    // "Java HotSpot(TM)" appears in output from free builds too. Only the exact "Java(TM)" spelling
    // on the runtime line means an Oracle JDK, and confusing the two would invent a licence bill.
    JavaVersionOutput output =
        JavaVersionOutput.parse(
            """
            openjdk version "1.8.0_412"
            OpenJDK Runtime Environment (Zulu 8.78.0.19-CA-win64) (build 1.8.0_412-b08)
            Java HotSpot(TM) 64-Bit Server VM (Zulu 8.78.0.19-CA-win64) (build 25.412-b08, mixed mode)
            """);

    assertThat(output.isJavaTm()).as("a Zulu build is not an Oracle JDK").isFalse();
  }

  @Test
  void readsLegacyOracleJdk8Output() {
    JavaVersionOutput output =
        JavaVersionOutput.parse(
            """
            java version "1.8.0_202"
            Java(TM) SE Runtime Environment (build 1.8.0_202-b08)
            Java HotSpot(TM) 64-Bit Server VM (build 25.202-b08, mixed mode)
            """);

    assertThat(output.isJavaTm()).isTrue();
    assertThat(output.versionString()).isEqualTo("1.8.0_202");
    assertThat(output.releaseDate()).as("Java 8 prints no date").isNull();
  }

  @Test
  void readsOracleGraalVmOutput() {
    JavaVersionOutput output =
        JavaVersionOutput.parse(
            """
            java version "21.0.4" 2024-07-16 LTS
            Java(TM) SE Runtime Environment Oracle GraalVM 21.0.4+8.1 (build 21.0.4+8-LTS-jvmci-23.1-b41)
            """);

    assertThat(output.isJavaTm()).isTrue();
    assertThat(output.runtimeLine()).contains("Oracle GraalVM");
  }

  @Test
  void handlesNothingAtAll() {
    assertThat(JavaVersionOutput.parse(null).isEmpty()).isTrue();
    assertThat(JavaVersionOutput.parse("").isEmpty()).isTrue();
    assertThat(JavaVersionOutput.parse("   ").isEmpty()).isTrue();
    assertThat(JavaVersionOutput.parse("some unrelated text").isEmpty()).isTrue();
  }

  @Test
  void leavesTheDiscriminatorUnknownWhenTheOutputSaysNeither() {
    JavaVersionOutput output = JavaVersionOutput.parse("java version \"11.0.1\"");

    assertThat(output.versionString()).isEqualTo("11.0.1");
    assertThat(output.isJavaTm()).as("no evidence either way, so no answer").isNull();
  }
}
