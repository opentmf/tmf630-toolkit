package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbGateDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbGateRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Under the opt-in {@code opentmf.tmf630.jsonb.querydsl-acceptance=true} (since 3.6.0) the JSONB
 * terminal accepts a request exactly when the QueryDSL terminal does, and refuses it with the
 * core's message: by default it answers rows where the QueryDSL terminal answers 400 (a string
 * operator on a number, {@code =~} on a non-string, a literal the declared type cannot read,
 * PostgreSQL-only jsonpath), because it never asks the declared type; the default is pinned by
 * {@link Tmf630JsonbTypeGateDefaultOffIT}. Three behaviours stay as released and are pinned here: a
 * negated group {@code !(…)} (TOOLKIT-NEGATION-MEANING-RULING-1), an unknown field inside {@code
 * filter=} (TOOLKIT-JSONB-JSONPATH-REJECT-1, under 681's {@code REJECT}), and a comparison at or
 * below a field of an open JSON type, Object, Map or JsonNode, which counts as any type.
 *
 * <p>The regex cap is lowered to 8 here, so a pattern of 11 characters being refused shows the
 * cap is read from the core's own setting (the default is 256).
 */
@SpringBootTest(
    classes = Tmf630JsonbTypeGateIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.regex.max-length=8",
      "opentmf.tmf630.attribute-filtering.on-unknown-json-path-field=REJECT",
      "opentmf.tmf630.jsonb.querydsl-acceptance=true"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbTypeGateIT {

  private static final String TABLE = JsonbGateRow.TABLE;
  private static final String STRING_OPERATOR =
      "String operator requires String or polymorphic (Object/Serializable) field: ";
  private static final String REGEX_TYPE =
      "Regex only supported for String or polymorphic (Object/Serializable) fields: ";
  private static final String INVALID = "jsonPath expression is invalid.";
  private static final String NO_OPERATOR = "Expected comparison operator in jsonPath filter.";

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "A",
            "{\"s\":\"alpha\",\"i\":5,\"d\":2.5,\"b\":true,\"e\":\"RED\",\"ld\":\"2025-01-01\","
                + "\"t\":\"2025-01-01T00:00:00Z\",\"o\":\"x5\",\"m\":{\"key\":\"x\"},"
                + "\"j\":{\"amount\":7},\"items\":[{\"b\":\"x\",\"i\":5,\"m\":{\"key\":\"x\"},"
                + "\"j\":\"y\"}],\"tags\":[\"x\"]}",
            "B",
            "{\"s\":\"bravo\",\"i\":9,\"d\":1,\"b\":false,\"e\":\"GREEN\",\"ld\":\"2025-06-30\","
                + "\"t\":\"2025-06-30T00:00:00Z\",\"o\":7,\"m\":{\"key\":\"z\"},"
                + "\"j\":{\"amount\":1},\"items\":[{\"b\":\"z\",\"i\":9,\"j\":\"n\"}],"
                + "\"tags\":[\"z\"]}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  // ---- refused, with the core's message -------------------------------------------------

  static Stream<Arguments> refused() {
    return Stream.of(
        // U1: eqi / nei on a non-string
        refusedUrl("i.eqi", "5", STRING_OPERATOR + "i"),
        refusedUrl("b.nei", "true", STRING_OPERATOR + "b"),
        refusedUrl("ld.eqi", "2025-01-01", STRING_OPERATOR + "ld"),
        refusedUrl("t.nei", "2025-01-01T00:00:00Z", STRING_OPERATOR + "t"),
        refusedUrl("e.eqi", "RED", STRING_OPERATOR + "e"),
        // U2: the substring operators on a number, also inside an array
        refusedUrl("i.contains", "5", STRING_OPERATOR + "i"),
        refusedUrl("d.startswith", "2", STRING_OPERATOR + "d"),
        refusedUrl("i.endswithi", "5", STRING_OPERATOR + "i"),
        refusedUrl("items.i.contains", "5", STRING_OPERATOR + "items.i"),
        // U3: the same family and like / regex wherever the value converts
        refusedUrl("i.like", "5", STRING_OPERATOR + "i"),
        refusedUrl("b.contains", "true", STRING_OPERATOR + "b"),
        refusedUrl("e.likei", "RED", STRING_OPERATOR + "e"),
        refusedUrl("t.startswith", "2025-01-01T00:00:00Z", STRING_OPERATOR + "t"),
        refusedUrl("b.regex", "true", REGEX_TYPE + "b"),
        refusedUrl("i.regexi", "5", REGEX_TYPE + "i"),
        // U5: the regex cap, read from the core's setting (8 here)
        refusedUrl("s.regex", "^abcdefghij", "Regex pattern too long for field: s"),
        // U6: between with three values, the core's message
        refusedUrl("i.between", "1,2,3", "between operator expects exactly 2 values for field: i"),
        // F1: =~ on a non-string, also inside an array match (querydsl-mongodb absent)
        refusedFilter("$[?(@.i =~ /^5/)]", REGEX_TYPE + "i"),
        refusedFilter("$[?(@.ld =~ /2025/i)]", REGEX_TYPE + "ld"),
        refusedFilter("$[?(@.items[?(@.i =~ /5/)])]", REGEX_TYPE + "i"),
        // F4: a literal the declared type cannot read
        refusedFilter("$[?(@.i == 'abc')]", "Field \"i\" (Integer) could not be parsed from value \"abc\"."),
        refusedFilter("$[?(@.i == 5.5)]", "Field \"i\" (Integer) could not be parsed from value \"5.5\"."),
        refusedFilter(
            "$[?(@.ld == 'someday')]",
            "Field \"ld\" (LocalDate) could not be parsed from value \"someday\"."),
        // F5: the regex cap in filter=
        refusedFilter("$[?(@.s =~ /^abcdefghij/)]", "Regex pattern too long for field: s"),
        // F6: PostgreSQL-only jsonpath
        refusedFilter("$[?(@.s.type() == 'string')]", NO_OPERATOR),
        refusedFilter("$[?(@.items.size() > 1)]", NO_OPERATOR),
        refusedFilter("$[?(@.i.double() > 1)]", NO_OPERATOR),
        refusedFilter("$[?(@.s like_regex \"^a\")]", INVALID),
        refusedFilter("$[?(@.s starts with \"a\")]", INVALID),
        refusedFilter("$[?(@.i + 1 > 2)]", INVALID),
        refusedFilter("$[?(@.i % 2 == 0)]", INVALID),
        refusedFilter("$[?(@.i > $.x)]", "Unsupported token in jsonPath filter near: $.x"),
        refusedFilter("$[?(@.s is unknown)]", INVALID),
        // T2: an ordered comparison with null
        refusedFilter("$[?(@.i > null)]", "Null literal only supports == and != operators."),
        refusedFilter("$[?(@.s >= null)]", "Null literal only supports == and != operators."),
        // T3: PostgreSQL's exists()
        refusedFilter("$[?(exists(@.s))]", INVALID),
        // T1 is held, but the gate applies INSIDE a negated group
        refusedFilter("$[?(!(@.i =~ /5/))]", REGEX_TYPE + "i"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("refused")
  void refusedWithTheCoresMessage(String param, String value, String message) {
    Map<String, String[]> params = Map.of(param, new String[] {value});
    assertThatThrownBy(() -> clauseBuilder.build(JsonbGateDomain.class, params))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageStartingWith(message);
  }

  // ---- accepted, answering as before ----------------------------------------------------

  static Stream<Arguments> accepted() {
    return Stream.of(
        // controls: what the core accepts stays accepted
        acceptedUrl("s.contains", "lph", "A"),
        acceptedUrl("o.contains", "5", "A"),
        acceptedUrl("i.eq", "5", "A"),
        acceptedUrl("s.regex", "^abcdefg"),
        acceptedUrl("s.regex", "^al", "A"),
        acceptedFilter("$[?(@.s =~ /^al/)]", "A"),
        acceptedFilter("$[?(@.o =~ /x/)]", "A"),
        acceptedFilter("$[?((@.s == 'alpha'))]", "A"),
        acceptedFilter("$[?(@.s != null)]", "A", "B"),
        // array matches stay accepted without querydsl-mongodb on the classpath
        acceptedFilter("$[?(@.items[?(@.b == 'x')])]", "A"),
        acceptedFilter("$[?(@.items[?(@.i > 1 && @.b == 'x')])]", "A"),
        // HELD (TOOLKIT-NEGATION-MEANING-RULING-1): a negated group, through an array too
        acceptedFilter("$[?(!(@.s == 'alpha'))]", "B"),
        acceptedFilter("$[?(!(@.items[?(@.b == 'x')]))]", "B"),
        // HELD (TOOLKIT-JSONB-JSONPATH-REJECT-1): an unknown field narrows to nothing, under
        // REJECT too, and is never dropped (AND keeps it)
        acceptedFilter("$[?(@.noSuch == 'x')]"),
        acceptedFilter("$[?(@.noSuch == 'x' && @.s == 'alpha')]"),
        // an open JSON type counts as any type: at or below a Map or JsonNode field, as before
        acceptedFilter("$[?(@.m == 'x')]"),
        acceptedFilter("$[?(@.m > 5)]"),
        acceptedFilter("$[?(@.m =~ /x/)]"),
        acceptedFilter("$[?(@.m.key == 'x')]", "A"),
        acceptedFilter("$[?(@.j.amount > 5)]", "A"),
        acceptedFilter("$[?(@.items[?(@.j == 'y')])]", "A"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("accepted")
  void acceptedAndAnswering(String param, String value, Set<String> expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbGateDomain.class, Map.of(param, new String[] {value}));

    assertThat(ids(where)).as(where.sql() + " " + where.params()).isEqualTo(expected);
  }

  /**
   * An Object field counts as any type: the orderings the QueryDSL terminal refuses on it (U4, F2)
   * are not refused, and keep JSONB's own answer (its ids are measured on the run, not predicted).
   */
  static Stream<Arguments> openTypeOrderings() {
    return Stream.of(
        Arguments.of("o.gt", "5"),
        Arguments.of("o.between", "1,5"),
        Arguments.of("filter", "$[?(@.o > 5)]"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("openTypeOrderings")
  void anObjectFieldCountsAsAnyType(String param, String value) {
    JsonbClause where =
        clauseBuilder.build(JsonbGateDomain.class, Map.of(param, new String[] {value}));

    assertThat(ids(where)).as(where.sql() + " " + where.params()).isNotNull();
  }

  @Test
  void theHeldFieldsAreTheOnlyComparisonsTheGateSkips() {
    // Open JSON types are left whole; their siblings in the same expression are not.
    Map<String, String[]> params =
        Map.of("filter", new String[] {"$[?(@.m == 'x' && @.i =~ /5/)]"});
    assertThatThrownBy(() -> clauseBuilder.build(JsonbGateDomain.class, params))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage(REGEX_TYPE + "i");
  }

  private Set<String> ids(JsonbClause where) {
    return Set.copyOf(
        jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
            .params(where.params())
            .query(String.class)
            .list());
  }

  private static Arguments refusedUrl(String param, String value, String message) {
    return Arguments.of(param, value, message);
  }

  private static Arguments refusedFilter(String expression, String message) {
    return Arguments.of("filter", expression, message);
  }

  private static Arguments acceptedUrl(String param, String value, String... ids) {
    return Arguments.of(param, value, Set.of(ids));
  }

  private static Arguments acceptedFilter(String expression, String... ids) {
    return Arguments.of("filter", expression, Set.of(ids));
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
