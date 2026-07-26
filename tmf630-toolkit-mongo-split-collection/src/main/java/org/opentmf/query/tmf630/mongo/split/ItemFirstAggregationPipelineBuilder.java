package org.opentmf.query.tmf630.mongo.split;

import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;

/**
 * Phase (d.3) alternative pipeline shape: <em>item-first — start on the child
 * collection, project up to parent docs via {@code $lookup}</em>. Complements the
 * default {@link ParentFirstLookupPipelineBuilder}.
 *
 * <p>Emits a pipeline of the shape:
 *
 * <pre>{@code
 * [
 *   { $match: <inner child-criteria> },
 *   { $group: { _id: "$<parentIdField>" } },
 *   { $lookup: {
 *       from: "<parentCollection>",
 *       localField: "_id",
 *       foreignField: "_id",
 *       as: "__tmf630Parent__"
 *   } },
 *   { $unwind: "$__tmf630Parent__" },
 *   { $replaceRoot: { newRoot: "$__tmf630Parent__" } }
 * ]
 * }</pre>
 *
 * <p>This pipeline is executed against the <strong>child</strong> collection (not the
 * parent). Callers using this builder must run
 * {@code mongoOperations.aggregate(pipeline, split.childCollection(), ParentType.class)}
 * — or use
 * {@link MongoSplitAwareFilterTranslator#translateAsItemFirstAggregation(Class, String)}
 * which packages the pipeline together with its target collection.
 *
 * <p>Use item-first over parent-first when the parent set is large and the child
 * filter is very selective. Item-first scans matching child docs (typically a small
 * fraction), groups them by parent id, then joins to the parent — avoids visiting
 * every parent doc, which parent-first with {@code $lookup} must do.
 *
 * <p><strong>MVP scope for this cut:</strong> supports exactly one split correlation
 * with no parent-only clauses. Compound filters (parent + split, or multiple splits)
 * must use the parent-first path via
 * {@link MongoSplitAwareFilterTranslator#translateAsPipeline(Class, String)}. The
 * item-first entry point rejects them at translation time.
 */
public class ItemFirstAggregationPipelineBuilder {

  private static final String PARENT_STAGE_FIELD = "__tmf630Parent__";

  private final MongoInnerPredicateTranslator innerTranslator;

  public ItemFirstAggregationPipelineBuilder(MongoInnerPredicateTranslator innerTranslator) {
    this.innerTranslator = innerTranslator;
  }

  /**
   * Builds the item-first pipeline that, when run against
   * {@code split.childCollection()}, yields the parent documents matching the
   * split-side correlation encoded in {@code innerRaw}.
   *
   * @param parentCollection Mongo collection name for the parent — read from
   *     {@code MongoSplitEntityMetadata.parentCollection()} at the caller.
   * @param split the metadata for the referenced split collection.
   * @param innerRaw the inner JsonPath predicate text (without the surrounding
   *     {@code ?(...)} wrapper), translated into a child-side criteria on
   *     {@code payload.<field>}.
   */
  public Aggregation build(
      String parentCollection, MongoSplitCollectionMetadata split, String innerRaw) {
    Document childCriteria = innerTranslator.translate(innerRaw).getCriteriaObject();

    AggregationOperation matchChildren = ctx -> new Document("$match", childCriteria);
    AggregationOperation groupByParent =
        ctx -> new Document("$group", new Document("_id", "$" + split.parentIdField()));
    AggregationOperation lookupParent =
        ctx ->
            new Document(
                "$lookup",
                new Document("from", parentCollection)
                    .append("localField", "_id")
                    .append("foreignField", "_id")
                    .append("as", PARENT_STAGE_FIELD));
    AggregationOperation unwindParent =
        ctx -> new Document("$unwind", "$" + PARENT_STAGE_FIELD);
    AggregationOperation replaceRootWithParent =
        ctx -> new Document("$replaceRoot", new Document("newRoot", "$" + PARENT_STAGE_FIELD));

    return Aggregation.newAggregation(
        List.of(matchChildren, groupByParent, lookupParent, unwindParent, replaceRootWithParent));
  }
}
