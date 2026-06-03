package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class AggregationKeyTranslatorTest {

  private final JsonPathSortParser parser = new JsonPathSortParser();

  @Test
  void translatesSimpleEqualityIntoLetFilterFirstStructure() {
    Document doc =
        AggregationKeyTranslator.translate(parser.parse("$.arr[?(@.id == 'X')].value"));

    assertTrue(doc.containsKey("$let"));
    Document letBody = doc.get("$let", Document.class);
    Document vars = letBody.get("vars", Document.class);
    Document mDef = vars.get("m0", Document.class);
    Document firstWrapper = mDef.get("$first", Document.class);
    Document filterDef = firstWrapper.get("$filter", Document.class);

    Document inputIfNull = filterDef.get("input", Document.class);
    List<?> ifNullArgs = inputIfNull.getList("$ifNull", Object.class);
    assertEquals("$arr", ifNullArgs.get(0));
    assertEquals(List.of(), ifNullArgs.get(1));

    assertEquals("c", filterDef.getString("as"));

    Document cond = filterDef.get("cond", Document.class);
    List<?> eqArgs = cond.getList("$eq", Object.class);
    assertEquals("$$c.id", eqArgs.get(0));
    assertEquals("X", eqArgs.get(1));

    assertEquals("$$m0.value", letBody.getString("in"));
  }

  @Test
  void translatesAllComparisonOperators() {
    assertEquals("$ne", findOpKey(parser.parse("$.a[?(@.x != 1)].v")));
    assertEquals("$gt", findOpKey(parser.parse("$.a[?(@.x > 1)].v")));
    assertEquals("$gte", findOpKey(parser.parse("$.a[?(@.x >= 1)].v")));
    assertEquals("$lt", findOpKey(parser.parse("$.a[?(@.x < 1)].v")));
    assertEquals("$lte", findOpKey(parser.parse("$.a[?(@.x <= 1)].v")));
  }

  @Test
  void translatesAndPredicate() {
    Document doc =
        AggregationKeyTranslator.translate(
            parser.parse("$.a[?(@.x == 'A' && @.y == 'B')].v"));

    Document cond = extractCond(doc);
    List<?> andArgs = cond.getList("$and", Object.class);
    assertEquals(2, andArgs.size());
    assertInstanceOf(Document.class, andArgs.get(0));
    assertTrue(((Document) andArgs.get(0)).containsKey("$eq"));
  }

  @Test
  void translatesOrPredicate() {
    Document doc =
        AggregationKeyTranslator.translate(
            parser.parse("$.a[?(@.x == 'A' || @.x == 'B')].v"));

    Document cond = extractCond(doc);
    List<?> orArgs = cond.getList("$or", Object.class);
    assertEquals(2, orArgs.size());
  }

  @Test
  void translatesNumberLiteralsAsLongOrDoubleByScale() {
    Document intDoc =
        AggregationKeyTranslator.translate(parser.parse("$.a[?(@.x == 42)].v"));
    Document intCond = extractCond(intDoc);
    List<?> intArgs = intCond.getList("$eq", Object.class);
    assertEquals(42L, intArgs.get(1));

    Document dblDoc =
        AggregationKeyTranslator.translate(parser.parse("$.a[?(@.x == 3.14)].v"));
    Document dblCond = extractCond(dblDoc);
    List<?> dblArgs = dblCond.getList("$eq", Object.class);
    assertEquals(3.14, (double) dblArgs.get(1), 1e-9);
  }

  @Test
  void translatesBooleanLiteral() {
    Document doc = AggregationKeyTranslator.translate(parser.parse("$.a[?(@.x == true)].v"));
    Document cond = extractCond(doc);
    List<?> args = cond.getList("$eq", Object.class);
    assertEquals(Boolean.TRUE, args.get(1));
  }

  @Test
  void translatesNullLiteralAsNullElementInArray() {
    Document doc = AggregationKeyTranslator.translate(parser.parse("$.a[?(@.x == null)].v"));
    Document cond = extractCond(doc);
    List<?> args = cond.getList("$eq", Object.class);
    assertEquals("$$c.x", args.get(0));
    assertNull(args.get(1));
  }

  @Test
  void translatesDottedFieldPathToDoubleDollarReference() {
    Document doc =
        AggregationKeyTranslator.translate(
            parser.parse("$.a[?(@.nested.field == 'X')].v"));
    Document cond = extractCond(doc);
    List<?> args = cond.getList("$eq", Object.class);
    assertEquals("$$c.nested.field", args.get(0));
  }

  @Test
  void translatesMaxAggregatorAsMapMaxOverFilter() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    Document doc = AggregationKeyTranslator.translate(sr.parse("arr[X].max(value)"));

    assertTrue(doc.containsKey("$max"));
    Document mapDoc = doc.get("$max", Document.class).get("$map", Document.class);
    Document filter = mapDoc.get("input", Document.class).get("$filter", Document.class);
    List<?> ifNullArgs = filter.get("input", Document.class).getList("$ifNull", Object.class);
    assertEquals("$arr", ifNullArgs.get(0));
    assertEquals("$$c.value", mapDoc.get("in"));
  }

  @Test
  void translatesMinAggregatorAsDollarMin() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    Document doc = AggregationKeyTranslator.translate(sr.parse("arr[X].min(value)"));
    assertTrue(doc.containsKey("$min"));
  }

  @Test
  void translatesCoercionAroundAggregatorAsConvertWrappingMax() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    Document doc =
        AggregationKeyTranslator.translate(sr.parse("arr[X].str(max(value))"));

    assertTrue(doc.containsKey("$convert"));
    Document convert = doc.get("$convert", Document.class);
    assertEquals("string", convert.getString("to"));
    assertTrue(convert.containsKey("onError"));
    Document inner = convert.get("input", Document.class);
    assertTrue(inner.containsKey("$max"));
  }

  @Test
  void translatesCoercionInsideAggregatorAsConvertPerElement() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    Document doc =
        AggregationKeyTranslator.translate(sr.parse("arr[X].max(str(value))"));

    Document mapDoc = doc.get("$max", Document.class).get("$map", Document.class);
    Document elementExpr = (Document) mapDoc.get("in");
    assertTrue(elementExpr.containsKey("$convert"));
    Document convert = elementExpr.get("$convert", Document.class);
    assertEquals("string", convert.getString("to"));
    assertEquals("$$c.value", convert.get("input"));
  }

  @Test
  void translatesNumCoercionToDouble() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    Document doc = AggregationKeyTranslator.translate(sr.parse("arr[X].num(value)"));
    Document letBody = doc.get("$let", Document.class);
    Document convert = ((Document) letBody.get("in")).get("$convert", Document.class);
    assertEquals("double", convert.getString("to"));
  }

  @Test
  void translatesDateCoercionToDate() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    Document doc = AggregationKeyTranslator.translate(sr.parse("arr[X].date(value)"));
    Document letBody = doc.get("$let", Document.class);
    Document convert = ((Document) letBody.get("in")).get("$convert", Document.class);
    assertEquals("date", convert.getString("to"));
  }

  @Test
  void translatesPlainCoercionAroundFieldRef() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    Document doc = AggregationKeyTranslator.translate(sr.parse("arr[X].str(value)"));
    Document letBody = doc.get("$let", Document.class);
    Document convert = ((Document) letBody.get("in")).get("$convert", Document.class);
    assertEquals("$$m0.value", convert.get("input"));
  }

  @Test
  void translatesAlwaysTrueAsLiteralTrueCondition() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    // Naked-hop $literal: true is still emitted when there is a pre-function dotted
    // segment between the explicit hop and an aggregator at the leaf. Plain trailing
    // paths without a function bypass naked hops entirely (Mongo path auto-traversal).
    Document doc = AggregationKeyTranslator.translate(sr.parse("arr[X].sub.max(value)"));

    Document outerLet = doc.get("$let", Document.class);
    Document inner = (Document) outerLet.get("in");
    Document innerFilter =
        inner.get("$max", Document.class).get("$map", Document.class)
            .get("input", Document.class)
            .get("$filter", Document.class);

    Document cond = innerFilter.get("cond", Document.class);
    assertEquals(Boolean.TRUE, cond.getBoolean("$literal"));
  }

  @Test
  void translatesPlainTrailingDottedPathWithoutAdditionalLetLayers() {
    SimpleRichSortParser sr = new SimpleRichSortParser("id");
    // Trailing dotted path with no function: leaf becomes a single FieldRef and the
    // translator emits "$$m0.<entire-path>" — no naked hop, no inner $let, no $filter
    // on possibly-non-array intermediate fields. Mongo handles object traversal and
    // array auto-projection.
    Document doc = AggregationKeyTranslator.translate(sr.parse("arr[X].deep.path.value"));

    Document outerLet = doc.get("$let", Document.class);
    assertEquals("$$m0.deep.path.value", outerLet.get("in"));
  }

  @Test
  void translatesTwoLevelCorrelationAsNestedLetExpressions() {
    Document doc =
        AggregationKeyTranslator.translate(
            parser.parse(
                "$.categories[?(@.name == 'electronics')]"
                    + ".subcategories[?(@.featured == true)]"
                    + ".price"));

    Document outerLet = doc.get("$let", Document.class);
    Document outerVars = outerLet.get("vars", Document.class);
    Document m0 = outerVars.get("m0", Document.class);
    Document outerFilter = m0.get("$first", Document.class).get("$filter", Document.class);
    List<?> outerInputArgs = outerFilter.get("input", Document.class).getList("$ifNull", Object.class);
    assertEquals("$categories", outerInputArgs.get(0));

    Object outerIn = outerLet.get("in");
    Document innerLet = ((Document) outerIn).get("$let", Document.class);
    Document innerVars = innerLet.get("vars", Document.class);
    Document m1 = innerVars.get("m1", Document.class);
    Document innerFilter = m1.get("$first", Document.class).get("$filter", Document.class);
    List<?> innerInputArgs = innerFilter.get("input", Document.class).getList("$ifNull", Object.class);
    assertEquals("$$m0.subcategories", innerInputArgs.get(0));

    assertEquals("$$m1.price", innerLet.getString("in"));
  }

  @Test
  void translatesThreeLevelCorrelationAsTriplyNestedLet() {
    Document doc =
        AggregationKeyTranslator.translate(
            parser.parse(
                "$.a[?(@.x == 'A')].b[?(@.y == 'B')].c[?(@.z == 'C')].leaf"));

    Document level0 = doc.get("$let", Document.class);
    Document level1 = ((Document) level0.get("in")).get("$let", Document.class);
    Document level2 = ((Document) level1.get("in")).get("$let", Document.class);

    assertEquals("$$m2.leaf", level2.getString("in"));
    Document m2Filter =
        level2.get("vars", Document.class).get("m2", Document.class).get("$first", Document.class)
            .get("$filter", Document.class);
    List<?> m2InputArgs = m2Filter.get("input", Document.class).getList("$ifNull", Object.class);
    assertEquals("$$m1.c", m2InputArgs.get(0));
  }

  @Test
  void translatesDottedLeafFieldIntoDoubleDollarReference() {
    Document doc =
        AggregationKeyTranslator.translate(
            parser.parse("$.a[?(@.id == 'X')].deep.leaf"));
    Document letBody = doc.get("$let", Document.class);
    assertEquals("$$m0.deep.leaf", letBody.getString("in"));
  }

  @Test
  void coercionWrapsArrayIntermediateInArrayElemAtToYieldScalarBeforeConvert() {
    // Regression for the silent-drop bug: num(arr[X].myArray.value) where
    // `myArray` is a collection-typed intermediate. Without the resolver-aware
    // projection, Mongo's path expression `$$m0.myArray.value` returns an array
    // and $convert(array, "double") yields null. With the resolver, the
    // translator wraps the dotted path in `$arrayElemAt: [..., 0]` before the
    // $convert, restoring numeric ordering.
    MongoMappingContext ctx = new MongoMappingContext();
    MongoFieldResolver resolver = new MongoFieldResolver(ctx);
    SimpleRichSortParser sr = new SimpleRichSortParser("id");

    Document doc =
        AggregationKeyTranslator.translate(
            sr.parse("outer[X].myArray.num(value)"), resolver, OuterEntity.class);

    // Inner-wrapper form should still work (this is the control case).
    assertTrue(doc.containsKey("$let") || doc.containsKey("$convert"));

    Document doc2 =
        AggregationKeyTranslator.translate(
            sr.parse("num(outer[X].myArray.value)"), resolver, OuterEntity.class);
    Document letBody2 = doc2.get("$let", Document.class);
    Document convert2 = ((Document) letBody2.get("in")).get("$convert", Document.class);
    Document input2 = (Document) convert2.get("input");
    assertTrue(input2.containsKey("$arrayElemAt"));
    List<?> elemArgs = input2.getList("$arrayElemAt", Object.class);
    assertEquals("$$m0.myArray.value", elemArgs.get(0));
    assertEquals(0, elemArgs.get(1));
  }

  @Test
  void directSortKeyEmissionWrapsArrayIntermediateInArrayElemAtToYieldScalar() {
    // Regression for the multi-term parallel-arrays bug: when two correlated
    // sort terms each emit a leaf whose path traverses an inner collection-typed
    // intermediate, Mongo's $sort rejects the multi-key sort doc with
    // "cannot sort with keys that are parallel arrays" (BadValue, code 2). The
    // translator now wraps the per-element leaf in $arrayElemAt for direct
    // sort-key emission too (not only inside coercion), so each _sortKeyN stays
    // scalar.
    MongoMappingContext ctx = new MongoMappingContext();
    MongoFieldResolver resolver = new MongoFieldResolver(ctx);
    SimpleRichSortParser sr = new SimpleRichSortParser("id");

    Document doc =
        AggregationKeyTranslator.translate(
            sr.parse("outer[X].myArray.value"), resolver, OuterEntity.class);

    Document letBody = doc.get("$let", Document.class);
    Document inExpr = (Document) letBody.get("in");
    assertTrue(inExpr.containsKey("$arrayElemAt"));
    List<?> elemArgs = inExpr.getList("$arrayElemAt", Object.class);
    assertEquals("$$m0.myArray.value", elemArgs.get(0));
    assertEquals(0, elemArgs.get(1));
  }

  @Test
  void directSortKeyEmissionDoesNotWrapWhenLeafPathHasNoArrayIntermediate() {
    // Sanity: when the leaf path crosses only scalar object intermediates, no
    // $arrayElemAt wrap is added — the dotted path resolves to a scalar already
    // and the wrap would either be a no-op or break valid expressions.
    MongoMappingContext ctx = new MongoMappingContext();
    MongoFieldResolver resolver = new MongoFieldResolver(ctx);
    SimpleRichSortParser sr = new SimpleRichSortParser("id");

    Document doc =
        AggregationKeyTranslator.translate(
            sr.parse("outer[X].scalarInner.value"), resolver, OuterEntity.class);

    Document letBody = doc.get("$let", Document.class);
    assertEquals("$$m0.scalarInner.value", letBody.get("in"));
  }

  @Test
  void coercionDoesNotWrapWhenIntermediatesAreScalarObjects() {
    // Sanity check: when the intermediate is a single-valued object, dot
    // traversal yields a scalar already, so $arrayElemAt would break it.
    MongoMappingContext ctx = new MongoMappingContext();
    MongoFieldResolver resolver = new MongoFieldResolver(ctx);
    SimpleRichSortParser sr = new SimpleRichSortParser("id");

    Document doc =
        AggregationKeyTranslator.translate(
            sr.parse("num(outer[X].scalarInner.value)"), resolver, OuterEntity.class);
    Document letBody = doc.get("$let", Document.class);
    Document convert = ((Document) letBody.get("in")).get("$convert", Document.class);
    assertEquals("$$m0.scalarInner.value", convert.get("input"));
  }

  @org.springframework.data.mongodb.core.mapping.Document
  static class OuterEntity {
    String id;
    List<OuterElement> outer;
  }

  static class OuterElement {
    String id;
    List<InnerElement> myArray;
    ScalarInner scalarInner;
  }

  static class InnerElement {
    String value;
  }

  static class ScalarInner {
    String value;
  }

  private Document extractCond(Document letDoc) {
    Document letBody = letDoc.get("$let", Document.class);
    Document vars = letBody.get("vars", Document.class);
    Document mDef = vars.get("m0", Document.class);
    Document firstWrapper = mDef.get("$first", Document.class);
    Document filterDef = firstWrapper.get("$filter", Document.class);
    return filterDef.get("cond", Document.class);
  }

  private String findOpKey(JsonPathSortAst.SortPath path) {
    Document doc = AggregationKeyTranslator.translate(path);
    Document cond = extractCond(doc);
    return cond.keySet().iterator().next();
  }
}
