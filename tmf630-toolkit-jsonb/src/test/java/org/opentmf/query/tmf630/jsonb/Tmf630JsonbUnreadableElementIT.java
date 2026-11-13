package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbElementDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbElementRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Under the DEFAULT {@code cast-mode=strict}, a URL operator on a typed field INSIDE an array (an
 * array of arrays too) skips an element it cannot read: the request answers, for the elements it
 * reads. 3.5.2 read nothing through an array (every such request answered no row), so an element
 * no cast reads must not fail a request that used to answer. Only a value in its JSON or ISO form
 * is read there ({@link JsonbCast#readCanonical}); row {@code Y} holds forms the cast would also
 * read ({@code "yes"}, {@code 20250101}, {@code "+7"}), which are skipped. The top level keeps
 * 3.5.2's failure ({@link Tmf630JsonbUnreadableValueDefaultsIT}). Every typed cast is covered:
 * bigint (Integer, Long), numeric (BigDecimal, Double), boolean, date, time, timestamp and
 * timestamptz; {@link Tmf630JsonbPostgres15IT} asks the same requests on PostgreSQL 15.
 */
@SpringBootTest(
    classes = Tmf630JsonbUnreadableElementIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbUnreadableElementIT {

  private static final String TABLE = JsonbElementRow.TABLE;

  static final String READABLE =
      "{\"n\":5,\"l\":9007199254740993,\"d\":2.50,\"f\":0.1,\"b\":true,\"ld\":\"2025-01-01\","
          + "\"t\":\"2025-01-01T08:00:00Z\",\"lt\":\"08:00:00\",\"ldt\":\"2025-01-01T08:00:00\"}";

  /** One value per field that no cast reads (an out-of-range Long, an impossible date-time). */
  static final String UNREADABLE =
      "{\"n\":\"abc\",\"l\":9223372036854775808,\"d\":\"abc\",\"f\":\"abc\",\"b\":\"maybe\","
          + "\"ld\":\"someday\",\"t\":1735718400,\"lt\":\"noonish\","
          + "\"ldt\":\"2025-02-30T08:00:00\"}";

  /** Forms the cast reads but the default skips inside an array: not the JSON or ISO form. */
  static final String OTHER_FORMS = "{\"n\":\"+7\",\"b\":\"yes\",\"ld\":20250101}";

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @BeforeAll
  void seed() {
    seed(jdbc);
  }

  static void seed(JdbcClient jdbc) {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "R", "{\"items\":[" + READABLE + "," + UNREADABLE + "]}",
            "X", "{\"items\":[" + UNREADABLE + "]}",
            "Y", "{\"items\":[" + OTHER_FORMS + "]}",
            "O",
                "{\"outer\":[{\"inner\":[" + UNREADABLE + "]},{\"inner\":[" + READABLE
                    + "]}]}")
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  static Stream<Arguments> filters() {
    Set<String> r = Set.of("R");
    return Stream.of(
        Arguments.of("items.n", "5", r),
        Arguments.of("items.n.gt", "4", r),
        Arguments.of("items.n.ne", "6", r),
        Arguments.of("items.n.in", "5,7", r),
        Arguments.of("items.n.nin", "6", r),
        Arguments.of("items.l", "9007199254740993", r),
        Arguments.of("items.l.gt", "1", r),
        Arguments.of("items.d.lt", "3", r),
        Arguments.of("items.f.gt", "0", r),
        Arguments.of("items.b", "true", r),
        Arguments.of("items.b.ne", "false", r),
        Arguments.of("items.ld", "2025-01-01", r),
        Arguments.of("items.ld.between", "2024-12-01,2025-02-01", r),
        Arguments.of("items.t.lt", "2025-06-01T00:00:00Z", r),
        Arguments.of("items.lt.gt", "07:00:00", r),
        Arguments.of("items.ldt.lt", "2025-06-01T00:00:00", r),
        Arguments.of("outer.inner.n", "5", Set.of("O")),
        Arguments.of("outer.inner.ld", "2025-01-01", Set.of("O")),
        Arguments.of("outer.inner.t.gte", "2025-01-01T08:00:00Z", Set.of("O")));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("filters")
  void anUnreadableElementIsSkippedByDefault(String param, String value, Set<String> expected) {
    assertThat(ids(param, value)).as(param + "=" + value).isEqualTo(expected);
  }

  private Set<String> ids(String param, String value) {
    JsonbClause where =
        clauseBuilder.build(JsonbElementDomain.class, Map.of(param, new String[] {value}));
    return Set.copyOf(
        jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
            .params(where.params())
            .query(String.class)
            .list());
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
