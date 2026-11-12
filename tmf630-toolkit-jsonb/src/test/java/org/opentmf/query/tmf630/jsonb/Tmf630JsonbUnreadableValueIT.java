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
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * A date or date-time field one of whose rows stores text no cast reads ({@code "someday"}, a
 * number out of range). Before 3.6.0 that one row made every filter and every sort on the field a
 * 500 ({@code invalid input syntax for type date}); now the value is read with
 * {@code pg_input_is_valid} first, so it is no match and sorts as a null.
 */
@SpringBootTest(
    classes = Tmf630JsonbUnreadableValueIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbUnreadableValueIT {

  private static final String TABLE = JsonbTypedRow.TABLE;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    Map.of(
            "LD1", "{\"ld\":\"2025-01-01\"}",
            "LD2", "{\"ld\":\"2025-06-30\"}",
            "LDX", "{\"ld\":\"someday\"}",
            "T1", "{\"t\":\"2025-01-01T08:00:00Z\"}",
            "T2", "{\"t\":\"2025-06-30T00:00:00Z\"}",
            "TX", "{\"t\":\"soon\"}",
            "TN", "{\"t\":1735718400}")
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
        Arguments.of("t.nin", "2025-06-30T00:00:00Z", Set.of("T1")));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("filters")
  @DisplayName("a filter on a date field with an unreadable row answers, the row no match")
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
  @DisplayName("a sort on a date or date-time field with unreadable rows answers, them as nulls")
  void sortAnswersWithUnreadableRows() {
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

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
