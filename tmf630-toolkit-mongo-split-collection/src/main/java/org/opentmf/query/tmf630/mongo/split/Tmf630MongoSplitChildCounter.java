package org.opentmf.query.tmf630.mongo.split;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Mongo mirror of {@code Tmf630JsonbSplitChildCounter}. When a parent GET returns
 * a merged view capped at {@code maxInlineItems}, this helper reports the true
 * child count per parent so callers can emit an {@code X-Total-Count-<ChildName>}
 * header (or an equivalent truncation signal).
 *
 * <p>Ships two shapes:
 *
 * <ul>
 *   <li>{@link #count(Class, String, Class)} — single parent id, returns
 *       {@link ChildCountInfo} with true count + inline cap.
 *   <li>{@link #countAll(Class, Collection, Class)} — batch variant using a single
 *       aggregation ({@code $match} then {@code $group} on parent id).
 * </ul>
 *
 * <p>Not wired into the executor or advice on purpose — header emission is a
 * controller-layer concern.
 */
public class Tmf630MongoSplitChildCounter {

  private final MongoOperations mongoOperations;
  private final MongoSplitEntityRegistry registry;

  public Tmf630MongoSplitChildCounter(
      MongoOperations mongoOperations, MongoSplitEntityRegistry registry) {
    this.mongoOperations = mongoOperations;
    this.registry = registry;
  }

  public ChildCountInfo count(Class<?> parentType, String parentId, Class<?> childType) {
    if (parentId == null || parentId.isEmpty()) {
      throw new IllegalArgumentException("parentId must not be blank");
    }
    MongoSplitCollectionMetadata split = resolveSplit(parentType, childType);
    long count =
        mongoOperations.count(
            new Query(Criteria.where(split.parentIdField()).is(parentId)),
            split.childCollection());
    return new ChildCountInfo(count, split.maxInlineItems());
  }

  public Map<String, Long> countAll(
      Class<?> parentType, Collection<String> parentIds, Class<?> childType) {
    if (parentIds == null || parentIds.isEmpty()) return Map.of();
    MongoSplitCollectionMetadata split = resolveSplit(parentType, childType);

    AggregationOperation match =
        ctx ->
            new Document(
                "$match",
                new Document(split.parentIdField(), new Document("$in", List.copyOf(parentIds))));
    AggregationOperation group =
        ctx ->
            new Document(
                "$group",
                new Document("_id", "$" + split.parentIdField())
                    .append("cnt", new Document("$sum", 1)));

    List<Document> results =
        mongoOperations
            .aggregate(Aggregation.newAggregation(match, group), split.childCollection(), Document.class)
            .getMappedResults();
    Map<String, Long> counts = new HashMap<>();
    for (Document row : results) {
      // _id may be any BSON type; treat as string for header emission.
      Object id = row.get("_id");
      Number cnt = row.get("cnt", Number.class);
      counts.put(id == null ? null : id.toString(), cnt == null ? 0L : cnt.longValue());
    }
    Map<String, Long> out = new HashMap<>();
    for (String id : parentIds) {
      out.put(id, counts.getOrDefault(id, 0L));
    }
    return out;
  }

  private MongoSplitCollectionMetadata resolveSplit(Class<?> parentType, Class<?> childType) {
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parentType)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Type " + parentType.getName() + " is not @Tmf630MongoSplitBacked"));
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

  /**
   * Small carrier for a single-parent count query: true child count + the
   * executor's {@code maxInlineItems} cap; caller decides whether the inline
   * response was truncated via {@link #truncated()}.
   */
  public record ChildCountInfo(long trueCount, int maxInlineItems) {
    public boolean truncated() {
      return trueCount > maxInlineItems;
    }
  }
}
