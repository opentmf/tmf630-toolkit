package org.opentmf.query.tmf630.mongo;

import java.util.Arrays;
import java.util.List;
import org.bson.Document;

public final class AggregationKeyTranslator {

  private AggregationKeyTranslator() {}

  public static Document translate(JsonPathSortAst.SortPath sortPath) {
    return translate(sortPath, MongoFieldResolver.passthrough(), null);
  }

  public static Document translate(
      JsonPathSortAst.SortPath sortPath, MongoFieldResolver resolver, Class<?> rootEntity) {
    return translateHop(sortPath.hops(), 0, sortPath.leaf(), resolver, rootEntity);
  }

  private static Document translateHop(
      List<JsonPathSortAst.ArrayHop> hops,
      int idx,
      JsonPathSortAst.LeafExpression leaf,
      MongoFieldResolver resolver,
      Class<?> currentEntity) {
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
      return new Document(
          "$let",
          new Document()
              .append("vars", new Document(varName, new Document("$first", filterStage)))
              .append("in", translatePerElement(leaf, "$$" + varName, resolver, elementType)));
    }

    String varName = "m" + idx;
    return new Document(
        "$let",
        new Document()
            .append("vars", new Document(varName, new Document("$first", filterStage)))
            .append("in", translateHop(hops, idx + 1, leaf, resolver, elementType)));
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
    if (leaf instanceof JsonPathSortAst.FieldRef fr) {
      String resolvedPath = resolver.resolveBsonPath(elementType, fr.fieldPath());
      return elementRef + "." + resolvedPath;
    }
    if (leaf instanceof JsonPathSortAst.Coercion c) {
      return convertWrap(
          translatePerElement(c.inner(), elementRef, resolver, elementType), c.type());
    }
    throw new IllegalStateException(
        "Aggregator may not appear nested inside another aggregator or per-element context: "
            + leaf);
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
