package org.opentmf.query.tmf630.jsonb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Phase (c.5) — abstract base class for the split-collection sub-endpoint. A developer
 * declares a thin subclass like:
 *
 * <pre>{@code
 * @RestController
 * @RequestMapping("/productOrder/{parentId}/productOrderItem")
 * public class ProductOrderItemSubController
 *     extends Tmf630JsonbSubResourceController<ProductOrderItem, ProductOrder> {
 *   public ProductOrderItemSubController(
 *       JdbcClient jdbcClient, ObjectMapper objectMapper, JsonbEntityRegistry registry) {
 *     super(jdbcClient, objectMapper, registry, ProductOrderItem.class, ProductOrder.class);
 *   }
 * }
 * }</pre>
 *
 * <p>Base class provides {@code GET /} (paged list of children scoped to
 * {@code {parentId}}) and {@code GET /{itemId}}. POST/PATCH/DELETE land in a follow-up
 * cut when the surgical write path is defined.
 *
 * <p>This class carries the {@code @GetMapping} annotations directly; the concrete
 * subclass carries {@code @RestController} and {@code @RequestMapping} — Spring MVC
 * inherits handler methods from a superclass. Type parameters {@code C} (child) and
 * {@code P} (parent) are passed explicitly at construction to avoid the reflection
 * cost of resolving generic bounds at every request.
 */
public abstract class Tmf630JsonbSubResourceController<C, P> {

  protected final JdbcClient jdbcClient;
  protected final ObjectMapper objectMapper;
  protected final JsonbEntityRegistry registry;
  protected final Class<C> childType;
  protected final Class<P> parentType;

  protected Tmf630JsonbSubResourceController(
      JdbcClient jdbcClient,
      ObjectMapper objectMapper,
      JsonbEntityRegistry registry,
      Class<C> childType,
      Class<P> parentType) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.registry = registry;
    this.childType = childType;
    this.parentType = parentType;
  }

  /**
   * {@code GET /} — paged list of children for one parent, ordered by {@code item_order}.
   * The client obtains the true total via the standard {@code X-Total-Count} header
   * emitted by the toolkit's {@code @Tmf630Response} advice on the concrete controller,
   * or by inspecting {@link Page#getTotalElements()} directly if consuming the Page
   * return type.
   */
  @GetMapping
  public Page<C> listChildren(@PathVariable("parentId") String parentId, Pageable pageable) {
    JsonbSplitCollectionMetadata split = requireSplitMetadata();
    long total = countChildren(split, parentId);
    Pageable effectivePageable = pageable == null ? Pageable.unpaged() : pageable;
    if (total == 0L) {
      return new PageImpl<>(List.of(), effectivePageable, 0L);
    }
    List<C> rows = fetchChildren(split, parentId, effectivePageable);
    return new PageImpl<>(rows, effectivePageable, total);
  }

  /**
   * {@code GET /{itemId}} — returns the child with the given id under the given parent,
   * or {@code 404 Not Found} if no such child exists.
   */
  @GetMapping("/{itemId}")
  public ResponseEntity<C> getChild(
      @PathVariable("parentId") String parentId, @PathVariable("itemId") String itemId) {
    JsonbSplitCollectionMetadata split = requireSplitMetadata();
    Optional<String> payloadJson =
        jdbcClient
            .sql(
                "SELECT "
                    + split.payloadColumn()
                    + " FROM "
                    + split.childTable()
                    + " WHERE "
                    + split.parentIdColumn()
                    + " = ? AND "
                    + split.itemIdColumn()
                    + " = ?")
            .param(1, parentId)
            .param(2, itemId)
            .query(String.class)
            .optional();
    return payloadJson
        .map(this::deserializeChild)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private JsonbSplitCollectionMetadata requireSplitMetadata() {
    JsonbEntityMetadata parentMetadata =
        registry
            .forDomainType(parentType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for parent domain: "
                            + parentType.getName()));
    return parentMetadata.splitCollections().stream()
        .filter(s -> s.childType().equals(childType))
        .findFirst()
        .orElseThrow(
            () ->
                new TmfFilteringException(
                    "No @Tmf630JsonbSplitCollection on "
                        + parentType.getSimpleName()
                        + " for child type "
                        + childType.getName()));
  }

  private long countChildren(JsonbSplitCollectionMetadata split, String parentId) {
    Long total =
        jdbcClient
            .sql(
                "SELECT COUNT(*) FROM "
                    + split.childTable()
                    + " WHERE "
                    + split.parentIdColumn()
                    + " = ?")
            .param(1, parentId)
            .query(Long.class)
            .single();
    return total == null ? 0L : total;
  }

  private List<C> fetchChildren(
      JsonbSplitCollectionMetadata split, String parentId, Pageable pageable) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(split.payloadColumn())
            .append(" FROM ")
            .append(split.childTable())
            .append(" WHERE ")
            .append(split.parentIdColumn())
            .append(" = ? ORDER BY ")
            .append(split.itemOrderColumn());
    JdbcClient.StatementSpec statement;
    if (pageable.isPaged()) {
      sql.append(" LIMIT ? OFFSET ?");
      statement =
          jdbcClient
              .sql(sql.toString())
              .param(1, parentId)
              .param(2, pageable.getPageSize())
              .param(3, pageable.getOffset());
    } else {
      statement = jdbcClient.sql(sql.toString()).param(1, parentId);
    }
    return statement.query((rs, rowNum) -> deserializeChild(rs.getString(split.payloadColumn())))
        .list();
  }

  private C deserializeChild(String payloadJson) {
    if (payloadJson == null) {
      return null;
    }
    try {
      return objectMapper.readValue(payloadJson, childType);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to deserialize split-child payload into " + childType.getName(), e);
    }
  }
}
