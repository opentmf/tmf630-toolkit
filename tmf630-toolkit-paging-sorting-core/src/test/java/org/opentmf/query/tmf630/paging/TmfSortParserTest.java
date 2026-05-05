package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class TmfSortParserTest {

  @Test
  void parseSignedAndUnsignedTokens() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);

    Sort sort = parser.parse(List.of("-createdOn,+id,name"));

    List<Sort.Order> orders = sort.toList();
    assertEquals(3, orders.size());
    assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
    assertEquals("createdOn", orders.get(0).getProperty());
    assertEquals(Sort.Direction.ASC, orders.get(1).getDirection());
    assertEquals("id", orders.get(1).getProperty());
    assertEquals(Sort.Direction.ASC, orders.get(2).getDirection());
    assertEquals("name", orders.get(2).getProperty());
  }

  @Test
  void parseReturnsUnsortedOnEmptyInput() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);
    assertEquals(Sort.unsorted(), parser.parse(List.of("", "   ")));
  }

  @Test
  void parseRejectsNonAllowlistedField() {
    TmfSortParser parser = new TmfSortParser(List.of("id"), false);
    assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of("-createdOn")));
  }

  @Test
  void parseSupportsMultipleSortParametersAndSkipsInvalidTokens() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);
    Sort sort = parser.parse(List.of(",-name,+", "+id"));
    assertEquals(2, sort.toList().size());
    assertEquals("name", sort.toList().get(0).getProperty());
    assertEquals("id", sort.toList().get(1).getProperty());
  }

  @Test
  void parseRejectsNestedPropertyWhenDisabled() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);
    assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of("customer.name")));
  }

  @Test
  void parseReturnsUnsortedForNullInput() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    assertEquals(Sort.unsorted(), parser.parse(null));
  }

  @Test
  void parseTreatsLeadingWhitespaceAsAscendingDirection() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    Sort sort = parser.parse(List.of(" transformationId"));
    assertEquals("transformationId", sort.toList().get(0).getProperty());
    assertEquals(Sort.Direction.ASC, sort.toList().get(0).getDirection());
  }

  @Test
  void splitTopLevelHandlesPlainComma() {
    assertEquals(List.of("a", "b", "c"), TmfSortParser.splitTopLevel("a,b,c"));
  }

  @Test
  void splitTopLevelDoesNotSplitInsideBrackets() {
    assertEquals(List.of("a[1,2,3]", "b"), TmfSortParser.splitTopLevel("a[1,2,3],b"));
  }

  @Test
  void splitTopLevelDoesNotSplitInsideParens() {
    assertEquals(
        List.of("$.x[?(@.a == 'x' && @.b == 'y')].v", "+id"),
        TmfSortParser.splitTopLevel("$.x[?(@.a == 'x' && @.b == 'y')].v,+id"));
  }

  @Test
  void splitTopLevelDoesNotSplitInsideQuotes() {
    assertEquals(
        List.of("$.x[?(@.name == 'a,b')].v"),
        TmfSortParser.splitTopLevel("$.x[?(@.name == 'a,b')].v"));
  }

  @Test
  void splitTopLevelHandlesMixedNesting() {
    assertEquals(
        List.of(
            "-$.outer[?(@.inner[?(@.k == 'a,b')])].v",
            "arr[id=foo].field",
            "+id"),
        TmfSortParser.splitTopLevel(
            "-$.outer[?(@.inner[?(@.k == 'a,b')])].v,arr[id=foo].field,+id"));
  }

  @Test
  void classifyReturnsPlainForBareField() {
    assertEquals(TmfSortTerm.Kind.PLAIN, TmfSortParser.classify("name"));
    assertEquals(TmfSortTerm.Kind.PLAIN, TmfSortParser.classify("nested.field"));
  }

  @Test
  void classifyReturnsJsonPathForDollarPrefix() {
    assertEquals(
        TmfSortTerm.Kind.JSONPATH,
        TmfSortParser.classify("$.arr[?(@.id == 'X')].value"));
  }

  @Test
  void classifyReturnsSimpleRichForBracketWithoutDollarPrefix() {
    assertEquals(
        TmfSortTerm.Kind.SIMPLE_RICH,
        TmfSortParser.classify("arr[id=X].value"));
    assertEquals(
        TmfSortTerm.Kind.SIMPLE_RICH, TmfSortParser.classify("arr[X].value"));
  }

  @Test
  void classifyReturnsJsonPathForPredicateBracketWithoutDollarPrefix() {
    // Per TMF630, `$.` may be omitted. The presence of `[?(...)]` unambiguously
    // identifies the term as JsonPath even without the prefix.
    assertEquals(
        TmfSortTerm.Kind.JSONPATH,
        TmfSortParser.classify("arr[?(@.id == 'X')].value"));
    assertEquals(
        TmfSortTerm.Kind.JSONPATH,
        TmfSortParser.classify("statusChange[?(@.status == 'Pending')]"));
  }

  @Test
  void classifyReturnsJsonPathForWildcardBracketWithoutDollarPrefix() {
    assertEquals(TmfSortTerm.Kind.JSONPATH, TmfSortParser.classify("arr[*].value"));
  }

  @Test
  void parseRejectsJsonPathTermAsNotYetSupported() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(List.of("$.arr[?(@.id == 'X')].value")));
    assertTrue(ex.getMessage().contains("jsonpath"));
  }

  @Test
  void parseRejectsSimpleRichTermAsNotYetSupported() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> parser.parse(List.of("arr[id=X].value")));
    assertTrue(ex.getMessage().contains("simple-rich"));
  }

  @Test
  void parseRejectsMixedPlainAndJsonPathTermsBecauseOneIsNonPlain() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse(List.of("name,$.arr[?(@.id == 'X')].value")));
  }

  @Test
  void parseHonoursDirectionPrefixOnNonPlainTermBeforeRejecting() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(List.of("-$.arr[?(@.id == 'X')].value")));
    assertTrue(ex.getMessage().contains("$.arr"));
  }

  @Test
  void parseRichReturnsEmptyForNullOrBlankInput() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    assertTrue(parser.parseRich(null).isEmpty());
    assertTrue(parser.parseRich(List.of("", "  ")).isEmpty());
  }

  @Test
  void parseRichAcceptsPlainTermsLikeParse() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    TmfSort rich = parser.parseRich(List.of("-createdOn,+id"));
    assertEquals(2, rich.terms().size());
    assertEquals(TmfSortTerm.Kind.PLAIN, rich.terms().get(0).kind());
    assertEquals(Sort.Direction.DESC, rich.terms().get(0).direction());
    assertEquals("createdOn", rich.terms().get(0).expression());
    assertEquals(TmfSortTerm.Kind.PLAIN, rich.terms().get(1).kind());
    assertEquals(Sort.Direction.ASC, rich.terms().get(1).direction());
    assertEquals("id", rich.terms().get(1).expression());
    assertFalse(rich.requiresAggregation());
  }

  @Test
  void parseRichAcceptsJsonPathTerms() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    TmfSort rich =
        parser.parseRich(List.of("-$.arr[?(@.id == 'X')].value,+id"));
    assertEquals(2, rich.terms().size());
    assertEquals(TmfSortTerm.Kind.JSONPATH, rich.terms().get(0).kind());
    assertEquals(Sort.Direction.DESC, rich.terms().get(0).direction());
    assertEquals("$.arr[?(@.id == 'X')].value", rich.terms().get(0).expression());
    assertEquals(TmfSortTerm.Kind.PLAIN, rich.terms().get(1).kind());
    assertTrue(rich.requiresAggregation());
  }

  @Test
  void parseRichAcceptsSimpleRichTerms() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    TmfSort rich = parser.parseRich(List.of("arr[id=X].value"));
    assertEquals(1, rich.terms().size());
    assertEquals(TmfSortTerm.Kind.SIMPLE_RICH, rich.terms().get(0).kind());
    assertEquals("arr[id=X].value", rich.terms().get(0).expression());
    assertTrue(rich.requiresAggregation());
  }

  @Test
  void parseRichRunsAllowlistOnPlainTermsOnly() {
    TmfSortParser parser = new TmfSortParser(List.of("name"), true);
    assertThrows(
        IllegalArgumentException.class, () -> parser.parseRich(List.of("createdOn")));
    TmfSort rich = parser.parseRich(List.of("$.arr[?(@.id == 'X')].value"));
    assertEquals(1, rich.terms().size());
    assertEquals(TmfSortTerm.Kind.JSONPATH, rich.terms().get(0).kind());
  }
}
