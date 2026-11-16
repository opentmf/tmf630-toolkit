package org.opentmf.query.tmf630.filtering.predicate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A filter value for a field of an open JSON type ({@link JsonTypedFields}): the text as the
 * caller wrote it, and the JSON number or boolean the same text reads as, if any (since 3.6.0,
 * ledger #4246). The field declares no type to convert to, and the caller cannot know whether the
 * producer stored {@code 42} or {@code "42"}, so equality matches any of the readings: {@code 42}
 * finds the number 42 (also written {@code 42.0}) and the string {@code "42"}; {@code true} finds
 * the boolean and the string.
 *
 * <p>A number is read only in JSON's own form ({@code -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?},
 * at most {@value #MAX_NUMBER_LENGTH} characters, finite as a {@code double}); a boolean only as
 * {@code true} or {@code false}. Anything else is the text only.
 *
 * @param text the value as written
 * @param number the JSON number it reads as, or {@code null}
 * @param bool the JSON boolean it reads as, or {@code null}
 */
public record JsonTypedValue(String text, BigDecimal number, Boolean bool) {

  /** The longest text read as a number. */
  static final int MAX_NUMBER_LENGTH = 64;

  private static final Pattern JSON_NUMBER =
      Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

  /** The readings of {@code text}. */
  public static JsonTypedValue read(String text) {
    return new JsonTypedValue(text, number(text), bool(text));
  }

  /** Whether the text reads as a JSON number. */
  public boolean isNumber() {
    return number != null;
  }

  /**
   * The values a document store compares the field with: the text, then the number (a
   * {@code Long} when it is integral and fits, otherwise a {@code Double}) or the boolean.
   */
  public List<Object> readings() {
    List<Object> readings = new ArrayList<>(2);
    readings.add(text);
    if (number != null) {
      readings.add(storeNumber());
    } else if (bool != null) {
      readings.add(bool);
    }
    return readings;
  }

  /** The readings as JSON literals: the text as a JSON string, then the number or boolean. */
  public List<String> jsonLiterals() {
    List<String> literals = new ArrayList<>(2);
    literals.add(jsonString(text));
    if (number != null || bool != null) {
      literals.add(text);
    }
    return literals;
  }

  /** The number as a document store holds it: a {@code Long} when integral and in range. */
  public Number storeNumber() {
    if (number == null) {
      return null;
    }
    BigDecimal stripped = number.stripTrailingZeros();
    if (stripped.scale() <= 0 && stripped.precision() - stripped.scale() <= 18) {
      return stripped.longValueExact();
    }
    return number.doubleValue();
  }

  /** {@code value} as a JSON string literal, quotes included. */
  public static String jsonString(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.append('"').toString();
  }

  private static BigDecimal number(String text) {
    if (text == null
        || text.length() > MAX_NUMBER_LENGTH
        || !JSON_NUMBER.matcher(text).matches()) {
      return null;
    }
    BigDecimal value = new BigDecimal(text);
    return Double.isFinite(value.doubleValue()) ? value : null;
  }

  private static Boolean bool(String text) {
    if ("true".equals(text)) {
      return Boolean.TRUE;
    }
    return "false".equals(text) ? Boolean.FALSE : null;
  }
}
