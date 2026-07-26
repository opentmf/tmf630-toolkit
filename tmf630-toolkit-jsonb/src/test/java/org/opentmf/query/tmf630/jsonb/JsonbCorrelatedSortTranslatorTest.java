package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;

class JsonbCorrelatedSortTranslatorTest {

  private final JsonbCorrelatedSortTranslator translator = new JsonbCorrelatedSortTranslator();

  @Test
  @DisplayName("simple-rich hop[key=value].leaf translates to Postgres SQL/JSON path")
  void simpleRichCanonical() {
    assertThat(translator.translate("characteristics[name=price].value"))
        .isEqualTo("$.characteristics[*] ? (@.name == \"price\").value");
  }

  @Test
  @DisplayName("simple-rich with dotted leaf preserves multi-segment leaf")
  void simpleRichDottedLeaf() {
    assertThat(translator.translate("items[type=install].service.version"))
        .isEqualTo("$.items[*] ? (@.type == \"install\").service.version");
  }

  @Test
  @DisplayName("simple-rich strips outer quotes from match value")
  void simpleRichStripsSingleQuotes() {
    assertThat(translator.translate("arr[key='the value'].leaf"))
        .isEqualTo("$.arr[*] ? (@.key == \"the value\").leaf");
  }

  @Test
  @DisplayName("JsonPath $.hop[?(@.key=='value')].leaf translates to same shape as simple-rich")
  void jsonPathCanonical() {
    assertThat(translator.translate("$.characteristics[?(@.name=='price')].value"))
        .isEqualTo("$.characteristics[*] ? (@.name == \"price\").value");
  }

  @Test
  @DisplayName("JsonPath with double-quoted literal accepted")
  void jsonPathDoubleQuoted() {
    assertThat(translator.translate("$.arr[?(@.key == \"the value\")].leaf"))
        .isEqualTo("$.arr[*] ? (@.key == \"the value\").leaf");
  }

  @Test
  @DisplayName("JsonPath with whitespace around operators tolerated")
  void jsonPathWithWhitespace() {
    assertThat(translator.translate("$.arr[  ? ( @.key   ==   'v' ) ].leaf"))
        .isEqualTo("$.arr[*] ? (@.key == \"v\").leaf");
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
  @DisplayName("wildcard [*] rejected — out of scope for b.6")
  void rejectsWildcard() {
    assertThatThrownBy(() -> translator.translate("arr[*].leaf"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Wildcard");
  }

  @Test
  @DisplayName("positional [N] rejected — recommend plain dotted sort with numeric segment")
  void rejectsPositional() {
    assertThatThrownBy(() -> translator.translate("arr[0].leaf"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Positional");
  }

  @Test
  @DisplayName("aggregators min()/max() rejected — deferred to Phase b.7")
  void rejectsAggregators() {
    assertThatThrownBy(() -> translator.translate("min(arr[key=v].leaf)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("b.7");
    assertThatThrownBy(() -> translator.translate("max(arr[key=v].leaf)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("b.7");
  }

  @Test
  @DisplayName("coercions num()/str()/date() rejected — deferred to Phase b.7")
  void rejectsCoercions() {
    assertThatThrownBy(() -> translator.translate("num(arr[key=v].leaf)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("b.7");
    assertThatThrownBy(() -> translator.translate("str(arr[key=v].leaf)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("b.7");
    assertThatThrownBy(() -> translator.translate("date(arr[key=v].leaf)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("b.7");
  }

  @Test
  @DisplayName("malformed term rejected with actionable message")
  void rejectsMalformed() {
    assertThatThrownBy(() -> translator.translate("plainName"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("single-hop shape");
    assertThatThrownBy(() -> translator.translate("arr[key=value]"))
        .isInstanceOf(TmfPagingException.class);
    assertThatThrownBy(() -> translator.translate("arr.leaf"))
        .isInstanceOf(TmfPagingException.class);
  }

  @Test
  @DisplayName("embedded double-quote in single-quoted match value is escaped in output")
  void embeddedDoubleQuoteEscaped() {
    assertThat(translator.translate("arr[key='the \"quoted\" value'].leaf"))
        .isEqualTo("$.arr[*] ? (@.key == \"the \\\"quoted\\\" value\").leaf");
  }
}
