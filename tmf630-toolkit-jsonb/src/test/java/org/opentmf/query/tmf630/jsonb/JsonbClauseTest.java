package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonbClauseTest {

  @Test
  @DisplayName("of() copies params defensively and rejects null sql")
  void ofBasics() {
    JsonbClause c = JsonbClause.of("x = ?", "v");
    assertThat(c.sql()).isEqualTo("x = ?");
    assertThat(c.params()).containsExactly("v");
    List<Object> emptyParams = List.of();
    assertThatThrownBy(() -> new JsonbClause(null, emptyParams))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("and() short-circuits on TRUE / FALSE identities")
  void andIdentities() {
    JsonbClause t = JsonbClause.alwaysTrue();
    JsonbClause f = JsonbClause.alwaysFalse();
    JsonbClause p = JsonbClause.of("p = ?", 1);
    assertThat(p.and(t)).isSameAs(p);
    assertThat(t.and(p).sql()).isEqualTo(p.sql());
    assertThat(p.and(f).sql()).isEqualTo("FALSE");
    assertThat(f.and(p).sql()).isEqualTo("FALSE");
    assertThat(p.and(null)).isSameAs(p);
  }

  @Test
  @DisplayName("or() short-circuits on TRUE / FALSE identities")
  void orIdentities() {
    JsonbClause t = JsonbClause.alwaysTrue();
    JsonbClause f = JsonbClause.alwaysFalse();
    JsonbClause p = JsonbClause.of("p = ?", 1);
    assertThat(p.or(f)).isSameAs(p);
    assertThat(f.or(p).sql()).isEqualTo(p.sql());
    assertThat(p.or(t).sql()).isEqualTo("TRUE");
    assertThat(t.or(p).sql()).isEqualTo("TRUE");
    assertThat(p.or(null)).isSameAs(p);
  }

  @Test
  @DisplayName("and() concatenates parenthesised sql and merges params in order")
  void andComposition() {
    JsonbClause a = JsonbClause.of("a = ?", 1);
    JsonbClause b = JsonbClause.of("b = ?", 2);
    JsonbClause combined = a.and(b);
    assertThat(combined.sql()).isEqualTo("(a = ? AND b = ?)");
    assertThat(combined.params()).containsExactly(1, 2);
  }

  @Test
  @DisplayName("or() concatenates parenthesised sql")
  void orComposition() {
    JsonbClause a = JsonbClause.of("a = ?", 1);
    JsonbClause b = JsonbClause.of("b = ?", 2);
    assertThat(a.or(b).sql()).isEqualTo("(a = ? OR b = ?)");
  }

  @Test
  @DisplayName("not() wraps and flips identities")
  void notBehavior() {
    assertThat(JsonbClause.alwaysTrue().not().sql()).isEqualTo("FALSE");
    assertThat(JsonbClause.alwaysFalse().not().sql()).isEqualTo("TRUE");
    JsonbClause p = JsonbClause.of("x = ?", 1);
    JsonbClause negated = p.not();
    assertThat(negated.sql()).isEqualTo("(NOT x = ?)");
    assertThat(negated.params()).containsExactly(1);
  }
}
