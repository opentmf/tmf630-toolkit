package org.opentmf.query.tmf630.jsonb;

import java.util.Optional;

/**
 * Structured result of parsing a JSONB sort term. Captures the outer wrapper stack —
 * optional aggregator ({@code min()} / {@code max()}) and optional coercion
 * ({@code num()} / {@code str()} / {@code date()}) — plus the translated Postgres
 * SQL/JSON path expression they wrap.
 *
 * <p>Emission side lives in {@link JsonbSortBuilder}:
 *
 * <ul>
 *   <li><b>With aggregator</b> — {@code (SELECT AGG(v #>> '{}')::cast FROM
 *       jsonb_path_query(payload, ?::jsonpath) AS v)}. Iterates over every match of the
 *       path and applies the aggregate.
 *   <li><b>Without aggregator</b> — {@code ((jsonb_path_query_first(payload,
 *       ?::jsonpath)) #>> '{}')::cast}. Picks the first match (or NULL if no match).
 * </ul>
 *
 * <p>{@code coercion} overrides the fieldTypeResolver-derived cast when present; when
 * absent, the emission falls back to whatever cast the resolver picks for the leaf
 * field's Java type.
 */
public record JsonbSortExpression(
    Optional<Aggregator> aggregator, Optional<JsonbCast> coercion, String jsonPath) {

  public JsonbSortExpression {
    if (jsonPath == null || jsonPath.isBlank()) {
      throw new IllegalArgumentException("jsonPath must not be blank");
    }
    aggregator = aggregator == null ? Optional.empty() : aggregator;
    coercion = coercion == null ? Optional.empty() : coercion;
  }

  public enum Aggregator {
    MIN("MIN"),
    MAX("MAX");

    private final String sqlName;

    Aggregator(String sqlName) {
      this.sqlName = sqlName;
    }

    public String sqlName() {
      return sqlName;
    }
  }
}
