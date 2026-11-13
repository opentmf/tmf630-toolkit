package org.opentmf.query.tmf630.jsonb.combo;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The combination suite's rows: every kind's stored values at every position, in the not-split
 * table and in the split parent and child tables. Shared by the suite and the compatibility report,
 * so both read the same data.
 */
final class ComboSeed {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private ComboSeed() {}

  /** The logical documents, id to payload, of the not-split entity. */
  static void documents(Map<String, JsonNode> rows, Map<String, JsonNode> splitRows) {
    for (ComboKind kind : ComboKind.values()) {
      for (String[] stored : kind.storedValues()) {
        ObjectNode leaf = JSON.createObjectNode().set(kind.key(), JSON.readTree(stored[1]));
        for (ComboPosition position : ComboRequests.NOT_SPLIT) {
          String id = position.name().toLowerCase() + "." + kind.key() + "." + stored[0];
          rows.put(id, position.payload(id, leaf));
        }
        String id = "split." + kind.key() + "." + stored[0];
        splitRows.put(id, ComboPosition.SPLIT.payload(id, leaf));
      }
    }
  }

  /** Creates the three tables and writes {@code rows} and {@code splitRows} into them. */
  static void write(JdbcClient jdbc, Map<String, JsonNode> rows, Map<String, JsonNode> splitRows) {
    jdbc.sql("DROP TABLE IF EXISTS " + ComboSplitRow.CHILD_TABLE + " CASCADE").update();
    jdbc.sql("DROP TABLE IF EXISTS " + ComboSplitRow.TABLE + " CASCADE").update();
    jdbc.sql("DROP TABLE IF EXISTS " + ComboRow.TABLE + " CASCADE").update();
    jdbc.sql("CREATE TABLE " + ComboRow.TABLE + " (id VARCHAR(80) PRIMARY KEY, payload JSONB NOT NULL)")
        .update();
    jdbc.sql(
            "CREATE TABLE " + ComboSplitRow.TABLE
                + " (id VARCHAR(80) PRIMARY KEY, payload JSONB NOT NULL)")
        .update();
    jdbc.sql(
            "CREATE TABLE " + ComboSplitRow.CHILD_TABLE + " (parent_id VARCHAR(80) NOT NULL"
                + " REFERENCES " + ComboSplitRow.TABLE + "(id) ON DELETE CASCADE,"
                + " item_id VARCHAR(50) NOT NULL, item_order INTEGER NOT NULL,"
                + " payload JSONB NOT NULL, PRIMARY KEY (parent_id, item_id))")
        .update();
    rows.forEach((id, doc) -> insert(jdbc, ComboRow.TABLE, id, doc));
    splitRows.forEach(
        (id, doc) -> {
          insert(jdbc, ComboSplitRow.TABLE, id, JSON.createObjectNode().put("id", id));
          List<JsonNode> items = doc.get("items").valueStream().toList();
          for (int order = 0; order < items.size(); order++) {
            jdbc.sql(
                    "INSERT INTO " + ComboSplitRow.CHILD_TABLE
                        + " (parent_id, item_id, item_order, payload) VALUES (?, ?, ?, ?::jsonb)")
                .params(id, "c" + order, order, items.get(order).toString())
                .update();
          }
        });
  }

  private static void insert(JdbcClient jdbc, String table, String id, JsonNode payload) {
    jdbc.sql("INSERT INTO " + table + " (id, payload) VALUES (?, ?::jsonb)")
        .params(id, payload.toString())
        .update();
  }
}
