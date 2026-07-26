package org.opentmf.query.tmf630.jsonb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Function;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Phase (b.5) — end-to-end JSONB query executor. Composes a filter clause (built by
 * {@link JsonbPredicateFactory}), an ORDER BY clause (built by {@link JsonbSortBuilder}),
 * and a paging tail into a full {@code SELECT payload FROM &lt;table&gt; WHERE ...
 * ORDER BY ... LIMIT ? OFFSET ?} query, runs it via {@link JdbcClient}, and
 * deserializes each result row's payload into the domain model via
 * {@link ObjectMapper}. A companion {@code COUNT(*)} query supplies the total for the
 * returned {@link PageImpl}.
 *
 * <p>Consumers typically hand-build the {@link JsonbClause} for the WHERE part; the
 * URL-parameter-to-{@code JsonbClause} bridge (that would let a controller use
 * {@code @QuerydslPredicate}-style transparent binding) lives in a later sub-milestone.
 * This executor is the runtime target the bridge will call.
 *
 * <p>Bean-transparent: registered by {@link Tmf630JsonbAutoConfiguration} when the JSONB
 * module is on the classpath and there is at least one {@code @Tmf630JsonbBacked}
 * entity present.
 */
public class Tmf630JsonbFilterExecutor {

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final JsonbEntityRegistry registry;
  private final JsonbSortBuilder sortBuilder;

  public Tmf630JsonbFilterExecutor(
      JdbcClient jdbcClient,
      ObjectMapper objectMapper,
      JsonbEntityRegistry registry,
      JsonbSortBuilder sortBuilder) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.registry = registry;
    this.sortBuilder = sortBuilder;
  }

  /**
   * Runs a paged, filtered, sorted query against the JSONB-backed entity identified by
   * {@code domainType}. The WHERE fragment is provided by the caller (built via
   * {@link JsonbPredicateFactory}); may be {@code null} or {@link JsonbClause#alwaysTrue()}
   * for an unfiltered query. Sort terms are translated per {@link JsonbSortBuilder}; the
   * {@code fieldTypeResolver} maps each sort field name to the Java type of the
   * corresponding domain-model field for cast selection.
   */
  public <T> Page<T> findAll(
      Class<T> domainType,
      JsonbClause where,
      TmfSort sort,
      Pageable pageable,
      Function<String, Class<?>> fieldTypeResolver) {
    JsonbEntityMetadata metadata =
        registry
            .forDomainType(domainType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for domain type: "
                            + domainType.getName()));

    String tableName = metadata.tableName();
    String payloadColumn = metadata.payloadField();
    JsonbClause effectiveWhere = where == null ? JsonbClause.alwaysTrue() : where;
    boolean hasWhere =
        !"TRUE".equals(effectiveWhere.sql()) && !effectiveWhere.sql().isEmpty();

    JsonbClause orderBy = sortBuilder.buildOrderByClause(sort, fieldTypeResolver);
    JsonbClause paging = sortBuilder.buildPagingClause(pageable);

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT ").append(payloadColumn).append(" FROM ").append(tableName);
    if (hasWhere) {
      sql.append(" WHERE ").append(effectiveWhere.sql());
    }
    if (!orderBy.sql().isEmpty()) {
      sql.append(" ORDER BY ").append(orderBy.sql());
    }
    if (!paging.sql().isEmpty()) {
      sql.append(" ").append(paging.sql());
    }

    // Param binding order matches emission order: WHERE first, then ORDER BY
    // (correlated-sort terms bind their SQL/JSON path here), then LIMIT/OFFSET.
    JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString());
    int paramIndex = 1;
    for (Object param : effectiveWhere.params()) {
      statement = statement.param(paramIndex++, param);
    }
    for (Object param : orderBy.params()) {
      statement = statement.param(paramIndex++, param);
    }
    for (Object param : paging.params()) {
      statement = statement.param(paramIndex++, param);
    }
    List<T> rows =
        statement.query((rs, rowNum) -> deserialize(rs.getString(payloadColumn), domainType)).list();

    long total = runCount(tableName, effectiveWhere, hasWhere);
    Pageable effectivePageable = pageable == null ? Pageable.unpaged() : pageable;
    return new PageImpl<>(rows, effectivePageable, total);
  }

  private long runCount(String tableName, JsonbClause where, boolean hasWhere) {
    StringBuilder countSql =
        new StringBuilder("SELECT COUNT(*) FROM ").append(tableName);
    if (hasWhere) {
      countSql.append(" WHERE ").append(where.sql());
    }
    JdbcClient.StatementSpec countStmt = jdbcClient.sql(countSql.toString());
    int paramIndex = 1;
    for (Object param : where.params()) {
      countStmt = countStmt.param(paramIndex++, param);
    }
    Long total = countStmt.query(Long.class).single();
    return total == null ? 0L : total;
  }

  private <T> T deserialize(String payloadJson, Class<T> domainType) {
    if (payloadJson == null) {
      return null;
    }
    try {
      return objectMapper.readValue(payloadJson, domainType);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to deserialize JSONB payload into " + domainType.getName(), e);
    }
  }
}
