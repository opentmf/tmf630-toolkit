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
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;
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
 * The null tests and the refused operators of {@code filter=} on the real PostgreSQL. Before
 * 3.6.0 {@code !@.field} reached PostgreSQL as a jsonpath syntax error (a 500), {@code == null}
 * matched only a stored JSON null, and {@code in} / {@code nin} were syntax errors too. Now
 * {@code !@.field} and {@code == null} mean "no value" and {@code != null} "a value", by the
 * configured {@code isnull-semantics} as on the other backends (MISSING_ONLY: the key absent;
 * NULLISH: also a JSON null), and {@code in} / {@code nin} are the core's 400.
 */
@SpringBootTest(
    classes = Tmf630JsonbFilterNullFormsIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbFilterNullFormsIT {

  private static final String TABLE = JsonbTypedRow.TABLE;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  private final JsonbJsonPathTranslator nullish =
      new JsonbJsonPathTranslator(
          new JsonbPathExtractor("payload"), "payload", false, IsnullSemantics.NULLISH);

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "S", "{\"s\":\"a\"}",
            "N", "{\"s\":null}",
            "A", "{\"n\":1}",
            "I1", "{\"items\":[{\"b\":\"x\"}]}",
            "I2", "{\"items\":[{}]}",
            "I3", "{\"items\":[{\"b\":null}]}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  static Stream<Arguments> cells() {
    return Stream.of(
        // filter, rows under MISSING_ONLY (the default), rows under NULLISH
        cell("$[?(!@.s)]", Set.of("A", "I1", "I2", "I3"), Set.of("A", "I1", "I2", "I3", "N")),
        cell("$[?(@.s == null)]", Set.of("A", "I1", "I2", "I3"), Set.of("A", "I1", "I2", "I3", "N")),
        cell("$[?(@.s != null)]", Set.of("S", "N"), Set.of("S")),
        cell("$[?(@.items[?(!@.b)])]", Set.of("I2"), Set.of("I2", "I3")),
        cell("$[?(@.items[?(@.b != null)])]", Set.of("I1", "I3"), Set.of("I1")));
  }

  private static Arguments cell(String filter, Set<String> missingOnly, Set<String> nullish) {
    return Arguments.of(filter, missingOnly, nullish);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cells")
  void nullTestFollowsTheConfiguredIsnullMeaning(
      String filter, Set<String> missingOnly, Set<String> underNullish) {
    JsonbClause byDefault =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of("filter", new String[] {filter}));
    assertThat(ids(byDefault)).as(byDefault.params().toString()).containsExactlyInAnyOrderElementsOf(missingOnly);

    JsonbClause underNullishSemantics = nullish.translate(filter);
    assertThat(ids(underNullishSemantics))
        .as(underNullishSemantics.params().toString())
        .containsExactlyInAnyOrderElementsOf(underNullish);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"$[?(@.s in ['a', 'b'])]", "$[?(@.s nin ['a'])]"})
  void inAndNinAreTheCores400(String filter) {
    Map<String, String[]> params = Map.of("filter", new String[] {filter});
    assertThatThrownBy(() -> clauseBuilder.build(JsonbTypedDomain.class, params))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageStartingWith("Unsupported token in jsonPath filter near: ");
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
