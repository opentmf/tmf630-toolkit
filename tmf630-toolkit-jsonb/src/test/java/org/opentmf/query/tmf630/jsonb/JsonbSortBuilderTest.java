package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class JsonbSortBuilderTest {

  private final JsonbPathExtractor extractor = new JsonbPathExtractor("payload");
  private final JsonbSortBuilder builderNullsLast = new JsonbSortBuilder(extractor, true);
  private final JsonbSortBuilder builderNoNullsLast = new JsonbSortBuilder(extractor, false);

  @Test
  @DisplayName("empty / null sort produces empty JsonbClause")
  void emptySort() {
    JsonbClause c = builderNullsLast.buildOrderByClause(TmfSort.empty(), any());
    assertThat(c.sql()).isEmpty();
    assertThat(c.params()).isEmpty();
    assertThat(builderNullsLast.buildOrderByClause(null, any()).sql()).isEmpty();
  }

  @Test
  @DisplayName("plain ASC text field — no cast, ASC direction, NULLS LAST decoration")
  void plainAscText() {
    TmfSort sort = plain(Sort.Direction.ASC, "name");
    assertThat(builderNullsLast.buildOrderByClause(sort, type(String.class)).sql())
        .isEqualTo("payload->>'name' ASC NULLS LAST");
    assertThat(builderNoNullsLast.buildOrderByClause(sort, type(String.class)).sql())
        .isEqualTo("payload->>'name' ASC");
  }

  @Test
  @DisplayName("plain DESC numeric field — parenthesised cast to ::bigint, DESC direction")
  void plainDescNumeric() {
    TmfSort sort = plain(Sort.Direction.DESC, "priority");
    assertThat(builderNullsLast.buildOrderByClause(sort, type(Integer.class)).sql())
        .isEqualTo("(payload->>'priority')::bigint DESC NULLS LAST");
  }

  @Test
  @DisplayName("correlated date-time sort: the guarded read repeats the jsonpath, bound twice")
  void correlatedDateTimeSortBindsTheJsonpathTwice() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "relatedParty[role=owner].createdAt")));
    JsonbClause c = builderNullsLast.buildOrderByClause(sort, f -> OffsetDateTime.class);
    String first = "((jsonb_path_query_first(payload, ?::jsonpath)) #>> '{}')";
    assertThat(c.sql())
        .isEqualTo(
            "CASE WHEN pg_input_is_valid(" + first + ", 'timestamptz') THEN (" + first
                + ")::timestamptz END ASC NULLS LAST");
    assertThat(c.params()).hasSize(2).containsOnly(c.params().get(0));
  }

  @Test
  @DisplayName("LocalDate, LocalTime and LocalDateTime read through pg_input_is_valid as well")
  void otherDateAndTimeTypesAreGuarded() {
    assertThat(JsonbCast.DATE.read("t")).isEqualTo("CASE WHEN pg_input_is_valid(t, 'date') THEN (t)::date END");
    assertThat(JsonbCast.TIME.read("t")).isEqualTo("CASE WHEN pg_input_is_valid(t, 'time') THEN (t)::time END");
    assertThat(JsonbCast.TIMESTAMP.read("t"))
        .isEqualTo("CASE WHEN pg_input_is_valid(t, 'timestamp') THEN (t)::timestamp END");
    assertThat(JsonbCast.BIGINT.read("t")).isEqualTo("(t)::bigint");
    assertThat(JsonbCast.TEXT.read("t")).isEqualTo("t");
  }

  @Test
  @DisplayName("plain ASC OffsetDateTime — read as timestamptz only where pg_input_is_valid says so")
  void plainAscDateTime() {
    TmfSort sort = plain(Sort.Direction.ASC, "createdAt");
    assertThat(builderNullsLast.buildOrderByClause(sort, type(OffsetDateTime.class)).sql())
        .isEqualTo(
            "CASE WHEN pg_input_is_valid(payload->>'createdAt', 'timestamptz')"
                + " THEN (payload->>'createdAt')::timestamptz END ASC NULLS LAST");
  }

  @Test
  @DisplayName("multi-term sort joins per-term fragments with commas")
  void multiTerm() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "priority"),
                new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "name")));
    Function<String, Class<?>> resolver =
        field -> "priority".equals(field) ? Integer.class : String.class;
    assertThat(builderNullsLast.buildOrderByClause(sort, resolver).sql())
        .isEqualTo("(payload->>'priority')::bigint DESC NULLS LAST, payload->>'name' ASC NULLS LAST");
  }

  @Test
  @DisplayName("dotted path uses #>> extraction")
  void dottedPathExtraction() {
    TmfSort sort = plain(Sort.Direction.ASC, "customer.name");
    assertThat(builderNoNullsLast.buildOrderByClause(sort, type(String.class)).sql())
        .isEqualTo("payload#>>'{customer,name}' ASC");
  }

  @Test
  @DisplayName("Phase (b.6): SIMPLE_RICH sort term renders as jsonb_path_query_first fragment "
      + "with the SQL/JSON path bound as a JDBC param")
  void simpleRichCorrelatedSort() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristics[name=price].value")));
    JsonbClause c = builderNullsLast.buildOrderByClause(sort, type(String.class));
    assertThat(c.sql())
        .isEqualTo(
            "((jsonb_path_query_first(payload, ?::jsonpath)) #>> '{}') ASC NULLS LAST");
    assertThat(c.params())
        .containsExactly("$.characteristics[*] ? (@.name == \"price\").value");
  }

  @Test
  @DisplayName("Phase (b.6): JSONPATH sort term renders through the same shape as simple-rich")
  void jsonPathCorrelatedSort() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.DESC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristics[?(@.name=='price')].value")));
    JsonbClause c = builderNoNullsLast.buildOrderByClause(sort, type(Integer.class));
    assertThat(c.sql())
        .isEqualTo(
            "((jsonb_path_query_first(payload, ?::jsonpath)) #>> '{}')::bigint DESC");
    assertThat(c.params())
        .containsExactly("$.characteristics[*] ? (@.name == \"price\").value");
  }

  @Test
  @DisplayName("Phase (b.6): mixed plain + correlated terms compose in one fragment")
  void mixedPlainAndCorrelated() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristics[name=price].value"),
                new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "createdAt")));
    Function<String, Class<?>> resolver =
        field -> "createdAt".equals(field) ? OffsetDateTime.class : String.class;
    JsonbClause c = builderNullsLast.buildOrderByClause(sort, resolver);
    assertThat(c.sql())
        .isEqualTo(
            "((jsonb_path_query_first(payload, ?::jsonpath)) #>> '{}') ASC NULLS LAST, "
                + "CASE WHEN pg_input_is_valid(payload->>'createdAt', 'timestamptz')"
                + " THEN (payload->>'createdAt')::timestamptz END DESC NULLS LAST");
    assertThat(c.params())
        .containsExactly("$.characteristics[*] ? (@.name == \"price\").value");
  }

  @Test
  @DisplayName("paging clause emits LIMIT ? OFFSET ? with size and offset params in order")
  void pagingClauseWithPage() {
    JsonbClause c = builderNullsLast.buildPagingClause(PageRequest.of(2, 25));
    assertThat(c.sql()).isEqualTo("LIMIT ? OFFSET ?");
    assertThat(c.params()).containsExactly(25, 50L);
  }

  @Test
  @DisplayName("paging clause is empty for unpaged / null Pageable")
  void pagingClauseUnpaged() {
    assertThat(builderNullsLast.buildPagingClause(Pageable.unpaged()).sql()).isEmpty();
    assertThat(builderNullsLast.buildPagingClause(null).sql()).isEmpty();
  }

  private static TmfSort plain(Sort.Direction dir, String expr) {
    return new TmfSort(List.of(new TmfSortTerm(dir, TmfSortTerm.Kind.PLAIN, expr)));
  }

  private static Function<String, Class<?>> type(Class<?> type) {
    return field -> type;
  }

  private static Function<String, Class<?>> any() {
    return field -> String.class;
  }
}
