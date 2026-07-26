package org.opentmf.query.tmf630.config;

import java.util.ArrayList;
import java.util.List;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opentmf.tmf630.paging")
public class Tmf630PagingProperties {

  private boolean enabled = true;
  private int defaultLimit = 50;
  private int maxLimit = 500;
  private boolean strictMode = true;
  private boolean allowNestedSortProperties = false;
  private List<String> sortAllowlist = new ArrayList<>();
  private boolean nullsLast = false;

  public Tmf630PagingSettings toSettings() {
    return new Tmf630PagingSettings(
        enabled,
        defaultLimit,
        maxLimit,
        strictMode,
        allowNestedSortProperties,
        sortAllowlist,
        nullsLast);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getDefaultLimit() {
    return defaultLimit;
  }

  public void setDefaultLimit(int defaultLimit) {
    this.defaultLimit = defaultLimit;
  }

  public int getMaxLimit() {
    return maxLimit;
  }

  public void setMaxLimit(int maxLimit) {
    this.maxLimit = maxLimit;
  }

  public boolean isStrictMode() {
    return strictMode;
  }

  public void setStrictMode(boolean strictMode) {
    this.strictMode = strictMode;
  }

  public boolean isAllowNestedSortProperties() {
    return allowNestedSortProperties;
  }

  public void setAllowNestedSortProperties(boolean allowNestedSortProperties) {
    this.allowNestedSortProperties = allowNestedSortProperties;
  }

  public List<String> getSortAllowlist() {
    return sortAllowlist;
  }

  public void setSortAllowlist(List<String> sortAllowlist) {
    this.sortAllowlist = sortAllowlist;
  }

  public boolean isNullsLast() {
    return nullsLast;
  }

  public void setNullsLast(boolean nullsLast) {
    this.nullsLast = nullsLast;
  }
}
