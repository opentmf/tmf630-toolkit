package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;

class JsonbCorrelatedSortTranslatorTest {

  private final JsonbCorrelatedSortTranslator translator = new JsonbCorrelatedSortTranslator();

  @Test
  @DisplayName("simple-rich hop[key=value].leaf → jsonPath only, no aggregator, no coercion")
  void simpleRichCanonical() {
    JsonbSortExpression e = translator.translate("characteristics[name=price].value");
    assertThat(e.jsonPath())
        .isEqualTo("$.characteristics[*] ? (@.name == \"price\").value");
    assertThat(e.aggregator()).isEmpty();
    assertThat(e.coercion()).isEmpty();
  }

  @Test
  @DisplayName("simple-rich with dotted leaf preserves multi-segment leaf")
  void simpleRichDottedLeaf() {
    JsonbSortExpression e = translator.translate("items[type=install].service.version");
    assertThat(e.jsonPath())
        .isEqualTo("$.items[*] ? (@.type == \"install\").service.version");
  }

  @Test
  @DisplayName("simple-rich strips outer quotes from match value")
  void simpleRichStripsSingleQuotes() {
    JsonbSortExpression e = translator.translate("arr[key='the value'].leaf");
    assertThat(e.jsonPath()).isEqualTo("$.arr[*] ? (@.key == \"the value\").leaf");
  }

  @Test
  @DisplayName("JsonPath $.hop[?(@.key=='value')].leaf → same jsonPath as simple-rich")
  void jsonPathCanonical() {
    JsonbSortExpression e =
        translator.translate("$.characteristics[?(@.name=='price')].value");
    assertThat(e.jsonPath())
        .isEqualTo("$.characteristics[*] ? (@.name == \"price\").value");
  }

  @Test
  @DisplayName("JsonPath with double-quoted literal accepted")
  void jsonPathDoubleQuoted() {
    JsonbSortExpression e = translator.translate("$.arr[?(@.key == \"the value\")].leaf");
    assertThat(e.jsonPath()).isEqualTo("$.arr[*] ? (@.key == \"the value\").leaf");
  }

  @Test
  @DisplayName("JsonPath with whitespace around operators tolerated")
  void jsonPathWithWhitespace() {
    JsonbSortExpression e = translator.translate("$.arr[  ? ( @.key   ==   'v' ) ].leaf");
    assertThat(e.jsonPath()).isEqualTo("$.arr[*] ? (@.key == \"v\").leaf");
  }

  @Test
  @DisplayName("Phase (b.7): wildcard hop[*].leaf renders as $.hop[*].leaf")
  void wildcardTerm() {
    JsonbSortExpression e = translator.translate("prices[*].value");
    assertThat(e.jsonPath()).isEqualTo("$.prices[*].value");
    assertThat(e.aggregator()).isEmpty();
    assertThat(e.coercion()).isEmpty();
  }

  @Test
  @DisplayName("Phase (b.7): min() aggregator around a wildcard path is captured")
  void minAggregatorAroundWildcard() {
    JsonbSortExpression e = translator.translate("min(prices[*].value)");
    assertThat(e.aggregator()).contains(JsonbSortExpression.Aggregator.MIN);
    assertThat(e.coercion()).isEmpty();
    assertThat(e.jsonPath()).isEqualTo("$.prices[*].value");
  }

  @Test
  @DisplayName("Phase (b.7): max() aggregator around a correlated path is captured")
  void maxAggregatorAroundCorrelated() {
    JsonbSortExpression e = translator.translate("max(items[type=install].amount)");
    assertThat(e.aggregator()).contains(JsonbSortExpression.Aggregator.MAX);
    assertThat(e.jsonPath()).isEqualTo("$.items[*] ? (@.type == \"install\").amount");
  }

  @Test
  @DisplayName("Phase (b.7): num() coercion around a correlated path is captured")
  void numCoercionAroundCorrelated() {
    JsonbSortExpression e = translator.translate("num(characteristics[name=price].value)");
    assertThat(e.coercion()).contains(JsonbCast.NUMERIC);
    assertThat(e.aggregator()).isEmpty();
    assertThat(e.jsonPath()).isEqualTo("$.characteristics[*] ? (@.name == \"price\").value");
  }

  @Test
  @DisplayName("Phase (b.7): str() coercion maps to TEXT (no cast in emission)")
  void strCoercion() {
    JsonbSortExpression e = translator.translate("str(characteristics[name=color].value)");
    assertThat(e.coercion()).contains(JsonbCast.TEXT);
  }

  @Test
  @DisplayName("Phase (b.7): date() coercion maps to TIMESTAMPTZ")
  void dateCoercion() {
    JsonbSortExpression e = translator.translate("date(items[type=start].timestamp)");
    assertThat(e.coercion()).contains(JsonbCast.TIMESTAMPTZ);
  }

  @Test
  @DisplayName("Phase (b.7): num(min(prices[*].value)) — coercion around aggregator")
  void coercionAroundAggregator() {
    JsonbSortExpression e = translator.translate("num(min(prices[*].value))");
    assertThat(e.coercion()).contains(JsonbCast.NUMERIC);
    assertThat(e.aggregator()).contains(JsonbSortExpression.Aggregator.MIN);
    assertThat(e.jsonPath()).isEqualTo("$.prices[*].value");
  }

  @Test
  @DisplayName("Phase (b.7): date(max(...)) — coercion around aggregator")
  void dateAroundMax() {
    JsonbSortExpression e =
        translator.translate("date(max(items[type=install].completedAt))");
    assertThat(e.coercion()).contains(JsonbCast.TIMESTAMPTZ);
    assertThat(e.aggregator()).contains(JsonbSortExpression.Aggregator.MAX);
    assertThat(e.jsonPath()).isEqualTo("$.items[*] ? (@.type == \"install\").completedAt");
  }

  @Test
  @DisplayName("Phase (b.7): nested duplicate wrappers rejected (num(num(...)))")
  void rejectsNestedDuplicateCoercions() {
    assertThatThrownBy(() -> translator.translate("num(num(arr[*].value))"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("single");
  }

  @Test
  @DisplayName("Phase (b.7): nested aggregators rejected (min(max(...)))")
  void rejectsNestedAggregators() {
    assertThatThrownBy(() -> translator.translate("min(max(arr[*].value))"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("single");
  }

  @Test
  @DisplayName("null or blank rejected")
  void rejectsBlank() {
    assertThatThrownBy(() -> translator.translate(null))
        .isInstanceOf(TmfPagingException.class);
    assertThatThrownBy(() -> translator.translate(""))
        .isInstanceOf(TmfPagingException.class);
  }

  @Test
  @DisplayName("positional [N] rejected — recommend plain dotted sort with numeric segment")
  void rejectsPositional() {
    assertThatThrownBy(() -> translator.translate("arr[0].leaf"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Positional");
  }

  @Test
  @DisplayName("malformed term rejected with actionable message")
  void rejectsMalformed() {
    assertThatThrownBy(() -> translator.translate("plainName"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("supported shape");
    assertThatThrownBy(() -> translator.translate("arr[key=value]"))
        .isInstanceOf(TmfPagingException.class);
  }

  @Test
  @DisplayName("embedded double-quote in single-quoted match value is escaped in output")
  void embeddedDoubleQuoteEscaped() {
    JsonbSortExpression e = translator.translate("arr[key='the \"quoted\" value'].leaf");
    assertThat(e.jsonPath()).isEqualTo("$.arr[*] ? (@.key == \"the \\\"quoted\\\" value\").leaf");
  }
}
