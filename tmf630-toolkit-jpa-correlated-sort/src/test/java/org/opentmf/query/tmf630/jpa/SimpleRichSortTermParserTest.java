package org.opentmf.query.tmf630.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;

class SimpleRichSortTermParserTest {

  @Test
  @DisplayName("parses simple-rich hop[key=value].leaf")
  void parsesCanonicalShape() {
    SimpleRichSortTermParser.ParsedTerm parsed =
        SimpleRichSortTermParser.parse("characteristics[name=price].value");
    assertThat(parsed.hopField()).isEqualTo("characteristics");
    assertThat(parsed.matchKey()).isEqualTo("name");
    assertThat(parsed.matchValue()).isEqualTo("price");
    assertThat(parsed.leafField()).isEqualTo("value");
  }

  @Test
  @DisplayName("strips outer single quotes around the match value")
  void stripsSingleQuotes() {
    SimpleRichSortTermParser.ParsedTerm parsed =
        SimpleRichSortTermParser.parse("arr[key='the value'].leaf");
    assertThat(parsed.matchValue()).isEqualTo("the value");
  }

  @Test
  @DisplayName("strips outer double quotes around the match value")
  void stripsDoubleQuotes() {
    SimpleRichSortTermParser.ParsedTerm parsed =
        SimpleRichSortTermParser.parse("arr[key=\"the value\"].leaf");
    assertThat(parsed.matchValue()).isEqualTo("the value");
  }

  @Test
  @DisplayName("rejects blank expression")
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
        .hasMessageContaining("JsonPath");
  }

  @Test
  @DisplayName("rejects positional [N] index")
  void rejectsPositionalIndex() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("characteristics[0].value"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Positional index");
  }

  @Test
  @DisplayName("rejects min() aggregator")
  void rejectsMinAggregator() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("min(characteristics[name=price].value)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("aggregators");
  }

  @Test
  @DisplayName("rejects max() aggregator")
  void rejectsMaxAggregator() {
    assertThatThrownBy(() -> SimpleRichSortTermParser.parse("max(characteristics[name=price].value)"))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("aggregators");
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
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("simple-rich shape");
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
}
