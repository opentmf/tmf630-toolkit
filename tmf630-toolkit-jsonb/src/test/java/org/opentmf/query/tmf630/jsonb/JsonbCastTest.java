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

  enum SampleEnum {
    A,
    B
  }
}
