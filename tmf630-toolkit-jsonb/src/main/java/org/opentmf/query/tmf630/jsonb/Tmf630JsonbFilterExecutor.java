package org.opentmf.query.tmf630.jsonb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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

  private static final String WHERE = " WHERE ";

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
      sql.append(WHERE).append(effectiveWhere.sql());
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
        statement
            .query(
                (rs, rowNum) ->
                    mergeAndDeserialize(rs.getString(payloadColumn), domainType, metadata))
            .list();

    long total = runCount(tableName, effectiveWhere, hasWhere);
    Pageable effectivePageable = pageable == null ? Pageable.unpaged() : pageable;
    return new PageImpl<>(rows, effectivePageable, total);
  }

  private long runCount(String tableName, JsonbClause where, boolean hasWhere) {
    StringBuilder countSql =
        new StringBuilder("SELECT COUNT(*) FROM ").append(tableName);
    if (hasWhere) {
      countSql.append(WHERE).append(where.sql());
    }
    JdbcClient.StatementSpec countStmt = jdbcClient.sql(countSql.toString());
    int paramIndex = 1;
    for (Object param : where.params()) {
      countStmt = countStmt.param(paramIndex++, param);
    }
    Long total = countStmt.query(Long.class).single();
    return total == null ? 0L : total;
  }

  /**
   * Deserializes a row's payload, merging in any split-collection children per §3.4 of
   * the JSONB design doc. When the domain type declares no
   * {@link Tmf630JsonbSplitCollection} fields (the common case), this is equivalent to
   * a plain {@code ObjectMapper.readValue}. When splits ARE declared, the parent
   * payload is parsed as a tree, each split-collection field is fetched from its child
   * table (capped at {@code maxInlineItems}, ordered by {@code item_order}), and the
   * assembled tree is materialised into the domain type — one merged JSON per parent
   * row from the client's perspective.
   */
  private <T> T mergeAndDeserialize(
      String payloadJson, Class<T> domainType, JsonbEntityMetadata metadata) {
    if (payloadJson == null) {
      return null;
    }
    try {
      if (metadata.splitCollections().isEmpty()) {
        return objectMapper.readValue(payloadJson, domainType);
      }
      JsonNode parentNode = objectMapper.readTree(payloadJson);
      String parentId = parentNode.path("id").asText(null);
      if (parentId != null && parentNode.isObject()) {
        ObjectNode parentObj = (ObjectNode) parentNode;
        for (JsonbSplitCollectionMetadata split : metadata.splitCollections()) {
          parentObj.set(split.fieldName(), fetchChildrenAsArrayNode(split, parentId));
        }
      }
      return objectMapper.treeToValue(parentNode, domainType);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to deserialize JSONB payload into " + domainType.getName(), e);
    }
  }

  /**
   * Fetches up to {@code maxInlineItems} child rows for one parent, ordered by
   * {@code item_order}. Returns a Jackson {@link ArrayNode} — a JsonNode
   * representation of the merged payload's split-collection field.
   */
  private ArrayNode fetchChildrenAsArrayNode(
      JsonbSplitCollectionMetadata split, String parentId) throws IOException {
    String sql =
        "SELECT "
            + split.payloadColumn()
            + " FROM "
            + split.childTable()
            + WHERE
            + split.parentIdColumn()
            + " = ? ORDER BY "
            + split.itemOrderColumn()
            + " LIMIT "
            + split.maxInlineItems();
    List<String> childPayloads =
        jdbcClient.sql(sql).param(1, parentId).query(String.class).list();
    ArrayNode arr = objectMapper.createArrayNode();
    for (String childJson : childPayloads) {
      arr.add(objectMapper.readTree(childJson));
    }
    return arr;
  }
}
