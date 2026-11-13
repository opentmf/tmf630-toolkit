package org.opentmf.query.tmf630.jsonb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The JSONB module's switches ({@code opentmf.tmf630.jsonb.*}, since 3.6.0). Every default is the
 * 3.5.2 behaviour; each switch is an opt-in.
 */
@ConfigurationProperties(prefix = "opentmf.tmf630.jsonb")
public class Tmf630JsonbProperties {

  /** How {@code EQ} and {@code IN} compare: {@code extraction} (default) or {@code containment}. */
  private JsonbEqualityMode equality = JsonbEqualityMode.EXTRACTION;

  /** What happens with a stored value a cast cannot read: {@code strict} (default) or {@code lenient}. */
  private JsonbCastMode castMode = JsonbCastMode.STRICT;

  public JsonbEqualityMode getEquality() {
    return equality;
  }

  public void setEquality(JsonbEqualityMode equality) {
    this.equality = equality;
  }

  public JsonbCastMode getCastMode() {
    return castMode;
  }

  public void setCastMode(JsonbCastMode castMode) {
    this.castMode = castMode;
  }
}
