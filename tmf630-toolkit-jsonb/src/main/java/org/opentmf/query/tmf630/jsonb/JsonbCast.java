package org.opentmf.query.tmf630.jsonb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Resolves the Postgres cast needed to compare a JSONB scalar (extracted via
 * {@code payload->>'field'}, which always returns {@code text}) against a typed value.
 * See {@code docs/JSONB_BACKEND_DESIGN.md} §6 for the full mapping table and the
 * fail-fast rationale.
 *
 * <p>Returned {@link #suffix()} is empty for the {@code String}/enum case (no cast
 * needed — direct text comparison); for every other type it is a Postgres cast
 * expression like {@code "::bigint"} that the predicate factory appends to the
 * extraction fragment.
 */
public enum JsonbCast {
  TEXT(""),
  BIGINT("::bigint"),
  NUMERIC("::numeric"),
  BOOLEAN("::boolean"),
  DATE("::date"),
  TIME("::time"),
  TIMESTAMP("::timestamp"),
  TIMESTAMPTZ("::timestamptz");

  private final String suffix;

  JsonbCast(String suffix) {
    this.suffix = suffix;
  }

  public String suffix() {
    return suffix;
  }

  /**
   * Maps a Java type to its Postgres cast. Returns {@link #TEXT} for {@code String},
   * {@code CharSequence}, {@code Enum} subtypes, and any type the mapping doesn't
   * recognize — the safe default is text-vs-text comparison, which matches the raw
   * output of {@code payload->>'field'} directly.
   */
  public static JsonbCast forJavaType(Class<?> type) {
    if (type == null || CharSequence.class.isAssignableFrom(type) || type.isEnum()) {
      return TEXT;
    }
    if (Boolean.class.equals(type) || boolean.class.equals(type)) {
      return BOOLEAN;
    }
    if (Byte.class.equals(type)
        || byte.class.equals(type)
        || Short.class.equals(type)
        || short.class.equals(type)
        || Integer.class.equals(type)
        || int.class.equals(type)
        || Long.class.equals(type)
        || long.class.equals(type)
        || BigInteger.class.equals(type)) {
      return BIGINT;
    }
    if (Float.class.equals(type)
        || float.class.equals(type)
        || Double.class.equals(type)
        || double.class.equals(type)
        || BigDecimal.class.equals(type)) {
      return NUMERIC;
    }
    if (LocalDate.class.equals(type)) {
      return DATE;
    }
    if (LocalTime.class.equals(type)) {
      return TIME;
    }
    if (LocalDateTime.class.equals(type)) {
      return TIMESTAMP;
    }
    if (OffsetDateTime.class.equals(type)
        || ZonedDateTime.class.equals(type)
        || Instant.class.equals(type)) {
      return TIMESTAMPTZ;
    }
    return TEXT;
  }

  /**
   * Returns the cast suffix as an {@link Optional} — empty for {@link #TEXT} (which
   * has no cast), non-empty otherwise. Convenience for callers that want to build a
   * fragment conditionally.
   */
  public Optional<String> suffixIfNeeded() {
    return suffix.isEmpty() ? Optional.empty() : Optional.of(suffix);
  }
}
