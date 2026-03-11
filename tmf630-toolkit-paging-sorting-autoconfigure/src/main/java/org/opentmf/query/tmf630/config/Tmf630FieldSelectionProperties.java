package org.opentmf.query.tmf630.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opentmf.tmf630.field-selection")
public class Tmf630FieldSelectionProperties {

  private boolean enabled = true;
  private int defaultDepth = 1;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getDefaultDepth() {
    return defaultDepth;
  }

  public void setDefaultDepth(int defaultDepth) {
    this.defaultDepth = defaultDepth;
  }
}
