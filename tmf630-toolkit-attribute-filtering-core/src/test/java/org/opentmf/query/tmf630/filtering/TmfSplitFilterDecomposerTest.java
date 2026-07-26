package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Combinator;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Decomposition;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.SplitClauseRef;

class TmfSplitFilterDecomposerTest {

  private static final Set<String> SPLITS = Set.of("items", "characteristic");

  @Test
  @DisplayName("blank input → empty decomposition")
  void blankInput() {
    assertTrue(TmfSplitFilterDecomposer.decompose(null, SPLITS).isEmpty());
    assertTrue(TmfSplitFilterDecomposer.decompose("  ", SPLITS).isEmpty());
  }

  @Test
  @DisplayName("parent-only clause → parentOnlyFilter set, no split clauses")
  void parentOnly() {
    Decomposition d = TmfSplitFilterDecomposer.decompose("$[?(@.status == 'X')]", SPLITS);
    assertEquals(Optional.of("$[?(@.status == 'X')]"), d.parentOnlyFilter());
    assertTrue(d.splitClauses().isEmpty());
  }

  @Test
  @DisplayName("split-only clause → one split clause, no parent filter")
  void splitOnly() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose("$[?(@.items[?(@.state == 'Y')])]", SPLITS);
    assertTrue(d.parentOnlyFilter().isEmpty());
    assertEquals(
        List.of(new SplitClauseRef("items", "@.state == 'Y'")), d.splitClauses());
  }

  @Test
  @DisplayName("bare wrapper form [?(...)] accepted like the $-prefixed form")
  void bareWrapperAccepted() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose("[?(@.items[?(@.state == 'Y')])]", SPLITS);
    assertEquals(1, d.splitClauses().size());
  }

  @Test
  @DisplayName(
      "top-level conjunction of parent-only + split → separated parentOnlyFilter + one split")
  void parentAndSplitConjunction() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.status == 'X' && @.items[?(@.state == 'Y')])]", SPLITS);
    assertEquals(Optional.of("$[?(@.status == 'X')]"), d.parentOnlyFilter());
    assertEquals(
        List.of(new SplitClauseRef("items", "@.state == 'Y'")), d.splitClauses());
  }

  @Test
  @DisplayName("multiple parent conjuncts recombined with && in parentOnlyFilter")
  void multipleParentConjuncts() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.status == 'X' && @.priority > 5 && @.items[?(@.state == 'Y')])]", SPLITS);
    assertEquals(
        Optional.of("$[?(@.status == 'X' && @.priority > 5)]"), d.parentOnlyFilter());
    assertEquals(1, d.splitClauses().size());
  }

  @Test
  @DisplayName("multiple split correlations on different fields — one clause each")
  void multipleSplitFields() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.items[?(@.state == 'A')] && @.characteristic[?(@.name == 'color')])]",
            SPLITS);
    assertTrue(d.parentOnlyFilter().isEmpty());
    assertEquals(
        List.of(
            new SplitClauseRef("items", "@.state == 'A'"),
            new SplitClauseRef("characteristic", "@.name == 'color'")),
        d.splitClauses());
  }

  @Test
  @DisplayName(
      "multiple correlations on same field → separate clauses (diff-item semantics)")
  void multipleSplitClausesSameField() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.items[?(@.state == 'A')] && @.items[?(@.priority > 5)])]", SPLITS);
    assertEquals(
        List.of(
            new SplitClauseRef("items", "@.state == 'A'"),
            new SplitClauseRef("items", "@.priority > 5")),
        d.splitClauses());
  }

  @Test
  @DisplayName("inner compound predicate inside a split correlation is preserved as text")
  void compoundInnerPredicatePreserved() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.items[?(@.state == 'A' && @.priority > 5)])]", SPLITS);
    assertEquals(
        List.of(new SplitClauseRef("items", "@.state == 'A' && @.priority > 5")),
        d.splitClauses());
  }

  @Test
  @DisplayName("top-level || is accepted; combinator is OR")
  void topLevelOrAccepted() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.status == 'X' || @.items[?(@.state == 'Y')])]", SPLITS);
    assertEquals(Combinator.OR, d.combinator());
    assertEquals(Optional.of("$[?(@.status == 'X')]"), d.parentOnlyFilter());
    assertEquals(
        List.of(new SplitClauseRef("items", "@.state == 'Y'")), d.splitClauses());
  }

  @Test
  @DisplayName("OR-only clauses across two split fields → combinator OR, two splits, no parent")
  void topLevelOrTwoSplits() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.items[?(@.state == 'A')] || @.characteristic[?(@.name == 'B')])]", SPLITS);
    assertEquals(Combinator.OR, d.combinator());
    assertTrue(d.parentOnlyFilter().isEmpty());
    assertEquals(
        List.of(
            new SplitClauseRef("items", "@.state == 'A'"),
            new SplitClauseRef("characteristic", "@.name == 'B'")),
        d.splitClauses());
  }

  @Test
  @DisplayName("multiple parent-only OR clauses are rejoined with || in parentOnlyFilter")
  void parentOnlyOrRewrapped() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.status == 'A' || @.status == 'B' || @.items[?(@.state == 'X')])]", SPLITS);
    assertEquals(Combinator.OR, d.combinator());
    assertEquals(
        Optional.of("$[?(@.status == 'A' || @.status == 'B')]"), d.parentOnlyFilter());
    assertEquals(1, d.splitClauses().size());
  }

  @Test
  @DisplayName("mixed top-level && and || rejected with actionable message")
  void mixedTopLevelAndOrRejected() {
    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class,
            () ->
                TmfSplitFilterDecomposer.decompose(
                    "$[?(@.status == 'X' && @.priority > 5 || @.items[?(@.state == 'Y')])]",
                    SPLITS));
    assertTrue(
        ex.getMessage().contains("mixes top-level '&&' and '||'"), ex.getMessage());
  }

  @Test
  @DisplayName("plain AND decomposition still reports Combinator.AND")
  void combinatorAndForPlainAnd() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.status == 'X' && @.items[?(@.state == 'Y')])]", SPLITS);
    assertEquals(Combinator.AND, d.combinator());
  }

  @Test
  @DisplayName("split field referenced inside a non-top-level clause is rejected")
  void nestedSplitReferenceRejected() {
    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class,
            () ->
                TmfSplitFilterDecomposer.decompose(
                    "$[?(@.status == 'X' && (@.priority > 5 && @.items[?(@.state == 'Y')]))]",
                    SPLITS));
    assertTrue(
        ex.getMessage().contains("references a split field but is not a bare top-level"),
        ex.getMessage());
  }

  @Test
  @DisplayName("malformed wrapper rejected clearly")
  void malformedWrapperRejected() {
    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class,
            () -> TmfSplitFilterDecomposer.decompose("@.status == 'X'", SPLITS));
    assertTrue(ex.getMessage().contains("must be wrapped"), ex.getMessage());
  }

  @Test
  @DisplayName("|| inside a split's inner predicate is fine (only top-level matters)")
  void orInsideInnerPredicateAccepted() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.items[?(@.state == 'A' || @.state == 'B')])]", SPLITS);
    assertEquals(
        List.of(new SplitClauseRef("items", "@.state == 'A' || @.state == 'B'")),
        d.splitClauses());
  }

  @Test
  @DisplayName("clause referencing a NAME that is not in splitFieldNames stays parent-side")
  void unknownFieldNameStaysParentSide() {
    Decomposition d =
        TmfSplitFilterDecomposer.decompose(
            "$[?(@.status == 'X' && @.unknownField == 'Y')]", SPLITS);
    assertEquals(
        Optional.of("$[?(@.status == 'X' && @.unknownField == 'Y')]"), d.parentOnlyFilter());
    assertFalse(d.splitClauses().isEmpty() ? false : true);
    // Just to sanity-check emptiness.
    assertEquals(0, d.splitClauses().size());
  }
}
