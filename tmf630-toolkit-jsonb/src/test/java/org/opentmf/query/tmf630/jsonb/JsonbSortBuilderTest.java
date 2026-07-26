package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;
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
  @DisplayName("empty / null sort produces empty order-by clause")
  void emptySort() {
    assertThat(builderNullsLast.buildOrderByClause(TmfSort.empty(), any())).isEmpty();
    assertThat(builderNullsLast.buildOrderByClause(null, any())).isEmpty();
  }

  @Test
  @DisplayName("plain ASC text field — no cast, ASC direction, NULLS LAST decoration")
  void plainAscText() {
    TmfSort sort = plain(Sort.Direction.ASC, "name");
    assertThat(builderNullsLast.buildOrderByClause(sort, type(String.class)))
        .isEqualTo("payload->>'name' ASC NULLS LAST");
    assertThat(builderNoNullsLast.buildOrderByClause(sort, type(String.class)))
        .isEqualTo("payload->>'name' ASC");
  }

  @Test
  @DisplayName("plain DESC numeric field — parenthesised cast to ::bigint, DESC direction")
  void plainDescNumeric() {
    TmfSort sort = plain(Sort.Direction.DESC, "priority");
    assertThat(builderNullsLast.buildOrderByClause(sort, type(Integer.class)))
        .isEqualTo("(payload->>'priority')::bigint DESC NULLS LAST");
  }

  @Test
  @DisplayName("plain ASC OffsetDateTime — casts to ::timestamptz")
  void plainAscDateTime() {
    TmfSort sort = plain(Sort.Direction.ASC, "createdAt");
    assertThat(builderNullsLast.buildOrderByClause(sort, type(OffsetDateTime.class)))
        .isEqualTo("(payload->>'createdAt')::timestamptz ASC NULLS LAST");
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
    assertThat(builderNullsLast.buildOrderByClause(sort, resolver))
        .isEqualTo("(payload->>'priority')::bigint DESC NULLS LAST, payload->>'name' ASC NULLS LAST");
  }

  @Test
  @DisplayName("dotted path uses #>> extraction")
  void dottedPathExtraction() {
    TmfSort sort = plain(Sort.Direction.ASC, "customer.name");
    assertThat(builderNoNullsLast.buildOrderByClause(sort, type(String.class)))
        .isEqualTo("payload#>>'{customer,name}' ASC");
  }

  @Test
  @DisplayName("SIMPLE_RICH or JSONPATH sort term is rejected on JSONB Phase b.4")
  void rejectsNonPlainTerms() {
    TmfSort rich =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC, TmfSortTerm.Kind.SIMPLE_RICH, "arr[k=v].leaf")));
    assertThatThrownBy(() -> builderNullsLast.buildOrderByClause(rich, any()))
        .isInstanceOf(TmfPagingException.class)
        .hasMessageContaining("Phase b.6");
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
