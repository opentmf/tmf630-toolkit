package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Domain POJO of {@link JsonbElementRow}: an array of leaves holding one field of every type a
 * typed cast reads, and an array of arrays of them. The unreadable-element IT only builds clauses
 * against this type and selects ids; it never deserialises a payload, so an element may store a
 * value no cast reads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonbElementDomain(String id, List<Leaf> items, List<Outer> outer) {

  /** One field per typed cast: bigint (two), numeric (two), boolean, date, time, timestamps. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Leaf(
      Integer n,
      Long l,
      BigDecimal d,
      Double f,
      Boolean b,
      LocalDate ld,
      OffsetDateTime t,
      LocalTime lt,
      LocalDateTime ldt) {}

  /** An element of the outer array: an array of leaves. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Outer(List<Leaf> inner) {}
}
