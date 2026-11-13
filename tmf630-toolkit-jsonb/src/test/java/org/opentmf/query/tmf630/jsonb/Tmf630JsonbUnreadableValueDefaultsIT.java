package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedRow;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Under the DEFAULT {@code cast-mode=strict}, over the rows of {@link Tmf630JsonbUnreadableValueIT}:
 * one stored value the cast cannot read fails a typed filter or sort on that field, as in 3.5.2 and
 * as the design's §6 recommends ("Fail fast"). No PostgreSQL 16 check runs: strict needs 15.
 */
@SpringBootTest(
    classes = Tmf630JsonbUnreadableValueDefaultsIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbUnreadableValueDefaultsIT {

  private static final String TABLE = JsonbTypedRow.TABLE;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;
  @Autowired private ApplicationContext context;

  @BeforeAll
  void seed() {
    Tmf630JsonbUnreadableValueIT.seed(jdbc);
  }

  static Stream<Arguments> filters() {
    return Stream.of(
        Arguments.of("ld.gt", "2025-03-01"),
        Arguments.of("t.lt", "2025-03-01T00:00:00Z"),
        Arguments.of("n.gt", "6"),
        Arguments.of("b", "true"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("filters")
  void aTypedFilterWithAnUnreadableRowFails(String param, String value) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    assertThatThrownBy(
            () -> jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
                .params(where.params())
                .query(String.class)
                .list())
        .as(where.sql())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aTypedSortWithAnUnreadableRowFails() {
    TmfSort sort =
        new TmfSort(List.of(new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "ld")));
    JsonbClause order = sortBuilder.buildOrderByClause(sort, f -> LocalDate.class);
    String sql = "SELECT id FROM " + TABLE + " WHERE id LIKE 'LD%' ORDER BY " + order.sql();

    assertThat(order.sql()).isEqualTo("(payload->>'ld')::date ASC");
    assertThatThrownBy(() -> jdbc.sql(sql).params(order.params()).query(String.class).list())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void noPostgres16CheckRunsUnderStrict() {
    assertThat(context.getBeansOfType(Tmf630JsonbPostgresVersionCheck.class)).isEmpty();
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
