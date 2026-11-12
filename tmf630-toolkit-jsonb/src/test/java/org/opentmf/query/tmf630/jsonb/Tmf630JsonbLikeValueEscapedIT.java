package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code contains}, {@code startswith}, {@code endswith} and their case-insensitive forms match
 * their value literally. Before 3.6.0 a {@code %} or {@code _} in the value was a LIKE wildcard
 * ({@code s.contains=50%} also found "500 off") and a backslash was PostgreSQL's implicit LIKE
 * escape ({@code s.contains=x\y} found "xy"); now the value is escaped with {@code !}, the escape
 * character the QueryDSL terminal uses, under an explicit {@code ESCAPE '!'}. {@code like} and
 * {@code likei}, whose value is a pattern, keep their wildcards.
 */
@SpringBootTest(
    classes = Tmf630JsonbLikeValueEscapedIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbLikeValueEscapedIT {

  private static final String TABLE = JsonbTypedRow.TABLE;

  /** The field at each position the URL grammar reaches: top, nested, inside an array, a list. */
  private static final List<String> PATHS = List.of("s", "a.b", "items.b", "tags");

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "PCT", "50% off",
            "NUM", "500 off",
            "UND", "a_c",
            "ABC", "abc",
            "BSL", "x\\y",
            "XY", "xy",
            "BNG", "hi!",
            "HI", "hi")
        .forEach(
            (id, value) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload(value))
                    .update());
  }

  /** The same text at every position, so one cell per path reads the same rows. */
  private String payload(String value) {
    ObjectNode root = mapper.createObjectNode();
    root.put("s", value);
    root.putObject("a").put("b", value);
    root.putArray("items").addObject().put("b", value);
    root.putArray("tags").add(value);
    return mapper.writeValueAsString(root);
  }

  /**
   * One character LIKE reads specially, the values that hold it for each operator, and the one row
   * that holds them literally. The decoy row beside it ({@code NUM}, {@code ABC}, {@code XY}) is
   * what the unescaped value also found; {@code !} is the escape character itself.
   */
  private record Probe(String contains, String startsWith, String endsWith, String literal) {}

  private static final List<Probe> PROBES =
      List.of(
          new Probe("50%", "50%", "% off", "PCT"),
          new Probe("a_c", "a_", "_c", "UND"),
          new Probe("x\\y", "x\\y", "x\\y", "BSL"),
          new Probe("hi!", "hi!", "i!", "BNG"));

  static Stream<Arguments> cells() {
    List<Arguments> cells = new ArrayList<>();
    for (String path : PATHS) {
      for (Probe p : PROBES) {
        cells.add(cell(path, "contains", p.contains(), p.literal()));
        cells.add(cell(path, "containsi", upper(p.contains()), p.literal()));
        cells.add(cell(path, "startswith", p.startsWith(), p.literal()));
        cells.add(cell(path, "startswithi", upper(p.startsWith()), p.literal()));
        cells.add(cell(path, "endswith", p.endsWith(), p.literal()));
        cells.add(cell(path, "endswithi", upper(p.endsWith()), p.literal()));
      }
      // like / likei: the value IS the pattern, its wildcards stay wildcards.
      cells.add(cell(path, "like", "50%", "PCT", "NUM"));
      cells.add(cell(path, "likei", "A_C", "UND", "ABC"));
    }
    return cells.stream();
  }

  private static String upper(String value) {
    return value.toUpperCase(Locale.ROOT);
  }

  private static Arguments cell(String path, String operator, String value, String... ids) {
    return Arguments.of(path + "." + operator, value, Set.of(ids));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("cells")
  void theValueMatchesLiterally(String param, String value, Set<String> expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    List<String> ids =
        jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
            .params(where.params())
            .query(String.class)
            .list();
    assertThat(ids)
        .as(where.sql() + " " + where.params())
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
