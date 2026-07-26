package org.opentmf.query.tmf630.jsonb;

import java.util.List;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;

/**
 * Translates a {@link TmfOperator} + field-path + typed value(s) into a
 * {@link JsonbClause} — SQL fragment plus JDBC params — targeting a Postgres JSONB
 * payload column. Ships all 24 {@code TmfOperator} values per the mapping table in
 * {@code docs/JSONB_BACKEND_DESIGN.md} §5.
 *
 * <p>Regex uses Postgres native operators ({@code ~} / {@code ~*}), closing the JPA
 * gap #11 (see {@code docs/JPA_BACKEND_GAP_ANALYSIS.md} §3.6). Type coercion via
 * {@link JsonbCast} — the caller passes the domain-model Java type of the field;
 * numeric / date comparisons cast the extracted text before the comparator.
 *
 * <p>{@link IsnullSemantics#NULLISH} widens {@code IS_NULL}/{@code IS_NOT_NULL} to
 * include explicit JSON {@code null} values in addition to missing-key checks — per
 * §5.1 of the design doc, using an AND-of-complements form on the {@code IS_NOT_NULL}
 * side (Postgres's {@code ?} key-existence operator is always definite, so trilean
 * logic doesn't corrupt the complement the way it does on SQL {@code NOT IN (NULL)}).
 */
public class JsonbPredicateFactory {

  private final JsonbPathExtractor extractor;
  private final IsnullSemantics isnullSemantics;
  private final boolean regexEnabled;

  public JsonbPredicateFactory(
      JsonbPathExtractor extractor,
      IsnullSemantics isnullSemantics,
      boolean regexEnabled) {
    this.extractor = extractor;
    this.isnullSemantics = isnullSemantics == null ? IsnullSemantics.MISSING_ONLY : isnullSemantics;
    this.regexEnabled = regexEnabled;
  }

  /** Single-value operator (EQ/NE/EQI/NEI/GT/GTE/LT/LTE/LIKE/LIKEI/CONTAINS/CONTAINSI/ */
  public JsonbClause build(
      TmfOperator operator, String fieldPath, Class<?> targetType, Object value) {
    String text = extractor.extractAsText(fieldPath);
    JsonbCast cast = JsonbCast.forJavaType(targetType);
    String castedLhs = text + cast.suffix();
    return switch (operator) {
      case EQ -> JsonbClause.of(castedLhs + " = ?" + cast.suffix(), value);
      case NE -> JsonbClause.of(castedLhs + " <> ?" + cast.suffix(), value);
      case EQI -> JsonbClause.of("LOWER(" + text + ") = LOWER(?)", String.valueOf(value));
      case NEI -> JsonbClause.of("LOWER(" + text + ") <> LOWER(?)", String.valueOf(value));
      case GT -> JsonbClause.of(castedLhs + " > ?" + cast.suffix(), value);
      case GTE -> JsonbClause.of(castedLhs + " >= ?" + cast.suffix(), value);
      case LT -> JsonbClause.of(castedLhs + " < ?" + cast.suffix(), value);
      case LTE -> JsonbClause.of(castedLhs + " <= ?" + cast.suffix(), value);
      case LIKE -> JsonbClause.of(text + " LIKE ?", value);
      case LIKEI -> JsonbClause.of(text + " ILIKE ?", value);
      case CONTAINS -> JsonbClause.of(text + " LIKE '%' || ? || '%'", value);
      case CONTAINSI -> JsonbClause.of(text + " ILIKE '%' || ? || '%'", value);
      case STARTS_WITH -> JsonbClause.of(text + " LIKE ? || '%'", value);
      case STARTS_WITHI -> JsonbClause.of(text + " ILIKE ? || '%'", value);
      case ENDS_WITH -> JsonbClause.of(text + " LIKE '%' || ?", value);
      case ENDS_WITHI -> JsonbClause.of(text + " ILIKE '%' || ?", value);
      case REGEX -> {
        requireRegexEnabled();
        yield JsonbClause.of(text + " ~ ?", String.valueOf(value));
      }
      case REGEXI -> {
        requireRegexEnabled();
        yield JsonbClause.of(text + " ~* ?", String.valueOf(value));
      }
      case IS_NULL, IS_NOT_NULL ->
          throw new TmfFilteringException(
              "Use buildNoValue() for IS_NULL / IS_NOT_NULL: " + fieldPath);
      case BETWEEN, IN, NIN ->
          throw new TmfFilteringException(
              "Use buildMulti() for multi-value operators: " + fieldPath);
    };
  }

  /** Multi-value operator: IN, NIN, BETWEEN. */
  public JsonbClause buildMulti(
      TmfOperator operator, String fieldPath, Class<?> targetType, List<Object> values) {
    if (values == null || values.isEmpty()) {
      throw new TmfFilteringException(
          "Multi-value operator '" + operator.suffix() + "' requires at least one value.");
    }
    String text = extractor.extractAsText(fieldPath);
    JsonbCast cast = JsonbCast.forJavaType(targetType);
    return switch (operator) {
      case IN -> inClause(text, cast, values, false);
      case NIN -> inClause(text, cast, values, true);
      case BETWEEN -> betweenClause(text, cast, fieldPath, values);
      default ->
          throw new TmfFilteringException(
              "Not a multi-value operator: " + operator.suffix());
    };
  }

  /** No-value operator: IS_NULL, IS_NOT_NULL. */
  public JsonbClause buildNoValue(TmfOperator operator, String fieldPath) {
    return switch (operator) {
      case IS_NULL -> isNullClause(fieldPath);
      case IS_NOT_NULL -> isNotNullClause(fieldPath);
      default ->
          throw new TmfFilteringException(
              "Not a no-value operator: " + operator.suffix());
    };
  }

  // --- helpers ---

  private JsonbClause inClause(String text, JsonbCast cast, List<Object> values, boolean negate) {
    StringBuilder sb = new StringBuilder(text).append(cast.suffix());
    sb.append(negate ? " NOT IN (" : " IN (");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append("?").append(cast.suffix());
    }
    sb.append(")");
    return JsonbClause.of(sb.toString(), values.toArray());
  }

  private JsonbClause betweenClause(
      String text, JsonbCast cast, String fieldPath, List<Object> values) {
    if (values.size() != 2) {
      throw new TmfFilteringException(
          "BETWEEN requires exactly two values for field: " + fieldPath);
    }
    String casted = text + cast.suffix();
    return JsonbClause.of(
        casted + " BETWEEN ?" + cast.suffix() + " AND ?" + cast.suffix(),
        values.get(0),
        values.get(1));
  }

  private JsonbClause isNullClause(String fieldPath) {
    // §5.1: MISSING_ONLY = key absent; NULLISH = key absent OR explicit JSON null.
    // Top-level single-segment paths use the key-existence '?' operator; deeper paths
    // fall back to the direct extraction returning SQL NULL for both missing and
    // explicit-null cases (Postgres semantics — cannot distinguish at the extraction
    // level without a separate hasKey probe, which is only meaningful for the parent).
    boolean topLevel = !fieldPath.contains(".");
    if (isnullSemantics == IsnullSemantics.NULLISH) {
      if (topLevel) {
        return JsonbClause.of(
            "(NOT (" + extractor.hasTopLevelKey(fieldPath) + ") OR "
                + extractor.extractAsJsonb(fieldPath) + " = 'null'::jsonb)");
      }
      return JsonbClause.of(extractor.extractAsJsonb(fieldPath) + " IS NULL");
    }
    if (topLevel) {
      return JsonbClause.of("NOT (" + extractor.hasTopLevelKey(fieldPath) + ")");
    }
    return JsonbClause.of(extractor.extractAsJsonb(fieldPath) + " IS NULL");
  }

  private JsonbClause isNotNullClause(String fieldPath) {
    boolean topLevel = !fieldPath.contains(".");
    if (isnullSemantics == IsnullSemantics.NULLISH) {
      if (topLevel) {
        return JsonbClause.of(
            "(" + extractor.hasTopLevelKey(fieldPath) + " AND "
                + extractor.extractAsJsonb(fieldPath) + " <> 'null'::jsonb)");
      }
      return JsonbClause.of(extractor.extractAsJsonb(fieldPath) + " IS NOT NULL");
    }
    if (topLevel) {
      return JsonbClause.of(extractor.hasTopLevelKey(fieldPath));
    }
    return JsonbClause.of(extractor.extractAsJsonb(fieldPath) + " IS NOT NULL");
  }

  private void requireRegexEnabled() {
    if (!regexEnabled) {
      throw new TmfFilteringException("Regex operator is disabled.");
    }
  }
}
