package dev.jvmaudit.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.Product;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductCatalogTest {

  private static final ProductCatalog CATALOG = RulesLoader.fromClasspath().products();

  @ParameterizedTest(name = "IMPLEMENTOR={0} -> {1}")
  @CsvSource({
    "Eclipse Adoptium,   temurin",
    "AdoptOpenJDK,       adoptopenjdk",
    "Amazon.com Inc.,    corretto",
    "'Azul Systems, Inc.', zulu",
    "Microsoft,          microsoft",
    "'Red Hat, Inc.',    redhat",
    "BellSoft,           liberica",
    "SAP SE,             sapmachine",
    "IBM Corporation,    semeru",
    "Alibaba,            dragonwell",
    "Tencent,            kona",
  })
  void recognisesTheNonOracleDistributionsByVendorString(String implementor, String productId) {
    Optional<Product> product = CATALOG.resolve(implementor, null, null, null);

    assertThat(product).map(Product::id).contains(productId);
    assertThat(product).map(Product::oracle).contains(false);
  }

  @Test
  void ignoresVendorStringCaseAndSurroundingWhitespace() {
    assertThat(CATALOG.resolve("  eclipse adoptium  ", null, null, null))
        .map(Product::id)
        .contains("temurin");
  }

  @Test
  void separatesOracleJdkFromOracleOpenJdkOnTheJavaTmDiscriminator() {
    assertThat(CATALOG.resolve("Oracle Corporation", null, null, Boolean.TRUE))
        .map(Product::id)
        .contains("oracle-jdk");
    assertThat(CATALOG.resolve("Oracle Corporation", null, null, Boolean.FALSE))
        .map(Product::id)
        .contains("oracle-openjdk");
    assertThat(CATALOG.resolve("Oracle Corporation", null, null, null)).isEmpty();
  }

  @Test
  void recognisesOracleGraalVmAheadOfTheOtherOracleProducts() {
    assertThat(
            CATALOG.resolve("Oracle Corporation", "Oracle GraalVM 21.0.4+8.1", null, Boolean.TRUE))
        .map(Product::id)
        .contains("oracle-graalvm");
  }

  @Test
  void recognisesGraalVmCommunityEditionByItsImplementorVersion() {
    assertThat(CATALOG.resolve("GraalVM Community", "GraalVM CE 21.0.2+13.1", null, null))
        .map(Product::id)
        .contains("graalvm-ce");
  }

  @Test
  void doesNotRecogniseAnUnknownVendor() {
    assertThat(CATALOG.resolve("Acme Java Systems Ltd.", null, null, Boolean.FALSE)).isEmpty();
    assertThat(CATALOG.resolve(null, null, null, null)).isEmpty();
  }

  @Test
  void marksOnlyTheProductsConfirmedAgainstRealArtifactsAsVerified() {
    // Promoted after the JDK artifact survey of 2026-08-30 downloaded real builds of each and
    // confirmed the IMPLEMENTOR string. The four still unverified are the ones the survey could
    // not obtain: no setup-java distribution publishes Red Hat, Tencent Kona, the retired
    // AdoptOpenJDK, or either GraalVM edition.
    assertThat(CATALOG.products())
        .filteredOn(product -> product.matchConfidence() == Confidence.VERIFIED)
        .extracting(Product::id)
        .containsExactlyInAnyOrder(
            "oracle-jdk",
            "oracle-openjdk",
            "temurin",
            "corretto",
            "zulu",
            "microsoft",
            "liberica",
            "sapmachine",
            "semeru",
            "dragonwell");
    assertThat(CATALOG.products())
        .filteredOn(product -> product.matchConfidence() == Confidence.UNVERIFIED)
        .extracting(Product::id)
        .containsExactlyInAnyOrder(
            "oracle-graalvm", "graalvm-ce", "adoptopenjdk", "redhat", "kona");
  }

  @Test
  void marksTheOracleProductsAsOracleAndNoOthers() {
    assertThat(CATALOG.products())
        .filteredOn(Product::oracle)
        .extracting(Product::id)
        .containsExactlyInAnyOrder("oracle-jdk", "oracle-openjdk", "oracle-graalvm");
  }

  @Test
  void looksProductsUpById() {
    assertThat(CATALOG.byId("temurin")).map(Product::displayName).contains("Eclipse Temurin");
    assertThat(CATALOG.byId("not-a-product")).isEmpty();
  }

  @Test
  void recognisesAVendorFromTheVersionBannerAloneWhenThereIsNoReleaseFile() {
    // An installation with no readable release file offers only the java -version banner, so each
    // product carries an alternative that matches on that.
    assertThat(
            CATALOG.resolve(
                null,
                null,
                "OpenJDK Runtime Environment Temurin-21.0.4+7 (build 21.0.4+7-LTS)",
                Boolean.FALSE))
        .map(Product::id)
        .contains("temurin");
    assertThat(
            CATALOG.resolve(
                null,
                null,
                "OpenJDK Runtime Environment Corretto-17.0.13.11.1 (build 17.0.13+11)",
                Boolean.FALSE))
        .map(Product::id)
        .contains("corretto");
    assertThat(
            CATALOG.resolve(
                null, null, "OpenJDK Runtime Environment (Zulu 8.78.0.19-CA-win64)", Boolean.FALSE))
        .map(Product::id)
        .contains("zulu");
  }

  @Test
  void recognisesAnOracleJdkFromItsBannerAlone() {
    assertThat(
            CATALOG.resolve(
                null,
                null,
                "Java(TM) SE Runtime Environment (build 17.0.13+10-LTS-58)",
                Boolean.TRUE))
        .map(Product::id)
        .contains("oracle-jdk");
  }

  @Test
  void prefersOracleGraalVmOverOracleJdkWhenTheBannerNamesBoth() {
    // Oracle GraalVM's banner also says "Java(TM) SE Runtime Environment", so catalogue order is
    // what keeps it from being mistaken for a plain Oracle JDK.
    assertThat(
            CATALOG.resolve(
                null,
                null,
                "Java(TM) SE Runtime Environment Oracle GraalVM 21.0.4+8.1 (build 21.0.4+8-LTS)",
                Boolean.TRUE))
        .map(Product::id)
        .contains("oracle-graalvm");
  }

  @Test
  void stillRefusesToNameAnUnbrandedOpenJdkBuild() {
    // "OpenJDK Runtime Environment (build 21.0.2+13-58)" could be Oracle's own free build or any
    // rebuild of it. Without a release file there is no evidence, so there is no answer.
    assertThat(
            CATALOG.resolve(
                null, null, "OpenJDK Runtime Environment (build 21.0.2+13-58)", Boolean.FALSE))
        .isEmpty();
  }

  @Test
  void everyProductStatesAtLeastOneMatchCondition() {
    assertThat(CATALOG.entries())
        .allSatisfy(
            entry -> {
              assertThat(entry.conditions()).as(entry.product().id()).isNotEmpty();
              assertThat(entry.conditions())
                  .as(entry.product().id())
                  .allSatisfy(condition -> assertThat(condition.isEmpty()).isFalse());
            });
  }
}
