package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Domain POJO of {@link JsonbTypedRow}: one field per scalar type the JSONB equality translates,
 * plus a nested object path, an array of objects and an array of strings. The equality-parity IT
 * only builds clauses against this type and selects ids; it never deserialises a payload, so the
 * rows may store values in another JSON type than the field declares.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonbTypedDomain(
    String id,
    String s,
    Integer n,
    Long l,
    BigDecimal d,
    Double f,
    Boolean b,
    Color e,
    Status w,
    LocalDate ld,
    OffsetDateTime t,
    Nested a,
    List<Nested> items,
    List<String> tags) {

  /** An enum stored by its name. */
  public enum Color {
    RED,
    GREEN
  }

  /**
   * An enum stored by its wire value, the shape of generated TMF enums: constant {@code
   * IN_PROGRESS}, payload value {@code "inProgress"}. No String factory method, so a URL can name it
   * only by its constant.
   */
  public enum Status {
    IN_PROGRESS("inProgress"),
    DONE("done");

    private final String wire;

    Status(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  /** A nested object with one string field. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Nested(String b) {}
}
