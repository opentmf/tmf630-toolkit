package org.opentmf.query.tmf630.jsonb;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;

/**
 * URL operators on a {@link Tmf630JsonbColumn declared column}: the column compared with a typed
 * bind, in the same SQL the payload extraction uses ({@code C = ?}, {@code C BETWEEN ? AND ?},
 * {@code C IN (?, ?)}, {@code LOWER(C) = LOWER(?)}, a literal {@code LIKE} with {@code ESCAPE
 * '!'}). A NULL column, a field absent from the payload, answers as the extraction's NULL does:
 * no value operator matches it.
 */
final class JsonbColumnPredicates {

  private JsonbColumnPredicates() {}

  /** A single-value operator on {@code column}. */
  static JsonbClause single(TmfOperator operator, String column, Object rawValue) {
    Object value = bindable(rawValue);
    return switch (operator) {
      case EQ -> JsonbClause.of(column + " = ?", value);
      case NE -> JsonbClause.of(column + " <> ?", value);
      case GT -> JsonbClause.of(column + " > ?", value);
      case GTE -> JsonbClause.of(column + " >= ?", value);
      case LT -> JsonbClause.of(column + " < ?", value);
      case LTE -> JsonbClause.of(column + " <= ?", value);
      case EQI -> JsonbClause.of("LOWER(" + column + ") = LOWER(?)", String.valueOf(value));
      case NEI -> JsonbClause.of("LOWER(" + column + ") <> LOWER(?)", String.valueOf(value));
      case LIKE -> JsonbClause.of(column + " LIKE ?", value);
      case LIKEI -> JsonbClause.of(column + " ILIKE ?", value);
      case CONTAINS -> JsonbPredicateFactory.literalLike(column, " LIKE '%' || ? || '%'", value);
      case CONTAINSI -> JsonbPredicateFactory.literalLike(column, " ILIKE '%' || ? || '%'", value);
      case STARTS_WITH -> JsonbPredicateFactory.literalLike(column, " LIKE ? || '%'", value);
      case STARTS_WITHI -> JsonbPredicateFactory.literalLike(column, " ILIKE ? || '%'", value);
      case ENDS_WITH -> JsonbPredicateFactory.literalLike(column, " LIKE '%' || ?", value);
      case ENDS_WITHI -> JsonbPredicateFactory.literalLike(column, " ILIKE '%' || ?", value);
      case REGEX -> JsonbClause.of(column + " ~ ?", String.valueOf(value));
      case REGEXI -> JsonbClause.of(column + " ~* ?", String.valueOf(value));
      default ->
          throw new TmfFilteringException(
              "Operator requires dedicated handler: " + operator.suffix());
    };
  }

  /** A multi-value operator ({@code in}, {@code nin}, {@code between}) on {@code column}. */
  static JsonbClause multi(TmfOperator operator, String column, String fieldPath, List<Object> raw) {
    if (raw == null || raw.isEmpty()) {
      throw new TmfFilteringException(
          "Multi-value operator '" + operator.suffix() + "' requires at least one value.");
    }
    Object[] values = raw.stream().map(JsonbColumnPredicates::bindable).toArray();
    return switch (operator) {
      case IN -> JsonbClause.of(column + " IN (" + placeholders(values.length) + ")", values);
      case NIN -> JsonbClause.of(column + " NOT IN (" + placeholders(values.length) + ")", values);
      case BETWEEN -> {
        if (values.length != 2) {
          throw new TmfFilteringException(
              "BETWEEN requires exactly two values for field: " + fieldPath);
        }
        yield JsonbClause.of(column + " BETWEEN ? AND ?", values);
      }
      default ->
          throw new TmfFilteringException("Not a multi-value operator: " + operator.suffix());
    };
  }

  /** {@code isnull} / {@code isnotnull} on the column: absent or JSON null alike. */
  static JsonbClause noValue(TmfOperator operator, String column) {
    return switch (operator) {
      case IS_NULL -> JsonbClause.of(column + " IS NULL");
      case IS_NOT_NULL -> JsonbClause.of(column + " IS NOT NULL");
      default -> throw new TmfFilteringException("Not a no-value operator: " + operator.suffix());
    };
  }

  private static String placeholders(int n) {
    return String.join(", ", Collections.nCopies(n, "?"));
  }

  /** An {@link Instant} as the driver binds a {@code timestamptz}: an {@link OffsetDateTime} in UTC. */
  private static Object bindable(Object value) {
    return value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value;
  }
}
