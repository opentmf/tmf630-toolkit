package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonValue;

/** An enum stored by a {@code @JsonValue} wire value, without a String factory. */
public enum ComboStage {
  IN_PROGRESS("inProgress"),
  DONE("done");

  private final String wire;

  ComboStage(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
