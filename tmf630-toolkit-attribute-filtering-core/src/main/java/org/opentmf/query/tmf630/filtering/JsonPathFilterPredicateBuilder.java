package org.opentmf.query.tmf630.filtering;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Operator;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;

/**
 * Builds QueryDSL predicates from restricted JsonPath filter expressions.
 *
 * <p>Supported subset:
 *
 * <ul>
 *   <li>Wrapper: {@code $[?( ... )]}
 *   <li>Logical operators: {@code &&}, {@code ||}
 *   <li>Grouping with parentheses
 *   <li>Comparisons: {@code ==}, {@code !=}, {@code >}, {@code >=}, {@code <}, {@code <=}
 *   <li>Unary negation {@code !@.field} — matches rows where the field is missing or
 *       null (translated to {@code IS_NULL}). Useful for "absent field" filtering.
 *   <li>Field paths in comparison left-hand side: {@code @.field} or {@code @.nested.field}
 *   <li>Literals: single/double quoted strings, numbers, booleans, and {@code null}
 * </ul>
 */
public class JsonPathFilterPredicateBuilder {

  private static final Pattern FILTER_WRAPPER =
      Pattern.compile("^\\s*\\$\\s*\\[\\s*\\?\\s*\\((.*)\\)\\s*]\\s*$", Pattern.DOTALL);

  private static final Pattern BARE_WRAPPER =
      Pattern.compile("^\\s*\\[\\s*\\?\\s*\\((.*)\\)\\s*]\\s*$", Pattern.DOTALL);

  /** Counter for unique JPA correlation-subquery aliases across concurrent requests. */
  private static final AtomicInteger JPA_CORRELATION_ALIAS_COUNTER = new AtomicInteger();

  private final FieldPathResolver pathResolver;
  private final ValueConverter valueConverter;
  private final PredicateFactory predicateFactory;

  public JsonPathFilterPredicateBuilder(
      FieldPathResolver pathResolver, ValueConverter valueConverter, PredicateFactory predicateFactory) {
    this.pathResolver = pathResolver;
    this.valueConverter = valueConverter;
    this.predicateFactory = predicateFactory;
  }

  public Predicate build(
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      String expression,
      Set<String> allowlist,
      Tmf630FilterSettings settings) {
    return build(
        rootEntity,
        rootPath,
        expression,
        allowlist,
        settings,
        settings.allowNestedPathsFor(rootEntity));
  }

  public Predicate build(
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      String expression,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      boolean allowNestedPaths) {
    if (!settings.jsonPathFilterEnabled()) {
      throw new TmfFilteringException("jsonPath filter parameter is disabled.");
    }
    if (expression == null || expression.isBlank()) {
      return null;
    }
    if (expression.length() > settings.jsonPathMaxLength()) {
      throw new TmfFilteringException("jsonPath filter expression is too long.");
    }

    String stripped = stripWildcards(expression);
    validateAsJsonPath(stripped);
    String inner = unwrapFilterExpression(stripped);

    Parser parser = new Parser(inner);
    Node rootNode = parser.parseExpression();
    parser.ensureEnd();

    return toPredicate(rootNode, rootEntity, rootPath, allowlist, settings, "", allowNestedPaths)
        .orElse(null);
  }

  private void validateAsJsonPath(String expression) {
    try {
      JsonPath.compile(expression);
    } catch (InvalidPathException ex) {
      throw new TmfFilteringException("jsonPath expression is invalid.", ex);
    }
  }

  private String unwrapFilterExpression(String expression) {
    // Standard wrapper form: $[?(...)]
    Matcher direct = FILTER_WRAPPER.matcher(expression);
    if (direct.matches()) {
      return direct.group(1);
    }
    // TMF630 bare-wrapper shorthand: [?(...)]  (the `$.` prefix is omitted)
    Matcher bare = BARE_WRAPPER.matcher(expression);
    if (bare.matches()) {
      return bare.group(1);
    }
    // TMF630 sub-array shorthand: <arrayPath>[?(...)] or $.<arrayPath>[?(...)]
    // Rewrites to the canonical correlated-array filter form, equivalent to
    //   $[?(@.<arrayPath>[?(<inner>)])]
    String rewritten = trySubArrayShorthand(expression);
    if (rewritten != null) {
      return rewritten;
    }
    throw new TmfFilteringException(
        "jsonPath expression must be a filter expression: $[?(...)], [?(...)], or <arrayPath>[?(...)].");
  }

  /**
   * Strips canonical JsonPath {@code [*]} segments from the input as a transparent
   * projection sigil. Mongo's BSON path-equality auto-projects across arrays, so
   * {@code @.arr.field == 'X'} and {@code @.arr[*].field == 'X'} match the same
   * documents. Accepting both forms aligns with canonical JsonPath tooling
   * (jsonpath.com / Jayway evaluation) without changing what the toolkit actually
   * matches.
   *
   * <p>Quoted string literals are preserved verbatim — a literal value of
   * {@code '[*]'} inside a predicate is not treated as a projection sigil.
   */
  static String stripWildcards(String input) {
    StringBuilder out = new StringBuilder(input.length());
    boolean inQuotes = false;
    int i = 0;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (inQuotes) {
        out.append(c);
        if (c == '\'') {
          inQuotes = false;
        }
        i++;
      } else if (c == '\'') {
        out.append(c);
        inQuotes = true;
        i++;
      } else if (c == '[' && i + 2 < input.length()
          && input.charAt(i + 1) == '*'
          && input.charAt(i + 2) == ']') {
        i += 3;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  static String trySubArrayShorthand(String expression) {
    String s = expression.trim();
    if (s.startsWith("$.")) {
      s = s.substring(2);
    }
    int bracketStart = s.indexOf("[?(");
    if (bracketStart <= 0) {
      return null;
    }
    String arrayPath = s.substring(0, bracketStart).trim();
    if (!isValidDottedIdentifier(arrayPath)) {
      return null;
    }
    int contentStart = bracketStart + 3;
    int parenDepth = 1;
    int i = contentStart;
    while (i < s.length() && parenDepth > 0) {
      char c = s.charAt(i);
      if (c == '(') {
        parenDepth++;
      } else if (c == ')') {
        parenDepth--;
        if (parenDepth == 0) {
          break;
        }
      }
      i++;
    }
    if (parenDepth != 0) {
      return null;
    }
    String inner = s.substring(contentStart, i);
    i++;
    if (i >= s.length() || s.charAt(i) != ']') {
      return null;
    }
    i++;
    if (!isPureProjectionSuffix(s.substring(i))) {
      return null;
    }
    return "@." + arrayPath + "[?(" + inner + ")]";
  }

  /**
   * Returns true when {@code tail} is empty or a "pure projection suffix" — a
   * leading {@code .} followed by a valid dotted identifier with no further
   * {@code [?(...)]} predicates and no bracket forms ({@code [n]}, {@code [n:m]}).
   * DPC-style clients construct {@code ?filter=} URLs by reusing their
   * {@code ?sort=} templates, leaving a trailing projection like
   * {@code .productSpecCharacteristicValue.value} after the filter's
   * {@code [?(...)]}. The projection has no semantic effect on the filter — the
   * matched row set is fully determined by the predicate — so the parser
   * tolerates the suffix and discards it. Nested predicates or index accesses
   * in the suffix are rejected; the sub-array shorthand does not support
   * multi-level filtering on this surface.
   *
   * <p>{@code [*]} wildcards in the suffix are already removed by
   * {@link #stripWildcards} upstream, so this check only needs to recognise
   * a plain dotted identifier remainder.
   */
  static boolean isPureProjectionSuffix(String tail) {
    String t = tail.trim();
    if (t.isEmpty()) {
      return true;
    }
    if (!t.startsWith(".")) {
      return false;
    }
    String body = t.substring(1);
    if (body.indexOf('[') >= 0) {
      return false;
    }
    return isValidDottedIdentifier(body);
  }

  static boolean isValidDottedIdentifier(String s) {
    if (s.isEmpty() || s.startsWith(".") || s.endsWith(".") || s.contains("..")) {
      return false;
    }
    for (int j = 0; j < s.length(); j++) {
      char c = s.charAt(j);
      if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != '-') {
        return false;
      }
    }
    return true;
  }

  private Optional<Predicate> toPredicate(
      Node node,
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      String allowlistPrefix,
      boolean allowNestedPaths) {
    if (node instanceof LogicalNode logical) {
      return predicateForLogical(
          logical, rootEntity, rootPath, allowlist, settings, allowlistPrefix, allowNestedPaths);
    }
    if (node instanceof ArrayMatchNode arrayMatch) {
      return predicateForArrayMatch(
          arrayMatch, rootEntity, rootPath, allowlist, settings, allowlistPrefix, allowNestedPaths);
    }
    if (node instanceof LengthComparisonNode length) {
      return predicateForLength(
          length, rootEntity, rootPath, allowlist, settings, allowlistPrefix, allowNestedPaths);
    }
    if (node instanceof ComparisonNode comparison) {
      return predicateForComparison(
          comparison, rootEntity, rootPath, allowlist, settings, allowlistPrefix, allowNestedPaths);
    }
    throw new TmfFilteringException("Unsupported jsonPath filter expression.");
  }

  private Optional<Predicate> predicateForLogical(
      LogicalNode logical,
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      String allowlistPrefix,
      boolean allowNestedPaths) {
    Optional<Predicate> left =
        toPredicate(
            logical.left(), rootEntity, rootPath, allowlist, settings, allowlistPrefix, allowNestedPaths);
    Optional<Predicate> right =
        toPredicate(
            logical.right(), rootEntity, rootPath, allowlist, settings, allowlistPrefix, allowNestedPaths);
    if (left.isEmpty()) {
      return right;
    }
    if (right.isEmpty()) {
      return left;
    }
    BooleanBuilder builder = new BooleanBuilder();
    if (logical.operator() == LogicalOperator.AND) {
      builder.and(left.get()).and(right.get());
    } else {
      builder.or(left.get()).or(right.get());
    }
    return Optional.of(builder);
  }

  private Optional<Predicate> predicateForArrayMatch(
      ArrayMatchNode arrayMatch,
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      String allowlistPrefix,
      boolean allowNestedPaths) {
    Optional<ResolvedArrayPath> resolved =
        resolveArrayPathOrEmpty(rootEntity, rootPath, arrayMatch.arrayPath(), allowNestedPaths, settings);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    ResolvedArrayPath resolvedArrayPath = resolved.get();
    String nestedAllowlistPrefix =
        allowlistPrefix + normalizeArrayPathForAllowlist(arrayMatch.arrayPath()) + ".";
    if (isJpaEntity(rootEntity)) {
      return jpaArrayMatch(arrayMatch, resolvedArrayPath, allowlist, settings,
          nestedAllowlistPrefix, allowNestedPaths);
    }
    PathBuilder<?> elementRootPath = pathResolver.createRootPath(resolvedArrayPath.elementType());
    Optional<Predicate> nested =
        toPredicate(
            arrayMatch.inner(),
            resolvedArrayPath.elementType(),
            elementRootPath,
            allowlist,
            settings,
            nestedAllowlistPrefix,
            allowNestedPaths);
    return nested.map(p -> buildElemMatchPredicate(resolvedArrayPath.collectionPath(), p));
  }

  /**
   * Phase (a.3): array correlation for JOIN-mapped associations on JPA entities. Requires
   * the collection field to be annotated with one of {@code @OneToMany}, {@code @ManyToMany},
   * or {@code @ElementCollection}. Rejects any other collection shape (e.g. a
   * {@code @JdbcTypeCode(SqlTypes.JSON)}-mapped list stored as a JSON column) with a clear
   * message naming the escape hatch.
   *
   * <p>Emits a single correlated {@code EXISTS} subquery via {@code JPAExpressions} (loaded
   * reflectively to keep this module free of a compile-time querydsl-jpa dependency, mirroring
   * the existing Mongo {@code ELEM_MATCH} pattern in {@link #resolveElemMatchOperator}). The
   * nested predicate is built against a single fresh {@link PathBuilder} alias for the element
   * type, ensuring that multi-condition inner filters (e.g. {@code items.state=='X' && items.sku=='Y'})
   * translate to a SAME-element check ({@code WHERE alias.state=? AND alias.sku=?}) rather than
   * the cross-element {@code EXISTS(...WHERE state=?) AND EXISTS(...WHERE sku=?)} that
   * QueryDSL's {@code CollectionPath.any()} produces natively. See
   * {@code JPA_BACKEND_GAP_ANALYSIS.md} §3.3.
   */
  private Optional<Predicate> jpaArrayMatch(
      ArrayMatchNode arrayMatch,
      ResolvedArrayPath resolvedArrayPath,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      String nestedAllowlistPrefix,
      boolean allowNestedPaths) {
    Field collectionField = resolvedArrayPath.collectionField();
    if (collectionField == null || !isJoinMappedField(collectionField)) {
      throw new TmfFilteringException(
          "Array correlation in jsonPath filter on JPA entity requires the collection to be"
              + " JOIN-mapped via @OneToMany, @ManyToMany, or @ElementCollection. Field: "
              + arrayMatch.arrayPath()
              + ". For collections stored as JSON columns (e.g. @JdbcTypeCode(SqlTypes.JSON)),"
              + " use a JSONB-backed entity (see @Tmf630JsonbBacked) or repository-level"
              + " QueryDSL for the correlated predicate.");
    }
    Class<?> elementType = resolvedArrayPath.elementType();
    PathBuilder<?> subroot =
        new PathBuilder<>(
            elementType, "_jpaCorr_" + JPA_CORRELATION_ALIAS_COUNTER.incrementAndGet());
    Optional<Predicate> nested =
        toPredicate(
            arrayMatch.inner(),
            elementType,
            subroot,
            allowlist,
            settings,
            nestedAllowlistPrefix,
            allowNestedPaths);
    return nested.map(
        p -> buildJpaExistsPredicate(resolvedArrayPath.listExpression(), subroot, p));
  }

  private Predicate buildJpaExistsPredicate(
      com.querydsl.core.types.CollectionExpression<?, ?> collectionPath,
      PathBuilder<?> subroot,
      Predicate nested) {
    try {
      Class<?> jpaExpr = Class.forName("com.querydsl.jpa.JPAExpressions");
      Object selectOne = jpaExpr.getMethod("selectOne").invoke(null);
      Method fromCollection =
          selectOne
              .getClass()
              .getMethod(
                  "from",
                  Class.forName("com.querydsl.core.types.CollectionExpression"),
                  com.querydsl.core.types.Path.class);
      Object withFrom = fromCollection.invoke(selectOne, collectionPath, subroot);
      Method where =
          withFrom.getClass().getMethod("where", com.querydsl.core.types.Predicate[].class);
      Object withWhere = where.invoke(withFrom, (Object) new Predicate[] {nested});
      Method exists = withWhere.getClass().getMethod("exists");
      return (Predicate) exists.invoke(withWhere);
    } catch (ClassNotFoundException ex) {
      throw new TmfFilteringException(
          "Array correlation on JPA entity requires querydsl-jpa on the classpath.", ex);
    } catch (ReflectiveOperationException ex) {
      throw new TmfFilteringException(
          "Failed to build JPA correlated EXISTS subquery for array-match filter.", ex);
    }
  }

  @SuppressWarnings("java:S1872")
  private static boolean isJoinMappedField(Field field) {
    for (Annotation annotation : field.getAnnotations()) {
      String name = annotation.annotationType().getName();
      switch (name) {
        case "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToMany",
            "jakarta.persistence.ElementCollection",
            "javax.persistence.OneToMany",
            "javax.persistence.ManyToMany",
            "javax.persistence.ElementCollection" -> {
          return true;
        }
        default -> {
          // continue checking
        }
      }
    }
    return false;
  }

  private Optional<ResolvedArrayPath> resolveArrayPathOrEmpty(
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      String arrayPath,
      boolean allowNestedPaths,
      Tmf630FilterSettings settings) {
    try {
      return Optional.of(resolveArrayPath(rootEntity, rootPath, arrayPath, allowNestedPaths));
    } catch (TmfFilteringException ex) {
      if (settings.onUnknownJsonPathField() == UnknownParamBehavior.REJECT) {
        throw ex;
      }
      return Optional.empty();
    }
  }

  private Optional<Predicate> predicateForLength(
      LengthComparisonNode length,
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      String allowlistPrefix,
      boolean allowNestedPaths) {
    String effectivePath = allowlistPrefix + stripPositionalIndices(length.fieldPath());
    if (!isAllowedFieldOrReport(effectivePath, allowlist, settings)) {
      return Optional.empty();
    }
    Optional<ResolvedField> resolved =
        resolveFieldOrEmpty(rootEntity, length.fieldPath(), allowNestedPaths, settings);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    ResolvedField resolvedField = resolved.get();
    if (!resolvedField.leafIsCollection()) {
      throw new TmfFilteringException(
          "length() is supported only on collection fields: " + length.fieldPath());
    }
    return Optional.of(predicateFactory.buildLength(rootPath, resolvedField, length.size()));
  }

  private Optional<Predicate> predicateForComparison(
      ComparisonNode comparison,
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      String allowlistPrefix,
      boolean allowNestedPaths) {
    if (isJpaEntity(rootEntity) && containsPositionalIndex(comparison.fieldPath())) {
      return Optional.of(
          buildJpaPositionalPredicate(rootEntity, rootPath, comparison, allowNestedPaths));
    }
    // The allowlist is authored by JavaBean field name; positional index [N] narrows
    // which element, not which field, so it is stripped before the allowlist check.
    String effectivePath = allowlistPrefix + stripPositionalIndices(comparison.fieldPath());
    if (!isAllowedFieldOrReport(effectivePath, allowlist, settings)) {
      return Optional.empty();
    }
    Optional<ResolvedField> resolved =
        resolveFieldOrEmpty(rootEntity, comparison.fieldPath(), allowNestedPaths, settings);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    ResolvedField resolvedField = resolved.get();
    if (comparison.literal().kind() == LiteralKind.NULL) {
      return Optional.of(buildNullPredicate(comparison.operator(), rootPath, resolvedField));
    }
    if (comparison.operator() == ComparisonOperator.REGEX) {
      return Optional.of(buildRegexPredicate(rootPath, resolvedField, comparison.literal()));
    }
    return Optional.of(buildTypedPredicate(comparison, rootPath, resolvedField));
  }

  /**
   * Compiles {@code hopField[N].leaf <op> literal} on a JPA entity to a correlated
   * {@code EXISTS} subquery keyed on JPQL's {@code INDEX(alias)} function. The hop
   * field must be a JOIN-mapped collection annotated with {@code @OrderColumn} — the
   * annotation is what makes {@code [N]} meaningful under a normalized relational
   * schema. Rejected shapes: nested positional indices, positional after a scalar hop,
   * or a positional at the leaf (nonsensical for a comparison filter).
   */
  private Predicate buildJpaPositionalPredicate(
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      ComparisonNode comparison,
      boolean allowNestedPaths) {
    PositionalPath positional = parseSimplePositional(comparison.fieldPath());
    if (positional == null) {
      throw new TmfFilteringException(
          "Positional index [N] in jsonPath filter on a JPA entity is supported only in the"
              + " single-hop shape 'field[N].leaf' (v3.0.0 first cut). Nested positional"
              + " indices, positional after a scalar hop, or positional at the leaf are not"
              + " yet supported. Term: "
              + comparison.fieldPath());
    }
    Field collectionField = findField(rootEntity, positional.hopField());
    if (collectionField == null) {
      throw new TmfFilteringException(
          "Unknown field '" + positional.hopField() + "' on " + rootEntity.getSimpleName()
              + " in filter path: " + comparison.fieldPath());
    }
    if (!Collection.class.isAssignableFrom(collectionField.getType())
        || !isJoinMappedField(collectionField)) {
      throw new TmfFilteringException(
          "Positional index [N] in filter requires the field to be a JOIN-mapped collection"
              + " (@OneToMany, @ManyToMany, or @ElementCollection). Field: "
              + positional.hopField());
    }
    if (!hasOrderColumn(collectionField)) {
      throw new TmfFilteringException(
          "Positional index [N] in filter is only meaningful on a JPA collection that opts"
              + " into ordered persistence via @OrderColumn. Add @OrderColumn to '"
              + positional.hopField()
              + "' on "
              + rootEntity.getSimpleName()
              + ", or use a keyed match like "
              + positional.hopField()
              + "[?(@.name=='...')] instead.");
    }
    Class<?> elementType = resolveCollectionElementType(collectionField);
    PathBuilder<?> subroot =
        new PathBuilder<>(
            elementType, "_jpaPos_" + JPA_CORRELATION_ALIAS_COUNTER.incrementAndGet());
    ResolvedField leafField =
        pathResolver.resolve(elementType, positional.leafPath(), allowNestedPaths);
    Predicate leafPredicate = buildSubqueryLeafPredicate(comparison, subroot, leafField);
    com.querydsl.core.types.dsl.NumberExpression<Integer> index =
        Expressions.numberTemplate(Integer.class, "index({0})", subroot);
    Predicate combined = ExpressionUtils.allOf(index.eq(positional.index()), leafPredicate);
    com.querydsl.core.types.CollectionExpression<?, ?> listExpression =
        rootPath.getList(positional.hopField(), (Class) elementType);
    return buildJpaExistsPredicate(listExpression, subroot, combined);
  }

  private Predicate buildSubqueryLeafPredicate(
      ComparisonNode comparison, PathBuilder<?> subroot, ResolvedField leafField) {
    if (comparison.literal().kind() == LiteralKind.NULL) {
      return buildNullPredicate(comparison.operator(), subroot, leafField);
    }
    if (comparison.operator() == ComparisonOperator.REGEX) {
      return buildRegexPredicate(subroot, leafField, comparison.literal());
    }
    return buildTypedPredicate(comparison, subroot, leafField);
  }

  /**
   * Parses the simple positional shape supported by v1: {@code hop[N].leaf}, where
   * {@code hop} is a single identifier off the entity root, {@code N} is a
   * non-negative integer, and {@code leaf} is a dotted scalar path within the
   * collection element (no further {@code [N]}). Returns {@code null} if the shape
   * doesn't match — the caller then rejects with a scope-specific message.
   */
  static PositionalPath parseSimplePositional(String fieldPath) {
    Matcher matcher = SIMPLE_POSITIONAL.matcher(fieldPath);
    if (!matcher.matches()) {
      return null;
    }
    String leaf = matcher.group(3);
    if (containsPositionalIndex(leaf)) {
      return null;
    }
    return new PositionalPath(matcher.group(1), Integer.parseInt(matcher.group(2)), leaf);
  }

  private static final Pattern SIMPLE_POSITIONAL =
      Pattern.compile("^([A-Za-z_]\\w*)\\[(\\d+)\\]\\.(.+)$");

  record PositionalPath(String hopField, int index, String leafPath) {}

  @SuppressWarnings("java:S1872")
  private static boolean hasOrderColumn(Field field) {
    for (Annotation annotation : field.getAnnotations()) {
      String name = annotation.annotationType().getName();
      if ("jakarta.persistence.OrderColumn".equals(name)
          || "javax.persistence.OrderColumn".equals(name)) {
        return true;
      }
    }
    return false;
  }

  private boolean isAllowedFieldOrReport(
      String effectivePath, Set<String> allowlist, Tmf630FilterSettings settings) {
    if (isAllowedField(effectivePath, allowlist, settings.allowlistMode())) {
      return true;
    }
    if (settings.onUnknownJsonPathField() == UnknownParamBehavior.REJECT) {
      throw new TmfFilteringException("Unknown or disallowed field: " + effectivePath);
    }
    return false;
  }

  private Optional<ResolvedField> resolveFieldOrEmpty(
      Class<?> rootEntity, String fieldPath, boolean allowNestedPaths, Tmf630FilterSettings settings) {
    try {
      return Optional.of(pathResolver.resolve(rootEntity, fieldPath, allowNestedPaths));
    } catch (TmfFilteringException ex) {
      if (settings.onUnknownJsonPathField() == UnknownParamBehavior.REJECT) {
        throw ex;
      }
      return Optional.empty();
    }
  }

  private Predicate buildNullPredicate(
      ComparisonOperator op, PathBuilder<?> rootPath, ResolvedField resolvedField) {
    if (op == ComparisonOperator.EQ) {
      return predicateFactory.buildNoValue(rootPath, resolvedField, TmfOperator.IS_NULL);
    }
    if (op == ComparisonOperator.NE) {
      return predicateFactory.buildNoValue(rootPath, resolvedField, TmfOperator.IS_NOT_NULL);
    }
    throw new TmfFilteringException("Null literal only supports == and != operators.");
  }

  private Predicate buildTypedPredicate(
      ComparisonNode comparison, PathBuilder<?> rootPath, ResolvedField resolvedField) {
    Object typedValue =
        valueConverter.convert(
            comparison.literal().valueAsString(),
            resolvedField.javaType(),
            resolvedField.fieldPath());
    return predicateFactory.build(rootPath, resolvedField, toTmfOperator(comparison.operator()), typedValue);
  }

  private static TmfOperator toTmfOperator(ComparisonOperator op) {
    return switch (op) {
      case EQ -> TmfOperator.EQ;
      case NE -> TmfOperator.NE;
      case GT -> TmfOperator.GT;
      case GTE -> TmfOperator.GTE;
      case LT -> TmfOperator.LT;
      case LTE -> TmfOperator.LTE;
      case REGEX -> throw new IllegalStateException("REGEX is handled before typed conversion");
    };
  }

  /**
   * TMF630 Part 6 {@code =~} operator. Accepts exactly the spec's {@code /pattern/flags}
   * literal form; only the {@code i} flag is supported — the spec's own table notes library
   * variance, and silently ignoring unknown flags would change match semantics. Routes
   * through the same {@link PredicateFactory} REGEX/REGEXI path as the attribute-side
   * {@code .regex}/{@code .regexi} operators, so the {@code regex.enabled} gate and
   * {@code max-length} limit apply identically.
   */
  private Predicate buildRegexPredicate(
      PathBuilder<?> rootPath, ResolvedField resolvedField, LiteralToken literal) {
    if (literal.kind() != LiteralKind.REGEX) {
      throw new TmfFilteringException("=~ requires a /pattern/ literal in jsonPath filter.");
    }
    String raw = literal.rawValue();
    int close = raw.lastIndexOf('/');
    String pattern = raw.substring(1, close);
    String flags = raw.substring(close + 1);
    boolean ignoreCase = "i".equals(flags);
    if (!flags.isEmpty() && !ignoreCase) {
      throw new TmfFilteringException(
          "Unsupported regex flags '" + flags + "' in jsonPath filter; supported: i");
    }
    return predicateFactory.build(
        rootPath,
        resolvedField,
        ignoreCase ? TmfOperator.REGEXI : TmfOperator.REGEX,
        pattern);
  }

  private boolean isAllowedField(String fieldPath, Set<String> allowlist, AllowlistMode mode) {
    if (mode == AllowlistMode.ALLOW_ALL) {
      return allowlist.isEmpty() || allowlist.contains(fieldPath);
    }
    return allowlist.contains(fieldPath);
  }

  private static boolean containsPositionalIndex(String fieldPath) {
    for (int i = 0; i < fieldPath.length() - 1; i++) {
      if (fieldPath.charAt(i) == '[' && Character.isDigit(fieldPath.charAt(i + 1))) {
        return true;
      }
    }
    return false;
  }

  private String stripPositionalIndices(String fieldPath) {
    if (fieldPath.indexOf('[') < 0) {
      return fieldPath;
    }
    return fieldPath.replaceAll("\\[\\d+]", "");
  }

  // sonar java:S1872 — comparing the annotation's FQN string rather than doing
  // `instanceof Entity` is deliberate: attribute-filtering-core declares
  // jakarta.persistence-api as an optional dep so downstream services that don't
  // use JPA aren't burdened. String-based detection keeps the core module
  // backend-agnostic even when the annotation type is off the classpath.
  @SuppressWarnings("java:S1872")
  private boolean isJpaEntity(Class<?> type) {
    for (Annotation annotation : type.getAnnotations()) {
      if ("jakarta.persistence.Entity".equals(annotation.annotationType().getName())) {
        return true;
      }
    }
    return false;
  }

  private String normalizeArrayPathForAllowlist(String arrayPath) {
    return arrayPath.replace("[*]", "");
  }

  private ResolvedArrayPath resolveArrayPath(
      Class<?> rootEntity, PathBuilder<?> rootPath, String arrayPath, boolean allowNestedPaths) {
    String normalized = normalizeArrayPathForAllowlist(arrayPath);
    if (!allowNestedPaths && normalized.contains(".")) {
      throw new TmfFilteringException("Nested field paths are disabled: " + normalized);
    }
    if (normalized.isBlank()) {
      throw new TmfFilteringException("Array path must not be blank in jsonPath filter.");
    }

    PathBuilder<?> currentPath = rootPath;
    Class<?> currentType = rootEntity;
    String[] segments = normalized.split("\\.");
    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i];
      Field field = findField(currentType, segment);
      if (field == null) {
        throw new TmfFilteringException("Unknown field path: " + normalized);
      }

      Class<?> fieldType = field.getType();
      boolean last = i == segments.length - 1;
      if (Collection.class.isAssignableFrom(fieldType)) {
        Class<?> elementType = resolveCollectionElementType(field);
        Expression<?> collectionPath = currentPath.get(segment, fieldType);
        PathBuilder<?> elementPath = currentPath.getCollection(segment, elementType).any();
        com.querydsl.core.types.dsl.ListPath<?, ?> listPath =
            currentPath.getList(segment, (Class) elementType);
        if (last) {
          return new ResolvedArrayPath(elementType, collectionPath, field, elementPath, listPath);
        }
        currentType = elementType;
        currentPath = elementPath;
      } else {
        if (last) {
          throw new TmfFilteringException(
              "Array match requires a collection path but found scalar path: " + normalized);
        }
        currentType = fieldType;
        currentPath = currentPath.get(segment, fieldType);
      }
    }
    throw new TmfFilteringException("Invalid array path in jsonPath filter: " + normalized);
  }

  private Class<?> resolveCollectionElementType(Field field) {
    Type genericType = field.getGenericType();
    if (genericType instanceof ParameterizedType parameterizedType) {
      Type[] args = parameterizedType.getActualTypeArguments();
      if (args.length == 1 && args[0] instanceof Class<?> elementType) {
        return elementType;
      }
    }
    return Object.class;
  }

  private Field findField(Class<?> type, String name) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return cursor.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return null;
  }

  private Predicate buildElemMatchPredicate(Expression<?> collectionPath, Predicate nestedPredicate) {
    return Expressions.predicate(resolveElemMatchOperator(), collectionPath, nestedPredicate);
  }

  private Operator resolveElemMatchOperator() {
    try {
      Class<?> mongoOpsClass = Class.forName("com.querydsl.mongodb.MongodbOps");
      @SuppressWarnings({"rawtypes", "unchecked"})
      Enum<?> elemMatch = Enum.valueOf((Class<? extends Enum>) mongoOpsClass, "ELEM_MATCH");
      return (Operator) elemMatch;
    } catch (ClassNotFoundException ex) {
      throw new TmfFilteringException(
          "Array correlation requires querydsl-mongodb on the classpath.", ex);
    }
  }

  private interface Node {}

  private record LogicalNode(Node left, LogicalOperator operator, Node right) implements Node {}

  private record ArrayMatchNode(String arrayPath, Node inner) implements Node {}

  private record ResolvedArrayPath(
      Class<?> elementType,
      Expression<?> collectionPath,
      Field collectionField,
      PathBuilder<?> anyElementPath,
      com.querydsl.core.types.CollectionExpression<?, ?> listExpression) {}

  private enum LogicalOperator {
    AND,
    OR
  }

  private record ComparisonNode(
      String fieldPath, ComparisonOperator operator, LiteralToken literal) implements Node {}

  private record LengthComparisonNode(String fieldPath, int size) implements Node {}

  private enum ComparisonOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
    REGEX
  }

  private enum LiteralKind {
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    REGEX
  }

  private record LiteralToken(LiteralKind kind, String rawValue) {
    String valueAsString() {
      return rawValue;
    }
  }

  private static final class Parser {

    /**
     * Bounds the recursive-descent stack across both within-Parser recursion (via
     * parenthesised sub-expressions) and across-Parser recursion (via nested
     * array-match Parser instances created in {@link #parseArrayMatch(String)}).
     * The tokenizer's per-expression length cap ({@code json-path-filter.max-length})
     * bounds input size but not structural depth — a 2048-char string can still pack
     * hundreds of nested {@code ((((...))))} parens and blow the JVM stack. Rejecting
     * excessive nesting here surfaces the problem as a clean 400, not a 500 from
     * {@link StackOverflowError}.
     */
    private static final int MAX_DEPTH = 32;

    private final List<Token> tokens;
    private int index;
    private int currentDepth;

    private Parser(String expression) {
      this(expression, 0);
    }

    private Parser(String expression, int startDepth) {
      this.tokens = tokenize(expression);
      this.index = 0;
      this.currentDepth = startDepth;
    }

    private Node parseExpression() {
      if (currentDepth >= MAX_DEPTH) {
        throw new TmfFilteringException(
            "jsonPath filter expression nesting is too deep (max " + MAX_DEPTH + " levels).");
      }
      currentDepth++;
      try {
        return parseOr();
      } finally {
        currentDepth--;
      }
    }

    private Node parseOr() {
      Node left = parseAnd();
      while (match(TokenType.OR)) {
        Node right = parseAnd();
        left = new LogicalNode(left, LogicalOperator.OR, right);
      }
      return left;
    }

    private Node parseAnd() {
      Node left = parsePrimary();
      while (match(TokenType.AND)) {
        Node right = parsePrimary();
        left = new LogicalNode(left, LogicalOperator.AND, right);
      }
      return left;
    }

    private Node parsePrimary() {
      if (match(TokenType.LPAREN)) {
        Node node = parseExpression();
        expect(TokenType.RPAREN, "Missing closing parenthesis in jsonPath filter.");
        return node;
      }

      if (match(TokenType.NOT)) {
        Token negatedField =
            expect(TokenType.FIELD, "Expected @.fieldPath after '!' in jsonPath filter.");
        if (negatedField.text().contains("[?(")) {
          throw new TmfFilteringException(
              "Negation of array-match expressions is not supported in jsonPath filter.");
        }
        return new ComparisonNode(
            negatedField.text().substring(2),
            ComparisonOperator.EQ,
            new LiteralToken(LiteralKind.NULL, "null"));
      }

      if (peek(TokenType.FIELD_LENGTH)) {
        Token lengthField = tokens.get(index);
        index++;
        return parseLength(lengthField.text());
      }

      Token fieldToken = expect(TokenType.FIELD, "Expected @.fieldPath in jsonPath filter.");
      if (fieldToken.text().contains("[?(")) {
        return parseArrayMatch(fieldToken.text());
      }
      Token operatorToken =
          expect(TokenType.OPERATOR, "Expected comparison operator in jsonPath filter.");
      Token literalToken = expect(TokenType.LITERAL, "Expected literal in jsonPath filter.");

      ComparisonOperator operator =
          switch (operatorToken.text()) {
            case "==" -> ComparisonOperator.EQ;
            case "!=" -> ComparisonOperator.NE;
            case ">" -> ComparisonOperator.GT;
            case ">=" -> ComparisonOperator.GTE;
            case "<" -> ComparisonOperator.LT;
            case "<=" -> ComparisonOperator.LTE;
            case "=~" -> ComparisonOperator.REGEX;
            default -> throw new TmfFilteringException("Unsupported jsonPath operator: " + operatorToken.text());
          };

      return new ComparisonNode(
          fieldToken.text().substring(2), operator, parseLiteral(literalToken.text()));
    }

    private Node parseLength(String fieldTokenText) {
      // Reading-A scope: `length()==N` only. Non-`==` comparators and non-integer
      // literals are rejected here so the message is specific to length(), not the
      // generic "unsupported operator". The FIELD_LENGTH token text still carries
      // the leading `@.` prefix that FIELD tokens carry — strip it consistently.
      Token operatorToken =
          expect(TokenType.OPERATOR, "length() must be followed by a comparison operator.");
      if (!"==".equals(operatorToken.text())) {
        throw new TmfFilteringException(
            "length() supports only == comparison; use [?(...)] for non-empty checks.");
      }
      Token literalToken = expect(TokenType.LITERAL, "length() requires an integer literal.");
      String raw = literalToken.text();
      int value;
      try {
        value = Integer.parseInt(raw);
      } catch (NumberFormatException ex) {
        throw new TmfFilteringException("length() requires a non-negative integer literal.");
      }
      if (value < 0) {
        throw new TmfFilteringException("length() requires a non-negative integer literal.");
      }
      String fieldPath = fieldTokenText.startsWith("@.") ? fieldTokenText.substring(2) : "";
      if (fieldPath.isBlank()) {
        throw new TmfFilteringException("length() requires a named collection field.");
      }
      return new LengthComparisonNode(fieldPath, value);
    }

    private Node parseArrayMatch(String fieldToken) {
      int filterStart = fieldToken.indexOf("[?(");
      if (filterStart < 0 || !fieldToken.endsWith(")]")) {
        throw new TmfFilteringException("Invalid array match expression in jsonPath filter.");
      }
      String arrayPath = fieldToken.substring(2, filterStart);
      if (arrayPath.isBlank()) {
        throw new TmfFilteringException("Array path must not be blank in jsonPath filter.");
      }
      String innerExpression = fieldToken.substring(filterStart + 3, fieldToken.length() - 2);
      Parser nested = new Parser(innerExpression, currentDepth);
      Node nestedRoot = nested.parseExpression();
      nested.ensureEnd();
      return new ArrayMatchNode(arrayPath, nestedRoot);
    }

    private void ensureEnd() {
      if (!peek(TokenType.EOF)) {
        throw new TmfFilteringException("Invalid jsonPath filter expression.");
      }
    }

    private LiteralToken parseLiteral(String raw) {
      String value = raw.trim();
      if (value.startsWith("/") && value.lastIndexOf('/') > 0) {
        return new LiteralToken(LiteralKind.REGEX, value);
      }
      if (value.equals("null")) {
        return new LiteralToken(LiteralKind.NULL, "null");
      }
      if (value.equals("true") || value.equals("false")) {
        return new LiteralToken(LiteralKind.BOOLEAN, value);
      }
      if ((value.startsWith("'") && value.endsWith("'"))
          || (value.startsWith("\"") && value.endsWith("\""))) {
        return new LiteralToken(LiteralKind.STRING, unquote(value));
      }
      if (isNumeric(value)) {
        return new LiteralToken(LiteralKind.NUMBER, value);
      }
      throw new TmfFilteringException("Unsupported literal in jsonPath filter: " + raw);
    }

    private String unquote(String value) {
      if (value.length() < 2) {
        return value;
      }
      String body = value.substring(1, value.length() - 1);
      return body.replace("\\'", "'").replace("\\\"", "\"");
    }

    private boolean isNumeric(String value) {
      try {
        Double.parseDouble(value);
        return true;
      } catch (NumberFormatException ex) {
        return false;
      }
    }

    private boolean match(TokenType type) {
      if (peek(type)) {
        index++;
        return true;
      }
      return false;
    }

    private boolean peek(TokenType type) {
      return tokens.get(index).type() == type;
    }

    private Token expect(TokenType type, String message) {
      Token token = tokens.get(index);
      if (token.type() != type) {
        throw new TmfFilteringException(message);
      }
      index++;
      return token;
    }

    private static List<Token> tokenize(String input) {
      List<Token> tokens = new ArrayList<>();
      int i = 0;
      while (i < input.length()) {
        i = scanNextToken(input, i, tokens);
      }
      tokens.add(new Token(TokenType.EOF, ""));
      return tokens;
    }

    private static int scanNextToken(String input, int i, List<Token> tokens) {
      char ch = input.charAt(i);
      if (Character.isWhitespace(ch)) {
        return i + 1;
      }
      Integer punct = tryScanPunctuation(ch, tokens);
      if (punct != null) {
        return i + punct;
      }
      int twoChar = tryScanTwoCharOperator(input, i, tokens);
      if (twoChar >= 0) {
        return twoChar;
      }
      Integer singleOp = tryScanSingleCharOperator(ch, tokens);
      if (singleOp != null) {
        return i + singleOp;
      }
      return scanValueOrThrow(input, i, tokens, ch);
    }

    private static Integer tryScanPunctuation(char ch, List<Token> tokens) {
      if (ch == '(') {
        tokens.add(new Token(TokenType.LPAREN, "("));
        return 1;
      }
      if (ch == ')') {
        tokens.add(new Token(TokenType.RPAREN, ")"));
        return 1;
      }
      return null;
    }

    private static int tryScanTwoCharOperator(String input, int i, List<Token> tokens) {
      if (i + 1 >= input.length()) {
        return -1;
      }
      String two = input.substring(i, i + 2);
      Token token = switch (two) {
        case "&&" -> new Token(TokenType.AND, "&&");
        case "||" -> new Token(TokenType.OR, "||");
        case "==", "!=", ">=", "<=", "=~" -> new Token(TokenType.OPERATOR, two);
        default -> null;
      };
      if (token == null) {
        return -1;
      }
      tokens.add(token);
      return i + 2;
    }

    private static Integer tryScanSingleCharOperator(char ch, List<Token> tokens) {
      if (ch == '>' || ch == '<') {
        tokens.add(new Token(TokenType.OPERATOR, String.valueOf(ch)));
        return 1;
      }
      if (ch == '!') {
        tokens.add(new Token(TokenType.NOT, "!"));
        return 1;
      }
      return null;
    }

    private static int scanValueOrThrow(String input, int i, List<Token> tokens, char ch) {
      if (ch == '@') {
        return scanFieldToken(input, i, tokens);
      }
      if (ch == '/') {
        return scanRegexLiteral(input, i, tokens);
      }
      if (ch == '\'' || ch == '"') {
        return scanStringLiteral(input, i, tokens, ch);
      }
      int lit = tryScanNumberOrKeywordLiteral(input, i, tokens, ch);
      if (lit >= 0) {
        return lit;
      }
      throw new TmfFilteringException("Unsupported token in jsonPath filter near: " + input.substring(i));
    }

    private static int scanFieldToken(String input, int start, List<Token> tokens) {
      int i = start + 1;
      while (i < input.length()) {
        char c = input.charAt(i);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '@') {
          i++;
        } else if (c == '[' && input.startsWith("[?(", i)) {
          i = skipCorrelationBrackets(input, i);
        } else if (c == '[' && i + 1 < input.length() && Character.isDigit(input.charAt(i + 1))) {
          // TMF630 Part 6 JSONPath positional index `[N]` — kept in the FIELD token
          // verbatim so FieldPathResolver can translate it into a dotted numeric hop
          // (`arr[2].leaf` → `arr.2.leaf`) that Mongo resolves natively.
          i = skipPositionalIndex(input, i);
        } else {
          break;
        }
      }
      String field = input.substring(start, i);
      if (!field.startsWith("@.")) {
        throw new TmfFilteringException("jsonPath field must start with @.: " + field);
      }
      // TMF630 Part 6 Functions table lists `length()` returning Integer.
      // Consumed as part of the field token so parsePrimary can dispatch to
      // the length-comparison branch instead of the plain field comparison.
      if (field.endsWith(".length") && input.startsWith("()", i)) {
        String base = field.substring(0, field.length() - ".length".length());
        tokens.add(new Token(TokenType.FIELD_LENGTH, base));
        return i + 2;
      }
      tokens.add(new Token(TokenType.FIELD, field));
      return i;
    }

    private static int skipCorrelationBrackets(String input, int i) {
      int depth = 1;
      i += 3;
      while (i < input.length() && depth > 0) {
        char current = input.charAt(i);
        if (current == '(') {
          depth++;
        } else if (current == ')') {
          depth--;
        }
        i++;
      }
      if (depth != 0 || i >= input.length() || input.charAt(i) != ']') {
        throw new TmfFilteringException("Invalid array filter syntax in jsonPath filter.");
      }
      return i + 1;
    }

    private static int skipPositionalIndex(String input, int i) {
      int j = i + 1;
      while (j < input.length() && Character.isDigit(input.charAt(j))) {
        j++;
      }
      if (j >= input.length() || input.charAt(j) != ']') {
        throw new TmfFilteringException("Malformed positional index in jsonPath filter.");
      }
      return j + 1;
    }

    // TMF630 Part 6 regex literal for =~: /pattern/flags. The '\' escape keeps a
    // literal '/' inside the pattern; trailing letters are flags.
    private static int scanRegexLiteral(String input, int start, List<Token> tokens) {
      int i = start + 1;
      boolean closed = false;
      boolean escaped = false;
      while (i < input.length()) {
        char c = input.charAt(i);
        i++;
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '/') {
          closed = true;
          break;
        }
      }
      if (!closed) {
        throw new TmfFilteringException("Unterminated regex literal in jsonPath filter.");
      }
      while (i < input.length() && Character.isLetter(input.charAt(i))) {
        i++;
      }
      tokens.add(new Token(TokenType.LITERAL, input.substring(start, i)));
      return i;
    }

    private static int scanStringLiteral(String input, int start, List<Token> tokens, char quote) {
      int i = start + 1;
      boolean escaped = false;
      while (i < input.length()) {
        char c = input.charAt(i);
        if (c == '\\' && !escaped) {
          escaped = true;
          i++;
        } else if (c == quote && !escaped) {
          i++;
          break;
        } else {
          escaped = false;
          i++;
        }
      }
      if (i > input.length() || input.charAt(i - 1) != quote) {
        throw new TmfFilteringException("Unterminated string literal in jsonPath filter.");
      }
      tokens.add(new Token(TokenType.LITERAL, input.substring(start, i)));
      return i;
    }

    private static int tryScanNumberOrKeywordLiteral(
        String input, int start, List<Token> tokens, char ch) {
      if (!(Character.isDigit(ch) || ch == '-' || ch == 't' || ch == 'f' || ch == 'n')) {
        return -1;
      }
      int i = start;
      while (i < input.length()) {
        char c = input.charAt(i);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') {
          i++;
        } else {
          break;
        }
      }
      String token = input.substring(start, i);
      if (!isBooleanNullOrNumeric(token)) {
        return -1;
      }
      tokens.add(new Token(TokenType.LITERAL, token));
      return i;
    }

    private static boolean isBooleanNullOrNumeric(String token) {
      String lowered = token.toLowerCase(Locale.ROOT);
      return lowered.equals("true")
          || lowered.equals("false")
          || lowered.equals("null")
          || isNumericToken(token);
    }

    private static boolean isNumericToken(String value) {
      try {
        Double.parseDouble(value);
        return true;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
  }

  private record Token(TokenType type, String text) {}

  private enum TokenType {
    LPAREN,
    RPAREN,
    AND,
    OR,
    NOT,
    OPERATOR,
    FIELD,
    FIELD_LENGTH,
    LITERAL,
    EOF
  }
}
