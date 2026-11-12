package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Tmf630JsonbPostgresVersionCheckTest {

  @Test
  @DisplayName("below PostgreSQL 16 the module refuses to start, and says why")
  void refusesBelowSixteen() {
    Tmf630JsonbPostgresVersionCheck check = new Tmf630JsonbPostgresVersionCheck(() -> 150_004);

    assertThatThrownBy(check::afterPropertiesSet)
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessage(
            "tmf630-toolkit-jsonb needs PostgreSQL 16 or later: filters and sorts on date and"
                + " time fields read stored text with pg_input_is_valid (PostgreSQL 16), so a"
                + " value no cast reads is no match instead of an error; this server reports"
                + " server_version_num 150004.");
  }

  @Test
  @DisplayName("PostgreSQL 16 and later start")
  void startsFromSixteen() {
    assertThatCode(new Tmf630JsonbPostgresVersionCheck(() -> 160_000)::afterPropertiesSet)
        .doesNotThrowAnyException();
    assertThatCode(new Tmf630JsonbPostgresVersionCheck(() -> 180_001)::afterPropertiesSet)
        .doesNotThrowAnyException();
  }
}
