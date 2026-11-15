package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Domain of {@link JsonTypedRow}: fields of an open JSON type ({@code Map}, {@code JsonNode}), an
 * {@code Object} field beside them, and TMF characteristics whose value is a {@code JsonNode}. Only
 * clauses are built against it; no payload is deserialised.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonTypedDomain(
    String id, Object o, Map<String, Object> m, JsonNode j, List<Characteristic> characteristic) {

  /** A TMF characteristic: a name and a value of any JSON type. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Characteristic(String name, JsonNode value) {}
}
