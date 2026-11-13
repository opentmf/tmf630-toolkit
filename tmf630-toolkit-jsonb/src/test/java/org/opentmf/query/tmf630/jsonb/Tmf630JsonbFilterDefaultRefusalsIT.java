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
 * Under the defaults, since 3.6.0:
 *
 * <ul>
 *   <li>the {@code filter=} forms PostgreSQL's jsonpath cannot parse, which were a 500, are the
 *       core's 400 (a bare exists test, recursive descent, a function PostgreSQL does not have,
 *       {@code length()} compared by anything but {@code ==});
 *   <li>the regex length cap, the core's {@code regex.max-length}, does NOT apply on JSONB, as
 *       before 3.6.0: it is lowered to 8 here and an 11-character pattern still answers (the
 *       opt-in, {@code opentmf.tmf630.jsonb.apply-regex-max-length=true}, is
 *       {@link Tmf630JsonbRegexCapOptInIT});
 *   <li>PostgreSQL's own jsonpath that answers ({@code exists()}, item methods, {@code like_regex},
 *       {@code starts with}, arithmetic, a negated group) still answers: an extension on JSONB,
 *       which the Mongo and JPA terminals refuse.
 * </ul>
 */
@SpringBootTest(
    classes = Tmf630JsonbFilterDefaultRefusalsIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.regex.max-length=8"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbFilterDefaultRefusalsIT {

  private static final String TABLE = JsonbTypedRow.TABLE;
  private static final String EXPECTED_COMPARISON =
      "Expected comparison operator in jsonPath filter.";

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "A", "{\"s\":\"alpha\",\"n\":5,\"items\":[{\"b\":\"x\"}],\"tags\":[\"x\"]}",
            "B", "{\"s\":\"bravo\",\"n\":9,\"items\":[{\"b\":\"z\"},{\"b\":\"x\"}]}",
            "C", "{\"n\":7}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  static Stream<Arguments> refused() {
    return Stream.of(
        filter("$[?(@.s)]", EXPECTED_COMPARISON),
        filter("$[?(@.items[?(@.b)])]", EXPECTED_COMPARISON),
        filter("$[?(@..s == 'alpha')]", "Unknown field path: .s"),
        filter("$[?(@.items.count() > 1)]", EXPECTED_COMPARISON),
        filter(
            "$[?(@.items.length() > 1)]",
            "length() supports only == comparison; use [?(...)] for non-empty checks."));
  }

  private static Arguments filter(String expression, String message) {
    return Arguments.of("filter", expression, message);
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("refused")
  void theCores400(String param, String value, String message) {
    Map<String, String[]> params = Map.of(param, new String[] {value});
    assertThatThrownBy(() -> clauseBuilder.build(JsonbTypedDomain.class, params))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage(message);
  }

  static Stream<Arguments> answered() {
    return Stream.of(
        answered("$[?(exists(@.s))]", "A", "B"),
        answered("$[?(@.s.type() == 'string')]", "A", "B"),
        answered("$[?(@.items.size() > 1)]", "B"),
        answered("$[?(@.s like_regex \"^al\")]", "A"),
        answered("$[?(@.s starts with \"br\")]", "B"),
        answered("$[?(@.n + 1 > 8)]", "B"),
        answered("$[?(!(@.s == 'alpha'))]", "B", "C"),
        answered("$[?(@.items.length() == 2)]", "B"),
        answered("$[?(@.s =~ /^br/)]", "B"),
        Arguments.of("s.regex", "^al", Set.of("A")),
        // longer than regex.max-length (8): no cap by default
        answered("$[?(@.s =~ /^alpha|^bravo/)]", "A", "B"),
        Arguments.of("s.regex", "^alpha|^bravo", Set.of("A", "B")),
        Arguments.of("items.b.regexi", "^X|^Y|^nothing", Set.of("A", "B")));
  }

  private static Arguments answered(String expression, String... ids) {
    return Arguments.of("filter", expression, Set.of(ids));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("answered")
  void stillAnswers(String param, String value, Set<String> expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    List<String> ids =
        jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
            .params(where.params())
            .query(String.class)
            .list();
    assertThat(ids).as(where.sql() + " " + where.params()).containsExactlyInAnyOrderElementsOf(expected);
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
