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
 * See {@code docs/JSONB_BACKEND_DESIGN.md} §6 for the full mapping table, the fail-fast default
 * ({@link JsonbCastMode#STRICT}) and the lenient opt-in.
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

  // The JSON / ISO-8601 forms readCanonical reads, as PostgreSQL regular expressions. No "?" (an
  // optional part is {0,1}), so nothing reads as a bind parameter; no backslash (a dot is [.]), so
  // the pattern is the same under standard_conforming_strings=off.
  private static final String YEAR =
      "([0-9]{3}[1-9]|[0-9]{2}[1-9][0-9]|[0-9][1-9][0-9]{2}|[1-9][0-9]{3})";
  private static final String MONTH_DAY =
      "((0[1-9]|1[0-2])-(0[1-9]|1[0-9]|2[0-8])|(0[13-9]|1[0-2])-(29|30)|(0[13578]|1[02])-31)";
  private static final String LEAP_DAY =
      "([0-9]{2}(0[48]|[2468][048]|[13579][26])|(0[48]|[2468][048]|[13579][26])00)-02-29";
  private static final String ISO_DATE = "(" + YEAR + "-" + MONTH_DAY + "|" + LEAP_DAY + ")";
  private static final String ISO_TIME =
      "([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9]([.][0-9]{1,9}){0,1}){0,1}";
  private static final String ISO_OFFSET = "(Z|[+-](0[0-9]|1[0-5])(:{0,1}[0-5][0-9]){0,1})";
  private static final String JSON_INTEGER = "-{0,1}[0-9]{1,19}";
  private static final String JSON_NUMBER =
      "-{0,1}[0-9]+([.][0-9]+){0,1}([eE][+-]{0,1}[0-9]{1,3}){0,1}";
  private static final int JSON_NUMBER_MAX_LENGTH = 1000;

  private final String suffix;

  JsonbCast(String suffix) {
    this.suffix = suffix;
  }

  public String suffix() {
    return suffix;
  }

  /**
   * {@code text}, a SQL text expression, read as this type with the plain cast, {@code (text)::date}
   * (the 3.5.2 form, {@link JsonbCastMode#STRICT}): a stored value the cast cannot read fails the
   * query. {@link #TEXT} returns {@code text} unchanged.
   */
  public String read(String text) {
    return read(text, JsonbCastMode.STRICT);
  }

  /**
   * {@code text} read as this type under {@code mode}. {@link JsonbCastMode#STRICT}: the plain cast,
   * {@code (text)::bigint}. {@link JsonbCastMode#LENIENT}: only text PostgreSQL's input function
   * accepts, {@code CASE WHEN pg_input_is_valid(text, 'bigint') THEN (text)::bigint END}, so a
   * stored value no cast reads is {@code NULL} (no match, and a null in a sort) instead of an error
   * that fails the whole query; {@code text} then appears twice. Lenient needs PostgreSQL 16 or
   * later.
   */
  public String read(String text, JsonbCastMode mode) {
    if (this == TEXT) {
      return text;
    }
    if (mode == JsonbCastMode.LENIENT) {
      return caseWhen(
          "pg_input_is_valid(" + text + ", '" + suffix.substring(2) + "')",
          "(" + text + ")" + suffix);
    }
    return "(" + text + ")" + suffix;
  }

  /**
   * {@code text} read as this type only when it is in the type's JSON or ISO-8601 form; any other
   * text is {@code NULL} (no match). It never raises, on any PostgreSQL 15 or later: only text the
   * plain cast always reads passes the test before the cast. The default
   * ({@link JsonbCastMode#STRICT}) inside an array or a split collection (since 3.6.0; 3.5.2 read
   * nothing there), so an element no cast reads is skipped rather than failing the request:
   *
   * <ul>
   *   <li>integer types: a decimal numeral of at most 19 digits within the {@code bigint} range;
   *   <li>decimal types: a JSON number ({@code -1.5e3}), at most 1000 characters, exponent of at
   *       most three digits;
   *   <li>boolean: {@code true} or {@code false};
   *   <li>dates: {@code YYYY-MM-DD}, a real calendar day of a year 0001–9999;
   *   <li>times: {@code HH:MM}, {@code HH:MM:SS}, {@code HH:MM:SS.fraction} (up to 9 digits);
   *   <li>local date-times: an ISO date, {@code T} or a space, an ISO time;
   *   <li>offset date-times: the same, then optionally {@code Z} or an offset up to
   *       {@code ±15:59} ({@code +02:00}, {@code +0200}, {@code +02}).
   * </ul>
   *
   * <p>Text the cast would also read in another form ({@code 20250101}, {@code yes}, {@code +7})
   * is skipped. {@code text} appears more than once, so it must hold no bind parameter.
   */
  String readCanonical(String text) {
    String cast = "(" + text + ")" + suffix;
    return switch (this) {
      case TEXT -> text;
      // the range test reads the numeral as numeric, which a 19-digit numeral never overflows
      case BIGINT ->
          caseWhen(
              matches(text, JSON_INTEGER),
              caseWhen(
                  "(" + text + ")::numeric BETWEEN " + Long.MIN_VALUE + " AND " + Long.MAX_VALUE,
                  cast));
      case NUMERIC ->
          caseWhen(
              "length(" + text + ") <= " + JSON_NUMBER_MAX_LENGTH + " AND "
                  + matches(text, JSON_NUMBER),
              cast);
      case BOOLEAN -> caseWhen("(" + text + ") IN ('true', 'false')", cast);
      case DATE -> caseWhen(matches(text, ISO_DATE), cast);
      case TIME -> caseWhen(matches(text, ISO_TIME), cast);
      case TIMESTAMP -> caseWhen(matches(text, ISO_DATE + "[T ]" + ISO_TIME), cast);
      case TIMESTAMPTZ ->
          caseWhen(matches(text, ISO_DATE + "[T ]" + ISO_TIME + ISO_OFFSET + "{0,1}"), cast);
    };
  }

  private static String caseWhen(String condition, String then) {
    return "CASE WHEN " + condition + " THEN " + then + " END";
  }

  private static String matches(String text, String form) {
    return "(" + text + ") ~ '^" + form + "$'";
  }

  /**
   * As {@link #read(String, JsonbCastMode)}, for a text expression that is already parenthesized:
   * the plain cast adds no second pair of parentheses, so its SQL stays as it was in 3.5.2.
   */
  public String readParenthesized(String parenthesizedText, JsonbCastMode mode) {
    return readRepeatsText(mode) || this == TEXT
        ? read(parenthesizedText, mode)
        : parenthesizedText + suffix;
  }

  /**
   * Whether {@link #read(String, JsonbCastMode)} repeats its text expression under {@code mode}
   * (so a bind parameter in it repeats too).
   */
  public boolean readRepeatsText(JsonbCastMode mode) {
    return mode == JsonbCastMode.LENIENT && this != TEXT;
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
