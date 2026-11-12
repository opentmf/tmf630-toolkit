package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedDomain.Color;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedDomain.Status;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Enum fields on a JSONB payload, against the real PostgreSQL: an enum stored by its name
 * ({@link Color}) and one stored by its {@code @JsonValue} wire value ({@link Status}, the shape of
 * generated TMF enums). Before 3.6.0 every such comparison bound the enum constant itself, which
 * the JDBC driver refuses ("Can't infer the SQL type"), so the request failed. The value is now
 * bound as the payload holds it: what the service's mapper writes for the constant.
 */
@SpringBootTest(
    classes = Tmf630JsonbEnumBindingIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbEnumBindingIT {

  private static final String TABLE = JsonbTypedRow.TABLE;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbPredicateFactory predicateFactory;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "E1", "{\"e\":\"RED\"}",
            "E2", "{\"e\":\"GREEN\"}",
            "W1", "{\"w\":\"inProgress\"}",
            "W2", "{\"w\":\"done\"}",
            "W3", "{\"w\":\"IN_PROGRESS\"}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  static Stream<Arguments> urlCells() {
    return Stream.of(
        Arguments.of("plain enum EQ", "e", "RED", Set.of("E1")),
        Arguments.of("plain enum NE", "e.ne", "RED", Set.of("E2")),
        Arguments.of("plain enum NIN", "e.nin", "GREEN", Set.of("E1")),
        Arguments.of("wire-value enum EQ finds its wire value", "w", "IN_PROGRESS", Set.of("W1")),
        Arguments.of("wire-value enum NE", "w.ne", "IN_PROGRESS", Set.of("W2", "W3")),
        Arguments.of("wire-value enum NIN", "w.nin", "DONE", Set.of("W1", "W3")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("urlCells")
  @DisplayName("URL grammar on an enum field finds the rows that hold what Jackson writes")
  void urlGrammarOnAnEnumField(String label, String param, String value, Set<String> expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    assertThat(ids(where)).as(where.sql()).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  @DisplayName("the factory's extraction form binds the same text for both enum shapes")
  void extractionFormOnAnEnumField() {
    assertThat(ids(predicateFactory.build(TmfOperator.EQ, "e", Color.class, Color.RED)))
        .containsExactly("E1");
    assertThat(ids(predicateFactory.build(TmfOperator.EQ, "w", Status.class, Status.IN_PROGRESS)))
        .containsExactly("W1");
    assertThat(
            ids(
                predicateFactory.buildMulti(
                    TmfOperator.IN, "w", Status.class, List.of(Status.IN_PROGRESS, Status.DONE))))
        .containsExactlyInAnyOrder("W1", "W2");
  }

  private List<String> ids(JsonbClause where) {
    return jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
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
