package dev.jvmaudit.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A Java distribution, as recognised from what an installation reports about itself. Product ids
 * are the vocabulary the licence rules are written against.
 *
 * @param id stable id from {@code rules/vendors.yaml}, for example {@code oracle-jdk}
 * @param displayName what to call it in the report, for example {@code Oracle JDK / Oracle JRE}
 * @param vendor the organisation behind it, for example {@code Oracle}
 * @param oracle whether this is an Oracle product, and therefore whether Oracle licensing applies
 * @param citations where the claim about this product's provenance comes from
 * @param matchConfidence how the strings used to recognise this product were established
 */
public record Product(
    String id,
    String displayName,
    String vendor,
    boolean oracle,
    List<Citation> citations,
    Confidence matchConfidence) {

  public Product {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(vendor, "vendor");
    citations = List.copyOf(Objects.requireNonNullElse(citations, List.of()));
    matchConfidence = Objects.requireNonNullElse(matchConfidence, Confidence.UNVERIFIED);
  }
}
