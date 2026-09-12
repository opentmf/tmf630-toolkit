package org.opentmf.query.tmf630.paging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;

public record TmfSort(List<TmfSortTerm> terms) {

  private static final TmfSort EMPTY = new TmfSort(List.of());

  public TmfSort {
    terms = terms == null ? List.of() : List.copyOf(terms);
  }

  public static TmfSort empty() {
    return EMPTY;
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

  /**
   * The expressions of the {@link TmfSortTerm.Kind#PLAIN PLAIN} terms, in request order —
   * correlated terms are skipped. What a {@link TmfSortKeyValidator} checks.
   */
  public List<String> plainKeys() {
    List<String> keys = new ArrayList<>(terms.size());
    for (TmfSortTerm term : terms) {
      if (term.kind() == TmfSortTerm.Kind.PLAIN) {
        keys.add(term.expression());
      }
    }
    return keys;
  }

  public Sort toPlainSort() {
    return toPlainSort(false);
  }

  public Sort toPlainSort(boolean nullsLast) {
    if (requiresAggregation()) {
      throw new IllegalStateException(
          "TmfSort contains correlated terms; cannot be converted to a plain Spring Data Sort.");
    }
    if (terms.isEmpty()) {
      return Sort.unsorted();
    }
    List<Sort.Order> orders = new ArrayList<>(terms.size());
    for (TmfSortTerm term : terms) {
      Sort.Order order = new Sort.Order(term.direction(), term.expression());
      orders.add(nullsLast ? order.nullsLast() : order);
    }
    return Sort.by(orders);
  }
}
