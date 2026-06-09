package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class JsonPathSortParserTest {

  private final JsonPathSortParser parser = new JsonPathSortParser();

  @Test
  void parsesSimpleEqualityWithStringLiteral() {
    JsonPathSortAst.SortPath p = parser.parse("$.arr[?(@.id == 'X')].value");

    assertEquals(1, p.hops().size());
    JsonPathSortAst.ArrayHop hop = p.hops().get(0);
    assertEquals("arr", hop.arrayPath());
    assertEquals("value", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
    JsonPathSortAst.ComparisonPredicate cp =
        assertInstanceOf(JsonPathSortAst.ComparisonPredicate.class, hop.predicate());
    assertEquals("id", cp.fieldPath());
    assertEquals(JsonPathSortAst.ComparisonOperator.EQ, cp.op());
    assertEquals("X", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void parsesAllComparisonOperators() {
    assertEquals(JsonPathSortAst.ComparisonOperator.NE, opOf("$.a[?(@.x != 1)].v"));
    assertEquals(JsonPathSortAst.ComparisonOperator.GT, opOf("$.a[?(@.x > 1)].v"));
    assertEquals(JsonPathSortAst.ComparisonOperator.GTE, opOf("$.a[?(@.x >= 1)].v"));
    assertEquals(JsonPathSortAst.ComparisonOperator.LT, opOf("$.a[?(@.x < 1)].v"));
    assertEquals(JsonPathSortAst.ComparisonOperator.LTE, opOf("$.a[?(@.x <= 1)].v"));
  }

  @Test
  void parsesNumericLiteral() {
    JsonPathSortAst.ComparisonPredicate cp = comparisonOf("$.a[?(@.x == 42)].v");
    assertEquals(new BigDecimal("42"), ((JsonPathSortAst.NumberLiteral) cp.literal()).value());
  }

  @Test
  void parsesDecimalLiteral() {
    JsonPathSortAst.ComparisonPredicate cp = comparisonOf("$.a[?(@.x == 3.14)].v");
    assertEquals(new BigDecimal("3.14"), ((JsonPathSortAst.NumberLiteral) cp.literal()).value());
  }

  @Test
  void parsesNegativeNumericLiteral() {
    JsonPathSortAst.ComparisonPredicate cp = comparisonOf("$.a[?(@.x == -7)].v");
    assertEquals(new BigDecimal("-7"), ((JsonPathSortAst.NumberLiteral) cp.literal()).value());
  }

  @Test
  void parsesBooleanLiterals() {
    JsonPathSortAst.ComparisonPredicate trueCp = comparisonOf("$.a[?(@.x == true)].v");
    assertEquals(true, ((JsonPathSortAst.BooleanLiteral) trueCp.literal()).value());
    JsonPathSortAst.ComparisonPredicate falseCp = comparisonOf("$.a[?(@.x == false)].v");
    assertEquals(false, ((JsonPathSortAst.BooleanLiteral) falseCp.literal()).value());
  }

  @Test
  void parsesNullLiteral() {
    JsonPathSortAst.ComparisonPredicate cp = comparisonOf("$.a[?(@.x == null)].v");
    assertInstanceOf(JsonPathSortAst.NullLiteral.class, cp.literal());
  }

  @Test
  void parsesAndPredicate() {
    JsonPathSortAst.SortPath p = parser.parse("$.a[?(@.x == 'A' && @.y == 'B')].v");
    JsonPathSortAst.AndPredicate ap =
        assertInstanceOf(JsonPathSortAst.AndPredicate.class, p.hops().get(0).predicate());
    assertInstanceOf(JsonPathSortAst.ComparisonPredicate.class, ap.left());
    assertInstanceOf(JsonPathSortAst.ComparisonPredicate.class, ap.right());
  }

  @Test
  void parsesOrPredicate() {
    JsonPathSortAst.SortPath p = parser.parse("$.a[?(@.x == 'A' || @.x == 'B')].v");
    assertInstanceOf(JsonPathSortAst.OrPredicate.class, p.hops().get(0).predicate());
  }

  @Test
  void andBindsTighterThanOr() {
    JsonPathSortAst.SortPath p =
        parser.parse("$.a[?(@.x == 'A' || @.x == 'B' && @.y == 'C')].v");
    JsonPathSortAst.OrPredicate top =
        assertInstanceOf(JsonPathSortAst.OrPredicate.class, p.hops().get(0).predicate());
    assertInstanceOf(JsonPathSortAst.ComparisonPredicate.class, top.left());
    assertInstanceOf(JsonPathSortAst.AndPredicate.class, top.right());
  }

  @Test
  void parsesDottedFieldPathInPredicate() {
    JsonPathSortAst.ComparisonPredicate cp =
        comparisonOf("$.a[?(@.nested.field == 'X')].v");
    assertEquals("nested.field", cp.fieldPath());
  }

  @Test
  void parsesDottedArrayAndLeafPaths() {
    JsonPathSortAst.SortPath p = parser.parse("$.outer.middle[?(@.id == 'X')].inner.leaf");
    assertEquals(1, p.hops().size());
    assertEquals("outer.middle", p.hops().get(0).arrayPath());
    assertEquals("inner.leaf", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
  }

  @Test
  void parsesTwoLevelCorrelationAsTwoHops() {
    JsonPathSortAst.SortPath p =
        parser.parse(
            "$.categories[?(@.name == 'electronics')]"
                + ".subcategories[?(@.featured == true)]"
                + ".price");

    assertEquals(2, p.hops().size());
    assertEquals("categories", p.hops().get(0).arrayPath());
    assertEquals("subcategories", p.hops().get(1).arrayPath());
    assertEquals("price", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
  }

  @Test
  void parsesThreeLevelCorrelation() {
    JsonPathSortAst.SortPath p =
        parser.parse("$.a[?(@.x == 'A')].b[?(@.y == 'B')].c[?(@.z == 'C')].leaf");

    assertEquals(3, p.hops().size());
    assertEquals("a", p.hops().get(0).arrayPath());
    assertEquals("b", p.hops().get(1).arrayPath());
    assertEquals("c", p.hops().get(2).arrayPath());
    assertEquals("leaf", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
  }

  @Test
  void parsesIntermediateDottedArrayPath() {
    JsonPathSortAst.SortPath p =
        parser.parse(
            "$.outer[?(@.id == 'X')].deep.middle[?(@.tag == 'Y')].leaf");

    assertEquals(2, p.hops().size());
    assertEquals("outer", p.hops().get(0).arrayPath());
    assertEquals("deep.middle", p.hops().get(1).arrayPath());
    assertEquals("leaf", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
  }

  @Test
  void rejectsMissingLeafField() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("$.arr[?(@.id == 'X')]"));
  }

  @Test
  void rejectsMissingLeafFieldAfterTrailingDot() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("$.arr[?(@.id == 'X')]."));
  }

  @Test
  void rejectsTrailingInputAfterLeaf() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse("$.arr[?(@.id == 'X')].value extra"));
  }

  @Test
  void acceptsMissingDollarPrefixAsTmf630ShorthandEquivalentToFullForm() {
    // TMF630 recommends allowing the `$.` prefix to be omitted for simplicity.
    // The toolkit accepts both forms and they produce identical SortPaths.
    JsonPathSortAst.SortPath withoutPrefix =
        parser.parse("arr[?(@.id == 'X')].value");
    JsonPathSortAst.SortPath withPrefix = parser.parse("$.arr[?(@.id == 'X')].value");

    assertEquals(withPrefix, withoutPrefix);
  }

  @Test
  void rejectsPathWithoutAnyPredicate() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("$.foo.bar"));
  }

  @Test
  void acceptsWildcardAsTransparentProjection() {
    // [*] is the canonical JsonPath way of projecting across array elements.
    // Mongo's path expression auto-projects implicitly, so we strip [*] at parse time
    // and the result is structurally identical to the form without [*]. This keeps
    // the toolkit syntactically aligned with jsonpath.com / Jayway tooling without
    // changing what the toolkit actually computes.
    JsonPathSortAst.SortPath withWildcard =
        parser.parse("$.arr[?(@.id == 'X')].sub[*].value");
    JsonPathSortAst.SortPath withoutWildcard =
        parser.parse("$.arr[?(@.id == 'X')].sub.value");

    assertEquals(withoutWildcard, withWildcard);
    assertEquals(1, withWildcard.hops().size());
    assertEquals(
        "sub.value", ((JsonPathSortAst.FieldRef) withWildcard.leaf()).fieldPath());
  }

  @Test
  void acceptsWildcardOnTheArrayBeforeAPredicate() {
    JsonPathSortAst.SortPath withWildcard =
        parser.parse("$.arr[*][?(@.id == 'X')].value");
    JsonPathSortAst.SortPath withoutWildcard =
        parser.parse("$.arr[?(@.id == 'X')].value");

    assertEquals(withoutWildcard, withWildcard);
  }

  @Test
  void preservesQuotedSquareStarLiteralInsidePredicate() {
    // A user could (theoretically) want to compare against the literal string '[*]'.
    // The strip pass must NOT remove [*] when it appears inside a quoted string.
    JsonPathSortAst.SortPath p = parser.parse("$.arr[?(@.label == '[*]')].value");
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate();
    assertEquals("[*]", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void stripWildcardsLeavesNonWildcardBracketsAlone() {
    assertEquals(
        "$.arr[?(@.id == 'X')].value",
        JsonPathSortParser.stripWildcards("$.arr[?(@.id == 'X')].value"));
    assertEquals("foo", JsonPathSortParser.stripWildcards("foo"));
    assertEquals("foo.bar", JsonPathSortParser.stripWildcards("foo[*].bar"));
    assertEquals("foo.bar", JsonPathSortParser.stripWildcards("foo[*][*].bar"));
  }

  @Test
  void rejectsRecursiveDescent() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("$..value"));
  }

  @Test
  void rejectsArraySlice() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("$.arr[0:5].value"));
  }

  @Test
  void rejectsUnterminatedStringLiteral() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("$.arr[?(@.id == 'X)].value"));
  }

  @Test
  void rejectsUnknownOperator() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("$.arr[?(@.id ~ 'X')].value"));
  }

  @Test
  void rejectsMalformedNumericLiteral() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("$.arr[?(@.id == 1.2.3)].v"));
  }

  @Test
  void rejectsNullInput() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
  }

  @Test
  void rejectsLoneDashAsLiteral() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("$.arr[?(@.id == -)].v"));
  }

  @Test
  void toleratesWhitespaceAroundTokens() {
    JsonPathSortAst.SortPath p =
        parser.parse("  $.arr[?(   @.id    ==   'X'  )].value   ");
    assertEquals("arr", p.hops().get(0).arrayPath());
    assertEquals("value", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
  }

  @Test
  void parsesLeafLevelNumCoercion() {
    // The motivating case: DNext stores Characteristic.value as String even when its
    // valueType is "number". A bare leaf would sort alphabetically; the num() wrap
    // forces numeric sort, mirroring SimpleRich's num(value) form.
    JsonPathSortAst.SortPath p =
        parser.parse("$.arr[?(@.id == 'X')].num(value)");
    assertEquals(1, p.hops().size());
    JsonPathSortAst.Coercion c =
        assertInstanceOf(JsonPathSortAst.Coercion.class, p.leaf());
    assertEquals(JsonPathSortAst.CoercionType.NUM, c.type());
    assertEquals("value", ((JsonPathSortAst.FieldRef) c.inner()).fieldPath());
  }

  @Test
  void parsesLeafLevelStrAndDateCoercions() {
    JsonPathSortAst.Coercion str =
        (JsonPathSortAst.Coercion) parser.parse("$.arr[?(@.id == 'X')].str(value)").leaf();
    assertEquals(JsonPathSortAst.CoercionType.STR, str.type());

    JsonPathSortAst.Coercion date =
        (JsonPathSortAst.Coercion) parser.parse("$.arr[?(@.id == 'X')].date(value)").leaf();
    assertEquals(JsonPathSortAst.CoercionType.DATE, date.type());
  }

  @Test
  void parsesLeafLevelMinAndMaxAggregators() {
    JsonPathSortAst.Aggregator min =
        (JsonPathSortAst.Aggregator) parser.parse("$.arr[?(@.id == 'X')].min(value)").leaf();
    assertEquals(JsonPathSortAst.AggregatorOp.MIN, min.op());

    JsonPathSortAst.Aggregator max =
        (JsonPathSortAst.Aggregator) parser.parse("$.arr[?(@.id == 'X')].max(value)").leaf();
    assertEquals(JsonPathSortAst.AggregatorOp.MAX, max.op());
  }

  @Test
  void parsesNestedFunctionLeafExpression() {
    // num(min(value)) — coerce the per-element min to a number. Tests recursive
    // parseLeafExpression handling.
    JsonPathSortAst.SortPath p =
        parser.parse("$.arr[?(@.id == 'X')].num(min(value))");
    JsonPathSortAst.Coercion num =
        assertInstanceOf(JsonPathSortAst.Coercion.class, p.leaf());
    assertEquals(JsonPathSortAst.CoercionType.NUM, num.type());
    JsonPathSortAst.Aggregator min =
        assertInstanceOf(JsonPathSortAst.Aggregator.class, num.inner());
    assertEquals(JsonPathSortAst.AggregatorOp.MIN, min.op());
    assertEquals("value", ((JsonPathSortAst.FieldRef) min.inner()).fieldPath());
  }

  @Test
  void promotesPreFunctionSegmentsToNakedArrayHopsOnlyForAggregatorLeaves() {
    // Aggregator (min/max) needs to $map across an array, so pre-function dotted
    // segments are promoted to naked ArrayHops with AlwaysTruePredicate.
    JsonPathSortAst.SortPath agg =
        parser.parse(
            "$.prodSpec[?(@.id == 'X')].productSpecCharacteristicValue.max(value)");
    assertEquals(2, agg.hops().size());
    assertEquals("prodSpec", agg.hops().get(0).arrayPath());
    assertEquals("productSpecCharacteristicValue", agg.hops().get(1).arrayPath());
    assertInstanceOf(
        JsonPathSortAst.AlwaysTruePredicate.class, agg.hops().get(1).predicate());
    JsonPathSortAst.Aggregator aggLeaf =
        assertInstanceOf(JsonPathSortAst.Aggregator.class, agg.leaf());
    assertEquals(JsonPathSortAst.AggregatorOp.MAX, aggLeaf.op());
  }

  @Test
  void doesNotPromotePreFunctionSegmentsForCoercionOnlyLeaves() {
    // Coercion-only leaves keep pre-function segments inside the FieldRef path;
    // the translator emits $map+$convert+$min/$max for array-intermediate paths
    // at the leaf, so naked hops are unnecessary AND would crash on object
    // intermediates ($filter on a non-array). This is the 2.1.3 fix for the
    // documented num() semantics ("coerces each value … numeric order").
    JsonPathSortAst.SortPath p =
        parser.parse(
            "$.prodSpec[?(@.id == 'X')].productSpecCharacteristicValue.num(value)");
    assertEquals(1, p.hops().size());
    assertEquals("prodSpec", p.hops().get(0).arrayPath());
    JsonPathSortAst.Coercion c =
        assertInstanceOf(JsonPathSortAst.Coercion.class, p.leaf());
    assertEquals(JsonPathSortAst.CoercionType.NUM, c.type());
    assertEquals(
        "productSpecCharacteristicValue.value",
        ((JsonPathSortAst.FieldRef) c.inner()).fieldPath());
  }

  @Test
  void plainDottedLeafStaysSingleFieldRef() {
    // Regression: when the trailing path has no function calls, the entire dotted
    // suffix remains a single FieldRef so the existing parallel-arrays fix in
    // AggregationKeyTranslator continues to apply via hasArrayIntermediate.
    JsonPathSortAst.SortPath p =
        parser.parse(
            "$.prodSpec[?(@.id == 'X')].productSpecCharacteristicValue.value");
    assertEquals(1, p.hops().size());
    assertEquals(
        "productSpecCharacteristicValue.value",
        ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
  }

  @Test
  void parsesOuterCoercionWrapAroundEntireExpression() {
    // num($.arr[?()].value) — outer wrap form, symmetric with SimpleRich. Hops are
    // unchanged; the resulting leaf is wrapped in Coercion(NUM).
    JsonPathSortAst.SortPath outer =
        parser.parse("num($.arr[?(@.id == 'X')].value)");
    JsonPathSortAst.SortPath inner =
        parser.parse("$.arr[?(@.id == 'X')].num(value)");
    assertEquals(inner, outer);
  }

  @Test
  void parsesOuterAggregatorWrapAroundEntireExpression() {
    JsonPathSortAst.SortPath outer =
        parser.parse("max($.arr[?(@.id == 'X')].value)");
    JsonPathSortAst.Aggregator agg =
        assertInstanceOf(JsonPathSortAst.Aggregator.class, outer.leaf());
    assertEquals(JsonPathSortAst.AggregatorOp.MAX, agg.op());
    assertEquals("value", ((JsonPathSortAst.FieldRef) agg.inner()).fieldPath());
  }

  @Test
  void parsesOuterStrAndDateWraps() {
    JsonPathSortAst.Coercion str =
        (JsonPathSortAst.Coercion) parser.parse("str($.arr[?(@.id == 'X')].value)").leaf();
    assertEquals(JsonPathSortAst.CoercionType.STR, str.type());

    JsonPathSortAst.Coercion date =
        (JsonPathSortAst.Coercion) parser.parse("date($.arr[?(@.id == 'X')].value)").leaf();
    assertEquals(JsonPathSortAst.CoercionType.DATE, date.type());
  }

  @Test
  void doubleQuotedPredicateLiteralAcceptedEquivalentlyToSingleQuoted() {
    // The colleague's 2.1.3 follow-up: the filter tokenizer accepts both `'` and
    // `"` as string-literal delimiters; the sort parser only accepted `'`. Same
    // predicate @.id == "X" was therefore valid in ?filter= but rejected in
    // ?sort=. Now both produce identical SortPaths.
    JsonPathSortAst.SortPath singleQ =
        parser.parse("$.arr[?(@.id == 'X')].value");
    JsonPathSortAst.SortPath doubleQ =
        parser.parse("$.arr[?(@.id == \"X\")].value");
    assertEquals(singleQ, doubleQ);
  }

  @Test
  void doubleQuotedLiteralStringValuePreservedVerbatim() {
    JsonPathSortAst.SortPath p =
        parser.parse("$.arr[?(@.name == \"abc def\")].value");
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate();
    assertEquals("abc def", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void mixedQuoteStylesParseCorrectly() {
    // The two quote styles must be independent: a `'` opening a literal must NOT
    // be closed by a `"`. The opening char acts as the close sentinel.
    JsonPathSortAst.SortPath p =
        parser.parse("$.arr[?(@.id == 'X' && @.name == \"Y\")].value");
    JsonPathSortAst.AndPredicate ap =
        (JsonPathSortAst.AndPredicate) p.hops().get(0).predicate();
    JsonPathSortAst.ComparisonPredicate left =
        (JsonPathSortAst.ComparisonPredicate) ap.left();
    JsonPathSortAst.ComparisonPredicate right =
        (JsonPathSortAst.ComparisonPredicate) ap.right();
    assertEquals("X", ((JsonPathSortAst.StringLiteral) left.literal()).value());
    assertEquals("Y", ((JsonPathSortAst.StringLiteral) right.literal()).value());
  }

  @Test
  void doubleQuotedStringContainingSingleQuoteIsPreserved() {
    // The literal contains a `'` that must NOT terminate the `"..."` string.
    JsonPathSortAst.SortPath p =
        parser.parse("$.arr[?(@.name == \"with'apostrophe\")].value");
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate();
    assertEquals(
        "with'apostrophe",
        ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void stripWildcardsPreservesSquareStarInsideDoubleQuotedLiteral() {
    // Regression: stripWildcards must NOT remove [*] inside a "..." literal,
    // just as it doesn't inside a '...' literal. The opening quote (single or
    // double) opens a quote-active region until the same opening char repeats.
    JsonPathSortAst.SortPath p =
        parser.parse("$.arr[?(@.label == \"[*]\")].value");
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate();
    assertEquals("[*]", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void outerWrapDetectionSkipsParenInsideDoubleQuotedStringLiteral() {
    // Mirror of outerWrapDetectionSkipsParenInsideStringLiteral but with the
    // unbalanced parens inside a "..." literal. The depth counter must skip the
    // quoted region regardless of which quote style opened it.
    JsonPathSortAst.SortPath outer =
        parser.parse("num($.arr[?(@.id == \"with(paren\")].value)");
    JsonPathSortAst.Coercion num =
        assertInstanceOf(JsonPathSortAst.Coercion.class, outer.leaf());
    assertEquals(JsonPathSortAst.CoercionType.NUM, num.type());
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) outer.hops().get(0).predicate();
    assertEquals(
        "with(paren", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void outerWrapDetectionSkipsParenInsideStringLiteral() {
    // The outer-wrap detection walks parentheses depth-aware. A '(' inside a string
    // literal must NOT participate in the depth count, otherwise an inner quoted
    // unbalanced paren would silently disable outer-wrap detection.
    JsonPathSortAst.SortPath outer =
        parser.parse("num($.arr[?(@.id == 'with(paren')].value)");
    JsonPathSortAst.Coercion num =
        assertInstanceOf(JsonPathSortAst.Coercion.class, outer.leaf());
    assertEquals(JsonPathSortAst.CoercionType.NUM, num.type());
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) outer.hops().get(0).predicate();
    assertEquals(
        "with(paren", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void parsesNestedOuterWrap() {
    // num(min($.arr[?()].value)) — outer wraps compose recursively.
    JsonPathSortAst.SortPath p =
        parser.parse("num(min($.arr[?(@.id == 'X')].value))");
    JsonPathSortAst.Coercion num =
        assertInstanceOf(JsonPathSortAst.Coercion.class, p.leaf());
    JsonPathSortAst.Aggregator min =
        assertInstanceOf(JsonPathSortAst.Aggregator.class, num.inner());
    assertEquals(JsonPathSortAst.AggregatorOp.MIN, min.op());
  }

  @Test
  void leafWrapAndOuterWrapProduceIdenticalAstForCoercion() {
    // Since 2.1.3, the two grammars produce STRUCTURALLY IDENTICAL SortPaths
    // — no naked hop in either case. Earlier the leaf-wrap form promoted the
    // pre-function dotted segment to a naked hop, which gave different
    // structure but worse semantics on multi-value array intermediates
    // (first/last element converted instead of numeric extreme). Both forms
    // now flow through the translator's $map+$convert+$min/$max path.
    JsonPathSortAst.SortPath leafForm =
        parser.parse(
            "$.prodSpec[?(@.id == 'X')].productSpecCharacteristicValue.num(value)");
    JsonPathSortAst.SortPath outerForm =
        parser.parse(
            "num($.prodSpec[?(@.id == 'X')].productSpecCharacteristicValue.value)");
    assertEquals(leafForm, outerForm);
    assertEquals(1, leafForm.hops().size());
    JsonPathSortAst.Coercion leafCoercion =
        assertInstanceOf(JsonPathSortAst.Coercion.class, leafForm.leaf());
    assertEquals(JsonPathSortAst.CoercionType.NUM, leafCoercion.type());
    assertEquals(
        "productSpecCharacteristicValue.value",
        ((JsonPathSortAst.FieldRef) leafCoercion.inner()).fieldPath());
  }

  @Test
  void rejectsUnknownLeafFunctionName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse("$.arr[?(@.id == 'X')].cast(value)"));
  }

  @Test
  void rejectsLeafFunctionWithoutClosingParen() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse("$.arr[?(@.id == 'X')].num(value"));
  }

  @Test
  void rejectsTrailingInputAfterFunctionLeaf() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse("$.arr[?(@.id == 'X')].num(value)extra"));
  }

  @Test
  void rejectsFunctionCallInIntermediateSegment() {
    // num(value).field — function call before a more-path is rejected. The user
    // should put the function at the leaf or use the outer-wrap form.
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse("$.arr[?(@.id == 'X')].num(value).extra"));
  }

  private JsonPathSortAst.ComparisonOperator opOf(String expr) {
    return ((JsonPathSortAst.ComparisonPredicate) parser.parse(expr).hops().get(0).predicate())
        .op();
  }

  private JsonPathSortAst.ComparisonPredicate comparisonOf(String expr) {
    return (JsonPathSortAst.ComparisonPredicate) parser.parse(expr).hops().get(0).predicate();
  }
}
