package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * The module under its DEFAULTS on PostgreSQL 15, the minimum the design (§10) states: the context
 * starts (no PostgreSQL 16 check runs under {@code cast-mode=strict}) and the default forms answer,
 * the 3.6.0 fixes included (any element through an array, {@code filter=} array matches and null
 * tests, literal substring values).
 */
@SpringBootTest(
    classes = Tmf630JsonbPostgres15IT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///db",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbPostgres15IT {

  private static final String TABLE = JsonbTypedRow.TABLE;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "A",
            "{\"s\":\"50% off\",\"n\":5,\"ld\":\"2025-01-01\",\"items\":[{\"b\":\"x\"}]}",
            "B",
            "{\"s\":\"500 off\",\"n\":9,\"ld\":\"2025-06-30\",\"items\":[{\"b\":\"z\"}],"
                + "\"a\":{\"b\":\"y\"}}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  @Test
  void theServerIsPostgres15() {
    assertThat(serverVersionNum()).isBetween(150_000, 159_999);
  }

  static Stream<Arguments> requests() {
    return Stream.of(
        Arguments.of("n", "9", Set.of("B")),
        Arguments.of("n.gt", "6", Set.of("B")),
        Arguments.of("ld.lt", "2025-03-01", Set.of("A")),
        Arguments.of("items.b", "x", Set.of("A")),
        Arguments.of("s.contains", "50%", Set.of("A")),
        Arguments.of("filter", "$[?(@.items[?(@.b == 'z')])]", Set.of("B")),
        Arguments.of("filter", "$[?(!@.a)]", Set.of("A")),
        Arguments.of("filter", "$[?(@.n > 6)]", Set.of("B")));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("requests")
  void theDefaultsAnswerOnPostgres15(String param, String value, Set<String> expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    List<String> ids =
        jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
            .params(where.params())
            .query(String.class)
            .list();
    assertThat(ids).as(where.sql()).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void aDateSortAnswersOnPostgres15() {
    TmfSort sort =
        new TmfSort(List.of(new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "ld")));
    JsonbClause order = sortBuilder.buildOrderByClause(sort, f -> LocalDate.class);

    assertThat(
            jdbc.sql("SELECT id FROM " + TABLE + " ORDER BY " + order.sql())
                .params(order.params())
                .query(String.class)
                .list())
        .containsExactly("B", "A");
  }

  @Test
  @DisplayName("cast-mode=lenient on PostgreSQL 15: the application refuses to start, and says why")
  void lenientRefusesToStartOnPostgres15() {
    SpringApplicationBuilder lenient =
        new SpringApplicationBuilder(TestApp.class)
            .web(WebApplicationType.NONE)
            .profiles("test")
            .properties(
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///lenient",
                "spring.datasource.driver-class-name="
                    + "org.testcontainers.jdbc.ContainerDatabaseDriver",
                "opentmf.tmf630.jsonb.cast-mode=lenient");

    assertThatThrownBy(lenient::run)
        .hasRootCauseInstanceOf(Tmf630JsonbConfigurationException.class)
        .rootCause()
        .hasMessageStartingWith(
            "tmf630-toolkit-jsonb with opentmf.tmf630.jsonb.cast-mode=lenient needs PostgreSQL 16")
        .hasMessageEndingWith("server_version_num " + serverVersionNum() + ".");
  }

  private int serverVersionNum() {
    return jdbc.sql("SELECT current_setting('server_version_num')::int")
        .query(Integer.class)
        .single();
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
