package org.opentmf.query.tmf630.jsonb.it.parity;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An enum whose wire values are {@code @JsonProperty} names on its constants. */
public enum ParityPhase {
  @JsonProperty("phase-one")
  ONE,
  @JsonProperty("phase-two")
  TWO
}
