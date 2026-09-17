package org.opentmf.query.tmf630.config;

import org.opentmf.query.tmf630.querylimits.Tmf630QueryLimitSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opentmf.tmf630.query-limits.*} — the request-side guard's limits. Its own prefix rather
 * than {@code paging.*}: the guard protects every mapping, not paged ones, and must not switch
 * off with {@code paging.enabled=false}.
 */
@ConfigurationProperties(prefix = "opentmf.tmf630.query-limits")
public class Tmf630QueryLimitsProperties {

  private boolean enabled = true;
  private int maxQueryStringLength = Tmf630QueryLimitSettings.DEFAULT.maxQueryStringLength();
  private int maxParamValueLength = Tmf630QueryLimitSettings.DEFAULT.maxParamValueLength();

  public Tmf630QueryLimitSettings toSettings() {
    return new Tmf630QueryLimitSettings(maxQueryStringLength, maxParamValueLength);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getMaxQueryStringLength() {
    return maxQueryStringLength;
  }

  public void setMaxQueryStringLength(int maxQueryStringLength) {
    this.maxQueryStringLength = maxQueryStringLength;
  }

  public int getMaxParamValueLength() {
    return maxParamValueLength;
  }

  public void setMaxParamValueLength(int maxParamValueLength) {
    this.maxParamValueLength = maxParamValueLength;
  }
}
