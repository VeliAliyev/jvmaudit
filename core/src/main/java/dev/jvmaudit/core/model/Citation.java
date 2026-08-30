package dev.jvmaudit.core.model;

import java.util.Objects;

/**
 * A source a licence statement came from. Every classification JVMAudit produces carries at least
 * one, so that a reader can check the claim rather than trust it.
 *
 * @param id the short id used in the rule data files, for example {@code oracle-faq}
 * @param title what the source is called
 * @param url where to read it
 */
public record Citation(String id, String title, String url) {

  public Citation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(url, "url");
  }
}
