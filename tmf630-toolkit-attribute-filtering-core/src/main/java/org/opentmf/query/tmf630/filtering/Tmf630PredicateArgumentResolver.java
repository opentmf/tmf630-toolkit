package org.opentmf.query.tmf630.filtering;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
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
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

public class Tmf630PredicateArgumentResolver implements HandlerMethodArgumentResolver {

  private static final Set<String> RESERVED_PARAMS = Set.of("page", "size", "sort");

  private final ParamKeyParser keyParser;
  private final Tmf630FilterSettings settings;
  private final FieldAllowlistProvider allowlistProvider;
  private final FieldPathResolver pathResolver;
  private final ValueConverter valueConverter;
  private final PredicateFactory predicateFactory;

  public Tmf630PredicateArgumentResolver(
      ParamKeyParser keyParser,
      Tmf630FilterSettings settings,
      FieldAllowlistProvider allowlistProvider,
      FieldPathResolver pathResolver,
      ValueConverter valueConverter,
      PredicateFactory predicateFactory) {
    this.keyParser = keyParser;
    this.settings = settings;
    this.allowlistProvider = allowlistProvider;
    this.pathResolver = pathResolver;
    this.valueConverter = valueConverter;
    this.predicateFactory = predicateFactory;
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
      throw new ResponseStatusException(
          BAD_REQUEST, "Predicate parameter requires @QuerydslPredicate(root=...)");
    }

    try {
      return buildPredicate(rootEntity, webRequest.getParameterMap());
    } catch (TmfFilteringException ex) {
      throw new ResponseStatusException(BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  private Predicate buildPredicate(Class<?> rootEntity, Map<String, String[]> parameterMap) {
    BooleanBuilder result = new BooleanBuilder();
    PathBuilder<?> rootPath = pathResolver.createRootPath(rootEntity);
    Set<String> allowed = allowlistProvider.allowedFields(rootEntity);
    int clauseCount = 0;

    for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
      String rawKey = entry.getKey();
      if (RESERVED_PARAMS.contains(rawKey)) {
        continue;
      }

      Optional<ParsedParamKey> parsedOpt = keyParser.parse(rawKey);
      if (parsedOpt.isEmpty()) {
        handleUnknownOperator(rawKey);
        continue;
      }

      ParsedParamKey parsed = parsedOpt.get();
      String fieldPath = parsed.fieldPath();
      if (!isAllowedField(fieldPath, allowed)) {
        handleUnknownField(fieldPath);
        continue;
      }

      ResolvedField resolvedField;
      try {
        resolvedField =
            pathResolver.resolve(rootEntity, fieldPath, settings.allowNestedPaths());
      } catch (TmfFilteringException ex) {
        handleUnknownField(fieldPath);
        continue;
      }

      String[] values = entry.getValue();
      TmfOperator operator = parsed.operator();
      Predicate clause;

      if (operator.isNoValueOperator()) {
        clauseCount = incrementClauseCount(clauseCount);
        clause = predicateFactory.buildNoValue(rootPath, resolvedField, operator);
        result.and(clause);
        continue;
      }

      if (values == null || values.length == 0) {
        continue;
      }
      if (values.length > settings.limits().maxValuesPerKey()) {
        throw new TmfFilteringException("Too many values for key: " + rawKey);
      }

      if (operator.isMultiValueOperator()) {
        clauseCount = incrementClauseCount(clauseCount);
        List<Object> typedValues = new ArrayList<>(values.length);
        for (String rawValue : values) {
          typedValues.add(valueConverter.convert(rawValue, resolvedField.javaType()));
        }
        clause = predicateFactory.buildMulti(rootPath, resolvedField, operator, typedValues);
        result.and(clause);
        continue;
      }

      BooleanBuilder perKey = new BooleanBuilder();
      for (String rawValue : values) {
        clauseCount = incrementClauseCount(clauseCount);
        Object typedValue = valueConverter.convert(rawValue, resolvedField.javaType());
        clause = predicateFactory.build(rootPath, resolvedField, operator, typedValue);
        if (settings.combineRepeatedValues() == CombineMode.AND) {
          perKey.and(clause);
        } else {
          perKey.or(clause);
        }
      }
      result.and(perKey);
    }

    return result;
  }

  private int incrementClauseCount(int clauseCount) {
    int next = clauseCount + 1;
    if (next > settings.limits().maxClauses()) {
      throw new TmfFilteringException("Maximum clause limit exceeded.");
    }
    return next;
  }

  private boolean isAllowedField(String fieldPath, Set<String> allowlist) {
    if (settings.allowlistMode() == AllowlistMode.ALLOW_ALL) {
      return allowlist.isEmpty() || allowlist.contains(fieldPath);
    }
    return allowlist.contains(fieldPath);
  }

  private void handleUnknownOperator(String key) {
    if (settings.onUnknownOperator() == UnknownParamBehavior.REJECT) {
      throw new TmfFilteringException("Unknown or unsupported operator in key: " + key);
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
