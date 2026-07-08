package org.opentmf.query.tmf630.filtering;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import java.util.ArrayList;
import java.util.Collections;
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

public class Tmf630PredicateArgumentResolver implements HandlerMethodArgumentResolver {

  // depth/expand are the TMF630 Part 2 Ch.3 dereferencing directives; the toolkit does
  // not implement them but must not misread them as attribute filters. An entity field
  // sharing a reserved name stays filterable via the explicit-operator escape (depth.eq=2).
  private static final Set<String> RESERVED_PARAMS =
      Set.of("page", "size", "sort", "offset", "limit", "fields", "depth", "expand");
  private static final String FILTER_PARAM = "filter";
  private static final String FILTER_COMBINE_PARAM = "filter.combineWithAttributes";

  private final ParamKeyParser keyParser;
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
    this.keyParser = keyParser;
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
    PathBuilder<?> rootPath = pathResolver.createRootPath(rootEntity);
    Set<String> allowed = allowlistProvider.allowedFields(rootEntity);
    boolean allowNestedPaths = settings.allowNestedPathsFor(rootEntity);

    BooleanBuilder attributePredicate =
        buildAttributePredicate(rootEntity, parameterMap, rootPath, allowed, allowNestedPaths);
    Predicate jsonPathPredicate =
        buildJsonPathPredicate(rootEntity, parameterMap, rootPath, allowed, allowNestedPaths);

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

    CombineMode combineMode = resolveFilterCombineMode(parameterMap);
    BooleanBuilder merged = new BooleanBuilder();
    if (combineMode == CombineMode.OR) {
      merged.or(attributePredicate).or(jsonPathPredicate);
    } else {
      merged.and(attributePredicate).and(jsonPathPredicate);
    }
    return merged;
  }

  private BooleanBuilder buildAttributePredicate(
      Class<?> rootEntity,
      Map<String, String[]> parameterMap,
      PathBuilder<?> rootPath,
      Set<String> allowed,
      boolean allowNestedPaths) {
    BooleanBuilder result = new BooleanBuilder();
    int clauseCount = 0;

    for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
      String rawKey = entry.getKey();
      if (RESERVED_PARAMS.contains(rawKey)
          || FILTER_PARAM.equals(rawKey)
          || FILTER_COMBINE_PARAM.equals(rawKey)) {
        continue;
      }

      String[] values = entry.getValue();
      NormalizedParam encoded = normalizeEncodedOperatorKey(rawKey, values);
      if (encoded != null) {
        rawKey = encoded.key();
        values = encoded.values();
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
        resolvedField = pathResolver.resolve(rootEntity, fieldPath, allowNestedPaths);
      } catch (TmfFilteringException ex) {
        handleUnknownField(fieldPath);
        continue;
      }

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
        List<Object> typedValues = new ArrayList<>();
        for (String rawValue : values) {
          for (String element : splitCsvForMultiValue(rawValue)) {
            typedValues.add(
                valueConverter.convert(element, resolvedField.javaType(), resolvedField.fieldPath()));
          }
        }
        if (typedValues.size() > settings.limits().maxValuesPerKey()) {
          throw new TmfFilteringException("Too many values for key: " + rawKey);
        }
        clause = predicateFactory.buildMulti(rootPath, resolvedField, operator, typedValues);
        result.and(clause);
        continue;
      }

      // TMF630 value-list semantics: an implicit-eq value is an OR list separated by commas
      // (?attr=a,b) or, per Part 1 §4.4 explicit ORing, by semicolons (?attr=a;b and the
      // repeated-pair form ?attr=a;attr=b, whose redundant "<sameKey>=" prefixes are
      // stripped). Only the implicit spelling splits — every explicit single-value operator
      // (including .eq) keeps its raw value as one literal, which is the documented escape
      // hatch for values that legitimately contain a separator (as is the \, / \; escape).
      boolean implicitEqList = operator == TmfOperator.EQ && parsed.implicitEq();
      boolean splitCsv = implicitEqList && settings.implicitEqCsvOr();
      boolean splitSemicolon = implicitEqList && settings.implicitEqSemicolonOr();
      BooleanBuilder perKey = new BooleanBuilder();
      int elementCount = 0;
      for (String rawValue : values) {
        List<String> elements =
            splitCsv || splitSemicolon
                ? splitValueList(rawValue, splitSemicolon, splitCsv, rawKey + "=")
                : Collections.singletonList(rawValue);
        BooleanBuilder perValue = new BooleanBuilder();
        for (String element : elements) {
          clauseCount = incrementClauseCount(clauseCount);
          elementCount++;
          Object typedValue =
              valueConverter.convert(element, resolvedField.javaType(), resolvedField.fieldPath());
          perValue.or(predicateFactory.build(rootPath, resolvedField, operator, typedValue));
        }
        if (!perValue.hasValue()) {
          continue;
        }
        if (settings.combineRepeatedValues() == CombineMode.AND) {
          perKey.and(perValue);
        } else {
          perKey.or(perValue);
        }
      }
      if (elementCount > settings.limits().maxValuesPerKey()) {
        throw new TmfFilteringException("Too many values for key: " + rawKey);
      }
      if (perKey.hasValue()) {
        result.and(perKey);
      }
    }
    return result;
  }

  private Predicate buildJsonPathPredicate(
      Class<?> rootEntity,
      Map<String, String[]> parameterMap,
      PathBuilder<?> rootPath,
      Set<String> allowed,
      boolean allowNestedPaths) {
    String[] filters = parameterMap.get(FILTER_PARAM);
    if (filters == null || filters.length == 0) {
      return null;
    }
    if (filters.length > 1) {
      throw new TmfFilteringException("Only one filter parameter is supported.");
    }
    return jsonPathFilterPredicateBuilder.build(
        rootEntity, rootPath, filters[0], allowed, settings, allowNestedPaths);
  }

  private CombineMode resolveFilterCombineMode(Map<String, String[]> parameterMap) {
    String[] values = parameterMap.get(FILTER_COMBINE_PARAM);
    if (values == null || values.length == 0 || values[0] == null || values[0].isBlank()) {
      return CombineMode.AND;
    }
    if (values.length > 1) {
      throw new TmfFilteringException("Only one filter.combineWithAttributes value is supported.");
    }
    String normalized = values[0].trim().toUpperCase();
    if ("AND".equals(normalized)) {
      return CombineMode.AND;
    }
    if ("OR".equals(normalized)) {
      return CombineMode.OR;
    }
    throw new TmfFilteringException(
        "Invalid filter.combineWithAttributes value. Supported values: AND, OR.");
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

  /**
   * Splits a raw query-parameter value on unescaped commas for multi-value operators
   * (TMF630 §4.4 {@code .in} / {@code .nin} / {@code .between}). A literal comma can be
   * embedded with the {@code \,} escape. Empty elements are dropped so {@code "A,,B"}
   * yields {@code ["A", "B"]}; a {@code null} raw value passes through as a single
   * {@code null} so the value converter can apply its own null handling.
   */
  static List<String> splitCsvForMultiValue(String raw) {
    return splitValueList(raw, false, true, null);
  }

  private record NormalizedParam(String key, String[] values) {}

  /**
   * TMF630 Part 1 §4.4 URL-encoded operator literal form. The operator arrives embedded in
   * the parameter NAME — {@code ?dateTime%3E2013-04-20} decodes to the name
   * {@code dateTime>2013-04-20} because encoded characters are not name/value separators for
   * the servlet container. Rewrites such keys onto the equivalent dot-suffix operator so all
   * downstream machinery (allowlist, limits, conversion, predicates) applies unchanged. The
   * value is taken from the name remainder, or from the value slot when the name ends at the
   * operator ({@code ?field%3E=v} → name {@code field>}, value {@code v}). The spec's
   * ORING example ({@code ?dateTime%3C2013;dateTime%3C2017} — one decoded name carrying two
   * expressions) is split on {@code ;} with the duplicate {@code <field><op>} prefix
   * stripped, yielding one value per expression, folded like repeated parameters. Returns
   * {@code null} for keys without an operator literal (including a lone {@code =}).
   */
  private static NormalizedParam normalizeEncodedOperatorKey(String rawKey, String[] values) {
    for (int i = 0; i < rawKey.length(); i++) {
      char c = rawKey.charAt(i);
      if (c != '>' && c != '<' && c != '=') {
        continue;
      }
      char next = i + 1 < rawKey.length() ? rawKey.charAt(i + 1) : 0;
      TmfOperator op;
      int opLength;
      if (c == '>') {
        op = next == '=' ? TmfOperator.GTE : TmfOperator.GT;
        opLength = next == '=' ? 2 : 1;
      } else if (c == '<') {
        op = next == '=' ? TmfOperator.LTE : TmfOperator.LT;
        opLength = next == '=' ? 2 : 1;
      } else if (next == '=') {
        op = TmfOperator.EQ;
        opLength = 2;
      } else if (next == '~') {
        op = TmfOperator.REGEX;
        opLength = 2;
      } else {
        return null;
      }
      String fieldPath = rawKey.substring(0, i);
      if (fieldPath.isEmpty()) {
        return null;
      }
      String remainder = rawKey.substring(i + opLength);
      String[] newValues =
          remainder.isEmpty()
              ? values
              : splitValueList(remainder, true, false, rawKey.substring(0, i + opLength))
                  .toArray(String[]::new);
      return new NormalizedParam(fieldPath + "." + op.suffix(), newValues);
    }
    return null;
  }

  /**
   * Single-pass splitter behind both value-list forms: the comma list shared with the
   * multi-value operators and TMF630 Part 1 §4.4 explicit {@code ;} ORing. Splitting on both
   * separators must happen in one pass — sequential passes would consume the {@code \} of an
   * escape intended for the later separator. A segment opened by {@code ;} additionally has a
   * redundant {@code redundantSegmentPrefix} stripped ({@code "attr="} for the spec's
   * repeated-pair form {@code ?attr=v1;attr=v2}; {@code "attr<"} etc. for the encoded operator
   * literal ORING form); a prefix naming a DIFFERENT key stays literal (cross-attribute
   * {@code ;} pairs are out of scope).
   */
  private static List<String> splitValueList(
      String raw, boolean onSemicolon, boolean onComma, String redundantSegmentPrefix) {
    if (raw == null) {
      List<String> single = new ArrayList<>(1);
      single.add(null);
      return single;
    }
    boolean hasSeparator =
        (onComma && raw.indexOf(',') >= 0) || (onSemicolon && raw.indexOf(';') >= 0);
    if (!hasSeparator) {
      return List.of(raw);
    }
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder(raw.length());
    boolean escaped = false;
    boolean semicolonOpened = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (escaped) {
        current.append(c);
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (onSemicolon && c == ';') {
        addSegment(out, current, semicolonOpened, redundantSegmentPrefix);
        semicolonOpened = true;
      } else if (onComma && c == ',') {
        addSegment(out, current, semicolonOpened, redundantSegmentPrefix);
        semicolonOpened = false;
      } else {
        current.append(c);
      }
    }
    addSegment(out, current, semicolonOpened, redundantSegmentPrefix);
    return out;
  }

  private static void addSegment(
      List<String> out, StringBuilder current, boolean semicolonOpened, String redundantPrefix) {
    if (current.isEmpty()) {
      return;
    }
    String segment = current.toString();
    current.setLength(0);
    if (semicolonOpened && redundantPrefix != null && segment.startsWith(redundantPrefix)) {
      segment = segment.substring(redundantPrefix.length());
      if (segment.isEmpty()) {
        return;
      }
    }
    out.add(segment);
  }
}
