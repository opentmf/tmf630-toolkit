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

  private JsonPathSortAst.ComparisonOperator opOf(String expr) {
    return ((JsonPathSortAst.ComparisonPredicate) parser.parse(expr).hops().get(0).predicate())
        .op();
  }

  private JsonPathSortAst.ComparisonPredicate comparisonOf(String expr) {
    return (JsonPathSortAst.ComparisonPredicate) parser.parse(expr).hops().get(0).predicate();
  }
}
