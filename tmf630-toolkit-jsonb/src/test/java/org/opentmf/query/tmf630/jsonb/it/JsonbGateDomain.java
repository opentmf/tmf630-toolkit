package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedDomain.Color;
import tools.jackson.databind.JsonNode;

/**
 * Domain POJO of {@link JsonbGateRow}: one field per type family the type gate judges (string,
 * number, boolean, enum, date, date-time, the polymorphic Object, and the JSON-typed Map and
 * JsonNode), plus an array of elements carrying a string, a number and the two JSON-typed fields.
 * The type-gate IT only builds clauses against this type and selects ids.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonbGateDomain(
    String id,
    String s,
    Integer i,
    BigDecimal d,
    Boolean b,
    Color e,
    LocalDate ld,
    OffsetDateTime t,
    Object o,
    Map<String, String> m,
    JsonNode j,
    List<Item> items,
    List<String> tags) {

  /** An array element. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(String b, Integer i, Map<String, String> m, JsonNode j) {}
}
