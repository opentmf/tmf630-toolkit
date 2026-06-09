package org.opentmf.query.tmf630.mongo;

import java.util.Arrays;
import java.util.List;
import org.bson.Document;

public final class AggregationKeyTranslator {

  /** Mongo aggregation operator used to fold an array-valued leaf to a scalar. */
  public static final String MIN_REDUCER = "$min";

  /** Mongo aggregation operator used to fold an array-valued leaf to a scalar. */
  public static final String MAX_REDUCER = "$max";

  private AggregationKeyTranslator() {}

  public static Document translate(JsonPathSortAst.SortPath sortPath) {
    return translate(sortPath, MongoFieldResolver.passthrough(), null, MIN_REDUCER);
  }

  public static Document translate(
      JsonPathSortAst.SortPath sortPath, MongoFieldResolver resolver, Class<?> rootEntity) {
    return translate(sortPath, resolver, rootEntity, MIN_REDUCER);
  }

  /**
   * Translates a parsed sort path to a Mongo aggregation expression suitable for use
   * as a synthetic {@code _sortKeyN} field.
   *
   * <p>{@code leafArrayReducerOp} controls how a leaf path that crosses a
   * collection-typed intermediate is folded back to a scalar. Pass {@link #MIN_REDUCER}
   * for ASC sort terms and {@link #MAX_REDUCER} for DESC, mirroring MongoDB's native
   * array-key {@code $sort} semantics (which used to apply implicitly before 2.1.2
   * wrapped the leaf as a scalar to fix the parallel-arrays crash). The reducer is
   * only applied when {@link MongoFieldResolver#hasArrayIntermediate} flags the leaf
   * path; for purely scalar leaves the expression stays unchanged.
   */
  public static Document translate(
      JsonPathSortAst.SortPath sortPath,
      MongoFieldResolver resolver,
      Class<?> rootEntity,
      String leafArrayReducerOp) {
    return translateHop(
        sortPath.hops(), 0, sortPath.leaf(), resolver, rootEntity, leafArrayReducerOp);
  }

  private static Document translateHop(
      List<JsonPathSortAst.ArrayHop> hops,
      int idx,
      JsonPathSortAst.LeafExpression leaf,
      MongoFieldResolver resolver,
      Class<?> currentEntity,
      String leafArrayReducerOp) {
    JsonPathSortAst.ArrayHop hop = hops.get(idx);
    String resolvedArrayPath = resolver.resolveBsonPath(currentEntity, hop.arrayPath());
    Class<?> elementType = resolver.getElementTypeAtPath(currentEntity, hop.arrayPath());

    String inputRef =
        idx == 0 ? "$" + resolvedArrayPath : "$$m" + (idx - 1) + "." + resolvedArrayPath;
    Document filterStage =
        new Document(
            "$filter",
            new Document()
                .append("input", new Document("$ifNull", List.of(inputRef, List.of())))
                .append("as", "c")
                .append("cond", translatePredicate(hop.predicate(), "$$c", resolver, elementType)));

    if (idx == hops.size() - 1) {
      if (containsAggregator(leaf)) {
        Object aggregated = translateAggregatedLeaf(leaf, filterStage, resolver, elementType);
        return wrapDocument(aggregated);
      }
      String varName = "m" + idx;
      // needsScalar=true: this leaf becomes a synthetic _sortKeyN field. Mongo $sort
      // rejects multi-key sort docs whose values are parallel arrays (BadValue code
      // 2), so when the leaf path traverses a collection-typed intermediate (and
      // therefore auto-projects to an array under expression-context evaluation),
      // fold it with $min (ASC) or $max (DESC). Both produce a scalar — preserving
      // the parallel-arrays fix — and restore MongoDB's native array-key sort
      // semantics that applied implicitly up to 2.1.1.
      return new Document(
          "$let",
          new Document()
              .append("vars", new Document(varName, new Document("$first", filterStage)))
              .append(
                  "in",
                  translatePerElement(
                      leaf, "$$" + varName, resolver, elementType, true, leafArrayReducerOp)));
    }

    String varName = "m" + idx;
    return new Document(
        "$let",
        new Document()
            .append("vars", new Document(varName, new Document("$first", filterStage)))
            .append(
                "in",
                translateHop(hops, idx + 1, leaf, resolver, elementType, leafArrayReducerOp)));
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

  private static Object translateAggregatedLeaf(
      JsonPathSortAst.LeafExpression leaf,
      Document filterStage,
      MongoFieldResolver resolver,
      Class<?> elementType) {
    if (leaf instanceof JsonPathSortAst.Aggregator agg) {
      Document mapStage =
          new Document(
              "$map",
              new Document()
                  .append("input", filterStage)
                  .append("as", "c")
                  .append("in", translatePerElement(agg.inner(), "$$c", resolver, elementType)));
      String op =
          switch (agg.op()) {
            case MAX -> "$max";
            case MIN -> "$min";
          };
      return new Document(op, mapStage);
    }
    if (leaf instanceof JsonPathSortAst.Coercion c) {
      Object inner = translateAggregatedLeaf(c.inner(), filterStage, resolver, elementType);
      return convertWrap(inner, c.type());
    }
    throw new IllegalStateException(
        "translateAggregatedLeaf called on non-aggregating leaf: " + leaf);
  }

  private static Object translatePerElement(
      JsonPathSortAst.LeafExpression leaf,
      String elementRef,
      MongoFieldResolver resolver,
      Class<?> elementType) {
    return translatePerElement(leaf, elementRef, resolver, elementType, false, MIN_REDUCER);
  }

  /**
   * Builds the per-element expression for a sort leaf. The {@code needsScalar}
   * flag signals that the result is about to be fed into a scalar-only consumer
   * — either a {@code $convert} wrapper from a leaf {@code Coercion}, or the
   * synthetic {@code _sortKeyN} field emitted by
   * {@link Tmf630MongoCorrelatedSortExecutor}. In both cases, if the leaf's
   * dotted path crosses a collection-typed intermediate, Mongo's
   * expression-context path traversal auto-projects and returns an array. That
   * array breaks {@code $convert}, and it triggers Mongo's "cannot sort with
   * keys that are parallel arrays" error (code 2, BadValue) whenever two or
   * more such terms appear in the same multi-key {@code $sort}. We detect the
   * shape via {@link MongoFieldResolver#hasArrayIntermediate} and fold the path
   * with {@code leafArrayReducerOp} ({@code $min} for ASC, {@code $max} for DESC)
   * so the consumer sees a scalar AND ordering matches MongoDB's pre-2.1.2
   * native array-key {@code $sort} behaviour (which used to apply implicitly when
   * the leaf was emitted as an array).
   *
   * <p>The flag is false on the aggregator path ({@code translateAggregatedLeaf}
   * → {@code $map.in}), where each per-element value is folded by {@code $min}
   * / {@code $max} downstream and array-valued per-element results are accepted
   * by the reducer.
   */
  private static Object translatePerElement(
      JsonPathSortAst.LeafExpression leaf,
      String elementRef,
      MongoFieldResolver resolver,
      Class<?> elementType,
      boolean needsScalar,
      String leafArrayReducerOp) {
    // Coercion chain over a FieldRef that crosses an array intermediate: convert
    // each element first, then reduce direction-aware. The naive "reduce raw
    // values then convert" loses numeric semantics — for {"value": ["105.34",
    // "12.2", "4.31"]} sorted by $.arr[?].num(value), $convert($min(strings))
    // gives 105.34 (lex min) instead of the documented numeric min 4.31. Per-
    // element $convert inside a $map preserves the type information across the
    // array, after which $min/$max compares numerically. Behaviour for scalar
    // leaves (no array intermediate) is unchanged.
    if (needsScalar && leaf instanceof JsonPathSortAst.Coercion) {
      JsonPathSortAst.FieldRef innermost = innermostFieldRef(leaf);
      if (innermost != null
          && resolver.hasArrayIntermediate(elementType, innermost.fieldPath())) {
        String resolvedPath = resolver.resolveBsonPath(elementType, innermost.fieldPath());
        String pathExpr = elementRef + "." + resolvedPath;
        Document mapStage =
            new Document(
                "$map",
                new Document()
                    .append("input", new Document("$ifNull", List.of(pathExpr, List.of())))
                    .append("as", "e")
                    .append("in", buildCoercionChain(leaf, "$$e")));
        return new Document(leafArrayReducerOp, mapStage);
      }
    }
    if (leaf instanceof JsonPathSortAst.FieldRef fr) {
      String resolvedPath = resolver.resolveBsonPath(elementType, fr.fieldPath());
      String pathExpr = elementRef + "." + resolvedPath;
      if (needsScalar && resolver.hasArrayIntermediate(elementType, fr.fieldPath())) {
        return new Document(leafArrayReducerOp, pathExpr);
      }
      return pathExpr;
    }
    if (leaf instanceof JsonPathSortAst.Coercion c) {
      return convertWrap(
          translatePerElement(c.inner(), elementRef, resolver, elementType, true, leafArrayReducerOp),
          c.type());
    }
    throw new IllegalStateException(
        "Aggregator may not appear nested inside another aggregator or per-element context: "
            + leaf);
  }

  /**
   * Walks a {@link JsonPathSortAst.Coercion} chain to its innermost leaf and
   * returns it iff that leaf is a {@link JsonPathSortAst.FieldRef}. Returns
   * {@code null} when an {@link JsonPathSortAst.Aggregator} terminates the
   * chain — those paths go through {@link #translateAggregatedLeaf} instead.
   */
  private static JsonPathSortAst.FieldRef innermostFieldRef(
      JsonPathSortAst.LeafExpression leaf) {
    JsonPathSortAst.LeafExpression cursor = leaf;
    while (cursor instanceof JsonPathSortAst.Coercion c) {
      cursor = c.inner();
    }
    return cursor instanceof JsonPathSortAst.FieldRef fr ? fr : null;
  }

  /**
   * Builds the chain of {@code $convert} wrappers implied by a coercion-only
   * leaf chain, with {@code elementRef} as the per-element placeholder. For
   * {@code Coercion(NUM, FieldRef("..."))} this returns
   * {@code $convert(elementRef, double)}; for nested coercions like
   * {@code Coercion(NUM, Coercion(STR, FieldRef))} the wrappers compose
   * outside-in.
   */
  private static Object buildCoercionChain(
      JsonPathSortAst.LeafExpression leaf, String elementRef) {
    if (leaf instanceof JsonPathSortAst.FieldRef) {
      return elementRef;
    }
    if (leaf instanceof JsonPathSortAst.Coercion c) {
      return convertWrap(buildCoercionChain(c.inner(), elementRef), c.type());
    }
    throw new IllegalStateException(
        "Aggregator may not appear in a per-element coercion chain: " + leaf);
  }

  private static Document convertWrap(Object input, JsonPathSortAst.CoercionType type) {
    return new Document(
        "$convert",
        new Document()
            .append("input", input)
            .append("to", type.mongoTypeName())
            .append("onError", null));
  }

  private static Document wrapDocument(Object obj) {
    if (obj instanceof Document doc) {
      return doc;
    }
    throw new IllegalStateException(
        "Expected Document at top level of aggregated leaf translation, got " + obj);
  }

  private static Object translatePredicate(
      JsonPathSortAst.Predicate predicate,
      String varRef,
      MongoFieldResolver resolver,
      Class<?> elementType) {
    if (predicate instanceof JsonPathSortAst.ComparisonPredicate cp) {
      String resolvedField = resolver.resolveBsonPath(elementType, cp.fieldPath());
      String fieldRef = varRef + "." + resolvedField;
      Object literal = literalValue(cp.literal());
      String op =
          switch (cp.op()) {
            case EQ -> "$eq";
            case NE -> "$ne";
            case GT -> "$gt";
            case GTE -> "$gte";
            case LT -> "$lt";
            case LTE -> "$lte";
          };
      return new Document(op, Arrays.asList(fieldRef, literal));
    }
    if (predicate instanceof JsonPathSortAst.AndPredicate ap) {
      return new Document(
          "$and",
          Arrays.asList(
              translatePredicate(ap.left(), varRef, resolver, elementType),
              translatePredicate(ap.right(), varRef, resolver, elementType)));
    }
    if (predicate instanceof JsonPathSortAst.OrPredicate op) {
      return new Document(
          "$or",
          Arrays.asList(
              translatePredicate(op.left(), varRef, resolver, elementType),
              translatePredicate(op.right(), varRef, resolver, elementType)));
    }
    if (predicate instanceof JsonPathSortAst.AlwaysTruePredicate) {
      return new Document("$literal", true);
    }
    throw new IllegalStateException("Unknown predicate type: " + predicate.getClass());
  }

  private static Object literalValue(JsonPathSortAst.Literal literal) {
    if (literal instanceof JsonPathSortAst.StringLiteral s) {
      return s.value();
    }
    if (literal instanceof JsonPathSortAst.NumberLiteral n) {
      if (n.value().scale() <= 0) {
        return n.value().longValueExact();
      }
      return n.value().doubleValue();
    }
    if (literal instanceof JsonPathSortAst.BooleanLiteral b) {
      return b.value();
    }
    if (literal instanceof JsonPathSortAst.NullLiteral) {
      return null;
    }
    throw new IllegalStateException("Unknown literal type: " + literal.getClass());
  }
}
