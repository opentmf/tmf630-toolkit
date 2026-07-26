package org.opentmf.query.tmf630.mongo.split;

import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;

/**
 * Phase (d.3 + d.4) default pipeline shape: <em>parent-first with an extended
 * {@code $lookup} whose inner pipeline enforces the split correlation and matches
 * against child-side criteria in a single stage</em>.
 *
 * <p>Given a split-collection metadata block and a raw inner predicate, produces:
 *
 * <pre>{@code
 * [
 *   { $lookup: {
 *       from: "<childCollection>",
 *       let: { pId: "$_id" },
 *       pipeline: [
 *         { $match: { $expr: { $eq: ["$<parentIdField>", "$$pId"] } } },
 *         { $match: <inner child-criteria> },
 *         { $limit: 1 }
 *       ],
 *       as: "__tmf630Match__"
 *   } },
 *   { $match: { __tmf630Match__: { $ne: [] } } },
 *   { $project: { __tmf630Match__: 0 } }
 * ]
 * }</pre>
 *
 * <p>The {@code $limit: 1} inside the inner pipeline is a correctness/performance
 * lever, not a semantic constraint — we only need to know <em>whether at least one</em>
 * child matches, so the join short-circuits after the first hit per parent.
 *
 * <p>Compared to {@link MongoSplitAwareFilterTranslator}'s two-round-trip approach
 * (distinct query on child collection, then {@code _id IN (...)} on parent), this
 * shape is a single round trip and never risks the {@code $in} list overflowing at
 * pathological cardinalities.
 */
public class ParentFirstLookupPipelineBuilder implements MongoSplitPipelineBuilder {

  private static final String MATCH_ARRAY_FIELD = "__tmf630Match__";

  private final MongoInnerPredicateTranslator innerTranslator;

  public ParentFirstLookupPipelineBuilder(MongoInnerPredicateTranslator innerTranslator) {
    this.innerTranslator = innerTranslator;
  }

  @Override
  public Aggregation build(MongoSplitCollectionMetadata split, String innerRaw) {
    Document innerCriteria = innerTranslator.translate(innerRaw).getCriteriaObject();

    AggregationOperation lookup =
        ctx ->
            new Document(
                "$lookup",
                new Document("from", split.childCollection())
                    .append("let", new Document("pId", "$_id"))
                    .append(
                        "pipeline",
                        List.of(
                            new Document(
                                "$match",
                                new Document(
                                    "$expr",
                                    new Document(
                                        "$eq",
                                        List.of(
                                            "$" + split.parentIdField(), "$$pId")))),
                            new Document("$match", innerCriteria),
                            new Document("$limit", 1)))
                    .append("as", MATCH_ARRAY_FIELD));

    AggregationOperation retainMatching =
        ctx -> new Document("$match", new Document(MATCH_ARRAY_FIELD, new Document("$ne", List.of())));

    AggregationOperation stripHelper =
        ctx -> new Document("$project", new Document(MATCH_ARRAY_FIELD, 0));

    return Aggregation.newAggregation(lookup, retainMatching, stripHelper);
  }
}
