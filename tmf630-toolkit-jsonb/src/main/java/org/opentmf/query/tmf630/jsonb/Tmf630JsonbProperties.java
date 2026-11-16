package org.opentmf.query.tmf630.jsonb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The JSONB module's switches ({@code opentmf.tmf630.jsonb.*}, since 3.6.0). Every default is the
 * 3.5.2 behaviour; each switch is an opt-in.
 */
@ConfigurationProperties(prefix = "opentmf.tmf630.jsonb")
public class Tmf630JsonbProperties {

  /** How {@code EQ} and {@code IN} compare: {@code extraction} (default) or {@code containment}. */
  private JsonbEqualityMode equality = JsonbEqualityMode.EXTRACTION;

  /** What happens with a stored value a cast cannot read: {@code strict} (default) or {@code lenient}. */
  private JsonbCastMode castMode = JsonbCastMode.STRICT;

  /**
   * Whether JSONB applies the core's regex length cap,
   * {@code opentmf.tmf630.attribute-filtering.regex.max-length}, to {@code .regex} / {@code .regexi}
   * and {@code filter=} {@code =~}, as the JPA and Mongo terminals do: {@code false} (default, as
   * before 3.6.0) or {@code true}. A pattern runs on the database, so a service that accepts regex
   * should turn it on.
   */
  private boolean applyRegexMaxLength;

  /**
   * Whether JSONB refuses, with the core's 400 and message, every request the QueryDSL terminal
   * refuses (a string operator or regex on a non-string field, a literal the declared type cannot
   * read, the regex cap, PostgreSQL-only jsonpath): {@code false} (default, as in 3.5.2) or {@code
   * true}. A field of an open JSON type ({@code Object}, {@code Map}, {@code JsonNode}) counts as any
   * type and is never refused for its type.
   */
  private boolean querydslAcceptance;

  public JsonbEqualityMode getEquality() {
    return equality;
  }

  public void setEquality(JsonbEqualityMode equality) {
    this.equality = equality;
  }

  public JsonbCastMode getCastMode() {
    return castMode;
  }

  public void setCastMode(JsonbCastMode castMode) {
    this.castMode = castMode;
  }

  public boolean isApplyRegexMaxLength() {
    return applyRegexMaxLength;
  }

  public void setApplyRegexMaxLength(boolean applyRegexMaxLength) {
    this.applyRegexMaxLength = applyRegexMaxLength;
  }

  public boolean isQuerydslAcceptance() {
    return querydslAcceptance;
  }

  public void setQuerydslAcceptance(boolean querydslAcceptance) {
    this.querydslAcceptance = querydslAcceptance;
  }
}
