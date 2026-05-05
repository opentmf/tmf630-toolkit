package org.opentmf.query.tmf630.mongo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class JsonPathSortParser {

  public JsonPathSortAst.SortPath parse(String input) {
    if (input == null) {
      throw new IllegalArgumentException("Sort expression must not be null");
    }
    return new ParserState(stripWildcards(input.trim())).parseSortPath();
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
    boolean inQuotes = false;
    int i = 0;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (inQuotes) {
        out.append(c);
        if (c == '\'') {
          inQuotes = false;
        }
        i++;
        continue;
      }
      if (c == '\'') {
        out.append(c);
        inQuotes = true;
        i++;
        continue;
      }
      if (c == '[' && i + 2 < input.length()
          && input.charAt(i + 1) == '*'
          && input.charAt(i + 2) == ']') {
        i += 3;
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
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
          consume(3);
          JsonPathSortAst.Predicate predicate = parseOr();
          skipWhitespace();
          expect(")]");
          hops.add(new JsonPathSortAst.ArrayHop(dottedId, predicate));
          if (pos == input.length()) {
            throw new IllegalArgumentException(
                "Sort path is missing a trailing leaf field after the predicate: " + input);
          }
          expect(".");
        } else {
          if (hops.isEmpty()) {
            throw new IllegalArgumentException(
                "JsonPath sort term must contain at least one [?(...)] correlation predicate: "
                    + input);
          }
          skipWhitespace();
          if (pos != input.length()) {
            throw new IllegalArgumentException(
                "Unexpected trailing input in sort expression: " + input.substring(pos));
          }
          return new JsonPathSortAst.SortPath(hops, new JsonPathSortAst.FieldRef(dottedId));
        }
      }
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
      if (c == '\'') {
        consume(1);
        int end = input.indexOf('\'', pos);
        if (end < 0) {
          throw new IllegalArgumentException("Unterminated string literal in: " + input);
        }
        String value = input.substring(pos, end);
        pos = end + 1;
        return new JsonPathSortAst.StringLiteral(value);
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
