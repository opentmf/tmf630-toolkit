package org.opentmf.query.tmf630.jsonb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opentmf.query.tmf630.filtering.Tmf630AttributeClause;
import org.opentmf.query.tmf630.filtering.Tmf630FilterExpression;
import org.opentmf.query.tmf630.filtering.Tmf630FilterParser;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.opentmf.query.tmf630.jsonb.JsonbPathPredicates.SplitChild;

/**
 * JSONB terminal of the TMF-630 attribute-filter URL grammar — the counterpart of the
 * QueryDSL-side {@code Tmf630PredicateArgumentResolver} terminal. The backend-neutral
 * {@link Tmf630FilterParser} does all grammar work; this class turns the resulting
 * {@link Tmf630FilterExpression} into one composed {@link JsonbClause}:
 *
 * <ul>
 *   <li>attribute clauses via {@link JsonbPredicateFactory#build}/{@code buildMulti}/
 *       {@code buildNoValue}, with elements ORed within a repeated-parameter group and
 *       groups combined per {@link Tmf630FilterSettings#combineRepeatedValues()} — the
 *       exact repeated-value semantics of the QueryDSL terminal;
 *   <li>the {@code filter=} JsonPath expression via
 *       {@link JsonbSplitAwareFilterTranslator}, combined with the attribute side per
 *       {@code filter.combineWithAttributes}.
 * </ul>
 *
 * <p>On an object path (every step an object field: no collection on the way, no positional
 * index), {@code EQ} and {@code IN} go through {@link JsonbPredicateFactory#buildOnObjectPath} /
 * {@link JsonbPredicateFactory#buildMultiOnObjectPath}: the extraction by default, as in 3.5.2, or
 * containment served by a GIN index on the payload under {@code opentmf.tmf630.jsonb.equality=
 * containment} (an opt-in since 3.6.0).
 *
 * <p>Field paths resolve against the <em>domain type</em> (the payload POJO, not the row
 * entity) with the same {@link FieldPathResolver} the QueryDSL terminal uses — identical
 * unknown-field detection and {@code List}-element type resolution — and values coerce
 * through the same {@link ValueConverter}. Nested paths are gated by
 * {@link Tmf630FilterSettings#allowNestedPathsDocdb()}: a JSONB payload is
 * document-shaped, so the DocDB policy applies, not the JPA one.
 */
public class Tmf630JsonbClauseBuilder {

  private final Tmf630FilterParser filterParser;
  private final Tmf630FilterSettings settings;
  private final FieldPathResolver fieldPathResolver;
  private final ValueConverter valueConverter;
  private final JsonbPredicateFactory predicateFactory;
  private final JsonbSplitAwareFilterTranslator filterTranslator;
  private final JsonbPathPredicates pathPredicates;
  private final boolean applyRegexMaxLength;

  /** A clause builder that applies no regex length cap, as before 3.6.0. */
  public Tmf630JsonbClauseBuilder(
      Tmf630FilterParser filterParser,
      Tmf630FilterSettings settings,
      FieldPathResolver fieldPathResolver,
      ValueConverter valueConverter,
      JsonbPredicateFactory predicateFactory,
      JsonbSplitAwareFilterTranslator filterTranslator) {
    this(
        filterParser,
        settings,
        fieldPathResolver,
        valueConverter,
        predicateFactory,
        filterTranslator,
        false);
  }

  /**
   * As the six-argument constructor; {@code applyRegexMaxLength} applies the core's
   * {@code regex.max-length} to {@code .regex} / {@code .regexi}
   * ({@code opentmf.tmf630.jsonb.apply-regex-max-length}, an opt-in since 3.6.0).
   */
  public Tmf630JsonbClauseBuilder(
      Tmf630FilterParser filterParser,
      Tmf630FilterSettings settings,
      FieldPathResolver fieldPathResolver,
      ValueConverter valueConverter,
      JsonbPredicateFactory predicateFactory,
      JsonbSplitAwareFilterTranslator filterTranslator,
      boolean applyRegexMaxLength) {
    this.applyRegexMaxLength = applyRegexMaxLength;
    this.filterParser = filterParser;
    this.settings = settings;
    this.fieldPathResolver = fieldPathResolver;
    this.valueConverter = valueConverter;
    this.predicateFactory = predicateFactory;
    this.filterTranslator = filterTranslator;
    this.pathPredicates = new JsonbPathPredicates(predicateFactory);
  }

  /**
   * Builds the WHERE clause for one request's filter surface against the given domain
   * type. Returns {@link JsonbClause#alwaysTrue()} when the request carries no filter —
   * callers can pass the result to {@code Tmf630JsonbFilterExecutor.findAll} unchanged.
   */
  public JsonbClause build(Class<?> domainType, Map<String, String[]> parameterMap) {
    return build(domainType, parameterMap, Set.of());
  }

  /**
   * As {@link #build(Class, Map)}, leaving the exact parameter names in {@code passThrough} to
   * the handler's own bindings — see {@code Tmf630PassThrough}.
   */
  public JsonbClause build(
      Class<?> domainType, Map<String, String[]> parameterMap, Set<String> passThrough) {
    Tmf630FilterExpression expression = filterParser.parse(domainType, parameterMap, passThrough);

    JsonbClause attributeClause = null;
    for (Tmf630AttributeClause clause : expression.attributeClauses()) {
      JsonbClause next = toClause(clause, domainType);
      if (next != null) {
        attributeClause = attributeClause == null ? next : attributeClause.and(next);
      }
    }

    JsonbClause jsonPathClause =
        expression.hasJsonPathFilter()
            ? translateJsonPath(domainType, expression.jsonPathFilter())
            : null;

    if (attributeClause == null && jsonPathClause == null) {
      return JsonbClause.alwaysTrue();
    }
    if (jsonPathClause == null) {
      return attributeClause;
    }
    if (attributeClause == null) {
      return jsonPathClause;
    }
    return expression.combineWithAttributes() == CombineMode.OR
        ? attributeClause.or(jsonPathClause)
        : attributeClause.and(jsonPathClause);
  }

  private JsonbClause toClause(Tmf630AttributeClause clause, Class<?> domainType) {
    ResolvedField resolvedField = resolveField(domainType, clause.fieldPath());
    if (resolvedField == null) {
      return null;
    }
    TmfOperator operator = clause.operator();
    Target target = target(domainType, clause.fieldPath());
    if (operator.isNoValueOperator()) {
      return target.anyElementForPresence()
          ? pathPredicates.noValue(operator, target.path(), target.split())
          : predicateFactory.buildNoValue(operator, resolvedField.fieldPath());
    }
    if (operator.isMultiValueOperator()) {
      return buildMultiValueClause(clause, resolvedField, target);
    }
    return buildRepeatedValueClause(clause, resolvedField, target);
  }

  /**
   * Where a clause's field lives: its path shape ({@code null} for a positional path, which keeps
   * the extraction) and, when it leads into a split collection, the child table.
   */
  private record Target(JsonbFieldPath path, SplitChild split) {

    /** isnull / isnotnull answer per element: the path crosses an array or enters a split. */
    boolean anyElementForPresence() {
      return path != null && (split != null || path.crossesArray());
    }

    /** Value operators answer per element: also when the leaf is a collection. */
    boolean anyElementForValues() {
      return path != null && (split != null || path.crossesArray() || path.leafIsCollection());
    }

    boolean objectPath() {
      return path != null && !anyElementForValues();
    }
  }

  private Target target(Class<?> domainType, String fieldPath) {
    if (fieldPath.indexOf('[') >= 0) {
      return new Target(null, null);
    }
    List<String> segments = List.of(fieldPath.split("\\."));
    List<Boolean> arrays = new ArrayList<>();
    for (int i = 1; i <= segments.size(); i++) {
      String prefix = String.join(".", segments.subList(0, i));
      arrays.add(fieldPathResolver.resolve(domainType, prefix, true).leafIsCollection());
    }
    JsonbFieldPath path = new JsonbFieldPath(segments, arrays);
    return new Target(path, splitChild(domainType, path));
  }

  /** The child table when the path's first step is a split collection of the domain type. */
  private SplitChild splitChild(Class<?> domainType, JsonbFieldPath path) {
    if (path.size() < 2) {
      return null;
    }
    String first = path.segments().get(0);
    return filterTranslator
        .registry()
        .forDomainType(domainType)
        .flatMap(
            metadata ->
                metadata.splitCollections().stream()
                    .filter(split -> split.fieldName().equals(first))
                    .findFirst()
                    .map(
                        split ->
                            new SplitChild(
                                split.childTable(),
                                split.parentIdColumn(),
                                metadata.tableName(),
                                split.payloadColumn())))
        .orElse(null);
  }

  private JsonbClause buildMultiValueClause(
      Tmf630AttributeClause clause, ResolvedField resolvedField, Target target) {
    List<Object> typedValues = new ArrayList<>();
    for (List<String> group : clause.valueGroups()) {
      for (String element : group) {
        typedValues.add(convert(element, resolvedField));
      }
    }
    TmfOperator operator = clause.operator();
    Class<?> type = resolvedField.javaType();
    if (target.anyElementForValues()) {
      return pathPredicates.multi(operator, target.path(), type, typedValues, target.split());
    }
    return target.objectPath()
        ? predicateFactory.buildMultiOnObjectPath(
            operator, resolvedField.fieldPath(), type, typedValues)
        : predicateFactory.buildMulti(operator, resolvedField.fieldPath(), type, typedValues);
  }

  private JsonbClause buildRepeatedValueClause(
      Tmf630AttributeClause clause, ResolvedField resolvedField, Target target) {
    JsonbClause perKey = null;
    for (List<String> group : clause.valueGroups()) {
      JsonbClause perValue = null;
      for (String element : group) {
        JsonbClause elementClause = elementClause(clause, resolvedField, target, element);
        perValue = perValue == null ? elementClause : perValue.or(elementClause);
      }
      if (perValue == null) {
        continue;
      }
      if (perKey == null) {
        perKey = perValue;
      } else {
        perKey =
            settings.combineRepeatedValues() == CombineMode.AND
                ? perKey.and(perValue)
                : perKey.or(perValue);
      }
    }
    return perKey;
  }

  private JsonbClause elementClause(
      Tmf630AttributeClause clause, ResolvedField field, Target target, String element) {
    Object value = convert(element, field);
    TmfOperator operator = clause.operator();
    requireRegexWithinCap(operator, field, value);
    if (target.anyElementForValues()) {
      return pathPredicates.single(operator, target.path(), field.javaType(), value, target.split());
    }
    return target.objectPath()
        ? predicateFactory.buildOnObjectPath(operator, field.fieldPath(), field.javaType(), value)
        : predicateFactory.build(operator, field.fieldPath(), field.javaType(), value);
  }

  /**
   * The regex switch, then (under {@code opentmf.tmf630.jsonb.apply-regex-max-length=true}, an
   * opt-in since 3.6.0) the length cap, {@code opentmf.tmf630.attribute-filtering.regex.max-length},
   * as the core applies them to {@code .regex} / {@code .regexi}, with its messages and order. By
   * default no cap applies, as before 3.6.0.
   */
  private void requireRegexWithinCap(TmfOperator operator, ResolvedField field, Object value) {
    if (operator != TmfOperator.REGEX && operator != TmfOperator.REGEXI) {
      return;
    }
    if (!settings.regexEnabled()) {
      throw new TmfFilteringException("Regex operator is disabled.");
    }
    if (applyRegexMaxLength
        && String.valueOf(value).length() > settings.limits().maxRegexLength()) {
      throw new TmfFilteringException("Regex pattern too long for field: " + field.fieldPath());
    }
  }

  private Object convert(String element, ResolvedField resolvedField) {
    return valueConverter.convert(element, resolvedField.javaType(), resolvedField.fieldPath());
  }

  private ResolvedField resolveField(Class<?> domainType, String fieldPath) {
    try {
      return fieldPathResolver.resolve(domainType, fieldPath, settings.allowNestedPathsDocdb());
    } catch (TmfFilteringException ex) {
      if (settings.onUnknownField() == UnknownParamBehavior.REJECT) {
        throw new TmfFilteringException("Unknown or disallowed field: " + fieldPath);
      }
      return null;
    }
  }

  /**
   * Mirrors {@code JsonPathFilterPredicateBuilder}'s settings gate — same error messages,
   * same disabled-check-before-blank-check ordering — so a {@code filter=} request behaves
   * identically on both backends before the backend-specific translation runs.
   */
  private JsonbClause translateJsonPath(Class<?> domainType, String expression) {
    if (!settings.jsonPathFilterEnabled()) {
      throw new TmfFilteringException("jsonPath filter parameter is disabled.");
    }
    if (expression == null || expression.isBlank()) {
      return null;
    }
    if (expression.length() > settings.jsonPathMaxLength()) {
      throw new TmfFilteringException("jsonPath filter expression is too long.");
    }
    return filterTranslator.translate(domainType, expression);
  }
}
