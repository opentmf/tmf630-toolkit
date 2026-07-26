package org.opentmf.query.tmf630.jsonb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

/**
 * Phase (b.3) — translates the toolkit's Jayway-flavour JsonPath {@code filter=}
 * expression into a Postgres SQL/JSON path expression, wrapped in a
 * {@code jsonb_path_exists(payload, ...)} call. Emits a {@link JsonbClause} the b.5
 * executor can compose into a full WHERE clause.
 *
 * <p>Translation rules:
 *
 * <ul>
 *   <li>Wrapper: {@code $[?(<pred>)]} / {@code [?(<pred>)]} / {@code $.[?(<pred>)]}
 *       → {@code $ ? (<pred>)}
 *   <li>Array match nested inside a predicate: {@code @.arr[?(<inner>)]} →
 *       {@code @.arr[*] ? (<inner>)}. Postgres SQL/JSON path natively supports
 *       nested filters this way, so multi-level array correlation composes
 *       recursively.
 *   <li>Quote conversion: single-quoted string literals → double-quoted (Postgres
 *       requires double quotes for path-language string literals). Escapes preserved.
 *   <li>Logical operators {@code &&} / {@code ||} / unary {@code !} pass through
 *       unchanged (SQL/JSON path uses the same syntax).
 *   <li>Comparison operators {@code ==}, {@code !=}, {@code >}, {@code >=},
 *       {@code <}, {@code <=} pass through unchanged.
 *   <li>Numeric and boolean literals pass through unchanged.
 *   <li>{@code null} → {@code null} (SQL/JSON path {@code null}, all lowercase).
 * </ul>
 *
 * <p>Deferred to a follow-up cut (rejected with a clear message):
 * {@code length()} function (handled by {@link #translateLengthEquality}),
 * {@code =~} regex (Postgres has {@code like_regex} but not first-class here yet),
 * {@code [*]} bare wildcard as top-level projection (implicit via [*] in array
 * correlation but not standalone).
 */
public class JsonbJsonPathTranslator {

  private static final Pattern OUTER_WRAPPER =
      Pattern.compile("^\\s*(?:\\$\\s*\\.?)?\\s*\\[\\s*\\?\\s*\\((.*)\\)\\s*]\\s*$", Pattern.DOTALL);

  /**
   * TMF630 Part 6 {@code length()} on a collection field:
   * {@code $[?(@.arr.length() == N)]} — translated separately via
   * {@link #translateLengthEquality} because it does not fit the {@code jsonb_path_exists}
   * shape (needs {@code jsonb_array_length(payload->'arr') = N}).
   */
  private static final Pattern LENGTH_EQUALITY =
      Pattern.compile(
          "^\\s*(?:\\$\\s*\\.?)?\\s*\\[\\s*\\?\\s*\\(\\s*@\\.([A-Za-z_][A-Za-z0-9_.]*)\\.length\\(\\)\\s*==\\s*(-?\\d+)\\s*\\)\\s*]\\s*$",
          Pattern.DOTALL);

  private final JsonbPathExtractor extractor;
  private final String payloadColumn;

  public JsonbJsonPathTranslator(JsonbPathExtractor extractor, String payloadColumn) {
    if (extractor == null) {
      throw new IllegalArgumentException("extractor must not be null");
    }
    if (payloadColumn == null || payloadColumn.isBlank()) {
      throw new IllegalArgumentException("payloadColumn must not be blank");
    }
    this.extractor = extractor;
    this.payloadColumn = payloadColumn;
  }

  /**
   * Translates a {@code filter=} expression into a {@link JsonbClause}. Recognises three
   * shapes:
   *
   * <ol>
   *   <li>{@code length()} equality — special-cased to {@code jsonb_array_length}.
   *   <li>Everything else — translated to Postgres SQL/JSON path and wrapped in
   *       {@code jsonb_path_exists}.
   * </ol>
   */
  public JsonbClause translate(String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) {
      throw new TmfFilteringException("filter= expression must not be blank");
    }
    Matcher lengthMatcher = LENGTH_EQUALITY.matcher(filterExpression);
    if (lengthMatcher.matches()) {
      return translateLengthEquality(lengthMatcher.group(1), Integer.parseInt(lengthMatcher.group(2)));
    }
    return translateAsJsonbPathExists(filterExpression);
  }

  private JsonbClause translateAsJsonbPathExists(String filterExpression) {
    String inner = unwrap(filterExpression);
    String jsonPath = "$ ? (" + translatePredicate(inner) + ")";
    // JDBC parameter for the jsonpath text — Postgres will cast the text to jsonpath.
    return JsonbClause.of(
        "jsonb_path_exists(" + payloadColumn + ", ?::jsonpath)", jsonPath);
  }

  private JsonbClause translateLengthEquality(String dottedField, int expectedLength) {
    if (expectedLength < 0) {
      throw new TmfFilteringException(
          "length() must compare to a non-negative integer; got " + expectedLength);
    }
    return JsonbClause.of(
        "jsonb_array_length(" + extractor.extractAsJsonb(dottedField) + ") = ?", expectedLength);
  }

  private String unwrap(String expression) {
    Matcher matcher = OUTER_WRAPPER.matcher(expression);
    if (!matcher.matches()) {
      throw new TmfFilteringException(
          "filter= expression must be wrapped in $[?(...)]: " + expression);
    }
    return matcher.group(1);
  }

  /**
   * Character-level translation of the predicate body. Handles:
   *
   * <ul>
   *   <li>Single-quoted string literals → double-quoted, escapes preserved.
   *   <li>{@code @.field[?(<pred>)]} → {@code @.field[*] ? (<pred>)} — array
   *       correlation. Depth-tracked so nested {@code [?(...)]} inside strings
   *       stays literal.
   *   <li>Everything else passed through verbatim.
   * </ul>
   */
  private String translatePredicate(String input) {
    StringBuilder out = new StringBuilder(input.length() + 16);
    int i = 0;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (c == '\'' || c == '"') {
        int end = findStringEnd(input, i);
        out.append('"').append(escapeForDoubleQuoted(input.substring(i + 1, end))).append('"');
        i = end + 1;
        continue;
      }
      if (c == '[' && looksLikeFilterOpen(input, i)) {
        // findMatchingFilterClose returns the position of the closing ']'; the ')' is
        // one character before. Inner-filter body is [i+3, filterEnd-1) — from just
        // past '[?(' up to but not including ')'.
        int filterEnd = findMatchingFilterClose(input, i);
        String innerFilter = input.substring(i + 3, filterEnd - 1);
        out.append("[*] ? (").append(translatePredicate(innerFilter)).append(")");
        i = filterEnd + 1;
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  private static boolean looksLikeFilterOpen(String s, int i) {
    // Must match "[?(" starting at i.
    return i + 2 < s.length() && s.charAt(i + 1) == '?' && s.charAt(i + 2) == '(';
  }

  private static int findMatchingFilterClose(String s, int start) {
    // start points at '['; scan to the matching ')' then ']'.
    int depth = 0;
    int i = start + 3;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\'' || c == '"') {
        i = findStringEnd(s, i) + 1;
        continue;
      }
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        if (depth == 0) {
          if (i + 1 < s.length() && s.charAt(i + 1) == ']') {
            return i + 1;
          }
          throw new TmfFilteringException(
              "Malformed array-match filter — expected ']' after inner ')' at index " + i);
        }
        depth--;
      }
      i++;
    }
    throw new TmfFilteringException("Unterminated array-match filter starting at index " + start);
  }

  private static int findStringEnd(String s, int quotePos) {
    char quote = s.charAt(quotePos);
    int i = quotePos + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i;
      }
      i++;
    }
    throw new TmfFilteringException("Unterminated string literal in filter expression");
  }

  private static String escapeForDoubleQuoted(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') {
        out.append('\\').append('"');
      } else if (c == '\\') {
        out.append('\\').append('\\');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
