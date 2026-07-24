package org.opentmf.query.tmf630.mongo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class JsonPathSortParser {

  public JsonPathSortAst.SortPath parse(String input) {
    if (input == null) {
      throw new IllegalArgumentException("Sort expression must not be null");
    }
    return parseWithOuterWrap(stripWildcards(input.trim()));
  }

  /**
   * Strips canonical JsonPath {@code [*]} segments from the input as a transparent
   * projection sigil. Mongo's path expression syntax already auto-projects fields across
   * arrays, so {@code arr[*].field} and {@code arr.field} are semantically equivalent
   * once translated. Accepting both forms aligns the toolkit with canonical JsonPath
   * tooling (jsonpath.com, Jayway evaluation) where {@code [*]} is the explicit way to
   * project across array elements.
   *
   * <p>Quoted string literals are preserved verbatim — a literal value of {@code '[*]'}
   * inside a predicate is not treated as a projection sigil.
   */
  static String stripWildcards(String input) {
    StringBuilder out = new StringBuilder(input.length());
    char activeQuote = 0;
    int i = 0;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (activeQuote != 0) {
        out.append(c);
        if (c == activeQuote) {
          activeQuote = 0;
        }
        i++;
      } else if (c == '\'' || c == '"') {
        out.append(c);
        activeQuote = c;
        i++;
      } else if (c == '[' && i + 2 < input.length()
          && input.charAt(i + 1) == '*'
          && input.charAt(i + 2) == ']') {
        i += 3;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /**
   * Accepts an outer function call {@code num(...)}, {@code str(...)}, {@code date(...)},
   * {@code min(...)}, or {@code max(...)} enclosing an entire JSONPath sort expression.
   * When present, the wrapped expression is parsed as usual and the resulting leaf is
   * wrapped in the corresponding {@code Coercion} or {@code Aggregator} node. Multiple
   * nested outer wrappers compose recursively. Mirrors the equivalent SimpleRich form
   * so the two grammars compose identically once translated.
   */
  private JsonPathSortAst.SortPath parseWithOuterWrap(String trimmed) {
    OuterCall outer = detectOuterCall(trimmed);
    if (outer == null) {
      return new ParserState(trimmed).parseSortPath();
    }
    String inner = trimmed.substring(outer.openIndex() + 1, trimmed.length() - 1).trim();
    JsonPathSortAst.SortPath innerPath = parseWithOuterWrap(inner);
    return new JsonPathSortAst.SortPath(innerPath.hops(), outer.wrap(innerPath.leaf()));
  }

  private static OuterCall detectOuterCall(String s) {
    if (s.length() < 5 || !s.endsWith(")")) {
      return null;
    }
    OuterCallKind kind;
    int openIndex;
    if (s.startsWith("num(")) {
      kind = OuterCallKind.NUM;
      openIndex = 3;
    } else if (s.startsWith("str(")) {
      kind = OuterCallKind.STR;
      openIndex = 3;
    } else if (s.startsWith("min(")) {
      kind = OuterCallKind.MIN;
      openIndex = 3;
    } else if (s.startsWith("max(")) {
      kind = OuterCallKind.MAX;
      openIndex = 3;
    } else if (s.startsWith("date(")) {
      kind = OuterCallKind.DATE;
      openIndex = 4;
    } else {
      return null;
    }
    return outerCallSpansEntireExpression(s, openIndex) ? new OuterCall(kind, openIndex) : null;
  }

  private static boolean outerCallSpansEntireExpression(String s, int openIndex) {
    int depth = 0;
    char activeQuote = 0;
    for (int i = openIndex; i < s.length(); i++) {
      char c = s.charAt(i);
      if (activeQuote != 0) {
        if (c == activeQuote) {
          activeQuote = 0;
        }
      } else if (c == '\'' || c == '"') {
        activeQuote = c;
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return i == s.length() - 1;
        }
      }
    }
    return false;
  }

  private enum OuterCallKind {
    NUM,
    STR,
    DATE,
    MIN,
    MAX
  }

  private record OuterCall(OuterCallKind kind, int openIndex) {

    JsonPathSortAst.LeafExpression wrap(JsonPathSortAst.LeafExpression inner) {
      return switch (kind) {
        case NUM -> new JsonPathSortAst.Coercion(JsonPathSortAst.CoercionType.NUM, inner);
        case STR -> new JsonPathSortAst.Coercion(JsonPathSortAst.CoercionType.STR, inner);
        case DATE -> new JsonPathSortAst.Coercion(JsonPathSortAst.CoercionType.DATE, inner);
        case MIN -> new JsonPathSortAst.Aggregator(JsonPathSortAst.AggregatorOp.MIN, inner);
        case MAX -> new JsonPathSortAst.Aggregator(JsonPathSortAst.AggregatorOp.MAX, inner);
      };
    }
  }

  private static final class ParserState {

    private final String input;
    private int pos;

    ParserState(String input) {
      this.input = input;
      this.pos = 0;
    }

    JsonPathSortAst.SortPath parseSortPath() {
      // The TMF630 recommendation allows omitting the leading `$.`. We accept either
      // form: `$.arr[?(@.id == 'X')].value` and `arr[?(@.id == 'X')].value` parse to
      // the same SortPath.
      skipWhitespace();
      if (peek("$.")) {
        consume(2);
      }
      List<JsonPathSortAst.ArrayHop> hops = new ArrayList<>();
      while (true) {
        String dottedId = readDottedIdentifier();
        if (peek("[?(")) {
          hops.add(readPredicateHop(dottedId));
        } else if (peekPositionalIndex()) {
          hops.add(readPositionalHop(dottedId));
        } else {
          return finishWithTrailingLeaf(hops, dottedId);
        }
      }
    }

    private JsonPathSortAst.ArrayHop readPredicateHop(String dottedId) {
      consume(3);
      JsonPathSortAst.Predicate predicate = parseOr();
      skipWhitespace();
      expect(")]");
      if (pos == input.length()) {
        throw new IllegalArgumentException(
            "Sort path is missing a trailing leaf field after the predicate: " + input);
      }
      expect(".");
      return new JsonPathSortAst.ArrayHop(dottedId, predicate);
    }

    // TMF630 Part 6 JSONPath 0-based index access: [N] picks the literal Nth
    // element. Modeled as a hop with AlwaysTruePredicate plus the index, so the
    // translator selects $arrayElemAt instead of $first — never the min/max fold.
    private JsonPathSortAst.ArrayHop readPositionalHop(String dottedId) {
      int index = readPositionalIndex();
      if (pos == input.length()) {
        throw new IllegalArgumentException(
            "Sort path is missing a trailing leaf field after the [N] index: " + input);
      }
      expect(".");
      return new JsonPathSortAst.ArrayHop(
          dottedId, JsonPathSortAst.AlwaysTruePredicate.INSTANCE, index);
    }

    // A dotted identifier may be followed by a single balanced parenthesised group
    // forming a function-call leaf: `num(value)`, `productSpec.num(value)`,
    // `num(min(value))`, etc. We consume the parens here so `splitTrailing` can
    // decide whether the trailing path is a plain dotted reference or a function
    // call wrapping a leaf expression.
    private JsonPathSortAst.SortPath finishWithTrailingLeaf(
        List<JsonPathSortAst.ArrayHop> hops, String dottedId) {
      String trailing = dottedId + readBalancedFunctionCall();
      if (hops.isEmpty()) {
        throw new IllegalArgumentException(
            "JsonPath sort term must contain at least one [?(...)] correlation predicate"
                + " or [N] index hop: "
                + input);
      }
      skipWhitespace();
      if (pos != input.length()) {
        throw new IllegalArgumentException(
            "Unexpected trailing input in sort expression: " + input.substring(pos));
      }
      return splitTrailing(hops, trailing);
    }

    private boolean peekPositionalIndex() {
      return pos + 1 < input.length()
          && input.charAt(pos) == '['
          && Character.isDigit(input.charAt(pos + 1));
    }

    private int readPositionalIndex() {
      consume(1);
      int start = pos;
      while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
        pos++;
      }
      String digits = input.substring(start, pos);
      expect("]");
      return Integer.parseInt(digits);
    }

    private String readBalancedFunctionCall() {
      if (pos >= input.length() || input.charAt(pos) != '(') {
        return "";
      }
      int start = pos;
      int depth = 1;
      pos++;
      while (pos < input.length() && depth > 0) {
        char c = input.charAt(pos);
        if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
        }
        pos++;
      }
      if (depth != 0) {
        throw new IllegalArgumentException(
            "Unbalanced parentheses in trailing leaf in: " + input);
      }
      return input.substring(start, pos);
    }

    private JsonPathSortAst.SortPath splitTrailing(
        List<JsonPathSortAst.ArrayHop> explicitHops, String trailing) {
      return LeafPathSplitter.splitTrailing(explicitHops, trailing, input);
    }

    JsonPathSortAst.Predicate parseOr() {
      JsonPathSortAst.Predicate left = parseAnd();
      while (true) {
        skipWhitespace();
        if (peek("||")) {
          consume(2);
          JsonPathSortAst.Predicate right = parseAnd();
          left = new JsonPathSortAst.OrPredicate(left, right);
        } else {
          return left;
        }
      }
    }

    JsonPathSortAst.Predicate parseAnd() {
      JsonPathSortAst.Predicate left = parseComparison();
      while (true) {
        skipWhitespace();
        if (peek("&&")) {
          consume(2);
          JsonPathSortAst.Predicate right = parseComparison();
          left = new JsonPathSortAst.AndPredicate(left, right);
        } else {
          return left;
        }
      }
    }

    JsonPathSortAst.Predicate parseComparison() {
      skipWhitespace();
      expect("@.");
      String fieldPath = readDottedIdentifier();
      skipWhitespace();
      JsonPathSortAst.ComparisonOperator op = readOperator();
      skipWhitespace();
      JsonPathSortAst.Literal literal = readLiteral();
      return new JsonPathSortAst.ComparisonPredicate(fieldPath, op, literal);
    }

    JsonPathSortAst.ComparisonOperator readOperator() {
      if (peek("==")) {
        consume(2);
        return JsonPathSortAst.ComparisonOperator.EQ;
      }
      if (peek("!=")) {
        consume(2);
        return JsonPathSortAst.ComparisonOperator.NE;
      }
      if (peek(">=")) {
        consume(2);
        return JsonPathSortAst.ComparisonOperator.GTE;
      }
      if (peek("<=")) {
        consume(2);
        return JsonPathSortAst.ComparisonOperator.LTE;
      }
      if (peek(">")) {
        consume(1);
        return JsonPathSortAst.ComparisonOperator.GT;
      }
      if (peek("<")) {
        consume(1);
        return JsonPathSortAst.ComparisonOperator.LT;
      }
      throw new IllegalArgumentException(
          "Expected comparison operator at position " + pos + " in: " + input);
    }

    JsonPathSortAst.Literal readLiteral() {
      if (pos >= input.length()) {
        throw new IllegalArgumentException("Expected literal at end of input: " + input);
      }
      char c = input.charAt(pos);
      if (c == '\'' || c == '"') {
        return readStringLiteral(c);
      }
      if (peek("true")) {
        consume(4);
        return new JsonPathSortAst.BooleanLiteral(true);
      }
      if (peek("false")) {
        consume(5);
        return new JsonPathSortAst.BooleanLiteral(false);
      }
      if (peek("null")) {
        consume(4);
        return new JsonPathSortAst.NullLiteral();
      }
      return readNumberLiteral();
    }

    private JsonPathSortAst.StringLiteral readStringLiteral(char openQuote) {
      consume(1);
      int end = input.indexOf(openQuote, pos);
      if (end < 0) {
        throw new IllegalArgumentException("Unterminated string literal in: " + input);
      }
      String value = input.substring(pos, end);
      pos = end + 1;
      return new JsonPathSortAst.StringLiteral(value);
    }

    private JsonPathSortAst.NumberLiteral readNumberLiteral() {
      int start = pos;
      if (pos < input.length() && input.charAt(pos) == '-') {
        pos++;
      }
      while (pos < input.length()
          && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
        pos++;
      }
      String slice = input.substring(start, pos);
      if (slice.isEmpty() || slice.equals("-")) {
        throw new IllegalArgumentException(
            "Expected literal at position " + start + " in: " + input);
      }
      try {
        return new JsonPathSortAst.NumberLiteral(new BigDecimal(slice));
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
            "Malformed numeric literal '" + slice + "' in: " + input, ex);
      }
    }

    String readDottedIdentifier() {
      skipWhitespace();
      int start = pos;
      while (pos < input.length()) {
        char c = input.charAt(pos);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
          pos++;
        } else {
          break;
        }
      }
      if (start == pos) {
        throw new IllegalArgumentException(
            "Expected identifier at position " + start + " in: " + input);
      }
      String id = input.substring(start, pos);
      if (id.startsWith(".") || id.endsWith(".") || id.contains("..")) {
        throw new IllegalArgumentException("Malformed identifier '" + id + "' in: " + input);
      }
      return id;
    }

    void expect(String token) {
      skipWhitespace();
      if (!peek(token)) {
        throw new IllegalArgumentException(
            "Expected '" + token + "' at position " + pos + " in: " + input);
      }
      consume(token.length());
    }

    boolean peek(String token) {
      return input.regionMatches(pos, token, 0, token.length());
    }

    void consume(int n) {
      pos += n;
    }

    void skipWhitespace() {
      while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
        pos++;
      }
    }
  }
}
