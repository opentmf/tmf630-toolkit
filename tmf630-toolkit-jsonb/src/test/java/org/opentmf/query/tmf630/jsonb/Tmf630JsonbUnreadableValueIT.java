package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Under {@code opentmf.tmf630.jsonb.cast-mode=lenient} (the opt-in this class runs with): a date,
 * date-time, number or boolean field one of whose rows stores text no cast reads ({@code
 * "someday"}, {@code "abc"}, {@code 42.0} in an Integer field, {@code "maybe"}) answers, that row
 * no match and a null in a sort, because the value is read with {@code pg_input_is_valid} first.
 * Text the cast reads ({@code "7"} in an Integer field, {@code "yes"} in a Boolean one) is read as
 * before. Under the default, {@code strict}, the same requests fail as in 3.5.2
 * ({@link Tmf630JsonbUnreadableValueDefaultsIT}).
 */
@SpringBootTest(
    classes = Tmf630JsonbUnreadableValueIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.jsonb.cast-mode=lenient"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbUnreadableValueIT {

  private static final String TABLE = JsonbTypedRow.TABLE;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;
  @Autowired private ApplicationContext context;

  @BeforeAll
  void seed() {
    seed(jdbc);
  }

  /** The rows, shared with {@link Tmf630JsonbUnreadableValueDefaultsIT}. */
  static void seed(JdbcClient jdbc) {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.ofEntries(
            Map.entry("LD1", "{\"ld\":\"2025-01-01\"}"),
            Map.entry("LD2", "{\"ld\":\"2025-06-30\"}"),
            Map.entry("LDX", "{\"ld\":\"someday\"}"),
            Map.entry("T1", "{\"t\":\"2025-01-01T08:00:00Z\"}"),
            Map.entry("T2", "{\"t\":\"2025-06-30T00:00:00Z\"}"),
            Map.entry("TX", "{\"t\":\"soon\"}"),
            Map.entry("TN", "{\"t\":1735718400}"),
            Map.entry("N5", "{\"n\":5}"),
            Map.entry("N9", "{\"n\":9}"),
            Map.entry("NS", "{\"n\":\"7\"}"),
            Map.entry("NX", "{\"n\":\"abc\"}"),
            Map.entry("ND", "{\"n\":42.0}"),
            Map.entry("B1", "{\"b\":true}"),
            Map.entry("BY", "{\"b\":\"yes\"}"),
            Map.entry("BX", "{\"b\":\"maybe\"}"))
        .forEach(
            (id, payload) ->
                jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }

  static Stream<Arguments> filters() {
    return Stream.of(
        Arguments.of("ld.gt", "2025-03-01", Set.of("LD2")),
        Arguments.of("ld", "2025-01-01", Set.of("LD1")),
        Arguments.of("ld.ne", "2025-01-01", Set.of("LD2")),
        Arguments.of("ld.between", "2024-12-01,2025-02-01", Set.of("LD1")),
        Arguments.of("ld.in", "2025-01-01,2025-06-30", Set.of("LD1", "LD2")),
        Arguments.of("t.lt", "2025-03-01T00:00:00Z", Set.of("T1")),
        Arguments.of("t.gte", "2025-01-01T08:00:00Z", Set.of("T1", "T2")),
        Arguments.of("t.nin", "2025-06-30T00:00:00Z", Set.of("T1")),
        Arguments.of("n.gt", "6", Set.of("N9", "NS")),
        Arguments.of("n.lt", "6", Set.of("N5")),
        Arguments.of("n.between", "5,8", Set.of("N5", "NS")),
        Arguments.of("n", "9", Set.of("N9")),
        Arguments.of("b", "true", Set.of("B1", "BY")),
        Arguments.of("b.ne", "true", Set.of()));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("filters")
  @DisplayName("lenient: a typed filter with an unreadable row answers, the row no match")
  void filterAnswersWithAnUnreadableRow(String param, String value, Set<String> expected) {
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
  @DisplayName("lenient: a typed sort with unreadable rows answers, them as nulls")
  void sortAnswersWithUnreadableRows() {
    assertThat(sortedIds("n", Sort.Direction.ASC, Integer.class, "N"))
        .startsWith("N5", "NS", "N9")
        .containsExactlyInAnyOrder("N5", "NS", "N9", "NX", "ND");
    assertThat(sortedIds("ld", Sort.Direction.ASC, LocalDate.class, "LD"))
        .containsExactly("LD1", "LD2", "LDX");
    assertThat(sortedIds("t", Sort.Direction.DESC, OffsetDateTime.class, "T"))
        .startsWith("TN", "TX")
        .endsWith("T2", "T1");
  }

  private List<String> sortedIds(
      String field, Sort.Direction direction, Class<?> type, String prefix) {
    TmfSort sort = new TmfSort(List.of(new TmfSortTerm(direction, TmfSortTerm.Kind.PLAIN, field)));
    JsonbClause order = sortBuilder.buildOrderByClause(sort, f -> type);
    return jdbc.sql(
            "SELECT id FROM " + TABLE + " WHERE id LIKE ? ORDER BY " + order.sql() + ", id")
        .param(prefix + "%")
        .query(String.class)
        .list();
  }

  @Test
  @DisplayName("lenient: the PostgreSQL 16 check runs (and passes on 18)")
  void thePostgres16CheckRunsUnderLenient() {
    assertThat(context.getBeansOfType(Tmf630JsonbPostgresVersionCheck.class)).hasSize(1);
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
