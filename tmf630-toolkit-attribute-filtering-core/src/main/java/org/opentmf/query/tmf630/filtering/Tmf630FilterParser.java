package org.opentmf.query.tmf630.filtering;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;

/**
 * Backend-neutral parser for the TMF-630 attribute-filter URL grammar. Everything the
 * grammar defines happens here — reserved-parameter skipping, Part 1 §4.4
 * encoded-operator-literal normalization ({@code ?dateTime%3E2013-04-20}), operator-suffix
 * parsing with the implicit-EQ fallback, escape-aware comma/semicolon value-list
 * splitting, allowlist filtering, unknown-field/operator REJECT/IGNORE behavior, and
 * clause/value limit enforcement. The output {@link Tmf630FilterExpression} carries plain
 * strings only; field resolution, type coercion and predicate construction belong to the
 * backend terminals ({@code Tmf630PredicateArgumentResolver} for QueryDSL,
 * {@code Tmf630JsonbClauseBuilder} for JSONB) so the same URL means the same thing on
 * every backend.
 *
 * <p>The {@code rootEntity} argument is used only as the allowlist lookup key — the parser
 * never reflects over it.
 */
public class Tmf630FilterParser {

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

  public Tmf630FilterParser(
      ParamKeyParser keyParser,
      Tmf630FilterSettings settings,
      FieldAllowlistProvider allowlistProvider) {
    this.keyParser = keyParser;
    this.settings = settings;
    this.allowlistProvider = allowlistProvider;
  }

  public Tmf630FilterExpression parse(Class<?> rootEntity, Map<String, String[]> parameterMap) {
    return parse(rootEntity, parameterMap, Set.of());
  }

  /**
   * Parses the filter surface, leaving the exact parameter names in {@code passThrough} to the
   * handler's own bindings ({@link Tmf630PassThrough}): they are never read as attribute filters
   * — nor as {@code filter} / {@code filter.combineWithAttributes} when a handler names those.
   * Every other name keeps the configured unknown-field / unknown-operator behavior.
   */
  public Tmf630FilterExpression parse(
      Class<?> rootEntity, Map<String, String[]> parameterMap, Set<String> passThrough) {
    Set<String> allowed = allowlistProvider.allowedFields(rootEntity);
    List<Tmf630AttributeClause> clauses = new ArrayList<>();
    ClauseCounter counter = new ClauseCounter(settings.limits().maxClauses());
    for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
      Tmf630AttributeClause clause = parseAttributeEntry(entry, allowed, counter, passThrough);
      if (clause != null) {
        clauses.add(clause);
      }
    }
    return new Tmf630FilterExpression(
        Collections.unmodifiableList(clauses),
        passThrough.contains(FILTER_PARAM) ? null : extractJsonPathFilter(parameterMap),
        passThrough.contains(FILTER_COMBINE_PARAM)
            ? CombineMode.AND
            : resolveFilterCombineMode(parameterMap));
  }

  private Tmf630AttributeClause parseAttributeEntry(
      Map.Entry<String, String[]> entry,
      Set<String> allowed,
      ClauseCounter counter,
      Set<String> passThrough) {
    if (isReservedKey(entry.getKey(), passThrough)) {
      return null;
    }
    NormalizedParam normalized = normalize(entry.getKey(), entry.getValue());
    ParsedParamKey parsed = parseAndAllowlist(normalized.key(), allowed);
    if (parsed == null) {
      return null;
    }
    return clauseFor(normalized.key(), normalized.values(), parsed, counter);
  }

  private boolean isReservedKey(String rawKey, Set<String> passThrough) {
    return RESERVED_PARAMS.contains(rawKey)
        || FILTER_PARAM.equals(rawKey)
        || FILTER_COMBINE_PARAM.equals(rawKey)
        || passThrough.contains(rawKey);
  }

  private NormalizedParam normalize(String rawKey, String[] rawValues) {
    NormalizedParam encoded = normalizeEncodedOperatorKey(rawKey, rawValues);
    if (encoded != null) {
      return encoded;
    }
    return new NormalizedParam(rawKey, rawValues == null ? List.of() : Arrays.asList(rawValues));
  }

  private ParsedParamKey parseAndAllowlist(String rawKey, Set<String> allowed) {
    Optional<ParsedParamKey> parsedOpt = keyParser.parse(rawKey);
    if (parsedOpt.isEmpty()) {
      handleUnknownOperator(rawKey);
      return null;
    }
    ParsedParamKey parsed = parsedOpt.get();
    if (!isAllowedField(parsed.fieldPath(), allowed)) {
      handleUnknownField(parsed.fieldPath());
      return null;
    }
    return parsed;
  }

  private Tmf630AttributeClause clauseFor(
      String rawKey, List<String> values, ParsedParamKey parsed, ClauseCounter counter) {
    TmfOperator operator = parsed.operator();
    if (operator.isNoValueOperator()) {
      counter.bump();
      return clause(rawKey, parsed, List.of());
    }
    if (values.isEmpty()) {
      return null;
    }
    enforceMaxValuesPerKey(rawKey, values.size());
    if (operator.isMultiValueOperator()) {
      return multiValueClause(rawKey, values, parsed, counter);
    }
    return repeatedValueClause(rawKey, values, parsed, counter);
  }

  private void enforceMaxValuesPerKey(String rawKey, int size) {
    if (size > settings.limits().maxValuesPerKey()) {
      throw new TmfFilteringException("Too many values for key: " + rawKey);
    }
  }

  private Tmf630AttributeClause multiValueClause(
      String rawKey, List<String> values, ParsedParamKey parsed, ClauseCounter counter) {
    counter.bump();
    List<List<String>> groups = new ArrayList<>();
    int elementCount = 0;
    for (String rawValue : values) {
      List<String> elements = splitCsvForMultiValue(rawValue);
      elementCount += elements.size();
      groups.add(elements);
    }
    enforceMaxValuesPerKey(rawKey, elementCount);
    return clause(rawKey, parsed, groups);
  }

  // TMF630 value-list semantics: an implicit-eq value is an OR list separated by commas
  // (?attr=a,b) or, per Part 1 §4.4 explicit ORing, by semicolons (?attr=a;b and the
  // repeated-pair form ?attr=a;attr=b, whose redundant "<sameKey>=" prefixes are
  // stripped). Only the implicit spelling splits — every explicit single-value operator
  // (including .eq) keeps its raw value as one literal, which is the documented escape
  // hatch for values that legitimately contain a separator (as is the \, / \; escape).
  private Tmf630AttributeClause repeatedValueClause(
      String rawKey, List<String> values, ParsedParamKey parsed, ClauseCounter counter) {
    SplitPolicy split = SplitPolicy.forOperator(parsed, settings);
    List<List<String>> groups = new ArrayList<>();
    int elementCount = 0;
    for (String rawValue : values) {
      List<String> elements = split.split(rawValue, rawKey);
      for (int i = 0; i < elements.size(); i++) {
        counter.bump();
      }
      elementCount += elements.size();
      if (!elements.isEmpty()) {
        groups.add(elements);
      }
    }
    enforceMaxValuesPerKey(rawKey, elementCount);
    return groups.isEmpty() ? null : clause(rawKey, parsed, groups);
  }

  private static Tmf630AttributeClause clause(
      String rawKey, ParsedParamKey parsed, List<List<String>> groups) {
    return new Tmf630AttributeClause(
        rawKey, parsed.fieldPath(), parsed.operator(), parsed.implicitEq(), groups);
  }

  private String extractJsonPathFilter(Map<String, String[]> parameterMap) {
    String[] filters = parameterMap.get(FILTER_PARAM);
    if (filters == null || filters.length == 0) {
      return null;
    }
    if (filters.length > 1) {
      throw new TmfFilteringException("Only one filter parameter is supported.");
    }
    return filters[0];
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

  private record SplitPolicy(boolean semicolon, boolean comma) {
    static SplitPolicy forOperator(ParsedParamKey parsed, Tmf630FilterSettings settings) {
      boolean implicitEqList = parsed.operator() == TmfOperator.EQ && parsed.implicitEq();
      return new SplitPolicy(
          implicitEqList && settings.implicitEqSemicolonOr(),
          implicitEqList && settings.implicitEqCsvOr());
    }

    List<String> split(String rawValue, String rawKey) {
      if (!semicolon && !comma) {
        return Collections.singletonList(rawValue);
      }
      return splitValueList(rawValue, semicolon, comma, rawKey + "=");
    }
  }

  private static final class ClauseCounter {
    private final int max;
    private int count;

    ClauseCounter(int max) {
      this.max = max;
    }

    void bump() {
      if (++count > max) {
        throw new TmfFilteringException("Maximum clause limit exceeded.");
      }
    }
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

  private record NormalizedParam(String key, List<String> values) {}

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
      if (!isOperatorLiteralStart(rawKey.charAt(i))) {
        continue;
      }
      OperatorMatch match = matchOperatorAt(rawKey, i);
      if (match == null) {
        return null;
      }
      String fieldPath = rawKey.substring(0, i);
      if (fieldPath.isEmpty()) {
        return null;
      }
      List<String> newValues = collectEncodedValues(rawKey, values, i, match.length());
      return new NormalizedParam(fieldPath + "." + match.op().suffix(), newValues);
    }
    return null;
  }

  private static boolean isOperatorLiteralStart(char c) {
    return c == '>' || c == '<' || c == '=';
  }

  private static OperatorMatch matchOperatorAt(String rawKey, int i) {
    char c = rawKey.charAt(i);
    char next = i + 1 < rawKey.length() ? rawKey.charAt(i + 1) : 0;
    return switch (c) {
      case '>' -> new OperatorMatch(
          next == '=' ? TmfOperator.GTE : TmfOperator.GT, next == '=' ? 2 : 1);
      case '<' -> new OperatorMatch(
          next == '=' ? TmfOperator.LTE : TmfOperator.LT, next == '=' ? 2 : 1);
      case '=' -> switch (next) {
        case '=' -> new OperatorMatch(TmfOperator.EQ, 2);
        case '~' -> new OperatorMatch(TmfOperator.REGEX, 2);
        default -> null;
      };
      default -> null;
    };
  }

  private static List<String> collectEncodedValues(
      String rawKey, String[] values, int operatorPosition, int operatorLength) {
    String remainder = rawKey.substring(operatorPosition + operatorLength);
    if (remainder.isEmpty()) {
      return Arrays.asList(values);
    }
    return splitValueList(
        remainder, true, false, rawKey.substring(0, operatorPosition + operatorLength));
  }

  private record OperatorMatch(TmfOperator op, int length) {}

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
