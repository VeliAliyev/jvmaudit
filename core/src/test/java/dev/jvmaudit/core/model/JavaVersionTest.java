package dev.jvmaudit.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaVersionTest {

  @ParameterizedTest(name = "{0} -> {1}.{2}.{3}.{4}")
  @CsvSource({
    // legacy scheme
    "1.8.0_202,        8, 0, 202, 0",
    "1.8.0_202-b08,    8, 0, 202, 0",
    "1.8.0_211,        8, 0, 211, 0",
    "1.8.0,            8, 0, 0,   0",
    "1.7.0_80,         7, 0, 80,  0",
    "1.4.2_42,         4, 2, 42,  0",
    // Oracle release-note spelling
    "8u202,            8, 0, 202, 0",
    "8u211,            8, 0, 211, 0",
    "8u503,            8, 0, 503, 0",
    // modern scheme
    "9.0.4,            9, 0, 4,   0",
    "11.0.32,         11, 0, 32,  0",
    "17.0.12,         17, 0, 12,  0",
    "17.0.13,         17, 0, 13,  0",
    "17.0.11+7-LTS-207, 17, 0, 11, 0",
    "21,              21, 0, 0,   0",
    "21.0.12,         21, 0, 12,  0",
    "21.0.12.1,       21, 0, 12,  1",
    "25.0.4.1,        25, 0, 4,   1",
    "22-ea,           22, 0, 0,   0",
  })
  void parsesBothVersionSchemes(String raw, int feature, int interim, int update, int patch) {
    JavaVersion version = JavaVersion.parse(raw);

    assertThat(version.feature()).isEqualTo(feature);
    assertThat(version.interim()).isEqualTo(interim);
    assertThat(version.update()).isEqualTo(update);
    assertThat(version.patch()).isEqualTo(patch);
    assertThat(version.raw()).isEqualTo(raw);
  }

  @Test
  void treatsTheTwoSpellingsOfTheSameJava8ReleaseAsEqual() {
    assertThat(JavaVersion.parse("8u202"))
        .isEqualTo(JavaVersion.parse("1.8.0_202"))
        .isEqualTo(JavaVersion.parse("1.8.0_202-b08"));
  }

  @Test
  void ordersReleasesAcrossTheLicenceBoundaries() {
    // The two boundaries the whole product turns on.
    assertThat(JavaVersion.parse("1.8.0_202")).isLessThan(JavaVersion.parse("8u211"));
    assertThat(JavaVersion.parse("17.0.12")).isLessThan(JavaVersion.parse("17.0.13"));
    assertThat(JavaVersion.parse("21.0.12")).isLessThan(JavaVersion.parse("21.0.12.1"));
    assertThat(JavaVersion.parse("21.0.12.1")).isLessThan(JavaVersion.parse("21.0.13"));
    assertThat(JavaVersion.parse("8u502")).isLessThan(JavaVersion.parse("8u503"));
  }

  @Test
  void ignoresBuildNumbersWhenOrdering() {
    assertThat(JavaVersion.parse("17.0.11+7-LTS-207"))
        .isEqualByComparingTo(JavaVersion.parse("17.0.11"));
    assertThat(JavaVersion.parse("21.0.2+13-58")).isEqualByComparingTo(JavaVersion.parse("21.0.2"));
  }

  @Test
  void reportsBoundsInclusively() {
    JavaVersion boundary = JavaVersion.parse("17.0.12");

    assertThat(JavaVersion.parse("17.0.12").isAtMost(boundary)).isTrue();
    assertThat(JavaVersion.parse("17.0.12").isAtLeast(boundary)).isTrue();
    assertThat(JavaVersion.parse("17.0.13").isAtMost(boundary)).isFalse();
    assertThat(JavaVersion.parse("17.0.11").isAtLeast(boundary)).isFalse();
  }

  @ParameterizedTest(name = "canonical({0}) = {1}")
  @CsvSource({
    "1.8.0_202, 8u202",
    "8u211,     8u211",
    "1.8.0,     8",
    "17.0.11+7-LTS-207, 17.0.11",
    "21.0.12.1, 21.0.12.1",
    "21,        21.0.0",
  })
  void rendersTheSpellingOracleUses(String raw, String canonical) {
    assertThat(JavaVersion.parse(raw).canonical()).isEqualTo(canonical);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "not-a-version", "vNext"})
  void refusesUnparseableInput(String raw) {
    assertThat(JavaVersion.parseOrNull(raw)).isNull();
    assertThatThrownBy(() -> JavaVersion.parse(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Not a recognisable Java version");
  }

  @Test
  void treatsNullAsUnparseable() {
    assertThat(JavaVersion.parseOrNull(null)).isNull();
  }
}
