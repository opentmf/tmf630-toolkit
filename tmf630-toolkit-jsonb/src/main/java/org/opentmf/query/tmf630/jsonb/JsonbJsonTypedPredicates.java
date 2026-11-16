package org.opentmf.query.tmf630.jsonb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.predicate.JsonTypedValue;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.opentmf.query.tmf630.jsonb.JsonbPathPredicates.SplitChild;

/**
 * URL operators on a field of an open JSON type ({@code Map}, {@code JsonNode}; since 3.6.0, ledger
 * #4246): one SQL/JSON path filter on the field, {@code $."a"."b" ? (<test on @>)}, which reads
 * through arrays at every step (lax mode), so it answers for any element as the other array paths
 * do. The value is read as JSON ({@link JsonTypedValue}):
 *
 * <ul>
 *   <li>{@code eq} / {@code in}: {@code @ == "42" || @ == 42} finds the string and every number
 *       equal to 42; sent as {@code payload @? ?::jsonpath}, which a GIN index on the payload serves;
 *   <li>{@code ne} / {@code nin}: a value is present, not null, and is none of them (JSONB's
 *       released reading of not-equal);
 *   <li>an ordering compares numbers only ({@code @ > 10}); a value that is not a JSON number is the
 *       core's 400;
 *   <li>a string operator ({@code eqi}, {@code like}, {@code contains}, {@code startswith},
 *       {@code endswith}, {@code regex} and their case-insensitive forms) is a {@code like_regex} on
 *       strings only, its value matched literally (a {@code like} pattern's {@code %} and {@code _}
 *       are wildcards).
 * </ul>
 *
 * Every operator but {@code eq} and {@code in} is sent in the function form,
 * {@code jsonb_path_exists(payload, ?::jsonpath)}: the index has no key for it, and under a generic
 * plan the operator form would read the whole index. A path into a split collection is answered in
 * the child table. The value is written into the path text as a JSON literal; the path's segments
 * are identifiers.
 */
final class JsonbJsonTypedPredicates {

  private static final Pattern SEGMENT = Pattern.compile("([A-Za-z_]\\w*+)((?:\\[\\d++])*+)");
  private static final String REGEX_SPECIALS = "\\^$.|?*+()[]{}";

  private final String payloadColumn;

  JsonbJsonTypedPredicates(String payloadColumn) {
    this.payloadColumn = payloadColumn;
  }

  /** A single-value operator on {@code fieldPath}, of declared type {@code type}. */
  JsonbClause single(
      TmfOperator operator, String fieldPath, Class<?> type, SplitChild split, JsonTypedValue value) {
    String test =
        switch (operator) {
          case EQ -> anyOf(List.of(value));
          case NE -> noneOf(List.of(value));
          case GT -> "@ > " + number(fieldPath, value);
          case GTE -> "@ >= " + number(fieldPath, value);
          case LT -> "@ < " + number(fieldPath, value);
          case LTE -> "@ <= " + number(fieldPath, value);
          case EQI -> regex("^" + quote(value.text()) + "$", true);
          case NEI ->
              "@ != null && !(@.type() == \"string\" && "
                  + regex("^" + quote(value.text()) + "$", true)
                  + ")";
          case LIKE -> regex(likeToRegex(value.text()), false);
          case LIKEI -> regex(likeToRegex(value.text()), true);
          case CONTAINS -> regex(quote(value.text()), false);
          case CONTAINSI -> regex(quote(value.text()), true);
          case STARTS_WITH -> regex("^" + quote(value.text()), false);
          case STARTS_WITHI -> regex("^" + quote(value.text()), true);
          case ENDS_WITH -> regex(quote(value.text()) + "$", false);
          case ENDS_WITHI -> regex(quote(value.text()) + "$", true);
          case REGEX -> regex(value.text(), false);
          case REGEXI -> regex(value.text(), true);
          default ->
              throw new TmfFilteringException(
                  "Operator requires dedicated handler: " + operator.suffix());
        };
    return clause(fieldPath, type, split, test, operator == TmfOperator.EQ);
  }

  /** A multi-value operator ({@code in}, {@code nin}, {@code between}) on {@code fieldPath}. */
  JsonbClause multi(
      TmfOperator operator,
      String fieldPath,
      Class<?> type,
      SplitChild split,
      List<JsonTypedValue> values) {
    String test =
        switch (operator) {
          case IN -> anyOf(values);
          case NIN -> noneOf(values);
          case BETWEEN -> {
            if (values.size() != 2) {
              throw new TmfFilteringException(
                  "between operator expects exactly 2 values for field: " + fieldPath);
            }
            yield "@ >= " + number(fieldPath, values.get(0))
                + " && @ <= " + number(fieldPath, values.get(1));
          }
          default ->
              throw new TmfFilteringException(
                  "Unsupported multi-value operator: " + operator.suffix());
        };
    return clause(fieldPath, type, split, test, operator == TmfOperator.IN);
  }

  private JsonbClause clause(
      String fieldPath, Class<?> type, SplitChild split, String test, boolean keyed) {
    List<String> segments = segments(fieldPath, type);
    String column = payloadColumn;
    if (split != null) {
      segments = segments.subList(1, segments.size());
      column = split.payloadColumn();
    }
    String jsonPath = "$" + String.join("", segments) + " ? (" + test + ")";
    JsonbClause onColumn =
        keyed
            ? JsonbClause.of(column + " @?? ?::jsonpath", jsonPath)
            : JsonbClause.of("jsonb_path_exists(" + column + ", ?::jsonpath)", jsonPath);
    if (split == null) {
      return onColumn;
    }
    return JsonbClause.of(
        "EXISTS (SELECT 1 FROM " + split.childTable() + " WHERE " + split.parentIdColumn() + " = "
            + split.parentTable() + ".id AND " + onColumn.sql() + ")",
        onColumn.params().toArray());
  }

  /** {@code a.b[2].c} as SQL/JSON path accessors: {@code ."a"."b"[2]."c"}. */
  private static List<String> segments(String fieldPath, Class<?> type) {
    List<String> accessors = new ArrayList<>();
    for (String segment : fieldPath.split("\\.", -1)) {
      Matcher m = SEGMENT.matcher(segment);
      if (!m.matches()) {
        throw new TmfFilteringException(ValueConverter.cannotConvertMessage(fieldPath, type));
      }
      accessors.add(".\"" + m.group(1) + "\"" + m.group(2));
    }
    return accessors;
  }

  private static String anyOf(List<JsonTypedValue> values) {
    List<String> tests = new ArrayList<>();
    for (JsonTypedValue value : values) {
      for (String literal : value.jsonLiterals()) {
        tests.add("@ == " + literal);
      }
    }
    return String.join(" || ", tests);
  }

  /**
   * A present, non-null value that is none of the readings. Each test is guarded by the value's
   * JSON type: in SQL/JSON path a comparison across types is unknown, not false, and the negation
   * of unknown would drop every value of another type.
   */
  private static String noneOf(List<JsonTypedValue> values) {
    List<String> tests = new ArrayList<>();
    for (JsonTypedValue value : values) {
      tests.add("(@.type() == \"string\" && @ == " + JsonTypedValue.jsonString(value.text()) + ")");
      if (value.isNumber()) {
        tests.add("(@.type() == \"number\" && @ == " + value.text() + ")");
      } else if (value.bool() != null) {
        tests.add("(@.type() == \"boolean\" && @ == " + value.text() + ")");
      }
    }
    return "@ != null && !(" + String.join(" || ", tests) + ")";
  }

  private static String number(String fieldPath, JsonTypedValue value) {
    if (!value.isNumber()) {
      throw new TmfFilteringException(
          "Operator requires a number for the JSON-typed field: " + fieldPath);
    }
    return value.text();
  }

  private static String regex(String pattern, boolean ignoreCase) {
    return "@ like_regex " + JsonTypedValue.jsonString(pattern) + (ignoreCase ? " flag \"i\"" : "");
  }

  /** {@code text} matched literally by a POSIX regular expression. */
  static String quote(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (REGEX_SPECIALS.indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  /**
   * A SQL {@code LIKE} pattern as an anchored regular expression: {@code %} is any text,
   * {@code _} one character, a backslash escapes the next character (PostgreSQL's default
   * {@code LIKE} escape), everything else is literal.
   */
  static String likeToRegex(String pattern) {
    StringBuilder out = new StringBuilder("^");
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        out.append(quote(String.valueOf(c)));
        escaped = false;
      } else if (c == '\\' && i + 1 < pattern.length()) {
        escaped = true;
      } else if (c == '%') {
        out.append(".*");
      } else if (c == '_') {
        out.append('.');
      } else {
        out.append(quote(String.valueOf(c)));
      }
    }
    return out.append('$').toString();
  }
}
