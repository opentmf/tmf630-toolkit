package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.jsonb.it.JsonTypedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonTypedRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * URL operators on a field of an open JSON type (ledger #4246, (a′), since 3.6.0): the value is
 * read as JSON. {@code 42} finds the numbers equal to 42 and the string {@code "42"}; {@code true}
 * the boolean and the string; an ordering compares numbers only; a string operator matches strings
 * only. Before 3.6.0 every such request was the core's 400 (the converter could not make a
 * {@code Map} or {@code JsonNode} from a String). The {@code Object} field beside them keeps its
 * released answers (compared as text). The same requests and stored values as the Mongo backend's
 * {@code Tmf630MongoJsonTypedFieldIT}, which answers the same rows.
 */
@SpringBootTest(
    classes = Tmf630JsonbJsonTypedFieldIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.allow-nested-paths-docdb=true"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbJsonTypedFieldIT {

  /** The stored values: id suffix → JSON; {@code abs} has no key. */
  static final Map<String, String> STORED = new LinkedHashMap<>();

  static {
    STORED.put("s42", "\"42\"");
    STORED.put("n42", "42");
    STORED.put("n42.0", "42.0");
    STORED.put("btrue", "true");
    STORED.put("strue", "\"true\"");
    STORED.put("salpha", "\"alpha\"");
    STORED.put("n7", "7");
    STORED.put("obj", "{\"amount\":5}");
    STORED.put("nul", "null");
    STORED.put("abs", null);
  }

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + JsonTypedRow.TABLE).update();
    for (String field : List.of("o", "m", "j")) {
      STORED.forEach(
          (suffix, json) ->
              insert(field + "." + suffix, json == null ? "{}" : "{\"" + field + "\":" + json + "}"));
    }
    STORED.forEach(
        (suffix, json) ->
            insert(
                "c." + suffix,
                "{\"characteristic\":[{\"name\":\"x\""
                    + (json == null ? "" : ",\"value\":" + json)
                    + "},{\"name\":\"y\",\"value\":\"other\"}]}"));
  }

  private void insert(String id, String payload) {
    jdbc.sql("INSERT INTO " + JsonTypedRow.TABLE + " (id, payload) VALUES (?, ?::jsonb)")
        .params(id, payload)
        .update();
  }

  static Stream<Arguments> answers() {
    return Stream.of(
        Arguments.of("j.eq", "42", List.of("j.n42", "j.n42.0", "j.s42")),
        Arguments.of("j.eq", "true", List.of("j.btrue", "j.strue")),
        Arguments.of("j.eq", "alpha", List.of("j.salpha")),
        Arguments.of("j.in", "42,alpha", List.of("j.n42", "j.n42.0", "j.s42", "j.salpha")),
        Arguments.of("j.ne", "42", List.of("j.btrue", "j.n7", "j.obj", "j.salpha", "j.strue")),
        Arguments.of("j.nin", "42,alpha", List.of("j.btrue", "j.n7", "j.obj", "j.strue")),
        Arguments.of("j.gt", "10", List.of("j.n42", "j.n42.0")),
        Arguments.of("j.lt", "10", List.of("j.n7")),
        Arguments.of("j.between", "5,42", List.of("j.n42", "j.n42.0", "j.n7")),
        Arguments.of("j.contains", "lph", List.of("j.salpha")),
        Arguments.of("j.startswith", "4", List.of("j.s42")),
        Arguments.of("j.regex", "^4", List.of("j.s42")),
        Arguments.of("j.eqi", "ALPHA", List.of("j.salpha")),
        Arguments.of(
            "j.nei",
            "ALPHA",
            List.of("j.btrue", "j.n42", "j.n42.0", "j.n7", "j.obj", "j.s42", "j.strue")),
        Arguments.of("j.like", "al%", List.of("j.salpha")),
        Arguments.of("m.eq", "42", List.of("m.n42", "m.n42.0", "m.s42")),
        Arguments.of("m.gt", "10", List.of("m.n42", "m.n42.0")),
        Arguments.of("characteristic.value.eq", "42", List.of("c.n42", "c.n42.0", "c.s42")),
        Arguments.of("characteristic.value.gt", "10", List.of("c.n42", "c.n42.0")),
        Arguments.of("characteristic.value.eq", "other", allOf("c")),
        Arguments.of("o.eq", "42", List.of("o.n42", "o.s42")),
        Arguments.of("j.isnotnull", "true", present("j")));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("answers")
  void aJsonTypedFieldReadsTheValueAsJson(String param, String value, List<String> rows) {
    assertThat(ids(param, value)).containsExactlyInAnyOrderElementsOf(rows);
  }

  @ParameterizedTest(name = "{0}=alpha")
  @MethodSource("orderings")
  void anOrderingOnANonNumberIsRefused(String param) {
    assertThatThrownBy(() -> ids(param, "alpha"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageStartingWith("Operator requires a number for the JSON-typed field: ");
  }

  static Stream<String> orderings() {
    return Stream.of("j.gt", "m.lte", "characteristic.value.gte");
  }

  private List<String> ids(String param, String value) {
    JsonbClause where = clauseBuilder.build(JsonTypedDomain.class, Map.of(param, new String[] {value}));
    return jdbc.sql("SELECT id FROM " + JsonTypedRow.TABLE + " WHERE " + where.sql())
        .params(where.params())
        .query(String.class)
        .list();
  }

  private static List<String> allOf(String field) {
    return STORED.keySet().stream().map(s -> field + "." + s).toList();
  }

  /** The rows of {@code field} that hold the key, a JSON null included. */
  private static List<String> present(String field) {
    return STORED.keySet().stream().filter(s -> !"abs".equals(s)).map(s -> field + "." + s).toList();
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
