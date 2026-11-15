package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** The free-JSON probe's JSONB domain: fields of an open JSON type, and TMF characteristics. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeJsonDomain(
    String id, Object o, Map<String, Object> m, JsonNode j, List<Characteristic> characteristic) {

  /** A TMF characteristic: a name and a value of any JSON type. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Characteristic(String name, JsonNode value) {}
}
