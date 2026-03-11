package org.opentmf.query.tmf630.paging.config;

import java.util.Collections;
import java.util.List;

public record Tmf630PagingSettings(
    boolean enabled,
    int defaultLimit,
    int maxLimit,
    boolean strictMode,
    boolean allowNestedSortProperties,
    List<String> sortAllowlist) {

  public Tmf630PagingSettings {
    sortAllowlist = sortAllowlist == null ? Collections.emptyList() : List.copyOf(sortAllowlist);
  }
}
