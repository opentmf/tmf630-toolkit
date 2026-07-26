package org.opentmf.query.tmf630.mongo.split;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Mongo mirror of {@code Tmf630JsonbSubResourceController}. Developer subclasses this,
 * annotates with {@code @RestController} and {@code @RequestMapping(...)}, and gets
 * paginated child list plus single-child lookup for free:
 *
 * <pre>{@code
 * @RestController
 * @RequestMapping("/productOrder/{parentId}/productOrderItem")
 * public class ProductOrderItemSubController
 *     extends Tmf630MongoSubResourceController<ProductOrderItem, ProductOrder> {
 *   public ProductOrderItemSubController(
 *       MongoOperations mongoOperations, MongoSplitEntityRegistry registry) {
 *     super(mongoOperations, registry, ProductOrderItem.class, ProductOrder.class);
 *   }
 * }
 * }</pre>
 *
 * <p>Handlers:
 *
 * <ul>
 *   <li>{@code GET /} — paged list of children scoped to {@code {parentId}}, ordered by
 *       {@code itemOrder}. Returns a {@link Page} with {@code totalElements} set to the
 *       count across all children (not just the current page).
 *   <li>{@code GET /{itemId}} — single child under the given parent, or 404.
 * </ul>
 *
 * <p>Reads go through raw {@link Document} + explicit payload-unwrap (via
 * {@link Tmf630MongoSplitReadMerger#deserializeChild}) rather than
 * {@code find(childType)}, so the child POJO's own {@code id} field is preserved
 * verbatim instead of being overwritten by the wrapper's {@code _id} ObjectId.
 *
 * @param <C> child type (must be a registered split child)
 * @param <P> parent type (must be {@link Tmf630MongoSplitBacked})
 */
public abstract class Tmf630MongoSubResourceController<C, P> {

  private final MongoOperations mongoOperations;
  private final MongoSplitEntityRegistry registry;
  private final Class<C> childType;
  private final Class<P> parentType;

  protected Tmf630MongoSubResourceController(
      MongoOperations mongoOperations,
      MongoSplitEntityRegistry registry,
      Class<C> childType,
      Class<P> parentType) {
    this.mongoOperations = mongoOperations;
    this.registry = registry;
    this.childType = childType;
    this.parentType = parentType;
  }

  @GetMapping
  public Page<C> listChildren(@PathVariable("parentId") String parentId, Pageable pageable) {
    MongoSplitCollectionMetadata split = resolveSplit();
    Query base = new Query(Criteria.where(split.parentIdField()).is(parentId));
    long total = mongoOperations.count(base, split.childCollection());
    Query paged =
        Query.of(base)
            .with(Sort.by(Sort.Order.asc(split.itemOrderField())))
            .skip(pageable.getOffset())
            .limit(pageable.getPageSize());
    List<Document> wrappers = mongoOperations.find(paged, Document.class, split.childCollection());
    List<C> content = new ArrayList<>(wrappers.size());
    for (Document wrapper : wrappers) {
      content.add(
          childType.cast(
              Tmf630MongoSplitReadMerger.deserializeChild(
                  mongoOperations.getConverter(), wrapper, split)));
    }
    return new PageImpl<>(content, pageable, total);
  }

  @GetMapping("/{itemId}")
  public ResponseEntity<C> getChild(
      @PathVariable("parentId") String parentId, @PathVariable("itemId") String itemId) {
    MongoSplitCollectionMetadata split = resolveSplit();
    Query q =
        new Query(
            Criteria.where(split.parentIdField())
                .is(parentId)
                .and(split.itemIdField())
                .is(itemId));
    Document wrapper = mongoOperations.findOne(q, Document.class, split.childCollection());
    if (wrapper == null) return ResponseEntity.notFound().build();
    C child =
        childType.cast(
            Tmf630MongoSplitReadMerger.deserializeChild(
                mongoOperations.getConverter(), wrapper, split));
    return ResponseEntity.ok(child);
  }

  private MongoSplitCollectionMetadata resolveSplit() {
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parentType)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Parent type "
                            + parentType.getName()
                            + " is not @Tmf630MongoSplitBacked"));
    MongoSplitCollectionMetadata split = metadata.splitByChildType(childType);
    if (split == null) {
      throw new IllegalStateException(
          "No @Tmf630MongoSplitCollection on "
              + parentType.getName()
              + " for child type "
              + childType.getName());
    }
    return split;
  }
}
