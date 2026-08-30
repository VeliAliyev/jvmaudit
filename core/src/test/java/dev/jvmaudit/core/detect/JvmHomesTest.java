package dev.jvmaudit.core.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmHomesTest {

  @TempDir Path temp;

  private Path plant(String name) {
    return JvmFixtures.plantJvm(
        temp.resolve(name), Map.of("IMPLEMENTOR", "Eclipse Adoptium", "JAVA_VERSION", "21.0.4"));
  }

  @Test
  void recognisesAHomeByItsReleaseFile() {
    assertThat(JvmHomes.looksLikeJvmHome(plant("jdk"))).isTrue();
  }

  @Test
  void recognisesAHomeByItsLauncherAlone() throws IOException {
    Path home = temp.resolve("no-release");
    Files.createDirectories(home.resolve("bin"));
    Files.writeString(home.resolve("bin").resolve("java"), "", StandardCharsets.UTF_8);

    assertThat(JvmHomes.looksLikeJvmHome(home)).isTrue();
    assertThat(JvmHomes.javaLauncher(home)).isPresent();
  }

  @Test
  void rejectsAnOrdinaryDirectory() throws IOException {
    Path plain = Files.createDirectories(temp.resolve("not-a-jdk"));

    assertThat(JvmHomes.looksLikeJvmHome(plain)).isFalse();
    assertThat(JvmHomes.looksLikeJvmHome(null)).isFalse();
    assertThat(JvmHomes.javaLauncher(null)).isEmpty();
    assertThat(JvmHomes.toJvmHome(plain)).isEmpty();
  }

  @Test
  void resolvesAHomeFromItsLauncher() {
    Path home = plant("jdk");

    assertThat(JvmHomes.toJvmHome(home.resolve("bin").resolve("java"))).contains(home);
    assertThat(JvmHomes.toJvmHome(home.resolve("bin"))).contains(home);
    assertThat(JvmHomes.toJvmHome(home)).contains(home);
  }

  @Test
  void resolvesAMacOsBundleToItsInnerHome() {
    // /Library/Java/JavaVirtualMachines/temurin-21.jdk -> .../Contents/Home
    Path bundle = temp.resolve("temurin-21.jdk");
    Path home =
        JvmFixtures.plantJvm(
            bundle.resolve("Contents").resolve("Home"),
            Map.of("IMPLEMENTOR", "Eclipse Adoptium", "JAVA_VERSION", "21.0.4"));

    assertThat(JvmHomes.toJvmHome(bundle)).contains(home);
  }

  @Test
  void tellsAJdkFromAJre() throws IOException {
    Path jdk = plant("jdk");
    Path jre = plant("jre");
    Files.deleteIfExists(jre.resolve("bin").resolve("javac"));
    Files.deleteIfExists(jre.resolve("bin").resolve("javac.exe"));

    assertThat(JvmHomes.isJdk(jdk)).isTrue();
    assertThat(JvmHomes.isJdk(jre)).isFalse();
    assertThat(JvmHomes.isJdk(null)).isFalse();
  }

  @Test
  void canonicalisesAPathThatDoesNotExist() {
    Path missing = temp.resolve("nowhere").resolve("..").resolve("nowhere");

    assertThat(JvmHomes.canonical(missing)).isAbsolute();
  }
}
