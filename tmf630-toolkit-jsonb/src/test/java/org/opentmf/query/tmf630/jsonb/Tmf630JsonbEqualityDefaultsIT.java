package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Equality under the DEFAULTS ({@code equality=extraction}, {@code cast-mode=strict}), over the rows
 * of {@link Tmf630JsonbEqualityParityIT}: on an object path the URL grammar answers what 3.5.2
 * answered (the text, cast by the declared type; a value the cast cannot read fails the request).
 * Through an array or on a collection field it answers for any element (3.5.2 never matched: a fix).
 */
@SpringBootTest(
    classes = Tmf630JsonbEqualityDefaultsIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbEqualityDefaultsIT {

  private static final Set<String> ERROR = Tmf630JsonbEqualityParityIT.ERROR;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    Tmf630JsonbEqualityParityIT.seed(jdbc);
  }

  /** Label, query parameter, its value, rows the default answers (or the failing request). */
  static Stream<Arguments> cells() {
    return Stream.of(
        cell("String stored as a string", "s", "x", "S7"),
        cell("String with a double quote", "s", "a\"b", "S6"),
        cell("Long beyond 2^53", "l", "9007199254740993", "L1"),
        cell("Double 0.1", "f", "0.1", "F1"),
        cell("Boolean false", "b", "false", "B5"),
        cell("Enum by its name", "e", "RED", "E1"),
        cell("nested object path", "a.b", "x", "A1"),
        // the stored text decides, as in 3.5.2: another JSON type is found through its text or cast
        cell("String field holding a number", "s", "42", "S1", "S2"),
        cell("String field holding a boolean", "s", "true", "S3", "S4"),
        cell("String field holding a decimal", "s", "42.0", "S5"),
        cell("IN on a String field", "s.in", "x,42", "S1", "S2", "S7"),
        cell("BigDecimal through its cast", "d", "1.5", "D1", "D2", "D3"),
        cell("Boolean through its cast", "b", "true", "B1", "B2", "B3", "B4"),
        // strict, as in 3.5.2: 42.0 and "abc" in an Integer field fail the bigint cast
        Arguments.of("Integer, with 42.0 and \"abc\" stored", "n", "42", ERROR),
        Arguments.of("IN on an Integer field", "n.in", "42,43", ERROR),
        cell("LocalDate by date", "ld", "2025-01-01", "LD1", "LD2"),
        cell("OffsetDateTime by instant", "t", "2025-01-01T08:00:00Z", "T1", "T2", "T3"),
        // a fix: through an array, any element (3.5.2 found none)
        cell("path through an array", "items.b", "x", "I1", "I2"),
        cell("positional index", "items[0].b", "x", "I1"),
        cell("array leaf", "tags", "x", "G1"));
  }

  private static Arguments cell(String label, String param, String value, String... ids) {
    return Arguments.of(label, param, value, Set.of(ids));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cells")
  void theDefaultAnswersAs352(String label, String param, String value, Set<String> expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    if (ERROR.equals(expected)) {
      assertThatThrownBy(() -> ids(where)).isInstanceOf(DataAccessException.class);
    } else {
      assertThat(ids(where)).as(where.sql()).containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  private List<String> ids(JsonbClause where) {
    return jdbc.sql("SELECT id FROM " + JsonbTypedRow.TABLE + " WHERE " + where.sql())
        .params(where.params())
        .query(String.class)
        .list();
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
