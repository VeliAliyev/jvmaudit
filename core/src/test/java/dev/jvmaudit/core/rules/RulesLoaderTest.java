package dev.jvmaudit.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmaudit.core.model.Citation;
import dev.jvmaudit.core.model.Confidence;
import dev.jvmaudit.core.model.JavaVersion;
import dev.jvmaudit.core.model.LicenseStatus;
import dev.jvmaudit.core.model.Product;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesLoaderTest {

  private static final RuleSet RULES = RulesLoader.fromClasspath();

  @Test
  void loadsTheRuleSetPackagedInTheJar() {
    assertThat(RULES.rulesVersion()).isNotBlank();
    assertThat(RULES.rules()).isNotEmpty();
    assertThat(RULES.sources()).isNotEmpty();
    assertThat(RULES.products().products()).isNotEmpty();
    assertThat(RULES.releases().size()).isGreaterThan(100);
    assertThat(RULES.disclaimer()).contains("not legal advice");
  }

  @Test
  void collapsesFoldedTextIntoSingleLines() {
    assertThat(RULES.disclaimer()).doesNotContain("\n");
    assertThat(RULES.rules()).allSatisfy(rule -> assertThat(rule.summary()).doesNotContain("\n"));
  }

  @Test
  void everyRuleCitesASourceThatExists() {
    assertThat(RULES.rules())
        .allSatisfy(
            rule -> {
              assertThat(rule.citations()).as(rule.id()).isNotEmpty();
              assertThat(rule.citations())
                  .as(rule.id())
                  .allSatisfy(citation -> assertThat(RULES.sources()).containsValue(citation));
            });
  }

  @Test
  void everyCitationPointsAtAnHttpsUrl() {
    assertThat(RULES.sources().values())
        .extracting(Citation::url)
        .allSatisfy(url -> assertThat(url).startsWith("https://"));
    assertThat(RULES.products().products())
        .allSatisfy(
            product ->
                assertThat(product.citations())
                    .as(product.id())
                    .isNotEmpty()
                    .allSatisfy(citation -> assertThat(citation.url()).startsWith("https://")));
  }

  @Test
  void everyRuleStatesAtLeastOneMatchCondition() {
    assertThat(RULES.rules()).allSatisfy(rule -> assertThat(rule.match().isEmpty()).isFalse());
  }

  @Test
  void ruleAndProductIdsAreUnique() {
    assertThat(RULES.rules()).extracting(LicenseRule::id).doesNotHaveDuplicates();
    assertThat(RULES.products().products()).extracting(Product::id).doesNotHaveDuplicates();
  }

  @Test
  void everyRuleThatNamesAProductNamesOneTheCatalogueKnows() {
    assertThat(RULES.rules())
        .allSatisfy(
            rule -> {
              String productId = rule.match().productId();
              if (productId != null) {
                assertThat(RULES.products().byId(productId)).as(rule.id()).isPresent();
              }
            });
  }

  @Test
  void theLastRuleIsTheCatchAllForNonOracleProducts() {
    // Ordering is the engine's whole semantics, so pin the one position that must not drift.
    LicenseRule last = RULES.rules().get(RULES.rules().size() - 1);

    assertThat(last.id()).isEqualTo("third-party-open-source");
    assertThat(last.status()).isEqualTo(LicenseStatus.FREE);
    assertThat(last.match().oracleProduct()).isFalse();
  }

  @Test
  void loadsTheKnownLicenceBoundaryDates() {
    assertThat(RULES.releases().gaDate(JavaVersion.parse("8u202")))
        .contains(LocalDate.parse("2019-01-15"));
    assertThat(RULES.releases().gaDate(JavaVersion.parse("8u211")))
        .contains(LocalDate.parse("2019-04-16"));
    assertThat(RULES.releases().gaDate(JavaVersion.parse("17.0.12")))
        .contains(LocalDate.parse("2024-07-16"));
    assertThat(RULES.releases().gaDate(JavaVersion.parse("17.0.13")))
        .contains(LocalDate.parse("2024-10-15"));
    assertThat(RULES.releases().gaDate(JavaVersion.parse("21.0.12.1")))
        .contains(LocalDate.parse("2026-08-18"));
  }

  @Test
  void looksUpAJava8ReleaseUnderEitherSpelling() {
    assertThat(RULES.releases().gaDate(JavaVersion.parse("1.8.0_202")))
        .isEqualTo(RULES.releases().gaDate(JavaVersion.parse("8u202")));
  }

  @Test
  void answersWithNoDateForAnUnknownRelease() {
    assertThat(RULES.releases().gaDate(JavaVersion.parse("21.0.99"))).isEmpty();
    assertThat(RULES.releases().gaDate(null)).isEmpty();
  }

  @Test
  void marksUnverifiedRulesAsSuch() {
    assertThat(RULES.rules())
        .filteredOn(rule -> rule.confidence() == Confidence.UNVERIFIED)
        .extracting(LicenseRule::id)
        .containsExactlyInAnyOrder(
            "oracle-jdk-9-10-bcl",
            "oracle-jdk-12-16-otn",
            "oracle-jdk-21-nftc-by-version",
            "oracle-jdk-21-otn-by-version");
  }

  @Test
  void readsRuleFilesFromADirectoryToo(@TempDir Path directory) throws IOException {
    for (String name :
        List.of(
            RulesLoader.LICENSE_RULES_FILE, RulesLoader.VENDORS_FILE, RulesLoader.RELEASES_FILE)) {
      Files.writeString(
          directory.resolve(name), RulesLoader.readClasspathFile(name), StandardCharsets.UTF_8);
    }

    RuleSet fromDisk = RulesLoader.fromDirectory(directory);

    assertThat(fromDisk.rulesVersion()).isEqualTo(RULES.rulesVersion());
    assertThat(fromDisk.rules()).hasSameSizeAs(RULES.rules());
  }

  @Test
  void refusesARuleThatCitesASourceThatDoesNotExist(@TempDir Path directory) throws IOException {
    writeCatalogues(directory);
    Files.writeString(
        directory.resolve(RulesLoader.LICENSE_RULES_FILE),
        """
        schemaVersion: 1
        rulesVersion: "test"
        disclaimer: "not legal advice"
        sources:
          - id: real-source
            title: "A real source"
            url: "https://example.invalid/"
        rules:
          - id: broken
            match:
              product: oracle-jdk
            status: FREE
            flags: []
            summary: "Broken on purpose."
            citations: [no-such-source]
            confidence: verified
        """,
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> RulesLoader.fromDirectory(directory))
        .isInstanceOf(RuleDataException.class)
        .hasMessageContaining("cites unknown source");
  }

  @Test
  void refusesARuleWithNoCitationAtAll(@TempDir Path directory) throws IOException {
    writeCatalogues(directory);
    Files.writeString(
        directory.resolve(RulesLoader.LICENSE_RULES_FILE),
        """
        schemaVersion: 1
        rulesVersion: "test"
        disclaimer: "not legal advice"
        sources:
          - id: real-source
            title: "A real source"
            url: "https://example.invalid/"
        rules:
          - id: uncited
            match:
              product: oracle-jdk
            status: ORACLE_PAID_LIKELY
            flags: []
            summary: "An unsourced licence claim."
            citations: []
            confidence: verified
        """,
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> RulesLoader.fromDirectory(directory))
        .isInstanceOf(RuleDataException.class)
        .hasMessageContaining("has no citation");
  }

  @Test
  void refusesARuleWithNoMatchConditions(@TempDir Path directory) throws IOException {
    writeCatalogues(directory);
    Files.writeString(
        directory.resolve(RulesLoader.LICENSE_RULES_FILE),
        """
        schemaVersion: 1
        rulesVersion: "test"
        disclaimer: "not legal advice"
        sources:
          - id: real-source
            title: "A real source"
            url: "https://example.invalid/"
        rules:
          - id: catch-everything
            match: {}
            status: FREE
            flags: []
            summary: "Matches every installation on earth."
            citations: [real-source]
            confidence: verified
        """,
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> RulesLoader.fromDirectory(directory))
        .isInstanceOf(RuleDataException.class)
        .hasMessageContaining("states no match conditions");
  }

  @Test
  void refusesARuleWithAnUnknownStatus(@TempDir Path directory) throws IOException {
    writeCatalogues(directory);
    Files.writeString(
        directory.resolve(RulesLoader.LICENSE_RULES_FILE),
        """
        schemaVersion: 1
        rulesVersion: "test"
        disclaimer: "not legal advice"
        sources:
          - id: real-source
            title: "A real source"
            url: "https://example.invalid/"
        rules:
          - id: typo
            match:
              product: oracle-jdk
            status: DEFINITELY_FINE
            flags: []
            summary: "A status that does not exist."
            citations: [real-source]
            confidence: verified
        """,
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> RulesLoader.fromDirectory(directory))
        .isInstanceOf(RuleDataException.class)
        .hasMessageContaining("unknown status");
  }

  @Test
  void refusesARuleThatNamesAProductTheCatalogueDoesNotKnow(@TempDir Path directory)
      throws IOException {
    writeCatalogues(directory);
    Files.writeString(
        directory.resolve(RulesLoader.LICENSE_RULES_FILE),
        """
        schemaVersion: 1
        rulesVersion: "test"
        disclaimer: "not legal advice"
        sources:
          - id: real-source
            title: "A real source"
            url: "https://example.invalid/"
        rules:
          - id: ghost-product
            match:
              product: oracle-jdk-classic
            status: FREE
            flags: []
            summary: "Names a product that is not in the catalogue."
            citations: [real-source]
            confidence: verified
        """,
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> RulesLoader.fromDirectory(directory))
        .isInstanceOf(RuleDataException.class)
        .hasMessageContaining("unknown product");
  }

  @Test
  void reportsAMissingRuleFileClearly(@TempDir Path directory) {
    assertThatThrownBy(() -> RulesLoader.fromDirectory(directory))
        .isInstanceOf(RuleDataException.class)
        .hasMessageContaining(RulesLoader.VENDORS_FILE);
  }

  private static void writeCatalogues(Path directory) throws IOException {
    Files.writeString(
        directory.resolve(RulesLoader.VENDORS_FILE),
        RulesLoader.readClasspathFile(RulesLoader.VENDORS_FILE),
        StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve(RulesLoader.RELEASES_FILE),
        RulesLoader.readClasspathFile(RulesLoader.RELEASES_FILE),
        StandardCharsets.UTF_8);
  }
}
