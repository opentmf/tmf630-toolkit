package org.opentmf.query.tmf630.paging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;

public final class TmfSort {

  private static final TmfSort EMPTY = new TmfSort(List.of());

  private final List<TmfSortTerm> terms;

  public TmfSort(List<TmfSortTerm> terms) {
    this.terms = terms == null ? List.of() : List.copyOf(terms);
  }

  public static TmfSort empty() {
    return EMPTY;
  }

  public List<TmfSortTerm> terms() {
    return terms;
  }

  public boolean isEmpty() {
    return terms.isEmpty();
  }

  public boolean requiresAggregation() {
    for (TmfSortTerm term : terms) {
      if (term.kind() != TmfSortTerm.Kind.PLAIN) {
        return true;
      }
    }
    return false;
  }

  public Sort toPlainSort() {
    if (requiresAggregation()) {
      throw new IllegalStateException(
          "TmfSort contains correlated terms; cannot be converted to a plain Spring Data Sort.");
    }
    if (terms.isEmpty()) {
      return Sort.unsorted();
    }
    List<Sort.Order> orders = new ArrayList<>(terms.size());
    for (TmfSortTerm term : terms) {
      orders.add(new Sort.Order(term.direction(), term.expression()));
    }
    return Sort.by(orders);
  }
}
