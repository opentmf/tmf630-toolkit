package org.opentmf.query.tmf630.jsonb;

import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates a {@link TmfOperator} + field-path + typed value(s) into a
 * {@link JsonbClause} — SQL fragment plus JDBC params — targeting a Postgres JSONB
 * payload column. Ships all 24 {@code TmfOperator} values per the mapping table in
 * {@code docs/JSONB_BACKEND_DESIGN.md} §5.
 *
 * <p>{@code contains}, {@code startswith}, {@code endswith} and their case-insensitive forms
 * match their value literally: {@code %}, {@code _} and {@code !} in it are escaped under
 * {@code ESCAPE '!'}, as on the QueryDSL terminal (since 3.6.0; before, they were wildcards and a
 * backslash was PostgreSQL's implicit escape). {@code like} / {@code likei} take a pattern.
 *
 * <p>Regex uses Postgres native operators ({@code ~} / {@code ~*}), closing the JPA
 * gap #11 (see {@code docs/JPA_BACKEND_GAP_ANALYSIS.md} §3.6). Type coercion via
 * {@link JsonbCast} — the caller passes the domain-model Java type of the field;
 * numeric / date comparisons cast the extracted text before the comparator.
 *
 * <p>An {@link Enum} value is bound by what the payload holds for that constant: the value the
 * service's {@link ObjectMapper} writes for it, so {@code @JsonValue}, {@code @JsonProperty} on the
 * constants and the mapper's enum features all apply (constant {@code IN_PROGRESS} with wire value
 * {@code "inProgress"} is bound as {@code "inProgress"}). The JDBC driver cannot send an enum
 * constant itself. Without the service's mapper, a default {@link JsonMapper} decides: it honours
 * the annotations, not the service's own configuration. Note that Jackson 3 writes an enum by its
 * {@code toString()} by default ({@code EnumFeature.WRITE_ENUMS_USING_TO_STRING}), which equals
 * {@link Enum#name()} unless the enum overrides it.
 *
 * <p>{@link #buildOnObjectPath} / {@link #buildMultiOnObjectPath} (the URL grammar uses them)
 * emit {@code EQ} / {@code IN} on an object path by the factory's {@link JsonbEqualityMode}: the
 * extraction by default, as {@link #build} / {@link #buildMulti} always do, or containment,
 * {@code payload @> ?::jsonb}, which a GIN index on the payload serves (an opt-in, since 3.6.0).
 * A typed cast reads a stored value by the factory's {@link JsonbCastMode}: the plain cast by
 * default, which fails the request on a value it cannot read, or the guarded read (an opt-in).
 *
 * <p>{@link IsnullSemantics#NULLISH} widens {@code IS_NULL}/{@code IS_NOT_NULL} to
 * include explicit JSON {@code null} values in addition to missing-key checks — per
 * §5.1 of the design doc, using an AND-of-complements form on the {@code IS_NOT_NULL}
 * side (Postgres's {@code ?} key-existence operator is always definite, so trilean
 * logic doesn't corrupt the complement the way it does on SQL {@code NOT IN (NULL)}).
 */
public class JsonbPredicateFactory {

  private static final ObjectMapper DEFAULT_MAPPER = JsonMapper.builder().build();

  private final JsonbPathExtractor extractor;
  private final IsnullSemantics isnullSemantics;
  private final boolean regexEnabled;
  private final JsonbContainment containment;
  private final JsonbEqualityMode equalityMode;
  private final JsonbCastMode castMode;

  /** A factory that writes enum values with a default {@link JsonMapper}. */
  public JsonbPredicateFactory(
      JsonbPathExtractor extractor,
      IsnullSemantics isnullSemantics,
      boolean regexEnabled) {
    this(extractor, isnullSemantics, regexEnabled, (ObjectMapper) null);
  }

  /**
   * A factory that writes enum values with {@code mapper}, the mapper the service stores its
   * payloads with; {@code null} means a default {@link JsonMapper}.
   */
  public JsonbPredicateFactory(
      JsonbPathExtractor extractor,
      IsnullSemantics isnullSemantics,
      boolean regexEnabled,
      ObjectMapper mapper) {
    this(
        extractor,
        isnullSemantics,
        regexEnabled,
        mapper,
        JsonbEqualityMode.EXTRACTION,
        JsonbCastMode.STRICT);
  }

  /**
   * A factory whose {@code EQ} / {@code IN} compare by {@code equalityMode} and whose typed casts
   * read stored values by {@code castMode} ({@code null} means the 3.5.2 defaults,
   * {@link JsonbEqualityMode#EXTRACTION} and {@link JsonbCastMode#STRICT}).
   */
  public JsonbPredicateFactory(
      JsonbPathExtractor extractor,
      IsnullSemantics isnullSemantics,
      boolean regexEnabled,
      ObjectMapper mapper,
      JsonbEqualityMode equalityMode,
      JsonbCastMode castMode) {
    this.extractor = extractor;
    this.isnullSemantics = isnullSemantics == null ? IsnullSemantics.MISSING_ONLY : isnullSemantics;
    this.regexEnabled = regexEnabled;
    this.containment = new JsonbContainment(mapper == null ? DEFAULT_MAPPER : mapper);
    this.equalityMode = equalityMode == null ? JsonbEqualityMode.EXTRACTION : equalityMode;
    this.castMode = castMode == null ? JsonbCastMode.STRICT : castMode;
  }

  private JsonbPredicateFactory(JsonbPredicateFactory same, JsonbPathExtractor extractor) {
    this.extractor = extractor;
    this.isnullSemantics = same.isnullSemantics;
    this.regexEnabled = same.regexEnabled;
    this.containment = same.containment;
    this.equalityMode = same.equalityMode;
    this.castMode = same.castMode;
  }

  /** The same factory over another JSONB column (an array element, a split child's payload). */
  JsonbPredicateFactory forColumn(String column) {
    return new JsonbPredicateFactory(this, new JsonbPathExtractor(column));
  }

  JsonbPathExtractor extractor() {
    return extractor;
  }

  /**
   * Containment for {@code path = value} through the array steps of {@code path}, or empty when
   * the value's type has no fragment.
   */
  Optional<JsonbClause> containmentThrough(JsonbFieldPath path, Class<?> targetType, Object value) {
    if (equalityMode != JsonbEqualityMode.CONTAINMENT) {
      return Optional.empty();
    }
    String test = extractor.containsJsonb();
    return containment
        .fragment(path.segmentArray(), path.arrayFlags(), targetType, value)
        .map(fragment -> JsonbClause.of(test, fragment));
  }

  /**
   * {@link #build} for a field whose every path step is an object field: no array on the way and
   * no positional index. Under {@link JsonbEqualityMode#CONTAINMENT} (an opt-in), {@code EQ} on a
   * String, CharSequence, Enum, integer, decimal or Boolean field is emitted as containment,
   * {@code payload @> ?::jsonb}, with the value nested under the path in the JSON type the field
   * declares; a GIN index on the payload serves it. Under {@link JsonbEqualityMode#EXTRACTION}
   * (the default) it is exactly {@link #build}.
   *
   * <p>Containment is type-exact, as on the Mongo backend: it finds the value stored in the JSON
   * type the field declares, not one stored in another (a String field holding the number
   * {@code 42} is not found by {@code "42"}; an Integer field holding the string {@code "42"} is not
   * found by {@code 42}), and a value no cast can read no longer fails the query. Every other
   * operator, the date-time types, any other type and a {@code null} value are exactly
   * {@link #build}.
   */
  public JsonbClause buildOnObjectPath(
      TmfOperator operator, String fieldPath, Class<?> targetType, Object value) {
    if (operator == TmfOperator.EQ && equalityMode == JsonbEqualityMode.CONTAINMENT) {
      Optional<JsonbClause> contained = containment(fieldPath, targetType, value);
      if (contained.isPresent()) {
        return contained.get();
      }
    }
    return build(operator, fieldPath, targetType, value);
  }

  /**
   * {@link #buildMulti} for a field whose every path step is an object field: under
   * {@link JsonbEqualityMode#CONTAINMENT}, {@code IN} is an {@code OR} of containment fragments,
   * one per value, when every value has one (see
   * {@link #buildOnObjectPath}); otherwise, and for every other operator, exactly
   * {@link #buildMulti}.
   */
  public JsonbClause buildMultiOnObjectPath(
      TmfOperator operator, String fieldPath, Class<?> targetType, List<Object> values) {
    if (operator == TmfOperator.IN
        && equalityMode == JsonbEqualityMode.CONTAINMENT
        && values != null
        && !values.isEmpty()) {
      Optional<JsonbClause> anyOf = containmentAnyOf(fieldPath, targetType, values);
      if (anyOf.isPresent()) {
        return anyOf.get();
      }
    }
    return buildMulti(operator, fieldPath, targetType, values);
  }

  /** Single-value operator (EQ/NE/EQI/NEI/GT/GTE/LT/LTE/LIKE/LIKEI/CONTAINS/CONTAINSI/ */
  public JsonbClause build(
      TmfOperator operator, String fieldPath, Class<?> targetType, Object rawValue) {
    Object value = bindable(rawValue);
    String text = extractor.extractAsText(fieldPath);
    JsonbCast cast = JsonbCast.forJavaType(targetType);
    // Parenthesise the extraction before applying the cast — Postgres's :: binds
    // tighter than the ->> / #>> operators, so payload->>'x'::bigint would try to
    // cast the literal string 'x' to bigint instead of the extracted value.
    String castedLhs = cast.read(text, castMode);
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
      case CONTAINS -> literalLike(text, " LIKE '%' || ? || '%'", value);
      case CONTAINSI -> literalLike(text, " ILIKE '%' || ? || '%'", value);
      case STARTS_WITH -> literalLike(text, " LIKE ? || '%'", value);
      case STARTS_WITHI -> literalLike(text, " ILIKE ? || '%'", value);
      case ENDS_WITH -> literalLike(text, " LIKE '%' || ?", value);
      case ENDS_WITHI -> literalLike(text, " ILIKE '%' || ?", value);
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
      TmfOperator operator, String fieldPath, Class<?> targetType, List<Object> rawValues) {
    if (rawValues == null || rawValues.isEmpty()) {
      throw new TmfFilteringException(
          "Multi-value operator '" + operator.suffix() + "' requires at least one value.");
    }
    List<Object> values = rawValues.stream().map(this::bindable).toList();
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

  /**
   * A substring operator ({@code contains}, {@code startswith}, {@code endswith} and the
   * case-insensitive forms): {@code value} is matched literally, its {@code %}, {@code _} and
   * {@code !} escaped under an explicit {@code ESCAPE '!'}, and only the operator's own
   * {@code '%'} is a wildcard. {@code like} / {@code likei} bind their value as the pattern.
   *
   * <p>A value that is not text (a number, a boolean, a date) is bound as itself, as before 3.6.0:
   * PostgreSQL renders it as text ({@code 1.0} as {@code 1}, where Java's {@code "1.0"} would miss
   * a stored {@code 0.1} for {@code ?f.contains=1}), and that text holds no {@code %}, {@code _} or
   * {@code !}.
   */
  private static JsonbClause literalLike(String text, String operatorAndPattern, Object value) {
    Object bound =
        value instanceof CharSequence || value instanceof Character
            ? JsonbStringEscape.escapeLikeValue(String.valueOf(value))
            : value;
    return JsonbClause.of(
        text + operatorAndPattern + " ESCAPE '" + JsonbStringEscape.LIKE_ESCAPE + "'", bound);
  }

  /**
   * The value as the JDBC driver can bind it and as the payload holds it: an {@link Enum} by the
   * text the mapper writes for it ({@link Enum#name()} when the mapper writes no scalar).
   */
  private Object bindable(Object value) {
    if (!(value instanceof Enum<?> constant)) {
      return value;
    }
    return containment.writtenEnum(constant).map(JsonNode::asString).orElse(constant.name());
  }

  private Optional<JsonbClause> containment(String fieldPath, Class<?> targetType, Object value) {
    String test = extractor.containsJsonb();
    return containment
        .fragment(extractor.segments(fieldPath), targetType, value)
        .map(fragment -> JsonbClause.of(test, fragment));
  }

  private Optional<JsonbClause> containmentAnyOf(
      String fieldPath, Class<?> targetType, List<Object> values) {
    JsonbClause anyOf = JsonbClause.alwaysFalse();
    for (Object value : values) {
      Optional<JsonbClause> one = containment(fieldPath, targetType, value);
      if (one.isEmpty()) {
        return Optional.empty();
      }
      anyOf = anyOf.or(one.get());
    }
    return Optional.of(anyOf);
  }

  private JsonbClause inClause(String text, JsonbCast cast, List<Object> values, boolean negate) {
    String lhs = cast.read(text, castMode);
    StringBuilder sb = new StringBuilder(lhs);
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
    String casted = cast.read(text, castMode);
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
