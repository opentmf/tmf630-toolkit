package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

class JsonbPredicateFactoryTest {

  private final JsonbPathExtractor extractor = new JsonbPathExtractor("payload");
  private final JsonbPredicateFactory factory =
      new JsonbPredicateFactory(extractor, IsnullSemantics.MISSING_ONLY, true);

  /** equality=containment alone, the casts as by default. */
  private final JsonbPredicateFactory containment =
      new JsonbPredicateFactory(
          extractor,
          IsnullSemantics.MISSING_ONLY,
          true,
          null,
          JsonbEqualityMode.CONTAINMENT,
          JsonbCastMode.STRICT);

  /** The opt-ins: containment equality and the lenient (guarded) cast. */
  private final JsonbPredicateFactory optIns =
      new JsonbPredicateFactory(
          extractor,
          IsnullSemantics.MISSING_ONLY,
          true,
          null,
          JsonbEqualityMode.CONTAINMENT,
          JsonbCastMode.LENIENT);

  @Test
  @DisplayName("EQ / NE on String — no cast, direct text comparison")
  void equalityOnString() {
    JsonbClause eq = factory.build(TmfOperator.EQ, "status", String.class, "Pending");
    assertThat(eq.sql()).isEqualTo("payload->>'status' = ?");
    assertThat(eq.params()).containsExactly("Pending");

    JsonbClause ne = factory.build(TmfOperator.NE, "status", String.class, "Cancelled");
    assertThat(ne.sql()).isEqualTo("payload->>'status' <> ?");
  }

  private enum Color {
    RED,
    GREEN
  }

  private enum Labelled {
    ONE;

    @Override
    public String toString() {
      return "label " + name().toLowerCase(Locale.ROOT);
    }
  }

  private enum Status {
    IN_PROGRESS("inProgress"),
    DONE("done");

    private final String wire;

    Status(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  private enum Phase {
    @JsonProperty("phase-one")
    ONE
  }

  @Test
  @DisplayName("a plain Enum value is bound by the name the mapper writes for it")
  void plainEnumIsBoundByName() {
    assertThat(factory.build(TmfOperator.EQ, "c", Color.class, Color.RED).params())
        .containsExactly("RED");
    assertThat(factory.build(TmfOperator.NE, "c", Color.class, Color.RED).params())
        .containsExactly("RED");
    assertThat(factory.build(TmfOperator.EQI, "c", Color.class, Color.RED).params())
        .containsExactly("RED");
    assertThat(
            factory
                .buildMulti(TmfOperator.IN, "c", Color.class, List.of(Color.RED, Color.GREEN))
                .params())
        .containsExactly("RED", "GREEN");
    assertThat(
            factory.buildMulti(TmfOperator.NIN, "c", Color.class, List.of(Color.GREEN)).params())
        .containsExactly("GREEN");
  }

  @Test
  @DisplayName("an Enum with @JsonValue or @JsonProperty is bound by its wire value, not its name")
  void annotatedEnumIsBoundByWireValue() {
    assertThat(factory.build(TmfOperator.EQ, "w", Status.class, Status.IN_PROGRESS).params())
        .containsExactly("inProgress");
    assertThat(
            factory
                .buildMulti(TmfOperator.NIN, "w", Status.class, List.of(Status.DONE))
                .params())
        .containsExactly("done");
    assertThat(factory.build(TmfOperator.NE, "p", Phase.class, Phase.ONE).params())
        .containsExactly("phase-one");
  }

  @Test
  @DisplayName("the mapper decides: Jackson 3 writes toString(), a mapper without that writes name()")
  void configuredMapperDecidesTheBoundText() {
    // Jackson 3 enables WRITE_ENUMS_USING_TO_STRING by default, so that is what payloads hold
    assertThat(factory.build(TmfOperator.EQ, "l", Labelled.class, Labelled.ONE).params())
        .containsExactly("label one");

    ObjectMapper byName =
        JsonMapper.builder().disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING).build();
    JsonbPredicateFactory withMapper =
        new JsonbPredicateFactory(extractor, IsnullSemantics.MISSING_ONLY, true, byName);
    assertThat(withMapper.build(TmfOperator.EQ, "l", Labelled.class, Labelled.ONE).params())
        .containsExactly("ONE");
  }

  private static final String CONTAINS = "payload @> ?::jsonb";

  @Test
  @DisplayName("by default, EQ and IN on an object path keep the 3.5.2 extraction")
  void equalityOnAnObjectPathKeepsTheExtractionByDefault() {
    assertThat(factory.buildOnObjectPath(TmfOperator.EQ, "a.b", String.class, "x").sql())
        .isEqualTo("payload#>>'{a,b}' = ?");
    assertThat(factory.buildOnObjectPath(TmfOperator.EQ, "n", Integer.class, 42).sql())
        .isEqualTo("(payload->>'n')::bigint = ?::bigint");
    assertThat(
            factory
                .buildMultiOnObjectPath(TmfOperator.IN, "s", String.class, List.of("x", "y"))
                .sql())
        .isEqualTo("payload->>'s' IN (?, ?)");
    assertThat(
            factory.containmentThrough(
                new JsonbFieldPath(List.of("items", "b"), List.of(true, false)),
                String.class,
                "x"))
        .as("no containment through an array either")
        .isEmpty();
  }

  @Test
  @DisplayName("equality=containment: EQ on an object path is containment, in the field's JSON type")
  void equalityOnAnObjectPathIsContainment() {
    assertContainment(containment.buildOnObjectPath(TmfOperator.EQ, "a.b", String.class, "x"),
        "{\"a\":{\"b\":\"x\"}}");
    assertContainment(containment.buildOnObjectPath(TmfOperator.EQ, "n", Integer.class, 42),
        "{\"n\":42}");
    assertContainment(containment.buildOnObjectPath(TmfOperator.EQ, "n", short.class, (short) 7),
        "{\"n\":7}");
    assertContainment(containment.buildOnObjectPath(TmfOperator.EQ, "n", Byte.class, (byte) 1),
        "{\"n\":1}");
    assertContainment(
        containment.buildOnObjectPath(
            TmfOperator.EQ, "n", BigInteger.class, new BigInteger("9007199254740993")),
        "{\"n\":9007199254740993}");
    assertContainment(
        containment.buildOnObjectPath(TmfOperator.EQ, "d", BigDecimal.class, new BigDecimal("1.50")),
        "{\"d\":1.50}");
    assertContainment(containment.buildOnObjectPath(TmfOperator.EQ, "d", Double.class, 0.1),
        "{\"d\":0.1}");
    assertContainment(containment.buildOnObjectPath(TmfOperator.EQ, "d", float.class, 0.1f),
        "{\"d\":0.1}");
    assertContainment(containment.buildOnObjectPath(TmfOperator.EQ, "b", Boolean.class, true),
        "{\"b\":true}");
    assertContainment(
        containment.buildOnObjectPath(TmfOperator.EQ, "w", Status.class, Status.IN_PROGRESS),
        "{\"w\":\"inProgress\"}");
    assertContainment(
        containment.buildOnObjectPath(TmfOperator.EQ, "q", String.class, "a\"b"),
        "{\"q\":\"a\\\"b\"}");
  }

  @Test
  @DisplayName("equality=containment keeps the extraction for date-times, other types, NaN, [N]")
  void equalityKeepsTheExtractionWhereContainmentWouldDiffer() {
    assertThat(
            containment
                .buildOnObjectPath(TmfOperator.EQ, "ld", LocalDate.class, LocalDate.of(2025, 1, 1))
                .sql())
        .isEqualTo("(payload->>'ld')::date = ?::date");
    assertThat(
            containment
                .buildOnObjectPath(TmfOperator.EQ, "u", UUID.class, UUID.randomUUID())
                .sql())
        .isEqualTo("payload->>'u' = ?");
    assertThat(containment.buildOnObjectPath(TmfOperator.EQ, "d", Double.class, Double.NaN).sql())
        .isEqualTo("(payload->>'d')::numeric = ?::numeric");
    assertThat(containment.buildOnObjectPath(TmfOperator.EQ, "items.0.b", String.class, "x").sql())
        .isEqualTo("payload#>>'{items,0,b}' = ?");
    assertThat(containment.buildOnObjectPath(TmfOperator.NE, "s", String.class, "x").sql())
        .isEqualTo("payload->>'s' <> ?");
    assertThat(containment.buildOnObjectPath(TmfOperator.EQ, "n", Integer.class, "42").sql())
        .as("a value not of the declared type keeps the extraction")
        .isEqualTo("(payload->>'n')::bigint = ?::bigint");
  }

  @Test
  @DisplayName("equality=containment: IN on an object path is an OR of fragments; NIN keeps IN")
  void inOnAnObjectPathIsAnOrOfFragments() {
    JsonbClause two =
        containment.buildMultiOnObjectPath(TmfOperator.IN, "s", String.class, List.of("x", "y"));
    assertThat(two.sql()).isEqualTo("(" + CONTAINS + " OR " + CONTAINS + ")");
    assertThat(two.params()).containsExactly("{\"s\":\"x\"}", "{\"s\":\"y\"}");

    assertContainment(
        containment.buildMultiOnObjectPath(TmfOperator.IN, "s", String.class, List.of("x")),
        "{\"s\":\"x\"}");
    assertThat(
            containment
                .buildMultiOnObjectPath(
                    TmfOperator.IN, "d", Double.class, List.of(0.1, Double.NaN))
                .sql())
        .isEqualTo("(payload->>'d')::numeric IN (?::numeric, ?::numeric)");
    assertThat(
            containment
                .buildMultiOnObjectPath(TmfOperator.NIN, "s", String.class, List.of("x"))
                .sql())
        .isEqualTo("payload->>'s' NOT IN (?)");
  }

  private static void assertContainment(JsonbClause clause, String fragment) {
    assertThat(clause.sql()).isEqualTo(CONTAINS);
    assertThat(clause.params()).containsExactly(fragment);
  }

  @Test
  @DisplayName("EQI / NEI use LOWER() on both sides")
  void caseInsensitiveEquality() {
    JsonbClause eqi = factory.build(TmfOperator.EQI, "name", String.class, "Alice");
    assertThat(eqi.sql()).isEqualTo("LOWER(payload->>'name') = LOWER(?)");
    JsonbClause nei = factory.build(TmfOperator.NEI, "name", String.class, "Alice");
    assertThat(nei.sql()).isEqualTo("LOWER(payload->>'name') <> LOWER(?)");
  }

  @Test
  @DisplayName("GT / GTE / LT / LTE on numeric — parenthesised extraction, then ::bigint cast")
  void rangeOperatorsNumeric() {
    JsonbClause gt = factory.build(TmfOperator.GT, "priority", Integer.class, 5);
    assertThat(gt.sql()).isEqualTo("(payload->>'priority')::bigint > ?::bigint");
    assertThat(factory.build(TmfOperator.GTE, "priority", Integer.class, 5).sql())
        .isEqualTo("(payload->>'priority')::bigint >= ?::bigint");
    assertThat(factory.build(TmfOperator.LT, "priority", Integer.class, 5).sql())
        .isEqualTo("(payload->>'priority')::bigint < ?::bigint");
    assertThat(factory.build(TmfOperator.LTE, "priority", Integer.class, 5).sql())
        .isEqualTo("(payload->>'priority')::bigint <= ?::bigint");
  }

  @Test
  @DisplayName("inside an array or a split child, the default reads only the JSON or ISO form")
  void anElementFactoryReadsOnlyTheCanonicalFormByDefault() {
    JsonbPredicateFactory element = factory.forColumn("e1");
    assertThat(element.build(TmfOperator.EQ, "ld", LocalDate.class, LocalDate.of(2025, 1, 1)).sql())
        .isEqualTo(JsonbCast.DATE.readCanonical("e1->>'ld'") + " = ?::date");
    assertThat(element.build(TmfOperator.GT, "i", Integer.class, 5).sql())
        .isEqualTo(JsonbCast.BIGINT.readCanonical("e1->>'i'") + " > ?::bigint");
    assertThat(
            element
                .buildMulti(TmfOperator.NIN, "b", Boolean.class, List.of(Boolean.TRUE))
                .sql())
        .isEqualTo(JsonbCast.BOOLEAN.readCanonical("e1->>'b'") + " NOT IN (?::boolean)");
    assertThat(
            element
                .buildMulti(
                    TmfOperator.BETWEEN,
                    "d",
                    BigDecimal.class,
                    List.of(BigDecimal.ONE, BigDecimal.TEN))
                .sql())
        .isEqualTo(
            JsonbCast.NUMERIC.readCanonical("e1->>'d'") + " BETWEEN ?::numeric AND ?::numeric");
    assertThat(element.build(TmfOperator.EQ, "s", String.class, "a").sql())
        .as("text is read as it is")
        .isEqualTo("e1->>'s' = ?");
    assertThat(factory.build(TmfOperator.GT, "i", Integer.class, 5).sql())
        .as("the top level keeps the plain cast, as 3.5.2")
        .isEqualTo("(payload->>'i')::bigint > ?::bigint");
  }

  @Test
  @DisplayName("inside an array under cast-mode=lenient, pg_input_is_valid as everywhere")
  void anElementFactoryUnderLenientKeepsTheExactCheck() {
    assertThat(optIns.forColumn("e1").build(TmfOperator.GT, "i", Integer.class, 5).sql())
        .isEqualTo(
            "CASE WHEN pg_input_is_valid(e1->>'i', 'bigint') THEN (e1->>'i')::bigint END"
                + " > ?::bigint");
  }

  @Test
  @DisplayName("cast-mode=lenient guards numbers and booleans with pg_input_is_valid too")
  void lenientGuardsNumbersAndBooleans() {
    assertThat(optIns.build(TmfOperator.GT, "n", Integer.class, 5).sql())
        .isEqualTo(
            "CASE WHEN pg_input_is_valid(payload->>'n', 'bigint') THEN (payload->>'n')::bigint END"
                + " > ?::bigint");
    assertThat(optIns.build(TmfOperator.LT, "d", BigDecimal.class, BigDecimal.ONE).sql())
        .isEqualTo(
            "CASE WHEN pg_input_is_valid(payload->>'d', 'numeric') THEN (payload->>'d')::numeric"
                + " END < ?::numeric");
    assertThat(optIns.build(TmfOperator.NE, "b", Boolean.class, true).sql())
        .isEqualTo(
            "CASE WHEN pg_input_is_valid(payload->>'b', 'boolean') THEN (payload->>'b')::boolean"
                + " END <> ?::boolean");
    assertThat(optIns.build(TmfOperator.GT, "s", String.class, "a").sql())
        .as("text needs no guard")
        .isEqualTo("payload->>'s' > ?");
  }

  @Test
  @DisplayName("GT on OffsetDateTime: the plain cast by default, the guarded read when lenient")
  void rangeOperatorsDatetime() {
    OffsetDateTime when = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    assertThat(factory.build(TmfOperator.GT, "createdAt", OffsetDateTime.class, when).sql())
        .isEqualTo("(payload->>'createdAt')::timestamptz > ?::timestamptz");
    JsonbClause gt = optIns.build(TmfOperator.GT, "createdAt", OffsetDateTime.class, when);
    assertThat(gt.sql())
        .isEqualTo(
            "CASE WHEN pg_input_is_valid(payload->>'createdAt', 'timestamptz')"
                + " THEN (payload->>'createdAt')::timestamptz END > ?::timestamptz");
    assertThat(gt.params()).containsExactly(when);
  }

  @Test
  @DisplayName("BETWEEN casts both bounds with parenthesised extraction")
  void between() {
    JsonbClause c =
        factory.buildMulti(TmfOperator.BETWEEN, "priority", Integer.class, List.of(1, 9));
    assertThat(c.sql())
        .isEqualTo("(payload->>'priority')::bigint BETWEEN ?::bigint AND ?::bigint");
    assertThat(c.params()).containsExactly(1, 9);
  }

  @Test
  @DisplayName("BETWEEN rejects wrong-arity value list")
  void betweenWrongArity() {
    List<Object> tooFew = List.of(1);
    assertThatThrownBy(
            () -> factory.buildMulti(TmfOperator.BETWEEN, "priority", Integer.class, tooFew))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("IN / NIN generate parameterised IN list with per-value cast")
  void inAndNotIn() {
    JsonbClause in = factory.buildMulti(TmfOperator.IN, "status", String.class, List.of("A", "B"));
    assertThat(in.sql()).isEqualTo("payload->>'status' IN (?, ?)");
    assertThat(in.params()).containsExactly("A", "B");

    JsonbClause nin =
        factory.buildMulti(TmfOperator.NIN, "priority", Integer.class, List.of(1, 2, 3));
    assertThat(nin.sql()).isEqualTo("(payload->>'priority')::bigint NOT IN (?::bigint, ?::bigint, ?::bigint)");
  }

  @Test
  @DisplayName("Multi-value operators reject empty value list")
  void multiRejectsEmpty() {
    List<Object> empty = List.of();
    assertThatThrownBy(() -> factory.buildMulti(TmfOperator.IN, "x", String.class, empty))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("LIKE family produces LIKE / ILIKE fragments with wildcards")
  void likeFamily() {
    assertThat(factory.build(TmfOperator.LIKE, "n", String.class, "A%").sql())
        .isEqualTo("payload->>'n' LIKE ?");
    assertThat(factory.build(TmfOperator.LIKEI, "n", String.class, "a%").sql())
        .isEqualTo("payload->>'n' ILIKE ?");
    assertThat(factory.build(TmfOperator.CONTAINS, "n", String.class, "ow").sql())
        .isEqualTo("payload->>'n' LIKE '%' || ? || '%' ESCAPE '!'");
    assertThat(factory.build(TmfOperator.CONTAINSI, "n", String.class, "OW").sql())
        .isEqualTo("payload->>'n' ILIKE '%' || ? || '%' ESCAPE '!'");
    assertThat(factory.build(TmfOperator.STARTS_WITH, "n", String.class, "D").sql())
        .isEqualTo("payload->>'n' LIKE ? || '%' ESCAPE '!'");
    assertThat(factory.build(TmfOperator.STARTS_WITHI, "n", String.class, "d").sql())
        .isEqualTo("payload->>'n' ILIKE ? || '%' ESCAPE '!'");
    assertThat(factory.build(TmfOperator.ENDS_WITH, "n", String.class, "e").sql())
        .isEqualTo("payload->>'n' LIKE '%' || ? ESCAPE '!'");
    assertThat(factory.build(TmfOperator.ENDS_WITHI, "n", String.class, "E").sql())
        .isEqualTo("payload->>'n' ILIKE '%' || ? ESCAPE '!'");
  }

  @Test
  @DisplayName("substring operators bind their value escaped for LIKE, like/likei as the pattern")
  void substringOperatorsEscapeTheValue() {
    for (TmfOperator op :
        List.of(
            TmfOperator.CONTAINS,
            TmfOperator.CONTAINSI,
            TmfOperator.STARTS_WITH,
            TmfOperator.STARTS_WITHI,
            TmfOperator.ENDS_WITH,
            TmfOperator.ENDS_WITHI)) {
      assertThat(factory.build(op, "n", String.class, "x%_\\!y").params())
          .as(op.suffix())
          .containsExactly("x!%!_\\!!y");
    }
    assertThat(factory.build(TmfOperator.LIKE, "n", String.class, "x%_").params())
        .containsExactly("x%_");
    assertThat(factory.build(TmfOperator.LIKEI, "n", String.class, "x%_").params())
        .containsExactly("x%_");
  }

  @Test
  @DisplayName("substring operators bind a value that is not text as itself, as 3.5.2 did")
  void substringOperatorsBindANonTextValueAsItself() {
    // PostgreSQL renders the bound value as text (a double 1.0 as "1"); Java's "1.0" would miss
    // a stored 0.1 for ?f.contains=1. That text never holds %, _ or !, so nothing is escaped.
    LocalDate day = LocalDate.of(2025, 1, 1);
    BigDecimal amount = new BigDecimal("1.50");
    for (TmfOperator op :
        List.of(
            TmfOperator.CONTAINS,
            TmfOperator.CONTAINSI,
            TmfOperator.STARTS_WITH,
            TmfOperator.STARTS_WITHI,
            TmfOperator.ENDS_WITH,
            TmfOperator.ENDS_WITHI)) {
      assertThat(factory.build(op, "f", Double.class, 1.0d).params())
          .as(op.suffix())
          .containsExactly(1.0d);
      assertThat(factory.build(op, "i", Integer.class, 5).params())
          .as(op.suffix())
          .containsExactly(5);
      assertThat(factory.build(op, "a", BigDecimal.class, amount).params())
          .as(op.suffix())
          .containsExactly(amount);
      assertThat(factory.build(op, "b", Boolean.class, Boolean.TRUE).params())
          .as(op.suffix())
          .containsExactly(Boolean.TRUE);
      assertThat(factory.build(op, "ld", LocalDate.class, day).params())
          .as(op.suffix())
          .containsExactly(day);
      assertThat(factory.build(op, "o", Object.class, "50%").params())
          .as(op.suffix())
          .containsExactly("50!%");
    }
  }

  @Test
  @DisplayName("REGEX / REGEXI use Postgres native ~ / ~* operators")
  void regex() {
    assertThat(factory.build(TmfOperator.REGEX, "n", String.class, "^A.*").sql())
        .isEqualTo("payload->>'n' ~ ?");
    assertThat(factory.build(TmfOperator.REGEXI, "n", String.class, "^a.*").sql())
        .isEqualTo("payload->>'n' ~* ?");
  }

  @Test
  @DisplayName("REGEX / LIKE-family on polymorphic (Object) field emit text-vs-text SQL — no cast")
  void polymorphicObjectField() {
    // TMF-620 catalog's productSpecCharacteristicValue.value is declared Object so it can carry
    // String / Number / Boolean per valueType. JSONB stores it untyped, so text extraction
    // (payload->>'x') + Postgres's native regex/LIKE do the right thing without a static-type
    // gate: string values match the pattern, numeric/boolean values don't. Cross-reference the
    // Mongo/JPA counterpart in tmf630-toolkit-attribute-filtering-core's PredicateFactory —
    // JSONB never needed the widening because it doesn't hop through QueryDSL's typed path.
    assertThat(
            factory
                .build(TmfOperator.REGEX, "characteristic.value", Object.class, "^Infinity_.*")
                .sql())
        .isEqualTo("payload#>>'{characteristic,value}' ~ ?");
    assertThat(
            factory
                .build(TmfOperator.CONTAINS, "characteristic.value", Object.class, "Giga")
                .sql())
        .isEqualTo("payload#>>'{characteristic,value}' LIKE '%' || ? || '%' ESCAPE '!'");
  }

  @Test
  @DisplayName("REGEX rejected when regex.enabled=false")
  void regexDisabled() {
    JsonbPredicateFactory f =
        new JsonbPredicateFactory(extractor, IsnullSemantics.MISSING_ONLY, false);
    assertThatThrownBy(() -> f.build(TmfOperator.REGEX, "n", String.class, "x"))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> f.build(TmfOperator.REGEXI, "n", String.class, "x"))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("IS_NULL under MISSING_ONLY on top-level key uses NOT (payload ?? 'x')")
  void isNullMissingOnlyTopLevel() {
    // ?? is the JDBC escape for Postgres's single ? key-existence operator — the
    // driver unescapes it to a single ? on the wire. Prevents JDBC from treating
    // the ? as a parameter placeholder.
    JsonbClause c = factory.buildNoValue(TmfOperator.IS_NULL, "status");
    assertThat(c.sql()).isEqualTo("NOT (payload ?? 'status')");
  }

  @Test
  @DisplayName("IS_NOT_NULL under MISSING_ONLY on top-level key uses payload ?? 'x'")
  void isNotNullMissingOnlyTopLevel() {
    JsonbClause c = factory.buildNoValue(TmfOperator.IS_NOT_NULL, "status");
    assertThat(c.sql()).isEqualTo("payload ?? 'status'");
  }

  @Test
  @DisplayName("IS_NULL under MISSING_ONLY on nested path uses IS NULL on jsonb extraction")
  void isNullMissingOnlyNested() {
    JsonbClause c = factory.buildNoValue(TmfOperator.IS_NULL, "customer.email");
    assertThat(c.sql()).isEqualTo("payload#>'{customer,email}' IS NULL");
  }

  @Test
  @DisplayName("IS_NULL under NULLISH on top-level key widens to missing OR explicit null")
  void isNullNullishTopLevel() {
    JsonbPredicateFactory f =
        new JsonbPredicateFactory(extractor, IsnullSemantics.NULLISH, true);
    JsonbClause c = f.buildNoValue(TmfOperator.IS_NULL, "status");
    assertThat(c.sql())
        .isEqualTo(
            "(NOT (payload ?? 'status') OR payload->'status' = 'null'::jsonb)");
  }

  @Test
  @DisplayName("IS_NOT_NULL under NULLISH is the exact complement (AND of positive branches)")
  void isNotNullNullishTopLevel() {
    JsonbPredicateFactory f =
        new JsonbPredicateFactory(extractor, IsnullSemantics.NULLISH, true);
    JsonbClause c = f.buildNoValue(TmfOperator.IS_NOT_NULL, "status");
    assertThat(c.sql())
        .isEqualTo("(payload ?? 'status' AND payload->'status' <> 'null'::jsonb)");
  }

  @Test
  @DisplayName("build() rejects IS_NULL / IS_NOT_NULL — must go through buildNoValue()")
  void buildRejectsNoValueOps() {
    assertThatThrownBy(() -> factory.build(TmfOperator.IS_NULL, "x", String.class, null))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> factory.build(TmfOperator.IS_NOT_NULL, "x", String.class, null))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("build() rejects BETWEEN / IN / NIN — must go through buildMulti()")
  void buildRejectsMultiOps() {
    assertThatThrownBy(() -> factory.build(TmfOperator.IN, "x", String.class, "v"))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> factory.build(TmfOperator.NIN, "x", String.class, "v"))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> factory.build(TmfOperator.BETWEEN, "x", String.class, "v"))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("buildMulti() and buildNoValue() reject wrong operator kind")
  void multiAndNoValueRejectWrongOps() {
    List<Object> singleV = List.of("v");
    assertThatThrownBy(() -> factory.buildMulti(TmfOperator.EQ, "x", String.class, singleV))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> factory.buildNoValue(TmfOperator.EQ, "x"))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("null IsnullSemantics defaults to MISSING_ONLY")
  void nullIsnullSemanticsDefaults() {
    JsonbPredicateFactory f = new JsonbPredicateFactory(extractor, null, true);
    assertThat(f.buildNoValue(TmfOperator.IS_NULL, "status").sql())
        .isEqualTo("NOT (payload ?? 'status')");
  }
}
