package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.querydsl.core.types.Operation;
import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;

class PredicateFactoryTest {

  @Test
  void supportsStringCaseInsensitiveOperators() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    ResolvedField field = new ResolvedField("name", String.class);

    assertNotNull(factory.build(root, field, TmfOperator.EQI, "abc"));
    assertNotNull(factory.build(root, field, TmfOperator.LIKEI, "%abc%"));
    assertNotNull(factory.build(root, field, TmfOperator.CONTAINSI, "abc"));
    assertNotNull(factory.build(root, field, TmfOperator.STARTS_WITHI, "ab"));
    assertNotNull(factory.build(root, field, TmfOperator.ENDS_WITHI, "bc"));
    assertNotNull(factory.build(root, field, TmfOperator.REGEXI, ".*abc.*"));
    assertNotNull(factory.build(root, field, TmfOperator.EQ, "abc"));
    assertNotNull(factory.build(root, field, TmfOperator.NE, "abc"));
    assertNotNull(factory.build(root, field, TmfOperator.NEI, "abc"));
    assertNotNull(factory.build(root, field, TmfOperator.LIKE, "%abc%"));
    assertNotNull(factory.build(root, field, TmfOperator.CONTAINS, "abc"));
    assertNotNull(factory.build(root, field, TmfOperator.STARTS_WITH, "ab"));
    assertNotNull(factory.build(root, field, TmfOperator.ENDS_WITH, "bc"));
    assertNotNull(factory.build(root, field, TmfOperator.REGEX, ".*abc.*"));
  }

  @Test
  void supportsMultiValueAndNullOperators() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");

    Predicate inPredicate =
        factory.buildMulti(root, new ResolvedField("name", String.class), TmfOperator.IN,
            List.of("a", "b"));
    Predicate ninPredicate =
        factory.buildMulti(root, new ResolvedField("name", String.class), TmfOperator.NIN,
            List.of("a", "b"));
    Predicate betweenPredicate =
        factory.buildMulti(root, new ResolvedField("age", Integer.class), TmfOperator.BETWEEN,
            List.of(10, 20));
    Predicate isNullPredicate =
        factory.buildNoValue(root, new ResolvedField("name", String.class), TmfOperator.IS_NULL);
    Predicate isNotNullPredicate =
        factory.buildNoValue(root, new ResolvedField("name", String.class), TmfOperator.IS_NOT_NULL);

    assertNotNull(inPredicate);
    assertNotNull(ninPredicate);
    assertNotNull(betweenPredicate);
    assertNotNull(isNullPredicate);
    assertNotNull(isNotNullPredicate);
  }

  @Test
  void rejectsRegexWhenDisabled() {
    PredicateFactory factory = new PredicateFactory(false, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    ResolvedField field = new ResolvedField("name", String.class);
    assertThrows(
        TmfFilteringException.class, () -> factory.build(root, field, TmfOperator.REGEX, ".*abc.*"));
  }

  @Test
  void supportsComparableOperators() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    ResolvedField age = new ResolvedField("age", Integer.class);
    assertNotNull(factory.build(root, age, TmfOperator.GT, 10));
    assertNotNull(factory.build(root, age, TmfOperator.GTE, 10));
    assertNotNull(factory.build(root, age, TmfOperator.LT, 10));
    assertNotNull(factory.build(root, age, TmfOperator.LTE, 10));
  }

  @Test
  void rejectsStringOperatorsForNonStringField() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    ResolvedField age = new ResolvedField("age", Integer.class);
    assertThrows(TmfFilteringException.class, () -> factory.build(root, age, TmfOperator.LIKE, "x"));
  }

  @Test
  void rejectsComparableOperatorsForNonComparableField() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    ResolvedField payload = new ResolvedField("payload", NonComparable.class);
    assertThrows(
        TmfFilteringException.class,
        () -> factory.build(root, payload, TmfOperator.GT, new NonComparable()));
  }

  @Test
  void rejectsBetweenWithInvalidArity() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    assertThrows(
        TmfFilteringException.class,
        () ->
            factory.buildMulti(
                root,
                new ResolvedField("age", Integer.class),
                TmfOperator.BETWEEN,
                new ArrayList<>(List.of(1))));
  }

  @Test
  void rejectsRegexForNonStringAndOverlyLongPattern() {
    PredicateFactory factory = new PredicateFactory(true, 3);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    assertThrows(
        TmfFilteringException.class,
        () -> factory.build(root, new ResolvedField("age", Integer.class), TmfOperator.REGEX, "123"));
    assertThrows(
        TmfFilteringException.class,
        () -> factory.build(root, new ResolvedField("name", String.class), TmfOperator.REGEX, "1234"));
  }

  @Test
  void rejectsOperatorsThatNeedDedicatedHandlers() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");
    ResolvedField field = new ResolvedField("name", String.class);
    assertThrows(TmfFilteringException.class, () -> factory.build(root, field, TmfOperator.BETWEEN, "x"));
    assertThrows(TmfFilteringException.class, () -> factory.buildNoValue(root, field, TmfOperator.EQ));
    assertThrows(TmfFilteringException.class, () -> factory.buildMulti(root, field, TmfOperator.EQ, List.of("x")));
  }

  @Test
  void missingOnlyIsnullEmitsPlainIsNull() {
    PredicateFactory factory = new PredicateFactory(true, 256, IsnullSemantics.MISSING_ONLY);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");

    Predicate isNull =
        factory.buildNoValue(root, new ResolvedField("name", String.class), TmfOperator.IS_NULL);
    Predicate isNotNull =
        factory.buildNoValue(
            root, new ResolvedField("name", String.class), TmfOperator.IS_NOT_NULL);

    assertEquals(Ops.IS_NULL, ((Operation<?>) isNull).getOperator());
    assertEquals(Ops.IS_NOT_NULL, ((Operation<?>) isNotNull).getOperator());
  }

  @Test
  void nullishOnJpaRootDoesNotWiden() {
    // SampleEntity has no @Document — NULLISH must degrade to plain IS NULL / IS NOT NULL on
    // JPA and unknown roots, because SQL's IS NULL already captures the only "no value"
    // state for scalars and NOT IN (NULL) would poison the query via SQL trilean UNKNOWN.
    PredicateFactory factory = new PredicateFactory(true, 256, IsnullSemantics.NULLISH);
    PathBuilder<SampleEntity> root = new PathBuilder<>(SampleEntity.class, "sampleEntity");

    Predicate isNull =
        factory.buildNoValue(root, new ResolvedField("name", String.class), TmfOperator.IS_NULL);
    Predicate isNotNull =
        factory.buildNoValue(
            root, new ResolvedField("name", String.class), TmfOperator.IS_NOT_NULL);

    assertEquals(Ops.IS_NULL, ((Operation<?>) isNull).getOperator());
    assertEquals(Ops.IS_NOT_NULL, ((Operation<?>) isNotNull).getOperator());
  }

  // Mongo NULLISH widening (@Document root) is exercised end-to-end by the Mongo IT — see
  // Tmf630MongoNullishIT in tmf630-toolkit-mongo-aggregation — because spring-data-mongodb
  // is not on this module's classpath so we cannot stamp a real @Document on a fixture here.

  @Test
  void handlesPrimitiveTypeBoxingBranches() {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<PrimitiveEntity> root = new PathBuilder<>(PrimitiveEntity.class, "primitiveEntity");
    assertNotNull(factory.build(root, new ResolvedField("intValue", int.class), TmfOperator.EQ, 1));
    assertNotNull(factory.build(root, new ResolvedField("longValue", long.class), TmfOperator.EQ, 1L));
    assertNotNull(factory.build(root, new ResolvedField("doubleValue", double.class), TmfOperator.EQ, 1.0d));
    assertNotNull(factory.build(root, new ResolvedField("floatValue", float.class), TmfOperator.EQ, 1.0f));
    assertNotNull(factory.build(root, new ResolvedField("shortValue", short.class), TmfOperator.EQ, (short) 1));
    assertNotNull(factory.build(root, new ResolvedField("byteValue", byte.class), TmfOperator.EQ, (byte) 1));
    assertNotNull(factory.build(root, new ResolvedField("boolValue", boolean.class), TmfOperator.EQ, true));
    assertNotNull(factory.build(root, new ResolvedField("charValue", char.class), TmfOperator.EQ, 'a'));
    assertFalse(TmfOperator.EQ.isNoValueOperator());
    assertFalse(TmfOperator.EQ.isMultiValueOperator());
  }

  static class SampleEntity {
    private String name;
    private Integer age;
    private NonComparable payload;
    private List<String> tags;
  }

  static class NonComparable {}

  static class PrimitiveEntity {
    private int intValue;
    private long longValue;
    private double doubleValue;
    private float floatValue;
    private short shortValue;
    private byte byteValue;
    private boolean boolValue;
    private char charValue;
  }
}
