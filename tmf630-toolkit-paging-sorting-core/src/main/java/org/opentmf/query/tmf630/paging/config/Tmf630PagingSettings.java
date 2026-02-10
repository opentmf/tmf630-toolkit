package org.opentmf.query.tmf630.paging.config;

import java.util.Collections;
import java.util.List;

public final class Tmf630PagingSettings {

  private final boolean enabled;
  private final int defaultLimit;
  private final int maxLimit;
  private final boolean strictMode;
  private final boolean allowNestedSortProperties;
  private final List<String> sortAllowlist;

  public Tmf630PagingSettings(
      boolean enabled,
      int defaultLimit,
      int maxLimit,
      boolean strictMode,
      boolean allowNestedSortProperties,
      List<String> sortAllowlist) {
    this.enabled = enabled;
    this.defaultLimit = defaultLimit;
    this.maxLimit = maxLimit;
    this.strictMode = strictMode;
    this.allowNestedSortProperties = allowNestedSortProperties;
    this.sortAllowlist = sortAllowlist == null ? Collections.emptyList() : List.copyOf(sortAllowlist);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getDefaultLimit() {
    return defaultLimit;
  }

  public int getMaxLimit() {
    return maxLimit;
  }

  public boolean isStrictMode() {
    return strictMode;
  }

  public boolean isAllowNestedSortProperties() {
    return allowNestedSortProperties;
  }

  public List<String> getSortAllowlist() {
    return sortAllowlist;
  }
}
