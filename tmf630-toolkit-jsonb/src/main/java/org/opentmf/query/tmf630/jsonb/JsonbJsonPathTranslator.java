package org.opentmf.query.tmf630.jsonb;

import java.util.Optional;
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
 *
 * <p>The wrapper and the {@code length()} form are recognised by single-pass scans rather
 * than regular expressions: the former patterns backtracked quadratically on runs of
 * whitespace. The accepted language is unchanged — whitespace means the ASCII set
 * {@code [ \t\n\x0B\f\r]} (regex {@code \s}), exactly as before.
 */
public class JsonbJsonPathTranslator {

  private static final String LENGTH_SUFFIX = ".length";

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
    String body =
        filterBody(filterExpression)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "filter= expression must be wrapped in $[?(...)]: " + filterExpression));
    Optional<LengthEquality> lengthEquality = lengthEquality(body);
    if (lengthEquality.isPresent()) {
      return translateLengthEquality(
          lengthEquality.get().field(), Integer.parseInt(lengthEquality.get().literal()));
    }
    return translateAsJsonbPathExists(body);
  }

  private JsonbClause translateAsJsonbPathExists(String body) {
    String jsonPath = "$ ? (" + translatePredicate(body) + ")";
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

  /**
   * The predicate body of a {@code $[?(<body>)]} wrapper, or empty when the expression is not
   * wrapped. Grammar: whitespace, optionally {@code $} (then whitespace, optionally {@code .}),
   * whitespace, {@code [}, whitespace, {@code ?}, whitespace, {@code (}, the body, {@code )},
   * whitespace, {@code ]}, whitespace. The body runs from just past that {@code (} to the LAST
   * {@code )} followed only by whitespace, {@code ]} and whitespace.
   */
  private static Optional<String> filterBody(String s) {
    int i = skipSpaces(s, 0);
    if (i < s.length() && s.charAt(i) == '$') {
      i = skipSpaces(s, i + 1);
      if (i < s.length() && s.charAt(i) == '.') {
        i = skipSpaces(s, i + 1);
      }
    }
    i = consume(s, i, '[');
    if (i >= 0) {
      i = consume(s, skipSpaces(s, i), '?');
    }
    if (i >= 0) {
      i = consume(s, skipSpaces(s, i), '(');
    }
    if (i < 0) {
      return Optional.empty();
    }
    int close = closingParen(s, i);
    return close < 0 ? Optional.empty() : Optional.of(s.substring(i, close));
  }

  /**
   * Index of the {@code )} that starts the trailing {@code )}, whitespace, {@code ]},
   * whitespace suffix, provided it lies at or after {@code bodyStart}; otherwise -1.
   */
  private static int closingParen(String s, int bodyStart) {
    int end = skipSpacesBackward(s, s.length(), bodyStart);
    if (end <= bodyStart || s.charAt(end - 1) != ']') {
      return -1;
    }
    end = skipSpacesBackward(s, end - 1, bodyStart);
    if (end <= bodyStart || s.charAt(end - 1) != ')') {
      return -1;
    }
    return end - 1;
  }

  /**
   * The {@code @.<field>.length() == <integer>} form of a wrapper body, with optional
   * whitespace around the tokens. {@code <field>} is {@code [A-Za-z_][A-Za-z0-9_.]*} and may
   * itself contain {@code .length} segments — the LAST {@code .length()} is the call.
   */
  private static Optional<LengthEquality> lengthEquality(String body) {
    int start = skipSpaces(body, 0);
    if (!body.startsWith("@.", start)) {
      return Optional.empty();
    }
    int fieldStart = start + 2;
    int runEnd = fieldStart;
    while (runEnd < body.length() && isFieldChar(body.charAt(runEnd))) {
      runEnd++;
    }
    String run = body.substring(fieldStart, runEnd);
    if (!run.endsWith(LENGTH_SUFFIX) || !body.startsWith("()", runEnd)) {
      return Optional.empty();
    }
    String field = run.substring(0, run.length() - LENGTH_SUFFIX.length());
    if (field.isEmpty() || !isFieldStart(field.charAt(0))) {
      return Optional.empty();
    }
    int operator = skipSpaces(body, runEnd + 2);
    if (!body.startsWith("==", operator)) {
      return Optional.empty();
    }
    int literalStart = skipSpaces(body, operator + 2);
    int literalEnd = integerLiteralEnd(body, literalStart);
    if (literalEnd < 0 || skipSpaces(body, literalEnd) != body.length()) {
      return Optional.empty();
    }
    return Optional.of(new LengthEquality(field, body.substring(literalStart, literalEnd)));
  }

  /** End of an {@code -?[0-9]+} literal starting at {@code start}, or -1 when there is none. */
  private static int integerLiteralEnd(String s, int start) {
    int i = start;
    if (i < s.length() && s.charAt(i) == '-') {
      i++;
    }
    int digitsStart = i;
    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
      i++;
    }
    return i == digitsStart ? -1 : i;
  }

  private record LengthEquality(String field, String literal) {}

  private static int consume(String s, int i, char expected) {
    return i < s.length() && s.charAt(i) == expected ? i + 1 : -1;
  }

  private static int skipSpaces(String s, int from) {
    int i = from;
    while (i < s.length() && isSpace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipSpacesBackward(String s, int end, int floor) {
    int i = end;
    while (i > floor && isSpace(s.charAt(i - 1))) {
      i--;
    }
    return i;
  }

  /** The regex {@code \s} class: {@code [ \t\n\x0B\f\r]} — ASCII only, as before. */
  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
  }

  private static boolean isFieldStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  }

  private static boolean isFieldChar(char c) {
    return isFieldStart(c) || (c >= '0' && c <= '9') || c == '.';
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
      int consumed;
      if (c == '\'' || c == '"') {
        consumed = translateQuotedLiteral(input, i, out);
      } else if (c == '[' && looksLikeFilterOpen(input, i)) {
        consumed = translateFilterExpression(input, i, out);
      } else {
        out.append(c);
        consumed = 1;
      }
      i += consumed;
    }
    return out.toString();
  }

  private int translateQuotedLiteral(String input, int i, StringBuilder out) {
    int end = findStringEnd(input, i);
    out.append('"').append(JsonbStringEscape.escapeForDoubleQuoted(input.substring(i + 1, end))).append('"');
    return end + 1 - i;
  }

  /**
   * Consumes {@code [?(...)]} at position {@code i} and appends its SQL/JSON form. The
   * inner-filter body is at {@code [i+3, filterEnd-1)} — from just past {@code [?(} up
   * to but not including the closing {@code )}; {@code filterEnd} points at the closing
   * {@code ]}, so the {@code )} sits one character before it.
   */
  private int translateFilterExpression(String input, int i, StringBuilder out) {
    int filterEnd = findMatchingFilterClose(input, i);
    String innerFilter = input.substring(i + 3, filterEnd - 1);
    out.append("[*] ? (").append(translatePredicate(innerFilter)).append(")");
    return filterEnd + 1 - i;
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

}
