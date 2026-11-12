package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * URL attribute operators on a path that crosses an array ({@code items.b}, items being a list of
 * objects) or ends in a collection ({@code tags}, a list of strings). Before 3.6.0 such a path was
 * extracted as {@code payload#>>'{items,b}'}, which reads nothing through an array: every value
 * operator matched no row and {@code isnull} matched every row. They now answer for ANY element,
 * as the Mongo and JPA backends and {@code filter=} do; {@code isnull} / {@code isnotnull} mean no
 * element, or some element, holds the field. A value where an array is declared (an object, a
 * scalar) holds no element.
 */
@SpringBootTest(
    classes = Tmf630JsonbUrlArrayPathIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbUrlArrayPathIT {

  private static final String TABLE = JsonbTypedRow.TABLE;
  private static final Set<String> WITHOUT_ITEMS = Set.of("T1", "T2", "T3", "N0");
  private static final Set<String> WITHOUT_TAGS =
      Set.of("I1", "I2", "I3", "I4", "I5", "I6", "N0");

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "I1", "{\"items\":[{\"b\":\"x\"}]}",
            "I2", "{\"items\":[{\"b\":\"y\"},{\"b\":\"x\"}]}",
            "I3", "{\"items\":[{}]}",
            "I4", "{\"items\":[]}",
            "I5", "{\"items\":{\"b\":\"x\"}}",
            "I6", "{\"items\":[{\"b\":null}]}",
            "T1", "{\"tags\":[\"x\",\"y\"]}",
            "T2", "{\"tags\":[]}",
            "T3", "{\"tags\":\"x\"}",
            "N0", "{\"n\":1}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  static Stream<Arguments> cells() {
    return Stream.of(
        // ---- a field of the elements of a list of objects ----
        cell("items.b", "x", Set.of("I1", "I2")),
        cell("items.b.ne", "x", Set.of("I2")),
        cell("items.b.in", "x,z", Set.of("I1", "I2")),
        cell("items.b.nin", "x", Set.of("I2")),
        cell("items.b.gte", "y", Set.of("I2")),
        cell("items.b.eqi", "X", Set.of("I1", "I2")),
        cell("items.b.like", "x%", Set.of("I1", "I2")),
        cell("items.b.startswith", "y", Set.of("I2")),
        cell("items.b.regex", "^y", Set.of("I2")),
        cell("items.b.isnotnull", "true", Set.of("I1", "I2", "I6")),
        cell(
            "items.b.isnull",
            "true",
            union(Set.of("I3", "I4", "I5"), WITHOUT_ITEMS)),
        // ---- the elements of a list of strings ----
        cell("tags", "x", Set.of("T1")),
        cell("tags.ne", "x", Set.of("T1")),
        cell("tags.in", "y,z", Set.of("T1")),
        cell("tags.startswith", "y", Set.of("T1")),
        cell("tags.isnotnull", "true", Set.of("T1", "T2", "T3")),
        cell("tags.isnull", "true", WITHOUT_TAGS));
  }

  private static Arguments cell(String param, String value, Set<String> expected) {
    return Arguments.of(param, value, expected);
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    return Stream.concat(a.stream(), b.stream()).collect(Collectors.toSet());
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("cells")
  void urlOperatorAnswersForAnyElement(String param, String value, Set<String> expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    assertThat(ids(where)).as(where.sql()).containsExactlyInAnyOrderElementsOf(expected);
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
