package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class TmfRichPageableTest {

  @Test
  void delegatesPagingMethodsToWrappedPageable() {
    Pageable delegate = PageRequest.of(2, 5, Sort.by("id"));
    TmfRichPageable pageable = new TmfRichPageable(delegate, TmfSort.empty());

    assertEquals(2, pageable.getPageNumber());
    assertEquals(5, pageable.getPageSize());
    assertEquals(10, pageable.getOffset());
    assertTrue(pageable.isPaged());
    assertFalse(pageable.isUnpaged());
    assertTrue(pageable.hasPrevious());
  }

  @Test
  void getSortReturnsPlainSubsetWhenNoCorrelatedTerms() {
    TmfSort tmfSort =
        new TmfSort(
            List.of(
                new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "createdOn"),
                new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "id")));
    TmfRichPageable pageable = new TmfRichPageable(PageRequest.of(0, 10), tmfSort);

    Sort sort = pageable.getSort();
    assertEquals(2, sort.toList().size());
    assertEquals(Sort.Direction.DESC, sort.toList().get(0).getDirection());
    assertEquals("createdOn", sort.toList().get(0).getProperty());
  }

  @Test
  void getSortReturnsUnsortedWhenAnyTermIsCorrelated() {
    TmfSort tmfSort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.arr[?(@.id == 'X')].value")));
    TmfRichPageable pageable = new TmfRichPageable(PageRequest.of(0, 10), tmfSort);

    assertSame(Sort.unsorted(), pageable.getSort());
    assertTrue(pageable.tmfSort().requiresAggregation());
  }

  @Test
  void tmfSortAccessorReturnsTheCorrelatedAwareSortIntact() {
    TmfSort tmfSort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristic[name=price].value")));
    TmfRichPageable pageable = new TmfRichPageable(PageRequest.of(0, 10), tmfSort);

    assertSame(tmfSort, pageable.tmfSort());
  }

  @Test
  void navigationMethodsReturnTmfRichPageableCarryingTmfSort() {
    TmfSort tmfSort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.arr[?(@.id == 'X')].value")));
    TmfRichPageable page0 = new TmfRichPageable(PageRequest.of(0, 10), tmfSort);

    Pageable next = page0.next();
    assertInstanceOf(TmfRichPageable.class, next);
    assertSame(tmfSort, ((TmfRichPageable) next).tmfSort());

    Pageable previous = page0.previousOrFirst();
    assertInstanceOf(TmfRichPageable.class, previous);

    Pageable first = page0.first();
    assertInstanceOf(TmfRichPageable.class, first);

    Pageable withPage5 = page0.withPage(5);
    assertInstanceOf(TmfRichPageable.class, withPage5);
    assertEquals(5, withPage5.getPageNumber());
    assertSame(tmfSort, ((TmfRichPageable) withPage5).tmfSort());
  }

  @Test
  void nullTmfSortFallsBackToEmpty() {
    TmfRichPageable pageable = new TmfRichPageable(PageRequest.of(0, 10), null);
    assertTrue(pageable.tmfSort().isEmpty());
  }
}
