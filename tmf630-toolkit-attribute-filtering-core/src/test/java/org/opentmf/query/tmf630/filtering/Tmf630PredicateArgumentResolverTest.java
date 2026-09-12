package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.context.request.NativeWebRequest;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.PredicateLimits;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.springframework.core.MethodParameter;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class Tmf630PredicateArgumentResolverTest {

  @Test
  void buildsPredicateWhenParametersAreValid() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "abc");
    request.setParameter("page", "0");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
  }

  @Test
  void ignoresOffsetLimitAndFieldsAsReservedParams() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "abc");
    request.setParameter("offset", "0");
    request.setParameter("limit", "10");
    request.setParameter("fields", "name,age");
    request.setParameter("sort", "-name");
    // TMF630 Part 2 Ch.3 directives — must never be treated as attribute filters.
    request.setParameter("depth", "2");
    request.setParameter("expand", "productOffering.productSpecification");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
    assertTrue(predicate.toString().contains("name"));
  }

  @Test
  void rejectsUnsupportedParameterWithoutQuerydslRoot() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "abc");
    MethodParameter parameter = invalidPredicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void rejectsDisallowedFieldWhenAllowlistIsStrict() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.DENY_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("forbidden.eq", "abc");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void supportsParameterChecksPredicateType() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    Method method = StubController.class.getDeclaredMethod("search", com.querydsl.core.types.Predicate.class);
    assertTrue(resolver.supportsParameter(new MethodParameter(method, 0)));
  }

  @Test
  void supportsNoValueAndMultiValueOperators() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);
    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of("name", "age"),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128),
            new JsonPathFilterPredicateBuilder(
                new FieldPathResolver(),
                new ValueConverter(new DefaultFormattingConversionService()),
                new PredicateFactory(false, 128)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.isnull", "");
    request.setParameter("name.in", "a", "b");
    request.setParameter("age.between", "18", "65");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
  }

  @Test
  void ignoresUnknownOperatorAndFieldWhenConfiguredToIgnore() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.IGNORE,
            UnknownParamBehavior.IGNORE,
            true,
            2048,
            UnknownParamBehavior.IGNORE);

    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), false),
            settings,
            rootEntity -> Set.of("name"),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128),
            new JsonPathFilterPredicateBuilder(
                new FieldPathResolver(),
                new ValueConverter(new DefaultFormattingConversionService()),
                new PredicateFactory(false, 128)));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.badop", "x");
    request.setParameter("forbidden.eq", "x");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);
    assertNotNull(predicate);
  }

  @Test
  void rejectsWhenPredicateLimitsExceeded() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.AND,
            false,
            false,
            false,
            new PredicateLimits(1, 1, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);

    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of("name"),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128),
            new JsonPathFilterPredicateBuilder(
                new FieldPathResolver(),
                new ValueConverter(new DefaultFormattingConversionService()),
                new PredicateFactory(false, 128)));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "a", "b");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void supportsJsonPathFilterExpression() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("filter", "$[?(@.name == 'abc' && @.age >= 18)]");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
  }

  @Test
  void rejectsNonFilterJsonPathExpression() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("filter", "$.name");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void combinesAttributesAndJsonPathUsingConfiguredOrMode() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter valueConverter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory predicateFactory = new PredicateFactory(false, 128);
    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of("name"),
            pathResolver,
            valueConverter,
            predicateFactory,
            new JsonPathFilterPredicateBuilder(pathResolver, valueConverter, predicateFactory));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "abc");
    request.setParameter("filter", "$[?(@.name == 'xyz')]");
    request.setParameter("filter.combineWithAttributes", "OR");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);
    assertNotNull(predicate);
  }

  @Test
  void combinesMultiClauseAttributeBlockWithMultiConditionJsonPathUsingOr() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter valueConverter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory predicateFactory = new PredicateFactory(false, 128);
    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of("name", "age"),
            pathResolver,
            valueConverter,
            predicateFactory,
            new JsonPathFilterPredicateBuilder(pathResolver, valueConverter, predicateFactory));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "abc");
    request.setParameter("age.gte", "18");
    request.setParameter("filter", "$[?(@.name == 'xyz' && @.age >= 30)]");
    request.setParameter("filter.combineWithAttributes", "OR");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);
    assertNotNull(predicate);
  }

  @Test
  void rejectsWhenMultipleFilterParametersProvided() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("filter", "$[?(@.name == 'a')]");
    request.addParameter("filter", "$[?(@.name == 'b')]");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void splitsCsvOnMultiValueOperators() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.in", "a,b,c");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
    String rendered = predicate.toString();
    assertTrue(rendered.contains("a"));
    assertTrue(rendered.contains("b"));
    assertTrue(rendered.contains("c"));
  }

  @Test
  void csvSplitHonorsEscapedCommaAsLiteralInMultiValueOperators() {
    assertEquals(List.of("A,B", "C"), Tmf630FilterParser.splitCsvForMultiValue("A\\,B,C"));
  }

  @Test
  void csvSplitSkipsEmptyComponentsInMultiValueOperators() {
    assertEquals(List.of("A", "B"), Tmf630FilterParser.splitCsvForMultiValue("A,,B,"));
  }

  @Test
  void csvSplitPassesThroughNullAndCommaFreeValues() {
    assertEquals(1, Tmf630FilterParser.splitCsvForMultiValue(null).size());
    assertNull(Tmf630FilterParser.splitCsvForMultiValue(null).get(0));
    assertEquals(List.of("solo"), Tmf630FilterParser.splitCsvForMultiValue("solo"));
  }

  @Test
  void rejectsCsvExpansionThatExceedsMaxValuesPerKey() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(20, 2, 128),
            AllowlistMode.ALLOW_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);
    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of(),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128),
            new JsonPathFilterPredicateBuilder(
                new FieldPathResolver(),
                new ValueConverter(new DefaultFormattingConversionService()),
                new PredicateFactory(false, 128)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.in", "a,b,c");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void rejectsWhenJsonPathFilterIsDisabled() throws Exception {

    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            AllowlistMode.ALLOW_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            false,
            2048,
            UnknownParamBehavior.REJECT);
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter valueConverter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory predicateFactory = new PredicateFactory(false, 128);
    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of(),
            pathResolver,
            valueConverter,
            predicateFactory,
            new JsonPathFilterPredicateBuilder(pathResolver, valueConverter, predicateFactory));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("filter", "$[?(@.name == 'x')]");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  // ---------- TMF630 value-list (comma-OR) semantics for implicit eq ----------

  @Test
  void implicitEqCsvSplitsIntoOrOfEqualsMatchingRepeatedParamForm() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);

    MockHttpServletRequest csv = new MockHttpServletRequest();
    csv.setParameter("name", "a,b");
    MockHttpServletRequest repeated = new MockHttpServletRequest();
    repeated.setParameter("name", "a", "b");

    String csvPredicate = resolveToString(resolver, csv);
    assertEquals(resolveToString(resolver, repeated), csvPredicate);
    assertEquals("entity.name = a || entity.name = b", csvPredicate);
  }

  @Test
  void implicitEqCsvComposesWithRepeatedParamsPerCombineMode() throws Exception {
    Tmf630PredicateArgumentResolver orResolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest mixed = new MockHttpServletRequest();
    mixed.setParameter("name", "a,b", "c");
    MockHttpServletRequest repeated = new MockHttpServletRequest();
    repeated.setParameter("name", "a", "b", "c");

    // Under OR the value-list group collapses into the same OR chain as three repeated values.
    assertEquals(resolveToString(orResolver, repeated), resolveToString(orResolver, mixed));

    // Under AND the per-value groups compose per combineRepeatedValues: (a OR b) AND c.
    Tmf630PredicateArgumentResolver andResolver = csvResolver(CombineMode.AND, 20, true);
    assertEquals(
        "(entity.name = a || entity.name = b) && entity.name = c",
        resolveToString(andResolver, mixed));
  }

  @Test
  void explicitEqKeepsCommaAsLiteralEscapeHatch() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "a,b");

    assertEquals("entity.name = a,b", resolveToString(resolver, request));
  }

  @Test
  void otherExplicitSingleValueOperatorsKeepCommaAsLiteral() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);

    MockHttpServletRequest ne = new MockHttpServletRequest();
    ne.setParameter("name.ne", "a,b");
    assertTrue(resolveToString(resolver, ne).contains("a,b"));

    MockHttpServletRequest like = new MockHttpServletRequest();
    like.setParameter("name.like", "a,b");
    assertTrue(resolveToString(resolver, like).contains("a,b"));
  }

  @Test
  void explicitInStillSplitsAndCoversSameElementsAsImplicitEq() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);

    MockHttpServletRequest in = new MockHttpServletRequest();
    in.setParameter("name.in", "a,b");
    String inPredicate = resolveToString(resolver, in);
    assertEquals("entity.name in [a, b]", inPredicate);

    // Same elements, semantically equivalent OR-of-eq form for the implicit spelling.
    MockHttpServletRequest implicit = new MockHttpServletRequest();
    implicit.setParameter("name", "a,b");
    assertEquals("entity.name = a || entity.name = b", resolveToString(resolver, implicit));
  }

  @Test
  void implicitEqCsvConvertsEachElementToFieldType() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("age", "1,2");

    // Pre-split behavior could not even convert the literal "1,2" to Integer.
    assertEquals("entity.age = 1 || entity.age = 2", resolveToString(resolver, request));
  }

  @Test
  void implicitEqCsvOnNestedPathSplitsValueNeverKey() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest csv = new MockHttpServletRequest();
    csv.setParameter("ref.code", "x,y");
    MockHttpServletRequest repeated = new MockHttpServletRequest();
    repeated.setParameter("ref.code", "x", "y");

    String csvPredicate = resolveToString(resolver, csv);
    assertEquals(resolveToString(resolver, repeated), csvPredicate);
    assertEquals("entity.ref.code = x || entity.ref.code = y", csvPredicate);
  }

  @Test
  void implicitEqCsvDropsBlankSegments() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest csv = new MockHttpServletRequest();
    csv.setParameter("name", "a,,b,");
    MockHttpServletRequest repeated = new MockHttpServletRequest();
    repeated.setParameter("name", "a", "b");

    assertEquals(resolveToString(resolver, repeated), resolveToString(resolver, csv));
  }

  @Test
  void implicitEqEscapedCommaStaysLiteral() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "a\\,b");

    assertEquals("entity.name = a,b", resolveToString(resolver, request));
  }

  @Test
  void implicitEqCsvEnforcesMaxValuesPerKeyOnElementCount() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 2, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "a,b,c");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void implicitEqCsvToggleOffRestoresLiteralBehavior() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, false);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "a,b");

    assertEquals("entity.name = a,b", resolveToString(resolver, request));
  }

  // ---------- TMF630 Part 1 §4.4 URL-encoded operator literal form ----------
  // ?dateTime%3E2013-04-20 decodes to a parameter NAME containing the operator
  // ("dateTime>2013-04-20") because encoded chars are not name/value separators.

  @Test
  void encodedComparisonLiteralsInParamNameMapToSuffixOperators() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);

    MockHttpServletRequest gt = new MockHttpServletRequest();
    gt.setParameter("age>18", "");
    assertEquals("entity.age > 18", resolveToString(resolver, gt));

    MockHttpServletRequest gte = new MockHttpServletRequest();
    gte.setParameter("age>=18", "");
    assertEquals("entity.age >= 18", resolveToString(resolver, gte));

    MockHttpServletRequest lt = new MockHttpServletRequest();
    lt.setParameter("age<65", "");
    assertEquals("entity.age < 65", resolveToString(resolver, lt));

    MockHttpServletRequest lte = new MockHttpServletRequest();
    lte.setParameter("age<=65", "");
    assertEquals("entity.age <= 65", resolveToString(resolver, lte));
  }

  @Test
  void encodedEqualsLiteralIsExplicitEqAndKeepsCommasLiteral() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name==a,b", "");

    // '==' is the explicit EQ spelling: never value-list split.
    assertEquals("entity.name = a,b", resolveToString(resolver, request));
  }

  @Test
  void encodedOperatorValueMaySitInTheValueSlot() throws Exception {
    // A client sending ?age%3E=18 yields name "age>" with value "18".
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("age>", "18");

    assertEquals("entity.age > 18", resolveToString(resolver, request));
  }

  /**
   * Two malformed encoded-operator shapes that must fall through as unknown keys.
   * <ul>
   *   <li>{@code hello=world} — a lone {@code =} in the middle of the name is not a valid
   *       {@code ==} or {@code =~} operator, so {@code normalizeEncodedOperatorKey}
   *       hits its else-branch bailout.</li>
   *   <li>{@code >5} — an operator character at position 0 leaves an empty fieldPath,
   *       tripping the empty-fieldPath guard.</li>
   * </ul>
   */
  @ParameterizedTest(name = "rejects malformed encoded operator: name=\"{0}\" value=\"{1}\"")
  @CsvSource({"hello=world, x", "'>5', abc"})
  void encodedOperatorMalformedNamesRejectedAsUnknownKey(String name, String value)
      throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter(name, value);
    // Extract single-throwing invocation for assertThrows: S5778 requires the lambda
    // to contain only one call that might throw.
    NativeWebRequest webRequest = new ServletWebRequest(request);
    MethodParameter param = predicateParameter();
    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(param, null, webRequest, null));
  }

  @Test
  void encodedOringExampleFoldsSemicolonSeparatedExpressions() throws Exception {
    // Spec example: ?dateTime%3C2013-04-20;dateTime%3C2017-04-20 — one decoded name
    // carrying two full expressions; the duplicate "<field><op>" prefix is stripped.
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("age<18;age<65", "");

    assertEquals("entity.age < 18 || entity.age < 65", resolveToString(resolver, request));
  }

  @Test
  void encodedRegexLiteralIsGatedByRegexEnabled() throws Exception {
    // csvResolver builds PredicateFactory(regexEnabled=false) → 400.
    Tmf630PredicateArgumentResolver disabled = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name=~^ab.*", "");
    MethodParameter disabledParam = predicateParameter();
    ServletWebRequest disabledReq = new ServletWebRequest(request);
    assertThrows(
        TmfFilteringException.class,
        () -> disabled.resolveArgument(disabledParam, null, disabledReq, null));

    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            true,
            true,
            true,
            new PredicateLimits(20, 20, 128),
            AllowlistMode.ALLOW_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);
    Tmf630PredicateArgumentResolver enabled =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of(),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(true, 128),
            new JsonPathFilterPredicateBuilder(
                new FieldPathResolver(),
                new ValueConverter(new DefaultFormattingConversionService()),
                new PredicateFactory(true, 128)));
    MockHttpServletRequest ok = new MockHttpServletRequest();
    ok.setParameter("name=~^ab.*", "");
    assertTrue(resolveToString(enabled, ok).contains("matches"));
  }

  // ---------- TMF630 Part 1 §4.4 explicit ';' ORing ----------

  @Test
  void implicitEqSemicolonSplitsIntoOrOfEquals() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "ack;rejected");

    assertEquals("entity.name = ack || entity.name = rejected", resolveToString(resolver, request));
  }

  @Test
  void implicitEqSemicolonRepeatedPairFormStripsSameKeyPrefix() throws Exception {
    // ?status=ack;status=rejected arrives as one value "ack;status=rejected" — the
    // spec's repeated-pair form. The redundant "<sameKey>=" prefix is stripped.
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "ack;name=rejected");

    assertEquals("entity.name = ack || entity.name = rejected", resolveToString(resolver, request));
  }

  @Test
  void implicitEqSemicolonForeignKeyPrefixStaysLiteral() throws Exception {
    // Cross-attribute pairs (?a=x;b=y) are out of scope: a segment prefixed with a
    // DIFFERENT key is kept as a literal element, not silently reinterpreted.
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "ack;age=5");

    assertEquals("entity.name = ack || entity.name = age=5", resolveToString(resolver, request));
  }

  @Test
  void implicitEqSemicolonAndCommaCompose() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "a,b;c");

    assertEquals(
        "entity.name = a || entity.name = b || entity.name = c",
        resolveToString(resolver, request));
  }

  @Test
  void explicitEqKeepsSemicolonAsLiteral() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "ack;rejected");

    assertEquals("entity.name = ack;rejected", resolveToString(resolver, request));
  }

  @Test
  void implicitEqEscapedSemicolonStaysLiteral() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "a\\;b");

    assertEquals("entity.name = a;b", resolveToString(resolver, request));
  }

  @Test
  void implicitEqSemicolonToggleOffRestoresLiteralBehavior() throws Exception {
    Tmf630PredicateArgumentResolver resolver =
        csvResolver(CombineMode.OR, 20, true, false);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "ack;rejected");

    assertEquals("entity.name = ack;rejected", resolveToString(resolver, request));
  }

  @Test
  void implicitEqSemicolonElementsCountTowardMaxValuesPerKey() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 2, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "a;b;c");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void implicitEqValueOfOnlyCommasYieldsNoClause() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", ",");

    Object predicate =
        resolver.resolveArgument(predicateParameter(), null, new ServletWebRequest(request), null);
    assertFalse(((com.querydsl.core.BooleanBuilder) predicate).hasValue());
  }

  @Test
  void reservedFieldsParamIsNeverSplitIntoClauses() throws Exception {
    Tmf630PredicateArgumentResolver resolver = csvResolver(CombineMode.OR, 20, true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("fields", "name,age");

    Object predicate =
        resolver.resolveArgument(predicateParameter(), null, new ServletWebRequest(request), null);
    assertFalse(((com.querydsl.core.BooleanBuilder) predicate).hasValue());
  }

  @Test
  void passThroughNameIsLeftToTheHandlerAndNeverParsedAsFilter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("version", "v1");
    request.setParameter("name.eq", "abc");

    Object predicate =
        newResolver(AllowlistMode.ALLOW_ALL)
            .resolveArgument(
                passThroughParameter("scopedSearch"), null, new ServletWebRequest(request), null);

    assertTrue(predicate.toString().contains("name"));
    assertFalse(predicate.toString().contains("version"));
  }

  @Test
  void unknownNameBesideAPassThroughNameIsStillRejected() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("version", "v1");
    request.setParameter("bogus.eq", "x");
    MethodParameter parameter = passThroughParameter("scopedSearch");
    ServletWebRequest webRequest = new ServletWebRequest(request);

    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class,
            () -> resolver.resolveArgument(parameter, null, webRequest, null));
    assertTrue(ex.getMessage().contains("bogus"));
  }

  @Test
  void passThroughMatchesTheExactParameterNameOnly() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("version.eq", "v1");
    MethodParameter parameter = passThroughParameter("scopedSearch");
    ServletWebRequest webRequest = new ServletWebRequest(request);

    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class,
            () -> resolver.resolveArgument(parameter, null, webRequest, null));
    assertTrue(ex.getMessage().contains("version"));
  }

  @Test
  void withoutTheAnnotationThePassThroughNameIsStillParsedAsFilter() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("version", "v1");
    MethodParameter parameter = predicateParameter();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        TmfFilteringException.class,
        () -> resolver.resolveArgument(parameter, null, webRequest, null));
  }

  @Test
  void passThroughNameShadowsAnEntityFieldOnThatHandler() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name", "abc");

    Object predicate =
        newResolver(AllowlistMode.ALLOW_ALL)
            .resolveArgument(
                passThroughParameter("shadowingSearch"),
                null,
                new ServletWebRequest(request),
                null);

    assertFalse(((BooleanBuilder) predicate).hasValue());
  }

  @Test
  void passedThroughFilterNamesAreNotReadAsJsonPathFilter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("filter", "not-a-jsonpath-expression");
    request.setParameter("filter.combineWithAttributes", "BOGUS");

    Object predicate =
        newResolver(AllowlistMode.ALLOW_ALL)
            .resolveArgument(
                passThroughParameter("ownFilterSearch"),
                null,
                new ServletWebRequest(request),
                null);

    assertFalse(((BooleanBuilder) predicate).hasValue());
  }

  private static String resolveToString(
      Tmf630PredicateArgumentResolver resolver, MockHttpServletRequest request) throws Exception {
    return resolver
        .resolveArgument(predicateParameter(), null, new ServletWebRequest(request), null)
        .toString();
  }

  private static Tmf630PredicateArgumentResolver csvResolver(
      CombineMode combineRepeatedValues, int maxValuesPerKey, boolean implicitEqCsvOr) {
    return csvResolver(combineRepeatedValues, maxValuesPerKey, implicitEqCsvOr, true);
  }

  private static Tmf630PredicateArgumentResolver csvResolver(
      CombineMode combineRepeatedValues,
      int maxValuesPerKey,
      boolean implicitEqCsvOr,
      boolean implicitEqSemicolonOr) {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            implicitEqCsvOr,
            implicitEqSemicolonOr,
            combineRepeatedValues,
            true,
            true,
            false,
            new PredicateLimits(20, maxValuesPerKey, 128),
            AllowlistMode.ALLOW_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);

    return new Tmf630PredicateArgumentResolver(
        new ParamKeyParser(new OperatorRegistry(), true),
        settings,
        rootEntity -> Set.of(),
        new FieldPathResolver(),
        new ValueConverter(new DefaultFormattingConversionService()),
        new PredicateFactory(false, 128),
        new JsonPathFilterPredicateBuilder(
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128)));
  }

  private static Tmf630PredicateArgumentResolver newResolver(AllowlistMode mode) {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            mode,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);

    FieldAllowlistProvider allowlistProvider =
        rootEntity -> mode == AllowlistMode.ALLOW_ALL ? Set.of() : Set.of("name");

    return new Tmf630PredicateArgumentResolver(
        new ParamKeyParser(new OperatorRegistry(), true),
        settings,
        allowlistProvider,
        new FieldPathResolver(),
        new ValueConverter(new DefaultFormattingConversionService()),
        new PredicateFactory(false, 128),
        new JsonPathFilterPredicateBuilder(
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128)));
  }

  private static MethodParameter predicateParameter() throws Exception {
    Method method = StubController.class.getDeclaredMethod("search", com.querydsl.core.types.Predicate.class);
    return new MethodParameter(method, 0);
  }

  private static MethodParameter invalidPredicateParameter() throws Exception {
    Method method = InvalidStubController.class.getDeclaredMethod("search", com.querydsl.core.types.Predicate.class);
    return new MethodParameter(method, 0);
  }

  private static class StubController {
    @SuppressWarnings("unused")
    void search(@QuerydslPredicate(root = Entity.class) com.querydsl.core.types.Predicate predicate) { /* signature-only stub for MethodParameter reflection */ }
  }

  private static MethodParameter passThroughParameter(String methodName) throws Exception {
    Method method = PassThroughStubController.class.getDeclaredMethod(methodName, Predicate.class);
    return new MethodParameter(method, 0);
  }

  private static class PassThroughStubController {
    @Tmf630PassThrough({"version"})
    @SuppressWarnings("unused")
    void scopedSearch(@QuerydslPredicate(root = Entity.class) Predicate predicate) {
      // signature-only stub for MethodParameter reflection
    }

    @Tmf630PassThrough({"name"})
    @SuppressWarnings("unused")
    void shadowingSearch(@QuerydslPredicate(root = Entity.class) Predicate predicate) {
      // signature-only stub for MethodParameter reflection
    }

    @Tmf630PassThrough({"filter", "filter.combineWithAttributes"})
    @SuppressWarnings("unused")
    void ownFilterSearch(@QuerydslPredicate(root = Entity.class) Predicate predicate) {
      // signature-only stub for MethodParameter reflection
    }
  }

  private static class InvalidStubController {
    @SuppressWarnings("unused")
    void search(com.querydsl.core.types.Predicate predicate) { /* signature-only stub for MethodParameter reflection */ }
  }

  private static class Entity {
    @SuppressWarnings("unused")
    private String name;
    @SuppressWarnings("unused")
    private Integer age;
    @SuppressWarnings("unused")
    private Ref ref;
  }

  private static class Ref {
    @SuppressWarnings("unused")
    private String code;
  }
}
