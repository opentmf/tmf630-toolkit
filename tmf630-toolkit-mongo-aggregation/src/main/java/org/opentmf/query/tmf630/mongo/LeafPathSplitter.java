package org.opentmf.query.tmf630.mongo;

import java.util.ArrayList;
import java.util.List;

final class LeafPathSplitter {

  private LeafPathSplitter() {}

  static JsonPathSortAst.SortPath splitTrailing(
      List<JsonPathSortAst.ArrayHop> explicitHops, String trailing, String input) {
    List<String> segments = splitDepthAware(trailing);
    if (segments.isEmpty() || (segments.size() == 1 && segments.get(0).isEmpty())) {
      throw new IllegalArgumentException(
          "Sort path is missing a trailing leaf field after the final hop: " + input);
    }
    rejectPreLeafFunctionCalls(segments);
    String lastSegment = segments.get(segments.size() - 1);

    if (lastSegment.indexOf('(') < 0) {
      // No function call at the leaf — treat the entire trailing path as a single
      // FieldRef. The translator emits "$$mN.<entire-path>" and Mongo handles object
      // traversal AND array auto-projection naturally. No naked hops are introduced.
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
    JsonPathSortAst.LeafExpression leaf = parseLeafExpression(lastSegment);
    if (segments.size() == 1) {
      return new JsonPathSortAst.SortPath(explicitHops, leaf);
    }
    List<String> preSegments = segments.subList(0, segments.size() - 1);
    if (containsAggregator(leaf)) {
      List<JsonPathSortAst.ArrayHop> all = new ArrayList<>(explicitHops);
      for (String segment : preSegments) {
        all.add(
            new JsonPathSortAst.ArrayHop(
                segment, JsonPathSortAst.AlwaysTruePredicate.INSTANCE));
      }
      return new JsonPathSortAst.SortPath(all, leaf);
    }
    return new JsonPathSortAst.SortPath(
        explicitHops, prependPath(leaf, String.join(".", preSegments)));
  }

  private static void rejectPreLeafFunctionCalls(List<String> segments) {
    for (int i = 0; i < segments.size() - 1; i++) {
      if (segments.get(i).indexOf('(') >= 0) {
        throw new IllegalArgumentException(
            "Function call segments are only allowed as the leaf, not before more path: "
                + segments.get(i));
      }
    }
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
          throw new IllegalArgumentException("Unbalanced ')' in segment: " + s);
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
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Empty leaf expression");
    }
    int paren = trimmed.indexOf('(');
    if (paren < 0) {
      return new JsonPathSortAst.FieldRef(trimmed);
    }
    if (!trimmed.endsWith(")")) {
      throw new IllegalArgumentException(
          "Function call must end with ')' in leaf expression: " + trimmed);
    }
    String fnName = trimmed.substring(0, paren);
    String inner = trimmed.substring(paren + 1, trimmed.length() - 1);
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
}
