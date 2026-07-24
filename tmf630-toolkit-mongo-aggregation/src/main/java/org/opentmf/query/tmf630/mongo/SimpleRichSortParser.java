package org.opentmf.query.tmf630.mongo;

import java.util.ArrayList;
import java.util.List;

public final class SimpleRichSortParser {

  private final String defaultKey;

  public SimpleRichSortParser(String defaultKey) {
    if (defaultKey == null || defaultKey.isBlank()) {
      throw new IllegalArgumentException("defaultKey must not be null or blank");
    }
    this.defaultKey = defaultKey;
  }

  public JsonPathSortAst.SortPath parse(String input) {
    if (input == null) {
      throw new IllegalArgumentException("Sort expression must not be null");
    }
    return parseWithOuterCoercion(input.trim());
  }

  /**
   * Accepts an outer coercion wrapper {@code num(...)}, {@code str(...)}, or
   * {@code date(...)} enclosing an entire simple-rich expression. TMF630 §4.7
   * permits the wrapper to sit either on the leaf segment or wrap the whole sort
   * term. When present, the wrapped expression is parsed as usual and the
   * resulting leaf is composed inside the coercion. Multiple nested outer
   * wrappers compose recursively.
   */
  private JsonPathSortAst.SortPath parseWithOuterCoercion(String trimmed) {
    JsonPathSortAst.CoercionType outerCoercion = detectOuterCoercion(trimmed);
    if (outerCoercion == null) {
      return new ParserState(trimmed, defaultKey).parseSortPath();
    }
    String inner = stripOuterCall(trimmed).trim();
    JsonPathSortAst.SortPath innerPath = parseWithOuterCoercion(inner);
    return new JsonPathSortAst.SortPath(
        innerPath.hops(), new JsonPathSortAst.Coercion(outerCoercion, innerPath.leaf()));
  }

  private static JsonPathSortAst.CoercionType detectOuterCoercion(String s) {
    if (s.length() < 5 || !s.endsWith(")")) {
      return null;
    }
    JsonPathSortAst.CoercionType type;
    int openIndex;
    if (s.startsWith("num(")) {
      type = JsonPathSortAst.CoercionType.NUM;
      openIndex = 3;
    } else if (s.startsWith("str(")) {
      type = JsonPathSortAst.CoercionType.STR;
      openIndex = 3;
    } else if (s.startsWith("date(")) {
      type = JsonPathSortAst.CoercionType.DATE;
      openIndex = 4;
    } else {
      return null;
    }
    return outerCallSpansEntireExpression(s, openIndex) ? type : null;
  }

  private static boolean outerCallSpansEntireExpression(String s, int openIndex) {
    int depth = 0;
    for (int i = openIndex; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') {
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

  private static String stripOuterCall(String s) {
    int open = s.indexOf('(');
    return s.substring(open + 1, s.length() - 1);
  }

  private static final class ParserState {

    private final String input;
    private final String defaultKey;
    private int pos;

    ParserState(String input, String defaultKey) {
      this.input = input;
      this.defaultKey = defaultKey;
      this.pos = 0;
    }

    JsonPathSortAst.SortPath parseSortPath() {
      List<JsonPathSortAst.ArrayHop> explicitHops = new ArrayList<>();
      while (true) {
        String dottedOrCall = readPathSegment();
        if (pos < input.length() && input.charAt(pos) == '[') {
          explicitHops.add(readBracketHop(dottedOrCall));
        } else {
          return finishWithTrailingLeaf(explicitHops, dottedOrCall);
        }
      }
    }

    private JsonPathSortAst.ArrayHop readBracketHop(String dottedOrCall) {
      pos++;
      JsonPathSortAst.ComparisonPredicate predicate = parseBracketPredicate();
      if (pos >= input.length() || input.charAt(pos) != ']') {
        throw new IllegalArgumentException(
            "Expected ']' at position " + pos + " in: " + input);
      }
      pos++;
      if (pos == input.length()) {
        throw new IllegalArgumentException(
            "Sort path is missing a trailing leaf field after the final hop: " + input);
      }
      if (input.charAt(pos) != '.') {
        throw new IllegalArgumentException(
            "Expected '.' after ']' at position " + pos + " in: " + input);
      }
      pos++;
      return new JsonPathSortAst.ArrayHop(dottedOrCall, predicate);
    }

    private JsonPathSortAst.SortPath finishWithTrailingLeaf(
        List<JsonPathSortAst.ArrayHop> explicitHops, String dottedOrCall) {
      if (explicitHops.isEmpty()) {
        throw new IllegalArgumentException(
            "Simple-rich sort term must contain at least one [...] hop: " + input);
      }
      if (pos != input.length()) {
        throw new IllegalArgumentException(
            "Unexpected trailing input in sort expression: " + input.substring(pos));
      }
      return splitTrailing(explicitHops, dottedOrCall);
    }

    private JsonPathSortAst.SortPath splitTrailing(
        List<JsonPathSortAst.ArrayHop> explicitHops, String trailing) {
      return LeafPathSplitter.splitTrailing(explicitHops, trailing, input);
    }

    private String readPathSegment() {
      int start = pos;
      while (pos < input.length()) {
        char c = input.charAt(pos);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') {
          pos++;
        } else if (c == '(') {
          skipBalancedParens(start);
        } else {
          break;
        }
      }
      if (start == pos) {
        throw new IllegalArgumentException(
            "Expected identifier at position " + start + " in: " + input);
      }
      String segment = input.substring(start, pos);
      if (segment.indexOf('(') < 0
          && (segment.startsWith(".")
              || segment.endsWith(".")
              || segment.contains(".."))) {
        throw new IllegalArgumentException("Malformed identifier '" + segment + "' in: " + input);
      }
      return segment;
    }

    private void skipBalancedParens(int segmentStart) {
      int depth = 1;
      pos++;
      while (pos < input.length() && depth > 0) {
        char ch = input.charAt(pos);
        if (ch == '(') {
          depth++;
        } else if (ch == ')') {
          depth--;
        }
        pos++;
      }
      if (depth != 0) {
        throw new IllegalArgumentException(
            "Unbalanced parentheses starting at position " + segmentStart + " in: " + input);
      }
    }

    private JsonPathSortAst.ComparisonPredicate parseBracketPredicate() {
      String content = readBracketContent();
      int eq = content.indexOf('=');
      String key;
      String val;
      if (eq < 0) {
        key = defaultKey;
        val = content;
      } else {
        key = content.substring(0, eq);
        val = content.substring(eq + 1);
      }
      validateBareToken(key, "key");
      validateBareToken(val, "value");
      return new JsonPathSortAst.ComparisonPredicate(
          key,
          JsonPathSortAst.ComparisonOperator.EQ,
          new JsonPathSortAst.StringLiteral(val));
    }

    private String readBracketContent() {
      int start = pos;
      while (pos < input.length() && input.charAt(pos) != ']') {
        pos++;
      }
      return input.substring(start, pos);
    }

    private static void validateBareToken(String token, String role) {
      if (token.isEmpty()) {
        throw new IllegalArgumentException(
            "Simple-rich " + role + " in [...] must not be empty");
      }
      for (int i = 0; i < token.length(); i++) {
        char c = token.charAt(i);
        if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) {
          throw new IllegalArgumentException(
              "Simple-rich "
                  + role
                  + " in [...] contains an invalid character '"
                  + c
                  + "' (allowed: A-Z a-z 0-9 _ . -); use the JsonPath form for richer expressions");
        }
      }
    }

  }
}
