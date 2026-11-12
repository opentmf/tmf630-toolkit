package org.opentmf.query.tmf630.jsonb.it.parity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * An enum with a {@code @JsonValue} wire value and NO String factory method: before 3.6.0 a URL
 * could name it only by its constant ({@code IN_PROGRESS}), never by the value TMF documents
 * ({@code inProgress}).
 */
public enum ParityStage {
  IN_PROGRESS("inProgress"),
  DONE("done");

  private final String wire;

  ParityStage(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
