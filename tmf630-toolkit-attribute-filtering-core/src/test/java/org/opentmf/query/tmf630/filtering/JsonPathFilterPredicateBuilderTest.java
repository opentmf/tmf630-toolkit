package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.mongodb.DBObject;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Path;
import com.querydsl.mongodb.MongodbSerializer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.PredicateLimits;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.springframework.format.support.DefaultFormattingConversionService;

class JsonPathFilterPredicateBuilderTest {

  @Test
  void buildsPredicateFromValidRestrictedSubsetExpression() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?((@.name == 'abc' && @.age >= 18) || @.name != 'xyz')]",
            Set.of("name", "age"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void acceptsJsonPathWildcardAsTransparentProjection() {
    // [*] is canonical JsonPath projection. Mongo's BSON path-equality auto-projects
    // across arrays anyway, so the with- and without-[*] forms match the same
    // documents. Accepting both keeps the toolkit aligned with jsonpath.com / Jayway
    // tooling.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.externalReference[*].name == 'ORDER_REFERENCE')]",
            Set.of("externalReference", "externalReference.name"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void stripWildcardsRemovesBracketStarOutsideQuotedStrings() {
    // Direct unit coverage: the strip is applied at parse-time, before the wrapper
    // matchers run. Quoted literals are preserved verbatim.
    assertEquals(
        "$[?(@.arr.field == 'X')]",
        JsonPathFilterPredicateBuilder.stripWildcards("$[?(@.arr[*].field == 'X')]"));
    assertEquals(
        "$[?(@.label == '[*]')]",
        JsonPathFilterPredicateBuilder.stripWildcards("$[?(@.label == '[*]')]"));
    assertEquals("foo", JsonPathFilterPredicateBuilder.stripWildcards("foo[*]"));
    assertEquals("foo", JsonPathFilterPredicateBuilder.stripWildcards("foo[*][*]"));
    assertEquals("plain", JsonPathFilterPredicateBuilder.stripWildcards("plain"));
  }

  @Test
  void acceptsBareWrapperShorthandWithoutDollarPrefix() {
    // TMF630 recommends allowing the leading `$` to be omitted for simplicity.
    // The bare-wrapper form `[?(...)]` is treated identically to `$[?(...)]`.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "[?(@.name == 'abc')]",
            Set.of("name", "age"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void acceptsSubArrayShorthandWithoutDollarPrefix() {
    // TMF630 sub-array shorthand: `<arrayPath>[?(...)]` rewrites to the canonical
    // correlated-array filter form `$[?(@.<arrayPath>[?(...)])]`.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "externalReference[?(@.name == 'ORDER_REFERENCE')]",
            Set.of("externalReference", "externalReference.name", "externalReference.id"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void acceptsSubArrayShorthandWithDollarPrefixedPath() {
    // `$.externalReference[?(...)]` is the same shorthand with the optional `$.`
    // prefix included. Equivalent to `externalReference[?(...)]` and to the
    // canonical `$[?(@.externalReference[?(...)])]`.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$.externalReference[?(@.name == 'ORDER_REFERENCE')]",
            Set.of("externalReference", "externalReference.name", "externalReference.id"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void rejectsSubArrayShorthandWithUnbalancedParens() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    // Unbalanced parens — missing closing ')' before ']'
    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "externalReference[?(@.name == 'X']",
                Set.of("externalReference"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsSubArrayShorthandWithMalformedDottedPath() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    // The leading dotted path must be a clean identifier path. Embedded illegal chars
    // (here a space) cause the shorthand to be rejected; falls through to "must be a
    // filter expression" with the documented message.
    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "external Reference[?(@.name == 'X')]",
                Set.of("externalReference"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsSubArrayShorthandWithTrailingContentAfterPredicate() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    // Anything after the closing `]` (other than whitespace) is rejected — the
    // shorthand must terminate at the predicate.
    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "externalReference[?(@.name == 'X')].extra",
                Set.of("externalReference"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void isValidDottedIdentifierRecognisesGoodAndBadShapes() {
    // Direct unit coverage of every branch in the helper used by the sub-array
    // shorthand. Jayway's compile pass would reject most of these strings before
    // the helper sees them, so the helper has to be testable independently.
    assertEquals(true, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("foo"));
    assertEquals(true, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("foo.bar"));
    assertEquals(true, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("a-b_2.c-3"));
    assertEquals(false, JsonPathFilterPredicateBuilder.isValidDottedIdentifier(""));
    assertEquals(false, JsonPathFilterPredicateBuilder.isValidDottedIdentifier(".foo"));
    assertEquals(false, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("foo."));
    assertEquals(false, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("foo..bar"));
    assertEquals(false, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("foo bar"));
    assertEquals(false, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("foo$bar"));
    assertEquals(false, JsonPathFilterPredicateBuilder.isValidDottedIdentifier("foo[bar"));
  }

  @Test
  void trySubArrayShorthandRecognisesValidAndInvalidForms() {
    // Direct unit coverage of every branch in the rewriter — important because Jayway
    // rejects some invalid inputs at the compile step before they reach unwrap.
    assertEquals(
        "@.foo[?(@.x == 'y')]",
        JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo[?(@.x == 'y')]"));
    assertEquals(
        "@.a.b[?(@.x == 'y')]",
        JsonPathFilterPredicateBuilder.trySubArrayShorthand("$.a.b[?(@.x == 'y')]"));
    // Nested parens inside the predicate
    assertEquals(
        "@.foo[?((@.x == 'y') && @.z == 'q')]",
        JsonPathFilterPredicateBuilder.trySubArrayShorthand(
            "foo[?((@.x == 'y') && @.z == 'q')]"));
    // No bracket
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("$.field"));
    // Bracket at position 0 (no path before)
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("[?(@.x == 'y')]"));
    // Empty dotted path after `$.` strip
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("$.[?(@.x == 'y')]"));
    // Invalid identifier (space)
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo bar[?(@.x == 'y')]"));
    // Unbalanced parens — missing `)` before `]`
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo[?(@.x == 'y']"));
    // Missing closing `]` after the matching `)`
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo[?(@.x == 'y')"));
    // Pure projection suffix (dotted path with no further [?()] or [n]) is
    // tolerated and discarded — the filter semantics are fully determined by the
    // predicate. DPC-style consumers append sort-template projections to filter
    // URLs and rely on the toolkit to ignore them.
    assertEquals(
        "@.foo[?(@.x == 'y')]",
        JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo[?(@.x == 'y')].extra"));
    assertEquals(
        "@.foo[?(@.x == 'y')]",
        JsonPathFilterPredicateBuilder.trySubArrayShorthand(
            "foo[?(@.x == 'y')].extra.deeper.leaf"));
    assertEquals(
        "@.foo[?(@.x == 'y')]",
        JsonPathFilterPredicateBuilder.trySubArrayShorthand("$.foo[?(@.x == 'y')].extra"));
    // Trailing content that contains another [?()] is NOT a pure projection —
    // rejected to keep nested filtering off this surface.
    assertNull(
        JsonPathFilterPredicateBuilder.trySubArrayShorthand(
            "foo[?(@.x == 'y')].sub[?(@.z == 'q')]"));
    // Trailing index access — [0] — is also rejected.
    assertNull(
        JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo[?(@.x == 'y')].sub[0]"));
    // Trailing content that doesn't start with a `.` — malformed; rejected.
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo[?(@.x == 'y')]extra"));
  }

  @Test
  void rejectsSubArrayShorthandWithEmptyDottedPath() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    // Just `$.` followed by `[?(...)]` — after stripping `$.` the path is empty.
    // This isn't valid sub-array shorthand and isn't a wrapper either; falls through
    // to the same standard "must be a filter expression" rejection.
    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$.[?(@.name == 'X')]",
                Set.of("externalReference"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsExpressionThatIsNotAFilterWrapper() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$.name",
                Set.of("name", "age"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsUnsupportedJsonPathTokens() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.name =~ 'abc')]",
                Set.of("name", "age"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void supportsBooleanNumberAndDoubleQuotedLiterals() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.active == true && @.age > -1 && @.name == \"abc\")]",
            Set.of("name", "age", "active"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void doubleQuotedLiteralsWorkInsideSubArrayShorthand() {
    // The colleague's report claimed double quotes are rejected in sub-array
    // shorthand. They are not — the same tokenizer handles both `'...'` and
    // `"..."` regardless of which wrapper form unwrapped the expression. This
    // test pins that, and is the test the report says should be added.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicateBare =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "externalReference[?(@.name == \"abc\")]",
            Set.of("externalReference", "externalReference.name"),
            settings(UnknownParamBehavior.REJECT, true));
    assertNotNull(predicateBare);

    Predicate predicateWithDollarPrefix =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$.externalReference[?(@.name == \"abc\")]",
            Set.of("externalReference", "externalReference.name"),
            settings(UnknownParamBehavior.REJECT, true));
    assertNotNull(predicateWithDollarPrefix);
  }

  @Test
  void subArrayShorthandToleratesTrailingProjectionSuffix() {
    // Colleague-reported defect: DPC-style consumers reuse sort-URL templates
    // when constructing filter URLs, leaving a trailing projection like
    // `.productSpecCharacteristicValue.value` after the filter's `[?(...)]`.
    // The projection has no semantic effect on the matched row set, which is
    // fully determined by the predicate, so the parser strips it. Double quotes
    // and [*] wildcards in the suffix are both handled by upstream normalisation
    // (the tokenizer and stripWildcards respectively).
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    // Exact shape from the colleague's repro — double quotes + [*] wildcard +
    // trailing projection — must build successfully.
    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$.externalReference[?(@.name == \"abc\")].nested[*].value",
            Set.of("externalReference", "externalReference.name"),
            settings(UnknownParamBehavior.REJECT, true));
    assertNotNull(predicate);
  }

  @Test
  void subArrayShorthandRejectsTrailingNestedPredicate() {
    // A trailing `[?(...)]` is NOT a pure projection — it implies a nested
    // filter, which the sub-array shorthand does not support. Reject with the
    // standard "must be a filter expression" error so the caller knows to
    // rewrite, rather than silently dropping the nested filter.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$.externalReference[?(@.name == 'abc')].sub[?(@.kind == 'X')]",
                Set.of("externalReference", "externalReference.name"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void supportsUnaryNegationOnScalarField() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(!@.name)]",
            Set.of("name"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
    assertTrue(predicate.toString().contains("name"));
  }

  @Test
  void supportsUnaryNegationCombinedWithComparison() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(!@.name && @.age > 0)]",
            Set.of("name", "age"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void unaryNegationRejectsArrayMatchForm() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(!@.externalReference[?(@.id == 'X')])]",
                Set.of("externalReference", "externalReference.id"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void neOperatorStillTokenizedAfterAddingNotToken() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.name != 'abc')]",
            Set.of("name"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void supportsNullEqAndNeComparisons() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicateEq =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.name == null)]",
            Set.of("name"),
            settings(UnknownParamBehavior.REJECT, true));
    Predicate predicateNe =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.name != null)]",
            Set.of("name"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicateEq);
    assertNotNull(predicateNe);
  }

  @Test
  void rejectsNullWithNonEqualityComparison() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.name > null)]",
                Set.of("name"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsUnknownFieldsWhenConfiguredToReject() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.unknown == 'x')]",
                Set.of("name", "age"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsExpressionThatExceedsMaxLength() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Tmf630FilterSettings settings = settings(UnknownParamBehavior.REJECT, true);
    Tmf630FilterSettings limitedSettings =
        new Tmf630FilterSettings(
            settings.implicitEqEnabled(),
            settings.combineRepeatedValues(),
            settings.allowNestedPathsJpa(),
            settings.allowNestedPathsDocdb(),
            settings.regexEnabled(),
            settings.limits(),
            settings.allowlistMode(),
            settings.onUnknownField(),
            settings.onUnknownOperator(),
            settings.jsonPathFilterEnabled(),
            10,
            settings.onUnknownJsonPathField());

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.name == 'abcdefghijklmnop')]",
                Set.of("name"),
                limitedSettings));
  }

  @Test
  void ignoresUnknownFieldsWhenConfigured() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.unknown == 'x')]",
            Set.of("name", "age"),
            settings(UnknownParamBehavior.IGNORE, true));

    assertNull(predicate);
  }

  @Test
  void unknownJsonPathFieldHonoursIndependentSettingFromAttributeFilter() {
    // Proves the two settings are truly split: even though the legacy
    // onUnknownField is REJECT (which is correct for query-param attribute
    // filtering), the JSON Path filter consults onUnknownJsonPathField. With
    // that set to IGNORE, an unknown leaf inside ?filter= must return an
    // empty predicate instead of throwing.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Tmf630FilterSettings splitSettings =
        new Tmf630FilterSettings(
            true,
            CombineMode.OR,
            true,
            true,
            false,
            new PredicateLimits(50, 10, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.IGNORE);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.unknown == 'x')]",
            Set.of("name", "age"),
            splitSettings);

    assertNull(predicate);
  }

  @Test
  void unknownJsonPathArraySegmentHonoursIgnoreSetting() {
    // The array-match branch in resolveArrayPath used to throw unconditionally
    // when a segment didn't resolve to a real field. With
    // onUnknownJsonPathField=IGNORE the array-match path should now return an
    // empty predicate instead of bubbling the exception out as 400.
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.nonExistentArray[?(@.id == 'X')])]",
            Set.of(
                "nonExistentArray",
                "nonExistentArray.id",
                "externalReference",
                "externalReference.id"),
            settings(UnknownParamBehavior.IGNORE, true));

    assertNull(predicate);
  }

  @Test
  void unknownJsonPathArraySegmentStillThrowsWhenConfiguredToReject() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.nonExistentArray[?(@.id == 'X')])]",
                Set.of(
                    "nonExistentArray",
                    "nonExistentArray.id",
                    "externalReference",
                    "externalReference.id"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsWhenJsonPathFilterFeatureDisabled() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.name == 'x')]",
                Set.of("name"),
                settings(UnknownParamBehavior.REJECT, false)));
  }

  @Test
  void supportsSameElementArrayCorrelationForDocumentStyleEntity() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]",
            Set.of("externalReference.name", "externalReference.id"),
            settings(UnknownParamBehavior.REJECT, true));

    assertNotNull(predicate);
  }

  @Test
  void rejectsArrayCorrelationForJpaEntity() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                JpaLikeEntity.class,
                pathResolver.createRootPath(JpaLikeEntity.class),
                "$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]",
                Set.of("externalReference.name", "externalReference.id"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsArrayCorrelationWhenPathIsNotCollection() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                ScalarEntity.class,
                pathResolver.createRootPath(ScalarEntity.class),
                "$[?(@.name[?(@.id == 'x')])]",
                Set.of("name"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsNestedArrayCorrelationWhenNestedPathsDisabled() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                NestedEntity.class,
                pathResolver.createRootPath(NestedEntity.class),
                "$[?(@.wrapper.externalReference[?(@.name == 'ORDER_REFERENCE')])]",
                Set.of("wrapper.externalReference.name"),
                settings(UnknownParamBehavior.REJECT, true, false)));
  }

  @Test
  void supportsNestedArrayCorrelationWhenNestedPathsEnabled() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            NestedEntity.class,
            pathResolver.createRootPath(NestedEntity.class),
            "$[?(@.wrapper.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'x')])]",
            Set.of("wrapper.externalReference.name", "wrapper.externalReference.id"),
            settings(UnknownParamBehavior.REJECT, true, true));

    assertNotNull(predicate);
  }

  @Test
  void ignoresUnknownArrayNestedFieldsWhenConfigured() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.externalReference[?(@.unknown == 'x')])]",
            Set.of("externalReference.name", "externalReference.id"),
            settings(UnknownParamBehavior.IGNORE, true));

    assertNull(predicate);
  }

  @Test
  void serializesArrayCorrelationToExplicitElemMatch() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    Predicate predicate =
        builder.build(
            Entity.class,
            pathResolver.createRootPath(Entity.class),
            "$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]",
            Set.of("externalReference.name", "externalReference.id"),
            settings(UnknownParamBehavior.REJECT, true));
    DBObject query = (DBObject) new NoRefMongoSerializer().handle(predicate);
    String rendered = query.toString();

    assertTrue(rendered.contains("$elemMatch"), rendered);
    assertTrue(rendered.contains("externalReference"), rendered);
    assertTrue(rendered.contains("name"), rendered);
    assertTrue(rendered.contains("id"), rendered);
    assertFalse(rendered.contains("externalReference.name"), rendered);
    assertFalse(rendered.contains("externalReference.id"), rendered);
  }

  @Test
  void rejectsArrayCorrelationWhenArrayPathIsUnknown() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.missingArray[?(@.id == 'x')])]",
                Set.of("missingArray.id"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsArrayCorrelationWithBlankArrayPath() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.[?(@.id == 'x')])]",
                Set.of("externalReference.id"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsMalformedArrayFilterSyntax() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                Entity.class,
                pathResolver.createRootPath(Entity.class),
                "$[?(@.externalReference[?(@.id == 'x'))]",
                Set.of("externalReference.id"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  @Test
  void rejectsArrayCorrelationForRawListElementTypeWhenFieldCannotBeResolved() {
    FieldPathResolver pathResolver = new FieldPathResolver();
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    PredicateFactory factory = new PredicateFactory(false, 128);
    JsonPathFilterPredicateBuilder builder =
        new JsonPathFilterPredicateBuilder(pathResolver, converter, factory);

    assertThrows(
        TmfFilteringException.class,
        () ->
            builder.build(
                RawListEntity.class,
                pathResolver.createRootPath(RawListEntity.class),
                "$[?(@.externalReference[?(@.id == 'x')])]",
                Set.of("externalReference.id"),
                settings(UnknownParamBehavior.REJECT, true)));
  }

  private static Tmf630FilterSettings settings(
      UnknownParamBehavior unknownFieldBehavior, boolean jsonPathEnabled) {
    return settings(unknownFieldBehavior, jsonPathEnabled, true);
  }

  private static Tmf630FilterSettings settings(
      UnknownParamBehavior unknownFieldBehavior, boolean jsonPathEnabled, boolean allowNestedPaths) {
    // Mirror unknownFieldBehavior into both the legacy attribute-filter setting and
    // the new JSON-path-specific setting. This preserves the previous behaviour for
    // every existing test in this class: REJECT-flavoured tests still expect a 400
    // on unknown JSON Path fields, IGNORE-flavoured tests still expect an empty
    // result.
    return new Tmf630FilterSettings(
        true,
        CombineMode.OR,
        allowNestedPaths,
        allowNestedPaths,
        false,
        new PredicateLimits(50, 10, 128),
        AllowlistMode.DENY_ALL,
        unknownFieldBehavior,
        UnknownParamBehavior.REJECT,
        jsonPathEnabled,
        2048,
        unknownFieldBehavior);
  }

  private static class Entity {
    @SuppressWarnings("unused")
    private String name;

    @SuppressWarnings("unused")
    private Integer age;

    @SuppressWarnings("unused")
    private Boolean active;

    @SuppressWarnings("unused")
    private List<ExternalReference> externalReference;
  }

  @jakarta.persistence.Entity
  private static class JpaLikeEntity {
    @SuppressWarnings("unused")
    private List<ExternalReference> externalReference;
  }

  private static class ExternalReference {
    @SuppressWarnings("unused")
    private String id;

    @SuppressWarnings("unused")
    private String name;
  }

  private static class ScalarEntity {
    @SuppressWarnings("unused")
    private String name;
  }

  private static class NestedEntity {
    @SuppressWarnings("unused")
    private Wrapper wrapper;
  }

  private static class Wrapper {
    @SuppressWarnings("unused")
    private List<ExternalReference> externalReference;
  }

  private static class NoRefMongoSerializer extends MongodbSerializer {
    @Override
    protected com.mongodb.DBRef asReference(Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected boolean isReference(Path<?> path) {
      return false;
    }
  }

  @SuppressWarnings("rawtypes")
  private static class RawListEntity {
    @SuppressWarnings("unused")
    private List externalReference;
  }
}
