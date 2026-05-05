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
    // Trailing content after the closing `]`
    assertNull(JsonPathFilterPredicateBuilder.trySubArrayShorthand("foo[?(@.x == 'y')].extra"));
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
            10);

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
        2048);
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
