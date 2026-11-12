package org.opentmf.query.tmf630.jsonb.it.parity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Each constant's wire value is the OTHER constant's name, so the text {@code LEFT} is both the
 * name of {@link #LEFT} and the wire value of {@link #RIGHT}. A URL text resolves by the name.
 */
public enum ParityClash {
  LEFT("RIGHT"),
  RIGHT("LEFT");

  private final String wire;

  ParityClash(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
