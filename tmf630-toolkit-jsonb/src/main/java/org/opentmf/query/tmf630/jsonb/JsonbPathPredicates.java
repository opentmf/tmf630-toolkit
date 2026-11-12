package org.opentmf.query.tmf630.jsonb;

import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.filtering.TmfOperator;

/**
 * URL attribute operators on a field path that crosses an array, ends in a collection, or leads
 * into a split collection (since 3.6.0). Each answers for ANY element, as the Mongo and JPA
 * backends and {@code filter=} do:
 *
 * <ul>
 *   <li>{@code EQ} and {@code IN} on a type with a containment fragment are containment through
 *       the arrays, {@code payload @> '{"arr":[{"s":"x"}]}'}, which a GIN index on the payload
 *       serves;
 *   <li>every other operator is a nested {@code EXISTS} over {@code jsonb_array_elements}, one per
 *       array step, with the operator's scalar form on the element; a value where an array is
 *       declared holds no element, and raises no error;
 *   <li>the elements of a collection leaf are its values;
 *   <li>{@code isnotnull} is "some element holds the field", {@code isnull} its negation;
 *   <li>a path into a split collection is answered in the child table: {@code EXISTS (SELECT 1 FROM
 *       child WHERE parent_id = parent.id AND …)}.
 * </ul>
 */
final class JsonbPathPredicates {

  /** The child table that holds a split collection of the parent. */
  record SplitChild(
      String childTable, String parentIdColumn, String parentTable, String payloadColumn) {}

  /** The operator's form on a path without array steps before its leaf, on one column. */
  @FunctionalInterface
  private interface Leaf {
    JsonbClause on(JsonbPredicateFactory factory, JsonbFieldPath path);
  }

  private final JsonbPredicateFactory parent;

  JsonbPathPredicates(JsonbPredicateFactory parent) {
    this.parent = parent;
  }

  JsonbClause single(
      TmfOperator operator, JsonbFieldPath path, Class<?> type, Object value, SplitChild split) {
    Leaf leaf = (factory, rest) -> factory.buildOnObjectPath(operator, rest.dotted(), type, value);
    return answer(path, split, true, leaf, operator == TmfOperator.EQ ? List.of(value) : null, type);
  }

  JsonbClause multi(
      TmfOperator operator,
      JsonbFieldPath path,
      Class<?> type,
      List<Object> values,
      SplitChild split) {
    Leaf leaf =
        (factory, rest) -> factory.buildMultiOnObjectPath(operator, rest.dotted(), type, values);
    return answer(path, split, true, leaf, operator == TmfOperator.IN ? values : null, type);
  }

  JsonbClause noValue(TmfOperator operator, JsonbFieldPath path, SplitChild split) {
    Leaf someHolds = (factory, rest) -> factory.buildNoValue(TmfOperator.IS_NOT_NULL, rest.dotted());
    JsonbClause some = answer(path, split, false, someHolds, null, null);
    return operator == TmfOperator.IS_NOT_NULL ? some : some.not();
  }

  /**
   * The operator on {@code path}, in the split child when {@code split} is set. {@code anyOf},
   * when set, are the values whose equality containment may answer directly.
   */
  private JsonbClause answer(
      JsonbFieldPath path,
      SplitChild split,
      boolean intoLeaf,
      Leaf leaf,
      List<Object> anyOf,
      Class<?> type) {
    if (split == null) {
      return containment(parent, path, anyOf, type)
          .orElseGet(() -> within(parent, path, intoLeaf, leaf, 0));
    }
    JsonbPredicateFactory child = parent.forColumn(split.payloadColumn());
    JsonbFieldPath rest = path.tail(1);
    JsonbClause inner =
        containment(child, rest, anyOf, type).orElseGet(() -> within(child, rest, intoLeaf, leaf, 0));
    return JsonbClause.of(
        "EXISTS (SELECT 1 FROM " + split.childTable() + " WHERE " + split.parentIdColumn() + " = "
            + split.parentTable() + ".id AND " + inner.sql() + ")",
        inner.params().toArray());
  }

  /** {@code path = v} for every v of {@code anyOf} as containment, ORed; empty when one has none. */
  private static Optional<JsonbClause> containment(
      JsonbPredicateFactory factory, JsonbFieldPath path, List<Object> anyOf, Class<?> type) {
    if (anyOf == null || anyOf.isEmpty()) {
      return Optional.empty();
    }
    JsonbClause any = JsonbClause.alwaysFalse();
    for (Object value : anyOf) {
      Optional<JsonbClause> one = factory.containmentThrough(path, type, value);
      if (one.isEmpty()) {
        return Optional.empty();
      }
      any = any.or(one.get());
    }
    return Optional.of(any);
  }

  /** The leaf answered for any element of each array step of {@code path}, on one column. */
  private static JsonbClause within(
      JsonbPredicateFactory factory, JsonbFieldPath path, boolean intoLeaf, Leaf leaf, int depth) {
    int arrayAt = path.firstArray(intoLeaf);
    if (arrayAt < 0) {
      return leaf.on(factory, path);
    }
    String array = factory.extractor().extractAsJsonb(path.head(arrayAt + 1));
    String elements =
        "jsonb_array_elements(CASE WHEN jsonb_typeof(" + array + ") = 'array' THEN " + array
            + " ELSE '[]'::jsonb END)";
    String alias = "e" + (depth + 1);
    JsonbClause inner;
    String from;
    if (arrayAt == path.size() - 1) {
      // a collection leaf: each element, as {"v": element}, is a value
      String element = "x" + (depth + 1);
      inner = leaf.on(factory.forColumn(alias), new JsonbFieldPath(List.of("v"), List.of(false)));
      from = "(SELECT jsonb_build_object('v', " + element + ") AS " + alias + " FROM "
          + elements + " AS " + element
          + ") AS t" + (depth + 1);
    } else {
      inner = within(factory.forColumn(alias), path.tail(arrayAt + 1), intoLeaf, leaf, depth + 1);
      from = elements + " AS " + alias;
    }
    return JsonbClause.of(
        "EXISTS (SELECT 1 FROM " + from + " WHERE " + inner.sql() + ")", inner.params().toArray());
  }
}
