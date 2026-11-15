package org.opentmf.query.tmf630.jsonb.combo;

import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import tools.jackson.databind.JsonNode;

/** The Mongo twin of {@link FreeJsonDomain}; its documents are inserted raw. */
@Document(FreeJsonDoc.COLLECTION)
public record FreeJsonDoc(
    @Id String id,
    Object o,
    Map<String, Object> m,
    JsonNode j,
    List<FreeJsonDomain.Characteristic> characteristic) {

  public static final String COLLECTION = "free_json_doc";
}
