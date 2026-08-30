package dev.jvmaudit.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationTest {

  private static final Citation CITATION =
      new Citation("oracle-faq", "Oracle Java SE Licensing FAQ", "https://example.invalid/faq");

  private static Classification classification(
      LicenseStatus status, Set<ClassificationFlag> flags) {
    return new Classification(
        status, flags, "A summary.", List.of(CITATION), Confidence.VERIFIED, "a-rule", null, null);
  }

  @Test
  void refusesToExistWithoutACitation() {
    assertThatThrownBy(
            () ->
                new Classification(
                    LicenseStatus.ORACLE_PAID_LIKELY,
                    Set.of(),
                    "This one costs money.",
                    List.of(),
                    Confidence.VERIFIED,
                    "a-rule",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must cite at least one source");
  }

  @Test
  void takesItsSeverityFromTheStatusWhenThereAreNoFlags() {
    assertThat(classification(LicenseStatus.FREE, Set.of()).severity()).isEqualTo(Severity.OK);
    assertThat(classification(LicenseStatus.ORACLE_FREE_NFTC, Set.of()).severity())
        .isEqualTo(Severity.OK);
    assertThat(classification(LicenseStatus.LEGACY_BCL, Set.of()).severity())
        .isEqualTo(Severity.REVIEW);
    assertThat(classification(LicenseStatus.ORACLE_PAID_LIKELY, Set.of()).severity())
        .isEqualTo(Severity.ACTION);
    assertThat(classification(LicenseStatus.UNKNOWN, Set.of()).severity())
        .isEqualTo(Severity.UNKNOWN);
  }

  @Test
  void letsAFlagRaiseTheSeverityOfAnOtherwiseFreeInstallation() {
    Classification result =
        classification(
            LicenseStatus.ORACLE_FREE_NFTC, Set.of(ClassificationFlag.NFTC_WINDOW_CLOSING));

    assertThat(result.status()).isEqualTo(LicenseStatus.ORACLE_FREE_NFTC);
    assertThat(result.severity()).isEqualTo(Severity.REVIEW);
  }

  @Test
  void doesNotLetABundledFlagSoftenAPaidFinding() {
    Classification result =
        classification(
            LicenseStatus.ORACLE_PAID_LIKELY, Set.of(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED));

    assertThat(result.severity()).isEqualTo(Severity.ACTION);
  }

  @Test
  void doesNotLetAnUnknownDateFlagDowngradeAKnownFinding() {
    Classification result =
        classification(LicenseStatus.ORACLE_PAID_LIKELY, Set.of(ClassificationFlag.DATE_UNKNOWN));

    assertThat(result.severity()).isEqualTo(Severity.ACTION);
  }

  @Test
  void addsAFlagWithoutLosingTheRest() {
    Classification original =
        classification(
            LicenseStatus.ORACLE_FREE_NFTC, Set.of(ClassificationFlag.NFTC_WINDOW_CLOSING));

    Classification bundled = original.withFlag(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED);

    assertThat(bundled.flags())
        .containsExactlyInAnyOrder(
            ClassificationFlag.NFTC_WINDOW_CLOSING, ClassificationFlag.POSSIBLY_VENDOR_BUNDLED);
    assertThat(bundled.status()).isEqualTo(original.status());
    assertThat(bundled.citations()).isEqualTo(original.citations());
    assertThat(bundled.ruleId()).isEqualTo(original.ruleId());
  }

  @Test
  void returnsItselfWhenAskedToAddAFlagItAlreadyHas() {
    Classification original =
        classification(LicenseStatus.FREE, Set.of(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED));

    assertThat(original.withFlag(ClassificationFlag.POSSIBLY_VENDOR_BUNDLED)).isSameAs(original);
  }

  @Test
  void knowsWhichStatusesMeanOracleLicensing() {
    assertThat(LicenseStatus.FREE.isOracleLicensed()).isFalse();
    assertThat(LicenseStatus.UNKNOWN.isOracleLicensed()).isFalse();
    assertThat(LicenseStatus.ORACLE_FREE_NFTC.isOracleLicensed()).isTrue();
    assertThat(LicenseStatus.ORACLE_FREE_GFTC.isOracleLicensed()).isTrue();
    assertThat(LicenseStatus.LEGACY_BCL.isOracleLicensed()).isTrue();
    assertThat(LicenseStatus.ORACLE_PAID_LIKELY.isOracleLicensed()).isTrue();
  }

  @Test
  void keepsUnknownFromOutrankingARealFinding() {
    assertThat(Severity.max(Severity.ACTION, Severity.UNKNOWN)).isEqualTo(Severity.ACTION);
    assertThat(Severity.max(Severity.UNKNOWN, Severity.OK)).isEqualTo(Severity.OK);
    assertThat(Severity.max(Severity.UNKNOWN, Severity.UNKNOWN)).isEqualTo(Severity.UNKNOWN);
    assertThat(Severity.max(Severity.REVIEW, Severity.ACTION)).isEqualTo(Severity.ACTION);
  }
}
