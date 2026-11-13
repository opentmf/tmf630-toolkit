package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
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
 * Under the opt-in {@code opentmf.tmf630.jsonb.apply-regex-max-length=true} (since 3.6.0), JSONB
 * applies the core's regex length cap, {@code regex.max-length} (lowered to 8 here, so a refused
 * 11-character pattern shows the setting is read), to {@code .regex} / {@code .regexi} and to
 * {@code filter=} {@code =~}, with the core's 400, as the JPA and Mongo terminals do. By default
 * no cap applies ({@link Tmf630JsonbFilterDefaultRefusalsIT}).
 */
@SpringBootTest(
    classes = Tmf630JsonbRegexCapOptInIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.regex.max-length=8",
      "opentmf.tmf630.jsonb.apply-regex-max-length=true"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbRegexCapOptInIT {

  private static final String TABLE = JsonbTypedRow.TABLE;
  private static final String TOO_LONG = "Regex pattern too long for field: ";

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of("A", "{\"s\":\"alpha\",\"items\":[{\"b\":\"x\"}]}", "B", "{\"s\":\"bravo\"}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  static Stream<Arguments> refused() {
    return Stream.of(
        Arguments.of("filter", "$[?(@.s =~ /^abcdefghij/)]", TOO_LONG + "s"),
        Arguments.of("filter", "$[?(@.items[?(@.b =~ /abcdefghij/i)])]", TOO_LONG + "b"),
        Arguments.of("s.regex", "^abcdefghij", TOO_LONG + "s"),
        Arguments.of("items.b.regexi", "^abcdefghij", TOO_LONG + "items.b"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("refused")
  void aPatternLongerThanTheCapIsTheCores400(String param, String value, String message) {
    Map<String, String[]> params = Map.of(param, new String[] {value});
    assertThatThrownBy(() -> clauseBuilder.build(JsonbTypedDomain.class, params))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage(message);
  }

  @Test
  void aPatternWithinTheCapAnswers() {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of("s.regex", new String[] {"^al"}));

    List<String> ids =
        jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
            .params(where.params())
            .query(String.class)
            .list();
    assertThat(ids).containsExactly("A");
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
