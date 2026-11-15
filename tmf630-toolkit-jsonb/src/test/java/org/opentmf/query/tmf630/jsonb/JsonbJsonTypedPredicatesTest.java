package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.predicate.JsonTypedValue;
import org.opentmf.query.tmf630.jsonb.JsonbPathPredicates.SplitChild;
import tools.jackson.databind.JsonNode;

/** The SQL/JSON path a URL operator on a JSON-typed field is sent as (ledger #4246, (a′)). */
class JsonbJsonTypedPredicatesTest {

  private final JsonbJsonTypedPredicates predicates = new JsonbJsonTypedPredicates("payload");

  @Test
  void equalityIsTheKeyedOperatorOverEveryReading() {
    JsonbClause c = single(TmfOperator.EQ, "characteristic.value", "42");

    assertThat(c.sql()).isEqualTo("payload @?? ?::jsonpath");
    assertThat(c.params())
        .containsExactly("$.\"characteristic\".\"value\" ? (@ == \"42\" || @ == 42)");
  }

  @Test
  void aBooleanReadsAsTheBooleanAndTheString() {
    assertThat(single(TmfOperator.EQ, "j", "true").params())
        .containsExactly("$.\"j\" ? (@ == \"true\" || @ == true)");
  }

  @Test
  void textIsWrittenAsAnEscapedJsonString() {
    assertThat(single(TmfOperator.EQ, "j", "a\"b\\c").params())
        .containsExactly("$.\"j\" ? (@ == \"a\\\"b\\\\c\")");
  }

  @Test
  void notEqualIsAPresentValueThatIsNoneOfTheReadings() {
    JsonbClause c = single(TmfOperator.NE, "j", "42");

    assertThat(c.sql()).isEqualTo("jsonb_path_exists(payload, ?::jsonpath)");
    assertThat(c.params())
        .containsExactly(
            "$.\"j\" ? (@ != null && !((@.type() == \"string\" && @ == \"42\")"
                + " || (@.type() == \"number\" && @ == 42)))");
  }

  @Test
  void inIsAnyReadingOfAnyValueAndKeyed() {
    JsonbClause c =
        predicates.multi(
            TmfOperator.IN,
            "j",
            JsonNode.class,
            null,
            List.of(JsonTypedValue.read("1"), JsonTypedValue.read("alpha")));

    assertThat(c.sql()).isEqualTo("payload @?? ?::jsonpath");
    assertThat(c.params()).containsExactly("$.\"j\" ? (@ == \"1\" || @ == 1 || @ == \"alpha\")");
  }

  @Test
  void notInGuardsEveryReadingByItsType() {
    JsonbClause c =
        predicates.multi(
            TmfOperator.NIN,
            "j",
            JsonNode.class,
            null,
            List.of(JsonTypedValue.read("true"), JsonTypedValue.read("alpha")));

    assertThat(c.params())
        .containsExactly(
            "$.\"j\" ? (@ != null && !((@.type() == \"string\" && @ == \"true\")"
                + " || (@.type() == \"boolean\" && @ == true)"
                + " || (@.type() == \"string\" && @ == \"alpha\")))");
  }

  @Test
  void neiMatchesAnyPresentValueButTheStringEqualIgnoringCase() {
    assertThat(single(TmfOperator.NEI, "j", "alpha").params())
        .containsExactly(
            "$.\"j\" ? (@ != null && !(@.type() == \"string\" && @ like_regex \"^alpha$\""
                + " flag \"i\"))");
  }

  @Test
  void anOrderingComparesTheNumberInTheFunctionForm() {
    JsonbClause c = single(TmfOperator.GT, "j", "10");

    assertThat(c.sql()).isEqualTo("jsonb_path_exists(payload, ?::jsonpath)");
    assertThat(c.params()).containsExactly("$.\"j\" ? (@ > 10)");
  }

  @Test
  void betweenComparesTwoNumbers() {
    JsonbClause c =
        predicates.multi(
            TmfOperator.BETWEEN,
            "j",
            JsonNode.class,
            null,
            List.of(JsonTypedValue.read("1"), JsonTypedValue.read("9.5")));

    assertThat(c.params()).containsExactly("$.\"j\" ? (@ >= 1 && @ <= 9.5)");
  }

  @Test
  void anOrderingOnANonNumberIsRefused() {
    assertThatThrownBy(() -> single(TmfOperator.GT, "j", "alpha"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage("Operator requires a number for the JSON-typed field: j");
  }

  @Test
  void stringOperatorsMatchTheirValueLiterally() {
    assertThat(single(TmfOperator.CONTAINS, "j", "a.b").params())
        .containsExactly("$.\"j\" ? (@ like_regex \"a\\\\.b\")");
    assertThat(single(TmfOperator.STARTS_WITHI, "j", "Al").params())
        .containsExactly("$.\"j\" ? (@ like_regex \"^Al\" flag \"i\")");
    assertThat(single(TmfOperator.ENDS_WITH, "j", "x$").params())
        .containsExactly("$.\"j\" ? (@ like_regex \"x\\\\$$\")");
    assertThat(single(TmfOperator.EQI, "j", "alpha").params())
        .containsExactly("$.\"j\" ? (@ like_regex \"^alpha$\" flag \"i\")");
  }

  @Test
  void aLikePatternKeepsItsWildcards() {
    assertThat(JsonbJsonTypedPredicates.likeToRegex("al%_a.\\%")).isEqualTo("^al.*.a\\.%$");
  }

  @Test
  void aRegexIsSentAsWritten() {
    assertThat(single(TmfOperator.REGEXI, "j", "^al+").params())
        .containsExactly("$.\"j\" ? (@ like_regex \"^al+\" flag \"i\")");
  }

  @Test
  void aPositionalSegmentKeepsItsIndex() {
    assertThat(single(TmfOperator.EQ, "characteristic[2].value", "x").params())
        .containsExactly("$.\"characteristic\"[2].\"value\" ? (@ == \"x\")");
  }

  @Test
  void aSplitCollectionIsAnsweredInTheChildTable() {
    SplitChild split = new SplitChild("order_item", "parent_id", "order_row", "payload");

    JsonbClause c =
        predicates.single(
            TmfOperator.EQ, "items.value", JsonNode.class, split, JsonTypedValue.read("7"));

    assertThat(c.sql())
        .isEqualTo(
            "EXISTS (SELECT 1 FROM order_item WHERE parent_id = order_row.id AND"
                + " payload @?? ?::jsonpath)");
    assertThat(c.params()).containsExactly("$.\"value\" ? (@ == \"7\" || @ == 7)");
  }

  @Test
  void aSegmentThatIsNoIdentifierKeepsTheConvertersRefusal() {
    assertThatThrownBy(
            () ->
                predicates.single(
                    TmfOperator.EQ, "m.a-b", Map.class, null, JsonTypedValue.read("1")))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage("Field \"m.a-b\" has type Map which cannot be converted from a String.");
  }

  private JsonbClause single(TmfOperator operator, String fieldPath, String value) {
    return predicates.single(operator, fieldPath, JsonNode.class, null, JsonTypedValue.read(value));
  }
}
