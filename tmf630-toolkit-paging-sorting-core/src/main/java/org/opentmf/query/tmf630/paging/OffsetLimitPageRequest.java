package org.opentmf.query.tmf630.paging;

import java.io.Serial;
import java.io.Serializable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class OffsetLimitPageRequest implements Pageable, Serializable {

  @Serial private static final long serialVersionUID = 1L;

  private final long offset;
  private final int limit;
  private final Sort sort;

  public OffsetLimitPageRequest(long offset, int limit, Sort sort) {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0");
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be > 0");
    }
    this.offset = offset;
    this.limit = limit;
    this.sort = sort == null ? Sort.unsorted() : sort;
  }

  @Override
  public int getPageNumber() {
    return Math.toIntExact(offset / limit);
  }

  @Override
  public int getPageSize() {
    return limit;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return sort;
  }

  @Override
  public Pageable next() {
    return new OffsetLimitPageRequest(offset + limit, limit, sort);
  }

  @Override
  public Pageable previousOrFirst() {
    return hasPrevious() ? new OffsetLimitPageRequest(offset - limit, limit, sort) : first();
  }

  @Override
  public Pageable first() {
    return new OffsetLimitPageRequest(0, limit, sort);
  }

  @Override
  public Pageable withPage(int pageNumber) {
    if (pageNumber < 0) {
      throw new IllegalArgumentException("pageNumber must be >= 0");
    }
    return new OffsetLimitPageRequest((long) pageNumber * limit, limit, sort);
  }

  @Override
  public boolean hasPrevious() {
    return offset >= limit;
  }
}
