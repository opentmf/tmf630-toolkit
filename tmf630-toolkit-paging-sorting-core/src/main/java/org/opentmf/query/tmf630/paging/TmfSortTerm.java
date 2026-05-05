package org.opentmf.query.tmf630.paging;

import org.springframework.data.domain.Sort;

public record TmfSortTerm(Sort.Direction direction, Kind kind, String expression) {

  public enum Kind {
    PLAIN,
    SIMPLE_RICH,
    JSONPATH
  }
}
