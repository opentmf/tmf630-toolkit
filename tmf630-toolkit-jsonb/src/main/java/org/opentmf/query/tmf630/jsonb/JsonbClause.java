package org.opentmf.query.tmf630.jsonb;

import java.util.ArrayList;
import java.util.List;

/**
 * A composable JSONB SQL clause: a fragment intended to sit inside a WHERE clause,
 * plus its ordered JDBC parameters. Produced by {@link JsonbPredicateFactory} for each
 * per-operator translation and combined via {@link #and(JsonbClause)} /
 * {@link #or(JsonbClause)} / {@link #not()} at the resolver / fragment layer.
 *
 * <p>Fragments are always self-parenthesized when they contain a boolean operator,
 * so composition is safe without callers worrying about operator precedence. Empty
 * clauses (constant true / false) are permitted for identity handling during AND/OR
 * folding; {@link #alwaysTrue()} and {@link #alwaysFalse()} are the two constants.
 */
public record JsonbClause(String sql, List<Object> params) {

  private static final String TRUE_SQL = "TRUE";
  private static final String FALSE_SQL = "FALSE";

  public JsonbClause {
    if (sql == null) {
      throw new IllegalArgumentException("sql must not be null");
    }
    params = params == null ? List.of() : List.copyOf(params);
  }

  public static JsonbClause of(String sql, Object... params) {
    return new JsonbClause(sql, params == null ? List.of() : List.of(params));
  }

  public static JsonbClause alwaysTrue() {
    return new JsonbClause(TRUE_SQL, List.of());
  }

  public static JsonbClause alwaysFalse() {
    return new JsonbClause(FALSE_SQL, List.of());
  }

  public JsonbClause and(JsonbClause other) {
    if (other == null || TRUE_SQL.equals(other.sql)) {
      return this;
    }
    if (TRUE_SQL.equals(this.sql)) {
      return other;
    }
    if (FALSE_SQL.equals(this.sql) || FALSE_SQL.equals(other.sql)) {
      return alwaysFalse();
    }
    List<Object> merged = new ArrayList<>(this.params.size() + other.params.size());
    merged.addAll(this.params);
    merged.addAll(other.params);
    return new JsonbClause("(" + this.sql + " AND " + other.sql + ")", merged);
  }

  public JsonbClause or(JsonbClause other) {
    if (other == null || FALSE_SQL.equals(other.sql)) {
      return this;
    }
    if (FALSE_SQL.equals(this.sql)) {
      return other;
    }
    if (TRUE_SQL.equals(this.sql) || TRUE_SQL.equals(other.sql)) {
      return alwaysTrue();
    }
    List<Object> merged = new ArrayList<>(this.params.size() + other.params.size());
    merged.addAll(this.params);
    merged.addAll(other.params);
    return new JsonbClause("(" + this.sql + " OR " + other.sql + ")", merged);
  }

  public JsonbClause not() {
    if (TRUE_SQL.equals(this.sql)) {
      return alwaysFalse();
    }
    if (FALSE_SQL.equals(this.sql)) {
      return alwaysTrue();
    }
    return new JsonbClause("(NOT " + this.sql + ")", this.params);
  }
}
