package org.opentmf.query.tmf630.jsonb;

import java.util.Optional;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;

/**
 * Phase (b.3) — translates the toolkit's Jayway-flavour JsonPath {@code filter=}
 * expression into a Postgres SQL/JSON path expression, tested with the {@code @?} operator:
 * {@code payload @? ?::jsonpath}. Emits a {@link JsonbClause} the b.5 executor can compose into
 * a full WHERE clause.
 *
 * <p>The operator, not the equivalent {@code jsonb_path_exists(payload, ...)} function, because
 * PostgreSQL serves a GIN index ({@code jsonb_path_ops} or {@code jsonb_ops}) only through the
 * operators {@code @>}, {@code @?} and {@code @@}, never through a function call.
 * The JDBC text carries the operator as {@code @??}: the PostgreSQL driver turns {@code ??} into
 * a literal {@code ?}, so the server receives {@code payload @? $1::jsonpath} (the same escape
 * {@link JsonbPathExtractor#hasTopLevelKey} uses). The operator suppresses the errors the
 * function raises; for the lax {@code $ ? (...)} path this class emits, that differs only for a
 * body that continues outside the filter, which is refused (see {@link #translate}).
 *
 * <p>Translation rules:
 *
 * <ul>
 *   <li>Wrapper: {@code $[?(<pred>)]} / {@code [?(<pred>)]} / {@code $.[?(<pred>)]}
 *       → {@code $ ? (<pred>)}
 *   <li>Array match standing as a predicate: {@code @.arr[?(<inner>)]} →
 *       {@code exists(@.arr[*] ? (<inner>))}. A path with a filter is not a predicate in
 *       SQL/JSON path, so {@code exists(...)} turns it into one (before 3.5.3 the bare form was
 *       emitted and PostgreSQL rejected it as a jsonpath syntax error). "Standing as a
 *       predicate" means it is followed by the end of its predicate, a {@code )}, {@code &&} or
 *       {@code ||}. Multi-level correlation composes recursively, each level in its own
 *       {@code exists(...)}.
 *   <li>Array match used as an operand: {@code @.arr[?(<inner>)].field == 'X'} →
 *       {@code @.arr[*] ? (<inner>).field == "X"}, the filter followed by an accessor and a
 *       comparison, which is already a predicate.
 *   <li>Quote conversion: single-quoted string literals → double-quoted (Postgres
 *       requires double quotes for path-language string literals). Escapes preserved.
 *   <li>Logical operators {@code &&} / {@code ||} / unary {@code !} pass through
 *       unchanged (SQL/JSON path uses the same syntax).
 *   <li>Comparison operators {@code ==}, {@code !=}, {@code >}, {@code >=},
 *       {@code <}, {@code <=} pass through unchanged.
 *   <li>Numeric and boolean literals pass through unchanged.
 *   <li>Null tests follow the configured {@code isnull-semantics}, as the core reads them
 *       ({@code IS_NULL} / {@code IS_NOT_NULL}): {@code !@.field} and {@code @.field == null} →
 *       {@code !(exists(@.field))}, {@code @.field != null} → {@code exists(@.field)}; under
 *       NULLISH a JSON null counts as no value too ({@code exists(@.field ? (@ != null))}).
 *       Before 3.6.0 {@code !@.field} was a jsonpath syntax error and {@code == null} matched only
 *       a stored JSON null. A negated array match ({@code !@.arr[?(…)]}) is the core's 400.
 *   <li>{@code in} / {@code nin} are the core's 400 ("Unsupported token in jsonPath filter
 *       near: …"): they are not part of the toolkit's {@code filter=} grammar.
 *   <li>{@code =~ /pattern/} and {@code =~ /pattern/i} (TMF-630 Part 6 regex) →
 *       {@code like_regex "pattern"} / {@code like_regex "pattern" flag "i"}. Postgres
 *       evaluates {@code like_regex} with the same POSIX engine as the {@code ~} operator the
 *       attribute-side {@code .regex} uses, so both grammars match the same rows. Gated by
 *       {@code regex.enabled} exactly like the attribute side; only the {@code i} flag is
 *       accepted (3.4.0 — before that the operator passed through verbatim and Postgres
 *       rejected the jsonpath, surfacing as a 500).
 * </ul>
 *
 * <p>Deferred to a follow-up cut (rejected with a clear message):
 * {@code length()} function (handled by {@link #translateLengthEquality}),
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
  private static final String EXISTS = "exists(";

  private final JsonbPathExtractor extractor;
  private final String payloadColumn;
  private final boolean regexEnabled;
  private final IsnullSemantics isnullSemantics;

  /** Regex-disabled translator: {@code =~} is rejected, matching the attribute side's default. */
  public JsonbJsonPathTranslator(JsonbPathExtractor extractor, String payloadColumn) {
    this(extractor, payloadColumn, false);
  }

  /** A translator under the default isnull meaning, {@link IsnullSemantics#MISSING_ONLY}. */
  public JsonbJsonPathTranslator(
      JsonbPathExtractor extractor, String payloadColumn, boolean regexEnabled) {
    this(extractor, payloadColumn, regexEnabled, IsnullSemantics.MISSING_ONLY);
  }

  /**
   * A translator whose null tests ({@code !@.field}, {@code == null}, {@code != null}) follow
   * {@code isnullSemantics}, as the attribute {@code isnull} does.
   */
  public JsonbJsonPathTranslator(
      JsonbPathExtractor extractor,
      String payloadColumn,
      boolean regexEnabled,
      IsnullSemantics isnullSemantics) {
    if (extractor == null) {
      throw new IllegalArgumentException("extractor must not be null");
    }
    if (payloadColumn == null || payloadColumn.isBlank()) {
      throw new IllegalArgumentException("payloadColumn must not be blank");
    }
    this.extractor = extractor;
    this.payloadColumn = payloadColumn;
    this.regexEnabled = regexEnabled;
    this.isnullSemantics =
        isnullSemantics == null ? IsnullSemantics.MISSING_ONLY : isnullSemantics;
  }

  /**
   * Translates a {@code filter=} expression into a {@link JsonbClause}. Recognises three
   * shapes:
   *
   * <ol>
   *   <li>{@code length()} equality — special-cased to {@code jsonb_array_length}.
   *   <li>Everything else — translated to Postgres SQL/JSON path and tested with
   *       {@code payload @? ?::jsonpath}.
   * </ol>
   *
   * <p>A body that would close the {@code $ ? (...)} wrapper early, or leave a parenthesis open,
   * is refused: outside string and regex literals, its parenthesis depth must never fall below
   * zero and must end at zero. {@code $[?(@.a == 1) || (@.b == 2)]} balances by count and is
   * still refused, because its body closes the wrapper after {@code @.a == 1}. The QueryDSL
   * terminal refuses the same bodies.
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
    requireBodyInsideWrapper(body);
    Optional<LengthEquality> lengthEquality = lengthEquality(body);
    if (lengthEquality.isPresent()) {
      return translateLengthEquality(
          lengthEquality.get().field(), Integer.parseInt(lengthEquality.get().literal()));
    }
    return translateAsPathMatch(body);
  }

  private JsonbClause translateAsPathMatch(String body) {
    String jsonPath = "$ ? (" + translatePredicate(body) + ")";
    // JDBC parameter for the jsonpath text — Postgres will cast the text to jsonpath. "@??" is
    // the driver's escape for the "@?" operator, so the index can serve the test.
    return JsonbClause.of(payloadColumn + " @?? ?::jsonpath", jsonPath);
  }

  /**
   * Refuses a wrapper body whose parentheses, outside string and regex literals, close the
   * wrapper early (the depth falls below zero) or leave a parenthesis open (it ends above zero).
   */
  private static void requireBodyInsideWrapper(String body) {
    int depth = 0;
    int i = 0;
    while (i < body.length()) {
      char c = body.charAt(i);
      if (c == '\'' || c == '"') {
        i = findStringEnd(body, i) + 1;
      } else if (c == '=' && i + 1 < body.length() && body.charAt(i + 1) == '~') {
        i = endOfRegexOperand(body, i + 2);
      } else {
        depth += parenthesisDelta(c);
        if (depth < 0) {
          throw unbalancedBody("closes the $[?(...)] wrapper early");
        }
        i++;
      }
    }
    if (depth != 0) {
      throw unbalancedBody("leaves a parenthesis open");
    }
  }

  private static int parenthesisDelta(char c) {
    if (c == '(') {
      return 1;
    }
    return c == ')' ? -1 : 0;
  }

  /**
   * Index just past the {@code /pattern/} literal that follows {@code =~} at {@code from}; when
   * there is no complete literal, {@code from} itself, so the translation reports it.
   */
  private static int endOfRegexOperand(String s, int from) {
    int open = skipSpaces(s, from);
    if (open >= s.length() || s.charAt(open) != '/') {
      return from;
    }
    int i = open + 1;
    while (i < s.length() && s.charAt(i) != '/') {
      i += s.charAt(i) == '\\' ? 2 : 1;
    }
    return i < s.length() ? i + 1 : from;
  }

  private static TmfFilteringException unbalancedBody(String what) {
    return new TmfFilteringException(
        "filter= expression "
            + what
            + ": outside string and regex literals, the parentheses of $[?(...)] must close"
            + " exactly at its end.");
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
    PredicateText out = new PredicateText(input.length() + 16);
    int i = 0;
    while (i < input.length()) {
      i += translateAt(input, i, out);
    }
    return out.toString();
  }

  /** Translates the token starting at {@code i} and returns how many characters it consumed. */
  private int translateAt(String input, int i, PredicateText out) {
    char c = input.charAt(i);
    if (c == '!' && !input.startsWith("!=", i)) {
      int negated = translateNegation(input, i, out);
      if (negated > 0) {
        return negated;
      }
    }
    if (c == '@' || c == '$') {
      int nullTest = translateNullComparison(input, i, out);
      if (nullTest > 0) {
        return nullTest;
      }
    }
    refuseInOperator(input, i);
    if (c == '\'' || c == '"') {
      out.endPath();
      return translateQuotedLiteral(input, i, out.text());
    }
    if (c == '=' && i + 1 < input.length() && input.charAt(i + 1) == '~') {
      out.endPath();
      return translateRegexOperator(input, i, out.text());
    }
    if (c == '[' && looksLikeFilterOpen(input, i)) {
      return translateFilterExpression(input, i, out);
    }
    out.append(c);
    return 1;
  }

  /**
   * {@code !@.field}: the field has no value, by the configured isnull meaning (as the core,
   * which reads it as {@code @.field == null}). Returns the characters consumed, or 0 when the
   * {@code !} does not negate a path (a negated group {@code !(…)} passes through).
   */
  private int translateNegation(String input, int i, PredicateText out) {
    int path = skipSpaces(input, i + 1);
    if (path >= input.length() || (input.charAt(path) != '@' && input.charAt(path) != '$')) {
      return 0;
    }
    int end = pathEnd(input, path);
    if (looksLikeFilterOpen(input, end)) {
      throw new TmfFilteringException(
          "Negation of array-match expressions is not supported in jsonPath filter.");
    }
    out.endPath();
    out.text().append(noValue(input.substring(path, end)));
    return end - i;
  }

  /**
   * {@code <path> == null} / {@code <path> != null}: the field has no value / a value, by the
   * configured isnull meaning, as the core's {@code IS_NULL} / {@code IS_NOT_NULL}. Returns the
   * characters consumed, or 0 when no such comparison starts at {@code i}.
   */
  private int translateNullComparison(String input, int i, PredicateText out) {
    int end = pathEnd(input, i);
    if (looksLikeFilterOpen(input, end)) {
      return 0;
    }
    int operator = skipSpaces(input, end);
    boolean equal = input.startsWith("==", operator);
    if (!equal && !input.startsWith("!=", operator)) {
      return 0;
    }
    int literal = skipSpaces(input, operator + 2);
    if (!startsWithWord(input, literal, "null")) {
      return 0;
    }
    String path = input.substring(i, end);
    out.endPath();
    out.text().append(equal ? noValue(path) : someValue(path));
    return literal + "null".length() - i;
  }

  /** {@code in} / {@code nin} are not part of the toolkit's filter= grammar: the core's 400. */
  private static void refuseInOperator(String input, int i) {
    boolean wordStart = i == 0 || isSpace(input.charAt(i - 1));
    if (wordStart && (startsWithWord(input, i, "in") || startsWithWord(input, i, "nin"))) {
      throw new TmfFilteringException(
          "Unsupported token in jsonPath filter near: " + input.substring(i));
    }
  }

  private String noValue(String path) {
    return "!(" + someValue(path) + ")";
  }

  private String someValue(String path) {
    return isnullSemantics == IsnullSemantics.NULLISH
        ? EXISTS + path + " ? (@ != null))"
        : EXISTS + path + ")";
  }

  /** End of the path starting at {@code i}: field characters and positional {@code [N]} / {@code [*]}. */
  private static int pathEnd(String s, int i) {
    int j = i + 1;
    while (j < s.length()) {
      char c = s.charAt(j);
      if (isFieldChar(c)) {
        j++;
      } else if (c == '[' && positionalEnd(s, j) > 0) {
        j = positionalEnd(s, j);
      } else {
        break;
      }
    }
    return j;
  }

  /** Index past {@code [digits]} or {@code [*]} at {@code open}, or -1. */
  private static int positionalEnd(String s, int open) {
    int j = open + 1;
    while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '*')) {
      j++;
    }
    return j > open + 1 && j < s.length() && s.charAt(j) == ']' ? j + 1 : -1;
  }

  private static boolean startsWithWord(String s, int i, String word) {
    int end = i + word.length();
    return s.startsWith(word, i) && (end == s.length() || !isFieldChar(s.charAt(end)));
  }

  private int translateQuotedLiteral(String input, int i, StringBuilder out) {
    int end = findStringEnd(input, i);
    out.append('"').append(JsonbStringEscape.escapeForDoubleQuoted(input.substring(i + 1, end))).append('"');
    return end + 1 - i;
  }

  /**
   * Consumes {@code =~ /pattern/flags} starting at the {@code =} and appends
   * {@code like_regex "pattern"} plus {@code flag "i"} when the {@code i} flag is present. The
   * literal grammar is the one the core JsonPath builder accepts: {@code \\/} keeps a literal
   * slash inside the pattern, and trailing letters are flags.
   */
  private int translateRegexOperator(String input, int i, StringBuilder out) {
    if (!regexEnabled) {
      throw new TmfFilteringException("Regex operator is disabled.");
    }
    int open = skipSpaces(input, i + 2);
    if (open >= input.length() || input.charAt(open) != '/') {
      throw new TmfFilteringException("=~ requires a /pattern/ literal in jsonPath filter.");
    }
    int close = regexLiteralEnd(input, open);
    int flagsEnd = close + 1;
    while (flagsEnd < input.length() && Character.isLetter(input.charAt(flagsEnd))) {
      flagsEnd++;
    }
    String flags = input.substring(close + 1, flagsEnd);
    if (!flags.isEmpty() && !"i".equals(flags)) {
      throw new TmfFilteringException(
          "Unsupported regex flags '" + flags + "' in jsonPath filter; supported: i");
    }
    String pattern = input.substring(open + 1, close);
    out.append("like_regex \"")
        .append(JsonbStringEscape.escapeForDoubleQuoted(pattern))
        .append('"');
    if (!flags.isEmpty()) {
      out.append(" flag \"i\"");
    }
    return flagsEnd - i;
  }

  private static int regexLiteralEnd(String s, int open) {
    int i = open + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '/') {
        return i;
      }
      i++;
    }
    throw new TmfFilteringException("Unterminated regex literal in jsonPath filter.");
  }

  /**
   * Consumes {@code [?(...)]} at position {@code i} and appends its SQL/JSON form. The
   * inner-filter body is at {@code [i+3, filterEnd-1)} — from just past {@code [?(} up
   * to but not including the closing {@code )}; {@code filterEnd} points at the closing
   * {@code ]}, so the {@code )} sits one character before it. When the filtered path stands as a
   * predicate, the path and its filter are wrapped in {@code exists(...)}.
   */
  private int translateFilterExpression(String input, int i, PredicateText out) {
    int filterEnd = findMatchingFilterClose(input, i);
    String innerFilter = input.substring(i + 3, filterEnd - 1);
    String filter = "[*] ? (" + translatePredicate(innerFilter) + ")";
    if (out.inPath() && standsAsPredicate(input, filterEnd + 1)) {
      out.closePathInExists(filter);
    } else {
      out.appendToPath(filter);
    }
    return filterEnd + 1 - i;
  }

  /**
   * Whether what follows a {@code [?(...)]} ends the predicate it belongs to: the end of the
   * input, a {@code )}, {@code &&} or {@code ||}. Anything else (an accessor, a comparison)
   * makes the filtered path an operand.
   */
  private static boolean standsAsPredicate(String s, int from) {
    int i = skipSpaces(s, from);
    return i >= s.length() || s.charAt(i) == ')' || s.startsWith("&&", i) || s.startsWith("||", i);
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

  /**
   * The translated text of one predicate, and where the path being written started: the index
   * of its {@code @} or {@code $}, or -1 when no path is being written.
   */
  private static final class PredicateText {

    private final StringBuilder text;
    private int pathStart = -1;

    PredicateText(int capacity) {
      this.text = new StringBuilder(capacity);
    }

    StringBuilder text() {
      return text;
    }

    void append(char c) {
      if (c == '@' || c == '$') {
        pathStart = text.length();
      } else if (!isPathChar(c)) {
        pathStart = -1;
      }
      text.append(c);
    }

    void endPath() {
      pathStart = -1;
    }

    boolean inPath() {
      return pathStart >= 0;
    }

    /** Appends a filter the path continues after, as in {@code @.a[*] ? (...).b}. */
    void appendToPath(String filter) {
      text.append(filter);
    }

    /** Ends the path with {@code filter} and wraps the whole path in {@code exists(...)}. */
    void closePathInExists(String filter) {
      text.insert(pathStart, EXISTS).append(filter).append(')');
      pathStart = -1;
    }

    @Override
    public String toString() {
      return text.toString();
    }

    private static boolean isPathChar(char c) {
      return isFieldChar(c) || c == '[' || c == ']' || c == '*';
    }
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
