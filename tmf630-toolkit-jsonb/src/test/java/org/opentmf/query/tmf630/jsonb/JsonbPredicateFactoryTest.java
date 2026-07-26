package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;

class JsonbPredicateFactoryTest {

  private final JsonbPathExtractor extractor = new JsonbPathExtractor("payload");
  private final JsonbPredicateFactory factory =
      new JsonbPredicateFactory(extractor, IsnullSemantics.MISSING_ONLY, true);

  @Test
  @DisplayName("EQ / NE on String — no cast, direct text comparison")
  void equalityOnString() {
    JsonbClause eq = factory.build(TmfOperator.EQ, "status", String.class, "Pending");
    assertThat(eq.sql()).isEqualTo("payload->>'status' = ?");
    assertThat(eq.params()).containsExactly("Pending");

    JsonbClause ne = factory.build(TmfOperator.NE, "status", String.class, "Cancelled");
    assertThat(ne.sql()).isEqualTo("payload->>'status' <> ?");
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
  @DisplayName("GT / GTE / LT / LTE on numeric — casts LHS and RHS to ::bigint")
  void rangeOperatorsNumeric() {
    JsonbClause gt = factory.build(TmfOperator.GT, "priority", Integer.class, 5);
    assertThat(gt.sql()).isEqualTo("payload->>'priority'::bigint > ?::bigint");
    assertThat(factory.build(TmfOperator.GTE, "priority", Integer.class, 5).sql())
        .isEqualTo("payload->>'priority'::bigint >= ?::bigint");
    assertThat(factory.build(TmfOperator.LT, "priority", Integer.class, 5).sql())
        .isEqualTo("payload->>'priority'::bigint < ?::bigint");
    assertThat(factory.build(TmfOperator.LTE, "priority", Integer.class, 5).sql())
        .isEqualTo("payload->>'priority'::bigint <= ?::bigint");
  }

  @Test
  @DisplayName("GT on OffsetDateTime — casts to ::timestamptz")
  void rangeOperatorsDatetime() {
    OffsetDateTime when = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    JsonbClause gt = factory.build(TmfOperator.GT, "createdAt", OffsetDateTime.class, when);
    assertThat(gt.sql())
        .isEqualTo("payload->>'createdAt'::timestamptz > ?::timestamptz");
    assertThat(gt.params()).containsExactly(when);
  }

  @Test
  @DisplayName("BETWEEN casts both bounds")
  void between() {
    JsonbClause c =
        factory.buildMulti(TmfOperator.BETWEEN, "priority", Integer.class, List.of(1, 9));
    assertThat(c.sql())
        .isEqualTo("payload->>'priority'::bigint BETWEEN ?::bigint AND ?::bigint");
    assertThat(c.params()).containsExactly(1, 9);
  }

  @Test
  @DisplayName("BETWEEN rejects wrong-arity value list")
  void betweenWrongArity() {
    assertThatThrownBy(
            () -> factory.buildMulti(TmfOperator.BETWEEN, "priority", Integer.class, List.of(1)))
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
    assertThat(nin.sql()).isEqualTo("payload->>'priority'::bigint NOT IN (?::bigint, ?::bigint, ?::bigint)");
  }

  @Test
  @DisplayName("Multi-value operators reject empty value list")
  void multiRejectsEmpty() {
    assertThatThrownBy(() -> factory.buildMulti(TmfOperator.IN, "x", String.class, List.of()))
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
        .isEqualTo("payload->>'n' LIKE '%' || ? || '%'");
    assertThat(factory.build(TmfOperator.CONTAINSI, "n", String.class, "OW").sql())
        .isEqualTo("payload->>'n' ILIKE '%' || ? || '%'");
    assertThat(factory.build(TmfOperator.STARTS_WITH, "n", String.class, "D").sql())
        .isEqualTo("payload->>'n' LIKE ? || '%'");
    assertThat(factory.build(TmfOperator.STARTS_WITHI, "n", String.class, "d").sql())
        .isEqualTo("payload->>'n' ILIKE ? || '%'");
    assertThat(factory.build(TmfOperator.ENDS_WITH, "n", String.class, "e").sql())
        .isEqualTo("payload->>'n' LIKE '%' || ?");
    assertThat(factory.build(TmfOperator.ENDS_WITHI, "n", String.class, "E").sql())
        .isEqualTo("payload->>'n' ILIKE '%' || ?");
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
  @DisplayName("IS_NULL under MISSING_ONLY on top-level key uses NOT (payload ? 'x')")
  void isNullMissingOnlyTopLevel() {
    JsonbClause c = factory.buildNoValue(TmfOperator.IS_NULL, "status");
    assertThat(c.sql()).isEqualTo("NOT (payload ? 'status')");
  }

  @Test
  @DisplayName("IS_NOT_NULL under MISSING_ONLY on top-level key uses payload ? 'x'")
  void isNotNullMissingOnlyTopLevel() {
    JsonbClause c = factory.buildNoValue(TmfOperator.IS_NOT_NULL, "status");
    assertThat(c.sql()).isEqualTo("payload ? 'status'");
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
            "(NOT (payload ? 'status') OR payload->'status' = 'null'::jsonb)");
  }

  @Test
  @DisplayName("IS_NOT_NULL under NULLISH is the exact complement (AND of positive branches)")
  void isNotNullNullishTopLevel() {
    JsonbPredicateFactory f =
        new JsonbPredicateFactory(extractor, IsnullSemantics.NULLISH, true);
    JsonbClause c = f.buildNoValue(TmfOperator.IS_NOT_NULL, "status");
    assertThat(c.sql())
        .isEqualTo("(payload ? 'status' AND payload->'status' <> 'null'::jsonb)");
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
    assertThatThrownBy(
            () -> factory.buildMulti(TmfOperator.EQ, "x", String.class, List.of("v")))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> factory.buildNoValue(TmfOperator.EQ, "x"))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("null IsnullSemantics defaults to MISSING_ONLY")
  void nullIsnullSemanticsDefaults() {
    JsonbPredicateFactory f = new JsonbPredicateFactory(extractor, null, true);
    assertThat(f.buildNoValue(TmfOperator.IS_NULL, "status").sql())
        .isEqualTo("NOT (payload ? 'status')");
  }
}
