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
          pos++;
          JsonPathSortAst.ComparisonPredicate predicate = parseBracketPredicate();
          if (pos >= input.length() || input.charAt(pos) != ']') {
            throw new IllegalArgumentException(
                "Expected ']' at position " + pos + " in: " + input);
          }
          pos++;
          explicitHops.add(new JsonPathSortAst.ArrayHop(dottedOrCall, predicate));
          if (pos == input.length()) {
            throw new IllegalArgumentException(
                "Sort path is missing a trailing leaf field after the final hop: " + input);
          }
          if (input.charAt(pos) != '.') {
            throw new IllegalArgumentException(
                "Expected '.' after ']' at position " + pos + " in: " + input);
          }
          pos++;
        } else {
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
      }
    }

    private JsonPathSortAst.SortPath splitTrailing(
        List<JsonPathSortAst.ArrayHop> explicitHops, String trailing) {
      List<String> segments = splitDepthAware(trailing);
      if (segments.isEmpty() || (segments.size() == 1 && segments.get(0).isEmpty())) {
        throw new IllegalArgumentException(
            "Sort path is missing a trailing leaf field after the final hop: " + input);
      }
      String lastSegment = segments.get(segments.size() - 1);

      if (lastSegment.indexOf('(') < 0) {
        // No function call at the leaf — treat the entire trailing path as a single
        // FieldRef. The translator emits "$$mN.<entire-path>" and Mongo handles object
        // traversal AND array auto-projection naturally. No naked hops are introduced.
        for (int i = 0; i < segments.size() - 1; i++) {
          if (segments.get(i).indexOf('(') >= 0) {
            throw new IllegalArgumentException(
                "Function calls are only allowed as the leaf segment, not before more path: "
                    + segments.get(i));
          }
        }
        return new JsonPathSortAst.SortPath(
            explicitHops, new JsonPathSortAst.FieldRef(trailing));
      }

      // Function call at the leaf. Two sub-cases:
      //   1. The function chain contains an Aggregator (min/max). The aggregator
      //      needs to $map across an array, so pre-function segments are
      //      promoted to naked ArrayHops with AlwaysTruePredicate. The
      //      translator then iterates the right array.
      //   2. The function chain is pure Coercion (num/str/date). The translator
      //      handles array intermediates inside Coercion(FieldRef) by emitting
      //      $map+$convert+$min/$max at the leaf, so naked hops are unnecessary
      //      AND counter-productive (they would crash on object intermediates
      //      via $filter on a non-array). Pre-function segments are kept as
      //      part of the leaf's FieldRef path instead.
      for (int i = 0; i < segments.size() - 1; i++) {
        if (segments.get(i).indexOf('(') >= 0) {
          throw new IllegalArgumentException(
              "Function call segments are only allowed as the leaf, not before more path: "
                  + segments.get(i));
        }
      }
      JsonPathSortAst.LeafExpression leaf = parseLeafExpression(lastSegment);
      if (segments.size() == 1) {
        return new JsonPathSortAst.SortPath(explicitHops, leaf);
      }
      String prePath = String.join(".", segments.subList(0, segments.size() - 1));
      if (containsAggregator(leaf)) {
        List<JsonPathSortAst.ArrayHop> all = new ArrayList<>(explicitHops);
        for (String segment : segments.subList(0, segments.size() - 1)) {
          all.add(
              new JsonPathSortAst.ArrayHop(
                  segment, JsonPathSortAst.AlwaysTruePredicate.INSTANCE));
        }
        return new JsonPathSortAst.SortPath(all, leaf);
      }
      return new JsonPathSortAst.SortPath(explicitHops, prependPath(leaf, prePath));
    }

    private static boolean containsAggregator(JsonPathSortAst.LeafExpression leaf) {
      if (leaf instanceof JsonPathSortAst.Aggregator) {
        return true;
      }
      if (leaf instanceof JsonPathSortAst.Coercion c) {
        return containsAggregator(c.inner());
      }
      return false;
    }

    private static JsonPathSortAst.LeafExpression prependPath(
        JsonPathSortAst.LeafExpression leaf, String prefix) {
      if (leaf instanceof JsonPathSortAst.FieldRef fr) {
        return new JsonPathSortAst.FieldRef(prefix + "." + fr.fieldPath());
      }
      if (leaf instanceof JsonPathSortAst.Coercion c) {
        return new JsonPathSortAst.Coercion(c.type(), prependPath(c.inner(), prefix));
      }
      throw new IllegalStateException(
          "Aggregator must not reach prependPath (would have taken the naked-hop branch): "
              + leaf);
    }

    private static List<String> splitDepthAware(String s) {
      List<String> parts = new ArrayList<>();
      int depth = 0;
      int start = 0;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '(') {
          depth++;
        } else if (c == ')') {
          if (depth == 0) {
            throw new IllegalArgumentException(
                "Unbalanced ')' in segment: " + s);
          }
          depth--;
        } else if (c == '.' && depth == 0) {
          parts.add(s.substring(start, i));
          start = i + 1;
        }
      }
      if (depth != 0) {
        throw new IllegalArgumentException("Unbalanced '(' in segment: " + s);
      }
      parts.add(s.substring(start));
      return parts;
    }

    private static JsonPathSortAst.LeafExpression parseLeafExpression(String text) {
      if (text.isEmpty()) {
        throw new IllegalArgumentException("Empty leaf expression");
      }
      int paren = text.indexOf('(');
      if (paren < 0) {
        return new JsonPathSortAst.FieldRef(text);
      }
      if (!text.endsWith(")")) {
        throw new IllegalArgumentException(
            "Function call must end with ')' in leaf expression: " + text);
      }
      String fnName = text.substring(0, paren);
      String inner = text.substring(paren + 1, text.length() - 1);
      JsonPathSortAst.LeafExpression innerExpr = parseLeafExpression(inner);
      return switch (fnName) {
        case "max" -> new JsonPathSortAst.Aggregator(
            JsonPathSortAst.AggregatorOp.MAX, innerExpr);
        case "min" -> new JsonPathSortAst.Aggregator(
            JsonPathSortAst.AggregatorOp.MIN, innerExpr);
        case "str" -> new JsonPathSortAst.Coercion(
            JsonPathSortAst.CoercionType.STR, innerExpr);
        case "num" -> new JsonPathSortAst.Coercion(
            JsonPathSortAst.CoercionType.NUM, innerExpr);
        case "date" -> new JsonPathSortAst.Coercion(
            JsonPathSortAst.CoercionType.DATE, innerExpr);
        default ->
            throw new IllegalArgumentException(
                "Unknown function '"
                    + fnName
                    + "' in leaf; supported: max, min, str, num, date");
      };
    }

    private String readPathSegment() {
      int start = pos;
      while (pos < input.length()) {
        char c = input.charAt(pos);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') {
          pos++;
        } else if (c == '(') {
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
                "Unbalanced parentheses starting at position " + start + " in: " + input);
          }
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
