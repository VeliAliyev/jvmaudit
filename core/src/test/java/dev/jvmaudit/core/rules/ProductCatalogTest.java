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
    assertThat(CATALOG.products())
        .filteredOn(product -> product.matchConfidence() == Confidence.VERIFIED)
        .extracting(Product::id)
        .containsExactlyInAnyOrder("oracle-jdk", "oracle-openjdk", "corretto");
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
}
