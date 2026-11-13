package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonbCastTest {

  @Test
  @DisplayName("String / enum / null map to TEXT (no cast)")
  void textMapping() {
    assertThat(JsonbCast.forJavaType(String.class)).isEqualTo(JsonbCast.TEXT);
    assertThat(JsonbCast.forJavaType(CharSequence.class)).isEqualTo(JsonbCast.TEXT);
    assertThat(JsonbCast.forJavaType(SampleEnum.class)).isEqualTo(JsonbCast.TEXT);
    assertThat(JsonbCast.forJavaType(null)).isEqualTo(JsonbCast.TEXT);
    assertThat(JsonbCast.TEXT.suffix()).isEmpty();
    assertThat(JsonbCast.TEXT.suffixIfNeeded()).isEmpty();
  }

  @Test
  @DisplayName("Numeric primitives and wrappers map to BIGINT or NUMERIC")
  void numericMapping() {
    assertThat(JsonbCast.forJavaType(byte.class)).isEqualTo(JsonbCast.BIGINT);
    assertThat(JsonbCast.forJavaType(Short.class)).isEqualTo(JsonbCast.BIGINT);
    assertThat(JsonbCast.forJavaType(int.class)).isEqualTo(JsonbCast.BIGINT);
    assertThat(JsonbCast.forJavaType(Integer.class)).isEqualTo(JsonbCast.BIGINT);
    assertThat(JsonbCast.forJavaType(long.class)).isEqualTo(JsonbCast.BIGINT);
    assertThat(JsonbCast.forJavaType(BigInteger.class)).isEqualTo(JsonbCast.BIGINT);
    assertThat(JsonbCast.forJavaType(double.class)).isEqualTo(JsonbCast.NUMERIC);
    assertThat(JsonbCast.forJavaType(Float.class)).isEqualTo(JsonbCast.NUMERIC);
    assertThat(JsonbCast.forJavaType(BigDecimal.class)).isEqualTo(JsonbCast.NUMERIC);
    assertThat(JsonbCast.BIGINT.suffix()).isEqualTo("::bigint");
    assertThat(JsonbCast.NUMERIC.suffix()).isEqualTo("::numeric");
    assertThat(JsonbCast.BIGINT.suffixIfNeeded()).contains("::bigint");
  }

  @Test
  @DisplayName("Date/time types map to appropriate Postgres cast")
  void dateTimeMapping() {
    assertThat(JsonbCast.forJavaType(LocalDate.class)).isEqualTo(JsonbCast.DATE);
    assertThat(JsonbCast.forJavaType(LocalTime.class)).isEqualTo(JsonbCast.TIME);
    assertThat(JsonbCast.forJavaType(LocalDateTime.class)).isEqualTo(JsonbCast.TIMESTAMP);
    assertThat(JsonbCast.forJavaType(OffsetDateTime.class)).isEqualTo(JsonbCast.TIMESTAMPTZ);
    assertThat(JsonbCast.forJavaType(ZonedDateTime.class)).isEqualTo(JsonbCast.TIMESTAMPTZ);
    assertThat(JsonbCast.forJavaType(Instant.class)).isEqualTo(JsonbCast.TIMESTAMPTZ);
  }

  @Test
  @DisplayName("Boolean maps to BOOLEAN")
  void booleanMapping() {
    assertThat(JsonbCast.forJavaType(boolean.class)).isEqualTo(JsonbCast.BOOLEAN);
    assertThat(JsonbCast.forJavaType(Boolean.class)).isEqualTo(JsonbCast.BOOLEAN);
  }

  @Test
  @DisplayName("Unrecognized types default to TEXT")
  void unknownFallsBackToText() {
    assertThat(JsonbCast.forJavaType(Object.class)).isEqualTo(JsonbCast.TEXT);
  }

  @Test
  @DisplayName("readCanonical reads a value only in its JSON or ISO form, else NULL")
  void readCanonicalReadsOnlyTheJsonOrIsoForm() {
    assertThat(JsonbCast.TEXT.readCanonical("e1->>'s'")).isEqualTo("e1->>'s'");
    assertThat(JsonbCast.BIGINT.readCanonical("e1->>'i'"))
        .isEqualTo(
            "CASE WHEN (e1->>'i') ~ '^-{0,1}[0-9]{1,19}$' THEN CASE WHEN (e1->>'i')::numeric"
                + " BETWEEN -9223372036854775808 AND 9223372036854775807 THEN (e1->>'i')::bigint"
                + " END END");
    assertThat(JsonbCast.NUMERIC.readCanonical("e1->>'d'"))
        .isEqualTo(
            "CASE WHEN length(e1->>'d') <= 1000 AND (e1->>'d') ~"
                + " '^-{0,1}[0-9]+([.][0-9]+){0,1}([eE][+-]{0,1}[0-9]{1,3}){0,1}$'"
                + " THEN (e1->>'d')::numeric END");
    assertThat(JsonbCast.BOOLEAN.readCanonical("e1->>'b'"))
        .isEqualTo("CASE WHEN (e1->>'b') IN ('true', 'false') THEN (e1->>'b')::boolean END");
    assertThat(JsonbCast.TIME.readCanonical("e1->>'lt'"))
        .isEqualTo(
            "CASE WHEN (e1->>'lt') ~ '^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9]([.][0-9]{1,9}){0,1})"
                + "{0,1}$' THEN (e1->>'lt')::time END");
    for (JsonbCast dated : new JsonbCast[] {JsonbCast.DATE, JsonbCast.TIMESTAMP,
        JsonbCast.TIMESTAMPTZ}) {
      assertThat(dated.readCanonical("e1->>'x'"))
          .as(dated.name())
          .startsWith("CASE WHEN (e1->>'x') ~ '^(([0-9]{3}[1-9]|")
          .endsWith("$' THEN (e1->>'x')" + dated.suffix() + " END");
    }
    assertThat(JsonbCast.TIMESTAMP.readCanonical("e1->>'x'")).doesNotContain("(Z|");
    assertThat(JsonbCast.TIMESTAMPTZ.readCanonical("e1->>'x'"))
        .contains("(Z|[+-](0[0-9]|1[0-5])(:{0,1}[0-5][0-9]){0,1}){0,1}$'");
  }

  @Test
  @DisplayName("readCanonical holds no ? and no backslash, whatever the cast")
  void readCanonicalHoldsNoPlaceholderAndNoBackslash() {
    for (JsonbCast cast : JsonbCast.values()) {
      assertThat(cast.readCanonical("e1->>'x'")).as(cast.name()).doesNotContain("?", "\\");
    }
  }

  enum SampleEnum {
    A,
    B
  }
}
