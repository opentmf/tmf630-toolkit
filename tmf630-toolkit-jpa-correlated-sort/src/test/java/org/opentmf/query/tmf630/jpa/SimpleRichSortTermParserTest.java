package org.opentmf.query.tmf630.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.jpa.SimpleRichSortTermParser.Aggregator;
import org.opentmf.query.tmf630.jpa.SimpleRichSortTermParser.ParsedTerm;

class SimpleRichSortTermParserTest {

  @Test
  @DisplayName("parses single-hop 'hop[key=value].leaf'")
  void parsesSingleHop() {
    ParsedTerm parsed = SimpleRichSortTermParser.parse("characteristics[name=price].value");
    assertThat(parsed.hops()).hasSize(1);
    assertThat(parsed.hops().get(0).hopField()).isEqualTo("characteristics");
    assertThat(parsed.hops().get(0).matchKey()).isEqualTo("name");
    assertThat(parsed.hops().get(0).matchValue()).isEqualTo("price");
    assertThat(parsed.leafField()).isEqualTo("value");
    assertThat(parsed.aggregator()).isEqualTo(Aggregator.NONE);
  }

  @Test
  @DisplayName("parses two-hop 'a[k=v].b[k=v].leaf'")
  void parsesTwoHopChain() {
    ParsedTerm parsed =
        SimpleRichSortTermParser.parse("items[sku=A1].variants[color=red].price");
    assertThat(parsed.hops()).hasSize(2);
    assertThat(parsed.hops().get(0).hopField()).isEqualTo("items");
    assertThat(parsed.hops().get(0).matchKey()).isEqualTo("sku");
    assertThat(parsed.hops().get(0).matchValue()).isEqualTo("A1");
    assertThat(parsed.hops().get(1).hopField()).isEqualTo("variants");
    assertThat(parsed.hops().get(1).matchKey()).isEqualTo("color");
    assertThat(parsed.hops().get(1).matchValue()).isEqualTo("red");
    assertThat(parsed.leafField()).isEqualTo("price");
  }

  @Test
  @DisplayName("parses three-hop chain")
  void parsesThreeHopChain() {
    ParsedTerm parsed =
        SimpleRichSortTermParser.parse("a[k=v1].b[k=v2].c[k=v3].leaf");
    assertThat(parsed.hops()).hasSize(3);
    assertThat(parsed.hops().get(2).hopField()).isEqualTo("c");
    assertThat(parsed.leafField()).isEqualTo("leaf");
  }

  @Test
  @DisplayName("recognizes min() aggregator wrapper")
  void recognizesMinAggregator() {
    ParsedTerm parsed =
        SimpleRichSortTermParser.parse("min(characteristics[name=price].value)");
    assertThat(parsed.aggregator()).isEqualTo(Aggregator.MIN);
    assertThat(parsed.hops()).hasSize(1);
    assertThat(parsed.leafField()).isEqualTo("value");
  }

  @Test
  @DisplayName("recognizes max() aggregator wrapper on multi-hop")
  void recognizesMaxAggregatorOnMultiHop() {
    ParsedTerm parsed =
        SimpleRichSortTermParser.parse("max(items[sku=A].variants[color=red].price)");
    assertThat(parsed.aggregator()).isEqualTo(Aggregator.MAX);
    assertThat(parsed.hops()).hasSize(2);
  }

  @Test
  @DisplayName("strips outer single quotes around a hop match value")
  void stripsSingleQuotes() {
    ParsedTerm parsed = SimpleRichSortTermParser.parse("arr[key='the value'].leaf");
    assertThat(parsed.hops().get(0).matchValue()).isEqualTo("the value");
  }

  @Test
  @DisplayName("strips outer double quotes around a hop match value")
  void stripsDoubleQuotes() {
    ParsedTerm parsed = SimpleRichSortTermParser.parse("arr[key=\"the value\"].leaf");
    assertThat(parsed.hops().get(0).matchValue()).isEqualTo("the value");
  }

  @Test
  @DisplayName("rejects blank or null expression")
  void rejectsBlank() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse(""))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("must not be blank");
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse(null))
        .isInstanceOf(TmfPagingException.class);
  }

  @Test
  @DisplayName("rejects JsonPath grammar")
  void rejectsJsonPathGrammar() {
    assertThatThrownBy(
            () -> SimpleRichSortTermParser.parse("$.characteristics[?(@.name=='X')].value"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("JsonPath");
  }

  @Test
  @DisplayName("rejects wildcard [*] grammar")
  void rejectsWildcard() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("characteristics[*].value"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Wildcard");
  }

  @Test
  @DisplayName("rejects positional [N] index")
  void rejectsPositionalIndex() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("characteristics[0].value"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Positional index");
  }

  @Test
  @DisplayName("rejects num()/str()/date() coercions")
  void rejectsCoercions() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("num(characteristics[name=price].value)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("coercions");
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("str(characteristics[name=price].value)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("coercions");
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("date(characteristics[name=price].value)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("coercions");
  }

  @Test
  @DisplayName("rejects malformed shape (missing bracket, missing leaf, etc.)")
  void rejectsMalformed() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("plainName"))
        .isInstanceOf(TmfPagingException.class);
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("arr[key=value]"))
        .isInstanceOf(TmfPagingException.class);
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("arr.leaf"))
        .isInstanceOf(TmfPagingException.class);
  }

  @Test
  @DisplayName("rejects empty match value")
  void rejectsEmptyValue() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("arr[key=''].leaf"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("match value must not be empty");
  }

  @Test
  @DisplayName("rejects nested calls inside min()/max()")
  void rejectsNestedCallsInsideAggregator() {
    assertThatThrownBy(
            () -> SimpleRichSortTermParser.parse("min(max(characteristics[name=price].value))"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Nested calls");
  }

  @Test
  @DisplayName("rejects empty aggregator body")
  void rejectsEmptyAggregatorBody() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("min()"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("wrap a hop chain");
  }
}
