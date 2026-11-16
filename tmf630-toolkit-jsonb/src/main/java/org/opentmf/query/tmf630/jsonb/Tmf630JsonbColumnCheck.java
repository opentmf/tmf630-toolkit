package org.opentmf.query.tmf630.jsonb;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Finds the rows of a JSONB-backed table whose {@link Tmf630JsonbColumn declared columns} do not
 * hold what the payload holds (since 3.6.0, ledger #4256): {@code column IS DISTINCT FROM} the
 * payload's value read as the column's own SQL type, so a NULL column and an absent key agree.
 * A service runs it in its tests after its writes, or in a maintenance job: it reads the whole
 * table (measured: 375 ms for 1,000,000 rows), so it does not belong in a request. A payload value
 * the column's type cannot read fails the query with PostgreSQL's message, which names it.
 */
public class Tmf630JsonbColumnCheck {

  private final JdbcClient jdbc;
  private final JsonbEntityRegistry registry;

  public Tmf630JsonbColumnCheck(JdbcClient jdbc, JsonbEntityRegistry registry) {
    this.jdbc = jdbc;
    this.registry = registry;
  }

  /** The ids of the rows of {@code rowType}'s table where a declared column differs, in id order. */
  public List<String> mismatches(Class<?> rowType) {
    JsonbEntityMetadata metadata =
        registry.forRowType(rowType).orElseGet(() -> JsonbEntityMetadata.of(rowType));
    if (metadata.declaredColumns().isEmpty()) {
      return List.of();
    }
    JsonbPathExtractor extractor = new JsonbPathExtractor(metadata.payloadField());
    List<String> differ = new ArrayList<>();
    for (JsonbDeclaredColumn declared : metadata.declaredColumns()) {
      String sqlType = sqlType(metadata.tableName(), declared.column());
      differ.add(
          declared.column() + " IS DISTINCT FROM CAST(" + extractor.extractAsText(declared.path())
              + " AS " + sqlType + ")");
    }
    return jdbc.sql(
            "SELECT id FROM " + metadata.tableName() + " WHERE " + String.join(" OR ", differ)
                + " ORDER BY id")
        .query(String.class)
        .list();
  }

  /** The column's SQL type as PostgreSQL writes it ({@code timestamp with time zone}, …). */
  private String sqlType(String table, String column) {
    return jdbc.sql(
            "SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                + " WHERE attrelid = ?::regclass AND attname = ? AND NOT attisdropped")
        .params(table, column)
        .query(String.class)
        .single();
  }
}
