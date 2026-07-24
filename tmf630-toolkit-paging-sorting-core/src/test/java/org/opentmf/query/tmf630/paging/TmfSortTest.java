package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class TmfSortTest {

  @Test
  void emptyReturnsSingletonAndIsEmpty() {
    TmfSort empty = TmfSort.empty();
    assertSame(empty, TmfSort.empty());
    assertTrue(empty.isEmpty());
    assertFalse(empty.requiresAggregation());
    assertEquals(Sort.unsorted(), empty.toPlainSort());
  }

  @Test
  void plainTermsDoNotRequireAggregation() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "createdOn"),
                new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "id")));

    assertFalse(sort.requiresAggregation());

    Sort plain = sort.toPlainSort();
    assertEquals(2, plain.toList().size());
    assertEquals(Sort.Direction.DESC, plain.toList().get(0).getDirection());
    assertEquals("createdOn", plain.toList().get(0).getProperty());
    assertEquals(Sort.Direction.ASC, plain.toList().get(1).getDirection());
    assertEquals("id", plain.toList().get(1).getProperty());
  }

  @Test
  void anyJsonPathTermFlipsRequiresAggregation() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "id"),
                new TmfSortTerm(
                    Sort.Direction.DESC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.arr[?(@.id == 'X')].value")));

    assertTrue(sort.requiresAggregation());
  }

  @Test
  void anySimpleRichTermFlipsRequiresAggregation() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC, TmfSortTerm.Kind.SIMPLE_RICH, "arr[id=X].value")));

    assertTrue(sort.requiresAggregation());
  }

  @Test
  void toPlainSortThrowsWhenAnyCorrelatedTermPresent() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.arr[?(@.id == 'X')].value")));

    assertThrows(IllegalStateException.class, sort::toPlainSort);
  }

  @Test
  void termsListIsImmutable() {
    TmfSortTerm term =
        new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "id");
    TmfSort sort = new TmfSort(List.of(term));
    List<TmfSortTerm> terms = sort.terms();
    TmfSortTerm extra = new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "x");
    assertThrows(UnsupportedOperationException.class, () -> terms.add(extra));
  }

  @Test
  void nullTermsListYieldsEmptyTmfSort() {
    TmfSort sort = new TmfSort(null);
    assertTrue(sort.isEmpty());
    assertEquals(Sort.unsorted(), sort.toPlainSort());
  }
}
