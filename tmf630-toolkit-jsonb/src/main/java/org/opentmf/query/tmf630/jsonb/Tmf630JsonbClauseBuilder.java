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
 * {@link JsonbPredicateFactory#buildMultiOnObjectPath}, so a GIN index on the payload serves them
 * (since 3.6.0).
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

  public Tmf630JsonbClauseBuilder(
      Tmf630FilterParser filterParser,
      Tmf630FilterSettings settings,
      FieldPathResolver fieldPathResolver,
      ValueConverter valueConverter,
      JsonbPredicateFactory predicateFactory,
      JsonbSplitAwareFilterTranslator filterTranslator) {
    this.filterParser = filterParser;
    this.settings = settings;
    this.fieldPathResolver = fieldPathResolver;
    this.valueConverter = valueConverter;
    this.predicateFactory = predicateFactory;
    this.filterTranslator = filterTranslator;
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
    if (operator.isNoValueOperator()) {
      return predicateFactory.buildNoValue(operator, resolvedField.fieldPath());
    }
    boolean objectPath = isObjectPath(domainType, clause.fieldPath());
    if (operator.isMultiValueOperator()) {
      return buildMultiValueClause(clause, resolvedField, objectPath);
    }
    return buildRepeatedValueClause(clause, resolvedField, objectPath);
  }

  /**
   * Whether every step of {@code fieldPath} is an object field: no positional index and no prefix
   * of the path (itself included) resolving to a collection.
   */
  private boolean isObjectPath(Class<?> domainType, String fieldPath) {
    if (fieldPath.indexOf('[') >= 0) {
      return false;
    }
    int dot = -1;
    do {
      dot = fieldPath.indexOf('.', dot + 1);
      String prefix = dot < 0 ? fieldPath : fieldPath.substring(0, dot);
      if (fieldPathResolver.resolve(domainType, prefix, true).leafIsCollection()) {
        return false;
      }
    } while (dot >= 0);
    return true;
  }

  private JsonbClause buildMultiValueClause(
      Tmf630AttributeClause clause, ResolvedField resolvedField, boolean objectPath) {
    List<Object> typedValues = new ArrayList<>();
    for (List<String> group : clause.valueGroups()) {
      for (String element : group) {
        typedValues.add(convert(element, resolvedField));
      }
    }
    return objectPath
        ? predicateFactory.buildMultiOnObjectPath(
            clause.operator(), resolvedField.fieldPath(), resolvedField.javaType(), typedValues)
        : predicateFactory.buildMulti(
            clause.operator(), resolvedField.fieldPath(), resolvedField.javaType(), typedValues);
  }

  private JsonbClause buildRepeatedValueClause(
      Tmf630AttributeClause clause, ResolvedField resolvedField, boolean objectPath) {
    JsonbClause perKey = null;
    for (List<String> group : clause.valueGroups()) {
      JsonbClause perValue = null;
      for (String element : group) {
        JsonbClause elementClause = elementClause(clause, resolvedField, objectPath, element);
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
      Tmf630AttributeClause clause, ResolvedField field, boolean objectPath, String element) {
    Object value = convert(element, field);
    return objectPath
        ? predicateFactory.buildOnObjectPath(
            clause.operator(), field.fieldPath(), field.javaType(), value)
        : predicateFactory.build(clause.operator(), field.fieldPath(), field.javaType(), value);
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
