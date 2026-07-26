package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Unit tests for {@link SimpleMongoInnerPredicateTranslator}. Asserts on the produced
 * {@link Criteria} by serialising it to a BSON {@link Document} — that matches what
 * would be sent on the wire.
 */
class SimpleMongoInnerPredicateTranslatorTest {

  private final SimpleMongoInnerPredicateTranslator translator =
      new SimpleMongoInnerPredicateTranslator();

  @Test
  @DisplayName("equality with string literal")
  void equalityStringLiteral() {
    Criteria c = translator.translate("@.state == 'PENDING'");
    assertThat(c.getCriteriaObject()).isEqualTo(new Document("payload.state", "PENDING"));
  }

  @Test
  @DisplayName("inequality with string literal")
  void inequalityStringLiteral() {
    Criteria c = translator.translate("@.state != 'CANCELLED'");
    assertThat(c.getCriteriaObject())
        .isEqualTo(new Document("payload.state", new Document("$ne", "CANCELLED")));
  }

  @Test
  @DisplayName("numeric comparison — integer")
  void numericComparisonInt() {
    Criteria c = translator.translate("@.priority > 5");
    assertThat(c.getCriteriaObject())
        .isEqualTo(new Document("payload.priority", new Document("$gt", 5)));
  }

  @Test
  @DisplayName("numeric comparison — double")
  void numericComparisonDouble() {
    Criteria c = translator.translate("@.price <= 19.99");
    assertThat(c.getCriteriaObject())
        .isEqualTo(new Document("payload.price", new Document("$lte", 19.99)));
  }

  @Test
  @DisplayName("dotted field path resolves to dotted BSON key")
  void dottedFieldPath() {
    Criteria c = translator.translate("@.characteristic.value == 'blue'");
    assertThat(c.getCriteriaObject())
        .isEqualTo(new Document("payload.characteristic.value", "blue"));
  }

  @Test
  @DisplayName("&& composition produces $and")
  void andComposition() {
    Criteria c = translator.translate("@.state == 'PENDING' && @.priority > 5");
    Document out = c.getCriteriaObject();
    assertThat(out).containsKey("$and");
  }

  @Test
  @DisplayName("|| composition produces $or")
  void orComposition() {
    Criteria c = translator.translate("@.state == 'PENDING' || @.state == 'IN_PROGRESS'");
    Document out = c.getCriteriaObject();
    assertThat(out).containsKey("$or");
  }

  @Test
  @DisplayName("parentheses group correctly — (a || b) && c differs from a || (b && c)")
  void parensGrouping() {
    Criteria left =
        translator.translate("(@.state == 'A' || @.state == 'B') && @.priority > 3");
    Criteria right =
        translator.translate("@.state == 'A' || (@.state == 'B' && @.priority > 3)");
    assertThat(left.getCriteriaObject()).isNotEqualTo(right.getCriteriaObject());
    assertThat(left.getCriteriaObject()).containsKey("$and");
    assertThat(right.getCriteriaObject()).containsKey("$or");
  }

  @Test
  @DisplayName("all six comparison operators")
  void allComparisonOperators() {
    assertThat(translator.translate("@.n == 1").getCriteriaObject())
        .isEqualTo(new Document("payload.n", 1));
    assertThat(translator.translate("@.n != 1").getCriteriaObject())
        .isEqualTo(new Document("payload.n", new Document("$ne", 1)));
    assertThat(translator.translate("@.n < 1").getCriteriaObject())
        .isEqualTo(new Document("payload.n", new Document("$lt", 1)));
    assertThat(translator.translate("@.n <= 1").getCriteriaObject())
        .isEqualTo(new Document("payload.n", new Document("$lte", 1)));
    assertThat(translator.translate("@.n > 1").getCriteriaObject())
        .isEqualTo(new Document("payload.n", new Document("$gt", 1)));
    assertThat(translator.translate("@.n >= 1").getCriteriaObject())
        .isEqualTo(new Document("payload.n", new Document("$gte", 1)));
  }

  @Test
  @DisplayName("empty / null input rejected with clear message")
  void rejectsEmpty() {
    assertThatThrownBy(() -> translator.translate(""))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("empty");
    assertThatThrownBy(() -> translator.translate("   "))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> translator.translate(null))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("unparseable leaf rejected with actionable message")
  void rejectsUnparseable() {
    assertThatThrownBy(() -> translator.translate("@.foo LIKE 'bar'"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("could not be parsed");
  }

  @Test
  @DisplayName("unclosed parenthesis rejected")
  void rejectsUnclosedParen() {
    assertThatThrownBy(() -> translator.translate("(@.state == 'X'"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("Expected ')'");
  }
}
