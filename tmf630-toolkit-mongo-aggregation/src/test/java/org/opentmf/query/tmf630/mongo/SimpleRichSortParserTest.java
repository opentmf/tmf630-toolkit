package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SimpleRichSortParserTest {

  private final SimpleRichSortParser parser = new SimpleRichSortParser("id");

  @Test
  void parsesBareValueWithDefaultKey() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].value");

    assertEquals(1, p.hops().size());
    assertEquals("arr", p.hops().get(0).arrayPath());
    assertEquals("value", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
    JsonPathSortAst.ComparisonPredicate cp =
        assertInstanceOf(JsonPathSortAst.ComparisonPredicate.class, p.hops().get(0).predicate());
    assertEquals("id", cp.fieldPath());
    assertEquals("X", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void parsesExplicitKeyValuePair() {
    JsonPathSortAst.SortPath p = parser.parse("arr[name=foo].value");

    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate();
    assertEquals("name", cp.fieldPath());
    assertEquals("foo", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }

  @Test
  void honoursDefaultKeyOverride() {
    SimpleRichSortParser custom = new SimpleRichSortParser("code");
    JsonPathSortAst.SortPath p = custom.parse("arr[X].value");
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate();
    assertEquals("code", cp.fieldPath());
  }

  @Test
  void parsesTwoExplicitHops() {
    JsonPathSortAst.SortPath p = parser.parse("a[X].b[name=Y].leaf");

    assertEquals(2, p.hops().size());
    assertEquals("a", p.hops().get(0).arrayPath());
    assertEquals("b", p.hops().get(1).arrayPath());
    assertEquals("leaf", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
    assertEquals(
        "id",
        ((JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate()).fieldPath());
    assertEquals(
        "name",
        ((JsonPathSortAst.ComparisonPredicate) p.hops().get(1).predicate()).fieldPath());
  }

  @Test
  void treatsTrailingDottedPathAsSingleFieldRef() {
    JsonPathSortAst.SortPath p = parser.parse("a[X].b.c.value");

    // No function at the leaf, so the trailing dotted path is left intact as one
    // FieldRef. Mongo auto-traverses through `b` and `c` whether they are objects or
    // arrays — no naked array hops are introduced.
    assertEquals(1, p.hops().size());
    assertInstanceOf(
        JsonPathSortAst.ComparisonPredicate.class, p.hops().get(0).predicate());
    assertEquals("b.c.value", ((JsonPathSortAst.FieldRef) p.leaf()).fieldPath());
  }

  @Test
  void rejectsTermWithNoBracket() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("a.b.c"));
  }

  @Test
  void rejectsEmptyKey() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[=foo].v"));
  }

  @Test
  void rejectsEmptyValue() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[name=].v"));
  }

  @Test
  void rejectsEmptyBracket() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[].v"));
  }

  @Test
  void rejectsBracketWithSpaces() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[name=foo bar].v"));
  }

  @Test
  void rejectsBracketWithQuote() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[name='foo'].v"));
  }

  @Test
  void rejectsMissingClosingBracket() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[name=foo.v"));
  }

  @Test
  void rejectsMissingLeafAfterBracket() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[X]"));
  }

  @Test
  void rejectsMissingDotBetweenBrackets() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("a[X]b[Y].v"));
  }

  @Test
  void rejectsTrailingDot() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("arr[X]."));
  }

  @Test
  void rejectsNullInput() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
  }

  @Test
  void constructorRejectsBlankDefaultKey() {
    assertThrows(IllegalArgumentException.class, () -> new SimpleRichSortParser(""));
    assertThrows(IllegalArgumentException.class, () -> new SimpleRichSortParser(null));
  }

  @Test
  void parsesMaxAggregatorAtLeaf() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].max(value)");
    JsonPathSortAst.Aggregator agg =
        assertInstanceOf(JsonPathSortAst.Aggregator.class, p.leaf());
    assertEquals(JsonPathSortAst.AggregatorOp.MAX, agg.op());
    assertEquals("value", ((JsonPathSortAst.FieldRef) agg.inner()).fieldPath());
  }

  @Test
  void parsesMinAggregator() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].min(value)");
    JsonPathSortAst.Aggregator agg = (JsonPathSortAst.Aggregator) p.leaf();
    assertEquals(JsonPathSortAst.AggregatorOp.MIN, agg.op());
  }

  @Test
  void parsesStrCoercion() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].str(value)");
    JsonPathSortAst.Coercion c = assertInstanceOf(JsonPathSortAst.Coercion.class, p.leaf());
    assertEquals(JsonPathSortAst.CoercionType.STR, c.type());
    assertEquals("value", ((JsonPathSortAst.FieldRef) c.inner()).fieldPath());
  }

  @Test
  void parsesNumCoercion() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].num(value)");
    assertEquals(JsonPathSortAst.CoercionType.NUM, ((JsonPathSortAst.Coercion) p.leaf()).type());
  }

  @Test
  void parsesDateCoercion() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].date(value)");
    assertEquals(JsonPathSortAst.CoercionType.DATE, ((JsonPathSortAst.Coercion) p.leaf()).type());
  }

  @Test
  void parsesAggregatorOverCoercedField() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].max(str(value))");
    JsonPathSortAst.Aggregator agg = (JsonPathSortAst.Aggregator) p.leaf();
    JsonPathSortAst.Coercion c = (JsonPathSortAst.Coercion) agg.inner();
    assertEquals(JsonPathSortAst.CoercionType.STR, c.type());
    assertEquals("value", ((JsonPathSortAst.FieldRef) c.inner()).fieldPath());
  }

  @Test
  void parsesCoercionAroundAggregator() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].str(max(value))");
    JsonPathSortAst.Coercion c = (JsonPathSortAst.Coercion) p.leaf();
    assertEquals(JsonPathSortAst.CoercionType.STR, c.type());
    JsonPathSortAst.Aggregator agg = (JsonPathSortAst.Aggregator) c.inner();
    assertEquals(JsonPathSortAst.AggregatorOp.MAX, agg.op());
  }

  @Test
  void parsesNakedHopThenAggregatorLeaf() {
    JsonPathSortAst.SortPath p = parser.parse("arr[X].sub.max(value)");
    assertEquals(2, p.hops().size());
    assertInstanceOf(
        JsonPathSortAst.AlwaysTruePredicate.class, p.hops().get(1).predicate());
    JsonPathSortAst.Aggregator agg = (JsonPathSortAst.Aggregator) p.leaf();
    assertEquals(JsonPathSortAst.AggregatorOp.MAX, agg.op());
  }

  @Test
  void rejectsUnknownFunction() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("arr[X].foo(value)"));
  }

  @Test
  void rejectsFunctionAsNakedHop() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("arr[X].max(value).leaf"));
  }

  @Test
  void rejectsUnbalancedParensInLeaf() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("arr[X].max(value"));
  }

  @Test
  void acceptsOuterNumWrapperAroundSimpleRichExpression() {
    JsonPathSortAst.SortPath p = parser.parse("num(arr[X].value)");

    assertEquals(1, p.hops().size());
    assertEquals("arr", p.hops().get(0).arrayPath());
    JsonPathSortAst.Coercion c = assertInstanceOf(JsonPathSortAst.Coercion.class, p.leaf());
    assertEquals(JsonPathSortAst.CoercionType.NUM, c.type());
    assertEquals("value", ((JsonPathSortAst.FieldRef) c.inner()).fieldPath());
  }

  @Test
  void acceptsOuterStrWrapperAroundSimpleRichExpression() {
    JsonPathSortAst.SortPath p = parser.parse("str(arr[X].value)");
    JsonPathSortAst.Coercion c = (JsonPathSortAst.Coercion) p.leaf();
    assertEquals(JsonPathSortAst.CoercionType.STR, c.type());
  }

  @Test
  void acceptsOuterDateWrapperAroundSimpleRichExpression() {
    JsonPathSortAst.SortPath p = parser.parse("date(arr[X].value)");
    JsonPathSortAst.Coercion c = (JsonPathSortAst.Coercion) p.leaf();
    assertEquals(JsonPathSortAst.CoercionType.DATE, c.type());
  }

  @Test
  void acceptsOuterAndInnerWrapperComposingTogether() {
    // Outer num wraps the inner str's result.
    JsonPathSortAst.SortPath p = parser.parse("num(arr[X].str(value))");
    JsonPathSortAst.Coercion outer = (JsonPathSortAst.Coercion) p.leaf();
    assertEquals(JsonPathSortAst.CoercionType.NUM, outer.type());
    JsonPathSortAst.Coercion inner = (JsonPathSortAst.Coercion) outer.inner();
    assertEquals(JsonPathSortAst.CoercionType.STR, inner.type());
    assertEquals("value", ((JsonPathSortAst.FieldRef) inner.inner()).fieldPath());
  }

  @Test
  void acceptsRecursivelyNestedOuterWrappers() {
    JsonPathSortAst.SortPath p = parser.parse("num(str(arr[X].value))");
    JsonPathSortAst.Coercion outer = (JsonPathSortAst.Coercion) p.leaf();
    assertEquals(JsonPathSortAst.CoercionType.NUM, outer.type());
    JsonPathSortAst.Coercion inner = (JsonPathSortAst.Coercion) outer.inner();
    assertEquals(JsonPathSortAst.CoercionType.STR, inner.type());
  }

  @Test
  void acceptsOuterWrapperOnMultiHopExpression() {
    JsonPathSortAst.SortPath p = parser.parse("num(a[X].b[Y].leaf)");
    assertEquals(2, p.hops().size());
    JsonPathSortAst.Coercion c = (JsonPathSortAst.Coercion) p.leaf();
    assertEquals(JsonPathSortAst.CoercionType.NUM, c.type());
    assertEquals("leaf", ((JsonPathSortAst.FieldRef) c.inner()).fieldPath());
  }

  @Test
  void rejectsOuterWrapperOverBareDottedPathBecauseNoHopExists() {
    // Outer wrapper alone is not enough — at least one [...] hop must still be
    // present inside the wrapped expression.
    assertThrows(IllegalArgumentException.class, () -> parser.parse("num(a.b.c)"));
  }

  @Test
  void rejectsUnknownOuterWrapperFunction() {
    // foo() is not in the recognised outer-coercion set, so the parser treats
    // the whole expression as a single segment and the inner parsing complains
    // about the missing [...] hop.
    assertThrows(IllegalArgumentException.class, () -> parser.parse("foo(arr[X].value)"));
  }

  @Test
  void rejectsUnbalancedOuterWrapper() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("num(arr[X].value"));
  }

  @Test
  void allowsDottedKeyAndValueChars() {
    JsonPathSortAst.SortPath p = parser.parse("arr[name.subfield=foo-bar].v");
    JsonPathSortAst.ComparisonPredicate cp =
        (JsonPathSortAst.ComparisonPredicate) p.hops().get(0).predicate();
    assertEquals("name.subfield", cp.fieldPath());
    assertEquals("foo-bar", ((JsonPathSortAst.StringLiteral) cp.literal()).value());
  }
}
