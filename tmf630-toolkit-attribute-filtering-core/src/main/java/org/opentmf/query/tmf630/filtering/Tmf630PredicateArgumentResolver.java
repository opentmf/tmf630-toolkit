package org.opentmf.query.tmf630.filtering;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.springframework.core.MethodParameter;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * QueryDSL terminal of the TMF-630 attribute-filter URL grammar. All grammar-level work —
 * reserved-parameter skipping, encoded-operator normalization, value-list splitting,
 * allowlists, limits — lives in the backend-neutral {@link Tmf630FilterParser}; this class
 * turns the parsed {@link Tmf630FilterExpression} into a QueryDSL {@link Predicate} via
 * {@link FieldPathResolver} / {@link ValueConverter} / {@link PredicateFactory}, plus the
 * {@code filter=} JsonPath expression via {@link JsonPathFilterPredicateBuilder}.
 */
public class Tmf630PredicateArgumentResolver implements HandlerMethodArgumentResolver {

  private final Tmf630FilterParser filterParser;
  private final Tmf630FilterSettings settings;
  private final FieldAllowlistProvider allowlistProvider;
  private final FieldPathResolver pathResolver;
  private final ValueConverter valueConverter;
  private final PredicateFactory predicateFactory;
  private final JsonPathFilterPredicateBuilder jsonPathFilterPredicateBuilder;

  public Tmf630PredicateArgumentResolver(
      ParamKeyParser keyParser,
      Tmf630FilterSettings settings,
      FieldAllowlistProvider allowlistProvider,
      FieldPathResolver pathResolver,
      ValueConverter valueConverter,
      PredicateFactory predicateFactory,
      JsonPathFilterPredicateBuilder jsonPathFilterPredicateBuilder) {
    this.filterParser = new Tmf630FilterParser(keyParser, settings, allowlistProvider);
    this.settings = settings;
    this.allowlistProvider = allowlistProvider;
    this.pathResolver = pathResolver;
    this.valueConverter = valueConverter;
    this.predicateFactory = predicateFactory;
    this.jsonPathFilterPredicateBuilder = jsonPathFilterPredicateBuilder;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return Predicate.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Class<?> rootEntity = resolveRootEntity(parameter);
    if (rootEntity == null) {
      throw new TmfFilteringException("Predicate parameter requires @QuerydslPredicate(root=...)");
    }

    return buildPredicate(rootEntity, webRequest.getParameterMap());
  }

  private Predicate buildPredicate(Class<?> rootEntity, Map<String, String[]> parameterMap) {
    Tmf630FilterExpression expression = filterParser.parse(rootEntity, parameterMap);
    PathBuilder<?> rootPath = pathResolver.createRootPath(rootEntity);
    boolean allowNestedPaths = settings.allowNestedPathsFor(rootEntity);

    BooleanBuilder attributePredicate = new BooleanBuilder();
    for (Tmf630AttributeClause clause : expression.attributeClauses()) {
      Predicate predicate = toPredicate(clause, rootEntity, rootPath, allowNestedPaths);
      if (predicate != null) {
        attributePredicate.and(predicate);
      }
    }

    Predicate jsonPathPredicate = null;
    if (expression.hasJsonPathFilter()) {
      jsonPathPredicate =
          jsonPathFilterPredicateBuilder.build(
              rootEntity,
              rootPath,
              expression.jsonPathFilter(),
              allowlistProvider.allowedFields(rootEntity),
              settings,
              allowNestedPaths);
    }

    boolean hasAttribute = attributePredicate.hasValue();
    boolean hasJsonPath = jsonPathPredicate != null;
    if (!hasAttribute && !hasJsonPath) {
      return new BooleanBuilder();
    }
    if (!hasJsonPath) {
      return attributePredicate;
    }
    if (!hasAttribute) {
      return jsonPathPredicate;
    }

    BooleanBuilder merged = new BooleanBuilder();
    if (expression.combineWithAttributes() == CombineMode.OR) {
      merged.or(attributePredicate).or(jsonPathPredicate);
    } else {
      merged.and(attributePredicate).and(jsonPathPredicate);
    }
    return merged;
  }

  private Predicate toPredicate(
      Tmf630AttributeClause clause,
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      boolean allowNestedPaths) {
    ResolvedField resolvedField = resolveField(rootEntity, clause.fieldPath(), allowNestedPaths);
    if (resolvedField == null) {
      return null;
    }
    TmfOperator operator = clause.operator();
    if (operator.isNoValueOperator()) {
      return predicateFactory.buildNoValue(rootPath, resolvedField, operator);
    }
    if (operator.isMultiValueOperator()) {
      return buildMultiValuePredicate(clause, resolvedField, rootPath);
    }
    return buildRepeatedValuePredicate(clause, resolvedField, rootPath);
  }

  private Predicate buildMultiValuePredicate(
      Tmf630AttributeClause clause, ResolvedField resolvedField, PathBuilder<?> rootPath) {
    List<Object> typedValues = new ArrayList<>();
    for (List<String> group : clause.valueGroups()) {
      for (String element : group) {
        typedValues.add(
            valueConverter.convert(element, resolvedField.javaType(), resolvedField.fieldPath()));
      }
    }
    return predicateFactory.buildMulti(rootPath, resolvedField, clause.operator(), typedValues);
  }

  /**
   * One OR-group per repeated parameter occurrence (elements within a group are always
   * ORed), groups combined per {@code combineRepeatedValues} — the split itself already
   * happened in the parser.
   */
  private Predicate buildRepeatedValuePredicate(
      Tmf630AttributeClause clause, ResolvedField resolvedField, PathBuilder<?> rootPath) {
    BooleanBuilder perKey = new BooleanBuilder();
    for (List<String> group : clause.valueGroups()) {
      BooleanBuilder perValue = new BooleanBuilder();
      for (String element : group) {
        Object typedValue =
            valueConverter.convert(element, resolvedField.javaType(), resolvedField.fieldPath());
        perValue.or(
            predicateFactory.build(rootPath, resolvedField, clause.operator(), typedValue));
      }
      appendPerValueToPerKey(perKey, perValue);
    }
    return perKey.hasValue() ? perKey : null;
  }

  private void appendPerValueToPerKey(BooleanBuilder perKey, BooleanBuilder perValue) {
    if (!perValue.hasValue()) {
      return;
    }
    if (settings.combineRepeatedValues() == CombineMode.AND) {
      perKey.and(perValue);
    } else {
      perKey.or(perValue);
    }
  }

  private ResolvedField resolveField(Class<?> rootEntity, String fieldPath, boolean allowNested) {
    try {
      return pathResolver.resolve(rootEntity, fieldPath, allowNested);
    } catch (TmfFilteringException ex) {
      handleUnknownField(fieldPath);
      return null;
    }
  }

  private void handleUnknownField(String fieldPath) {
    if (settings.onUnknownField() == UnknownParamBehavior.REJECT) {
      throw new TmfFilteringException("Unknown or disallowed field: " + fieldPath);
    }
  }

  private Class<?> resolveRootEntity(MethodParameter parameter) {
    QuerydslPredicate annotation = parameter.getParameterAnnotation(QuerydslPredicate.class);
    if (annotation == null || annotation.root() == Object.class) {
      return null;
    }
    return annotation.root();
  }
}
