package dev.jvmaudit.core.rules;

import dev.jvmaudit.core.model.JavaVersion;
import dev.jvmaudit.core.model.Product;
import java.time.LocalDate;

/**
 * The conditions under which a {@link LicenseRule} applies. Every condition that is stated must
 * hold; a condition that cannot be evaluated - a date bound on an installation whose build date is
 * unknown - does not hold, so evaluation falls through to the next rule instead of guessing.
 *
 * @param productId required product id, or null
 * @param oracleProduct required value of {@link Product#oracle()}, or null
 * @param feature required exact feature version, or null
 * @param featureMin inclusive lower bound on the feature version, or null
 * @param featureMax inclusive upper bound on the feature version, or null
 * @param versionMin inclusive lower bound on the full version, or null
 * @param versionMax inclusive upper bound on the full version, or null
 * @param releasedOnOrAfter inclusive lower bound on the build's GA date, or null
 * @param releasedOnOrBefore inclusive upper bound on the build's GA date, or null
 */
public record RuleMatch(
    String productId,
    Boolean oracleProduct,
    Integer feature,
    Integer featureMin,
    Integer featureMax,
    JavaVersion versionMin,
    JavaVersion versionMax,
    LocalDate releasedOnOrAfter,
    LocalDate releasedOnOrBefore) {

  /** Whether this match states no conditions at all, which the loader rejects. */
  public boolean isEmpty() {
    return productId == null
        && oracleProduct == null
        && feature == null
        && featureMin == null
        && featureMax == null
        && versionMin == null
        && versionMax == null
        && releasedOnOrAfter == null
        && releasedOnOrBefore == null;
  }

  /** Whether this match states a condition on the build's release date. */
  public boolean usesReleaseDate() {
    return releasedOnOrAfter != null || releasedOnOrBefore != null;
  }

  /**
   * Evaluates the conditions against one installation.
   *
   * @param product the recognised product, or null if the vendor was not recognised
   * @param version the parsed version, or null if it was absent or unparseable
   * @param releaseDate the build's GA date, or null if it could not be resolved
   * @return true when every stated condition holds
   */
  public boolean matches(Product product, JavaVersion version, LocalDate releaseDate) {
    if (productId != null && (product == null || !productId.equals(product.id()))) {
      return false;
    }
    if (oracleProduct != null && (product == null || product.oracle() != oracleProduct)) {
      return false;
    }
    if (feature != null && (version == null || version.feature() != feature)) {
      return false;
    }
    if (featureMin != null && (version == null || version.feature() < featureMin)) {
      return false;
    }
    if (featureMax != null && (version == null || version.feature() > featureMax)) {
      return false;
    }
    if (versionMin != null && (version == null || !version.isAtLeast(versionMin))) {
      return false;
    }
    if (versionMax != null && (version == null || !version.isAtMost(versionMax))) {
      return false;
    }
    if (releasedOnOrAfter != null
        && (releaseDate == null || releaseDate.isBefore(releasedOnOrAfter))) {
      return false;
    }
    return releasedOnOrBefore == null
        || (releaseDate != null && !releaseDate.isAfter(releasedOnOrBefore));
  }
}
