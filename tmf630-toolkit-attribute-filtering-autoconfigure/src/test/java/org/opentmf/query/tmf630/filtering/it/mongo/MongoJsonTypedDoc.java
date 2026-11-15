package org.opentmf.query.tmf630.filtering.it.mongo;

import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import tools.jackson.databind.JsonNode;

/**
 * Fields of an open JSON type ({@code Map}, {@code JsonNode}), an {@code Object} field beside them,
 * and TMF characteristics whose value is a {@code JsonNode}. Its documents are inserted raw, so a
 * field holds any JSON value; only ids are read back.
 */
@Document(MongoJsonTypedDoc.COLLECTION)
public record MongoJsonTypedDoc(
    @Id String id,
    Object o,
    Map<String, Object> m,
    JsonNode j,
    List<Characteristic> characteristic) {

  public static final String COLLECTION = "json_typed_doc";

  /** A TMF characteristic: a name and a value of any JSON type. */
  public record Characteristic(String name, JsonNode value) {}
}
