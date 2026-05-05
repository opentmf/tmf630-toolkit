package org.opentmf.query.tmf630.paging;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class TmfRichPageable implements Pageable {

  private final Pageable delegate;
  private final TmfSort tmfSort;

  public TmfRichPageable(Pageable delegate, TmfSort tmfSort) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.tmfSort = tmfSort == null ? TmfSort.empty() : tmfSort;
  }

  public TmfSort tmfSort() {
    return tmfSort;
  }

  @Override
  public int getPageNumber() {
    return delegate.getPageNumber();
  }

  @Override
  public int getPageSize() {
    return delegate.getPageSize();
  }

  @Override
  public long getOffset() {
    return delegate.getOffset();
  }

  @Override
  @NonNull
  public Sort getSort() {
    if (tmfSort.requiresAggregation()) {
      return Sort.unsorted();
    }
    return tmfSort.toPlainSort();
  }

  @Override
  @NonNull
  public Pageable next() {
    return new TmfRichPageable(delegate.next(), tmfSort);
  }

  @Override
  @NonNull
  public Pageable previousOrFirst() {
    return new TmfRichPageable(delegate.previousOrFirst(), tmfSort);
  }

  @Override
  @NonNull
  public Pageable first() {
    return new TmfRichPageable(delegate.first(), tmfSort);
  }

  @Override
  @NonNull
  public Pageable withPage(int pageNumber) {
    return new TmfRichPageable(delegate.withPage(pageNumber), tmfSort);
  }

  @Override
  public boolean hasPrevious() {
    return delegate.hasPrevious();
  }

  @Override
  public boolean isPaged() {
    return delegate.isPaged();
  }

  @Override
  public boolean isUnpaged() {
    return delegate.isUnpaged();
  }
}
