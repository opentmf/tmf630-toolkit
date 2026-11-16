package org.opentmf.query.tmf630.jsonb;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import java.util.List;
import java.util.Set;
import org.opentmf.query.tmf630.filtering.FieldAllowlistProvider;
import org.opentmf.query.tmf630.filtering.JsonPathFilterPredicateBuilder;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.JsonTypedFields;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;

/**
 * The QueryDSL terminal's acceptance decision, taken for the JSONB terminal before it emits its
 * own SQL: an opt-in since 3.6.0, {@code opentmf.tmf630.jsonb.querydsl-acceptance=true}. Without
 * it the JSONB terminal uses {@link #none()}, which checks nothing, and answers what it answered in
 * 3.5.2. The JSONB terminal asks the core's own builders, {@link PredicateFactory}
 * for an attribute operator and {@link JsonPathFilterPredicateBuilder} for {@code filter=}, to build
 * the predicate the QueryDSL terminal would build for the same request, and discards it: a request
 * the QueryDSL terminal refuses is refused here with the core's own exception and message, before
 * any SQL, and an accepted request is emitted exactly as before. There is no second copy of the
 * checks, so a check the core adds applies to JSONB too.
 *
 * <p>Even when it is on, three behaviours stay as JSONB released them, each in one place below:
 *
 * <ul>
 *   <li>a negated group {@code !(…)} passes; the comparisons inside it are checked (each backend
 *       keeps its released negation, TOOLKIT-NEGATION-MEANING-RULING-1, {@link
 *       #negatedGroupsAsGroups});
 *   <li>an unknown or non-allowlisted field inside {@code filter=} is outside the check, as under
 *       {@code on-unknown-json-path-field: IGNORE}, whatever that setting says; the emitted SQL keeps
 *       the field, so it narrows to nothing and is never dropped (TOOLKIT-JSONB-JSONPATH-REJECT-1);
 *   <li>a field of an open JSON type ({@link Object}, a {@code Map}, a {@code JsonNode}) counts as
 *       any type: a comparison at or below it is outside the check and keeps its comparison, so a
 *       search on a TMF characteristic's value never turns into a 400 ({@link OpenJsonTypes}).
 * </ul>
 */
public final class JsonbQuerydslAcceptance {

  private static final String ROOT = "jsonb";

  private static final JsonbQuerydslAcceptance NONE = new JsonbQuerydslAcceptance();

  private final boolean enabled;
  private final Tmf630FilterSettings settings;
  private final PredicateFactory predicateFactory;
  private final JsonPathFilterPredicateBuilder filterBuilder;
  private final FieldAllowlistProvider allowlistProvider;

  /**
   * The check over the core's {@code predicateFactory} (the QueryDSL terminal's bean, so its regex
   * switch and cap are the ones the core reads), resolving and converting as the URL grammar does.
   */
  public JsonbQuerydslAcceptance(
      Tmf630FilterSettings settings,
      FieldPathResolver fieldPathResolver,
      ValueConverter valueConverter,
      PredicateFactory predicateFactory,
      FieldAllowlistProvider allowlistProvider) {
    this.enabled = true;
    this.settings = withUnknownJsonPathFieldsIgnored(settings);
    this.predicateFactory = predicateFactory;
    this.filterBuilder =
        new CheckingFilterBuilder(
            new OpenJsonTypes(fieldPathResolver), valueConverter, predicateFactory);
    this.allowlistProvider = allowlistProvider;
  }

  private JsonbQuerydslAcceptance() {
    this.enabled = false;
    this.settings = null;
    this.predicateFactory = null;
    this.filterBuilder = null;
    this.allowlistProvider = null;
  }

  /** The check that refuses nothing: the JSONB terminal's default, as in 3.5.2. */
  public static JsonbQuerydslAcceptance none() {
    return NONE;
  }

  /** The check with a core {@link PredicateFactory} built from {@code settings} and no allowlist. */
  public static JsonbQuerydslAcceptance of(
      Tmf630FilterSettings settings,
      FieldPathResolver fieldPathResolver,
      ValueConverter valueConverter) {
    return new JsonbQuerydslAcceptance(
        settings,
        fieldPathResolver,
        valueConverter,
        predicateFactoryFor(settings),
        rootEntity -> Set.of());
  }

  /** The core's {@link PredicateFactory} as the attribute-filtering settings configure it. */
  static PredicateFactory predicateFactoryFor(Tmf630FilterSettings settings) {
    return new PredicateFactory(
        settings.regexEnabled(),
        settings.limits().maxRegexLength(),
        settings.isnullSemantics(),
        settings.allowJpaLikeRegexSemantics());
  }

  /** Refuses a single-value operator on {@code field} as the QueryDSL terminal does. */
  void checkValue(ResolvedField field, TmfOperator operator, Object value) {
    if (!enabled || isOpenJsonType(field.javaType())) {
      return;
    }
    predicateFactory.build(new PathBuilder<>(Object.class, ROOT), field, operator, value);
  }

  /** Refuses {@code in}, {@code nin} or {@code between} on {@code field} as the QueryDSL terminal does. */
  void checkValues(ResolvedField field, TmfOperator operator, List<Object> values) {
    if (!enabled || isOpenJsonType(field.javaType())) {
      return;
    }
    predicateFactory.buildMulti(new PathBuilder<>(Object.class, ROOT), field, operator, values);
  }

  /** Refuses a {@code filter=} expression as the QueryDSL terminal does. */
  void checkFilter(Class<?> domainType, String expression) {
    if (!enabled) {
      return;
    }
    filterBuilder.build(
        domainType,
        new PathBuilder<>(Object.class, ROOT),
        negatedGroupsAsGroups(expression),
        allowlistProvider.allowedFields(domainType),
        settings,
        settings.allowNestedPathsDocdb());
  }

  /**
   * HELD (TOOLKIT-NEGATION-MEANING-RULING-1): the expression as the check reads it, each negated
   * group {@code !(…)} read as the plain group {@code (…)}, so the comparisons inside are checked
   * and the negation itself passes. On JSONB {@code !(…)} through an array is today the one way to
   * say "no element equals"; the core refuses it. String and regex literals are copied unchanged.
   */
  static String negatedGroupsAsGroups(String expression) {
    StringBuilder out = new StringBuilder(expression.length());
    int i = 0;
    while (i < expression.length()) {
      int literalEnd = literalEnd(expression, i);
      if (literalEnd > i) {
        out.append(expression, i, literalEnd);
        i = literalEnd;
      } else {
        out.append(isNegatedGroup(expression, i) ? ' ' : expression.charAt(i));
        i++;
      }
    }
    return out.toString();
  }

  /** The index after the string or regex literal starting at {@code at}, or {@code at} if none. */
  private static int literalEnd(String s, int at) {
    char c = s.charAt(at);
    if (c == '\'' || c == '"') {
      return closingIndex(s, at, c);
    }
    if (c == '/' && followsRegexOperator(s, at)) {
      return closingIndex(s, at, '/');
    }
    return at;
  }

  /** The index after the unescaped {@code close} that ends the literal opened at {@code at}. */
  private static int closingIndex(String s, int at, char close) {
    int i = at + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == close) {
        return i + 1;
      } else {
        i++;
      }
    }
    return s.length();
  }

  private static boolean followsRegexOperator(String s, int at) {
    int i = at - 1;
    while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
      i--;
    }
    return i >= 1 && s.charAt(i) == '~' && s.charAt(i - 1) == '=';
  }

  private static boolean isNegatedGroup(String s, int at) {
    if (s.charAt(at) != '!') {
      return false;
    }
    int i = at + 1;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i < s.length() && s.charAt(i) == '(';
  }

  /**
   * HELD (TOOLKIT-JSONB-JSONPATH-REJECT-1): the settings the check runs under, with unknown and
   * non-allowlisted fields inside {@code filter=} outside it, so the switch keeps its effect on
   * JSONB as before (none) until the ruling.
   */
  private static Tmf630FilterSettings withUnknownJsonPathFieldsIgnored(Tmf630FilterSettings s) {
    return new Tmf630FilterSettings(
        s.implicitEqEnabled(),
        s.implicitEqCsvOr(),
        s.implicitEqSemicolonOr(),
        s.combineRepeatedValues(),
        s.allowNestedPathsJpa(),
        s.allowNestedPathsDocdb(),
        s.regexEnabled(),
        s.limits(),
        s.allowlistMode(),
        s.onUnknownField(),
        s.onUnknownOperator(),
        s.jsonPathFilterEnabled(),
        s.jsonPathMaxLength(),
        UnknownParamBehavior.IGNORE,
        s.isnullSemantics(),
        s.allowJpaLikeRegexSemantics());
  }

  /**
   * The core's {@code filter=} builder, building an accepted array match as the checked element
   * predicate itself: the predicate is discarded, so it needs no Mongo {@code ELEM_MATCH} (and no
   * querydsl-mongodb on the classpath).
   */
  private static final class CheckingFilterBuilder extends JsonPathFilterPredicateBuilder {

    CheckingFilterBuilder(
        FieldPathResolver pathResolver,
        ValueConverter valueConverter,
        PredicateFactory predicateFactory) {
      super(pathResolver, valueConverter, predicateFactory);
    }

    @Override
    protected Predicate elementMatch(Expression<?> collection, Predicate inner) {
      return inner;
    }
  }

  /**
   * Whether a field declared as {@code type} is of an open JSON type ({@link Object}, a {@code Map},
   * a {@code JsonNode}): it may hold any JSON value, so it counts as any type and the check leaves it
   * alone.
   */
  static boolean isOpenJsonType(Class<?> type) {
    return type == Object.class || JsonTypedFields.isJsonTyped(type);
  }

  /**
   * A field of an open JSON type resolves as an unknown field for the {@code filter=} check, so a
   * comparison on it is outside the check, as one on a path below it already is (the resolver knows
   * no field below an Object, a Map or a JsonNode).
   */
  private static final class OpenJsonTypes extends FieldPathResolver {

    private final FieldPathResolver delegate;

    OpenJsonTypes(FieldPathResolver delegate) {
      this.delegate = delegate;
    }

    @Override
    public ResolvedField resolve(Class<?> rootEntity, String fieldPath, boolean allowNestedPaths) {
      ResolvedField field = delegate.resolve(rootEntity, fieldPath, allowNestedPaths);
      if (isOpenJsonType(field.javaType())) {
        throw new TmfFilteringException("Open JSON type outside the check: " + fieldPath);
      }
      return field;
    }
  }
}
