package org.opentmf.query.tmf630.mongo.split;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;

/**
 * Phase (d.3) — third pipeline shape: <em>{@code $unionWith} across parent-side and
 * split-side matches to satisfy top-level {@code ||} across collections</em>.
 *
 * <p>The other two shapes ({@link ParentFirstLookupPipelineBuilder},
 * {@link ItemFirstAggregationPipelineBuilder}) handle AND-shaped compounds well.
 * OR-shaped compounds are different: the parent-side and split-side answers live in
 * two different collections and need to be <em>combined</em>, not intersected.
 * {@code $unionWith} is the Mongo primitive for that.
 *
 * <p>Shape when a parent-only conjunct exists (starts on the parent collection):
 *
 * <pre>{@code
 * db.<parentCollection>.aggregate([
 *   { $match: <parent-side criteria> },
 *   { $unionWith: {
 *       coll: "<childCollection_1>",
 *       pipeline: [
 *         { $match: <child_1 criteria> },
 *         { $group: { _id: "$<parentIdField>" } },
 *         { $lookup: {from:"<parentCollection>", localField:"_id",
 *                     foreignField:"_id", as:"__P__"} },
 *         { $unwind: "$__P__" },
 *         { $replaceRoot: {newRoot: "$__P__"} }
 *       ]
 *   } },
 *   // ...one more $unionWith per additional split clause...
 *   { $group: {_id: "$_id", doc: {$first: "$$ROOT"}} },
 *   { $replaceRoot: {newRoot: "$doc"} }
 * ])
 * }</pre>
 *
 * <p>Shape when there is no parent-only conjunct (starts on the first child
 * collection so the pipeline has a real first stage rather than an empty parent match):
 *
 * <pre>{@code
 * db.<firstChildCollection>.aggregate([
 *   { $match: <child_1 criteria> }, { $group }, { $lookup back to parent },
 *   { $unwind }, { $replaceRoot },
 *   { $unionWith: { coll: "<childCollection_2>", pipeline: [...same shape...] } },
 *   ...
 *   { $group: dedup }, { $replaceRoot: promote }
 * ])
 * }</pre>
 *
 * <p><strong>Transaction caveat:</strong> Mongo forbids {@code $unionWith} inside a
 * multi-document transaction. Callers running under {@code @Transactional} with a
 * {@code MongoTransactionManager} must execute this pipeline outside the transaction,
 * or use {@link ParentFirstLookupPipelineBuilder} which composes with {@code $lookup}
 * (transaction-legal) at the cost of not being able to express OR across
 * collections in a single pipeline.
 *
 * <p>Emitted via
 * {@link MongoSplitAwareFilterTranslator#translateAsUnionWithAggregation(Class, String)}
 * for filters whose decomposition combinator is
 * {@link org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Combinator#OR}.
 */
public class UnionWithAggregationPipelineBuilder {

  private static final String PARENT_STAGE_FIELD = "__tmf630Parent__";
  private static final String DEDUP_TEMP_FIELD = "__tmf630Root__";
  private static final String MATCH = "$match";
  private static final String GROUP = "$group";
  private static final String REPLACE_ROOT = "$replaceRoot";
  private static final String NEW_ROOT = "newRoot";

  private final MongoInnerPredicateTranslator childInnerTranslator;
  private final MongoInnerPredicateTranslator parentInnerTranslator;

  public UnionWithAggregationPipelineBuilder(
      MongoInnerPredicateTranslator childInnerTranslator,
      MongoInnerPredicateTranslator parentInnerTranslator) {
    this.childInnerTranslator = childInnerTranslator;
    this.parentInnerTranslator = parentInnerTranslator;
  }

  /**
   * Builds the union-with pipeline for an OR-shaped decomposition.
   *
   * @param parentCollection Mongo collection name for the parent.
   * @param parentOnlyInnerPredicate the parent-side predicate text (already unwrapped
   *     from the outer {@code $[?(...)]}), or {@link Optional#empty()} when the
   *     decomposition has no parent-only conjunct.
   * @param splitPieces one entry per split correlation, each pairing the split's
   *     metadata with its inner-predicate text.
   * @return the packaged pipeline + the collection it must be executed against
   *     (parent collection when a parent-only clause exists; the first split's child
   *     collection otherwise).
   */
  public SplitAwareAggregation build(
      String parentCollection,
      Optional<String> parentOnlyInnerPredicate,
      List<SplitPiece> splitPieces) {
    if (splitPieces == null || splitPieces.isEmpty()) {
      throw new IllegalArgumentException(
          "$unionWith shape requires at least one split correlation; got none.");
    }
    List<AggregationOperation> stages = new ArrayList<>();
    int firstSplitIndex;
    String targetCollection;

    if (parentOnlyInnerPredicate.isPresent()) {
      // Start on the parent collection with the parent-side match, then union in
      // each split-side result set.
      Document parentBson =
          parentInnerTranslator.translate(parentOnlyInnerPredicate.get()).getCriteriaObject();
      stages.add(ctx -> new Document(MATCH, parentBson));
      targetCollection = parentCollection;
      firstSplitIndex = 0;
    } else {
      // No parent-side clause — start on the first split's child collection with its
      // full inline sub-pipeline, then union any remaining splits.
      SplitPiece first = splitPieces.get(0);
      addChildSubPipelineInline(stages, parentCollection, first);
      targetCollection = first.split().childCollection();
      firstSplitIndex = 1;
    }

    for (int i = firstSplitIndex; i < splitPieces.size(); i++) {
      SplitPiece piece = splitPieces.get(i);
      Document unionWithBson = buildUnionWithFor(parentCollection, piece);
      stages.add(ctx -> new Document("$unionWith", unionWithBson));
    }

    // Dedup by _id: multiple disjuncts may match the same parent. Keep first-hit.
    stages.add(
        ctx ->
            new Document(
                GROUP,
                new Document("_id", "$_id").append(DEDUP_TEMP_FIELD, new Document("$first", "$$ROOT"))));
    stages.add(
        ctx -> new Document(REPLACE_ROOT, new Document(NEW_ROOT, "$" + DEDUP_TEMP_FIELD)));

    return new SplitAwareAggregation(Aggregation.newAggregation(stages), targetCollection);
  }

  private void addChildSubPipelineInline(
      List<AggregationOperation> stages, String parentCollection, SplitPiece piece) {
    Document childCriteria =
        childInnerTranslator.translate(piece.innerPredicate()).getCriteriaObject();
    MongoSplitCollectionMetadata split = piece.split();
    stages.add(ctx -> new Document(MATCH, childCriteria));
    stages.add(
        ctx -> new Document(GROUP, new Document("_id", "$" + split.parentIdField())));
    stages.add(
        ctx ->
            new Document(
                "$lookup",
                new Document("from", parentCollection)
                    .append("localField", "_id")
                    .append("foreignField", "_id")
                    .append("as", PARENT_STAGE_FIELD)));
    stages.add(ctx -> new Document("$unwind", "$" + PARENT_STAGE_FIELD));
    stages.add(
        ctx -> new Document(REPLACE_ROOT, new Document(NEW_ROOT, "$" + PARENT_STAGE_FIELD)));
  }

  private Document buildUnionWithFor(String parentCollection, SplitPiece piece) {
    Document childCriteria =
        childInnerTranslator.translate(piece.innerPredicate()).getCriteriaObject();
    MongoSplitCollectionMetadata split = piece.split();
    List<Document> innerStages =
        List.of(
            new Document(MATCH, childCriteria),
            new Document(GROUP, new Document("_id", "$" + split.parentIdField())),
            new Document(
                "$lookup",
                new Document("from", parentCollection)
                    .append("localField", "_id")
                    .append("foreignField", "_id")
                    .append("as", PARENT_STAGE_FIELD)),
            new Document("$unwind", "$" + PARENT_STAGE_FIELD),
            new Document(REPLACE_ROOT, new Document(NEW_ROOT, "$" + PARENT_STAGE_FIELD)));
    return new Document("coll", split.childCollection()).append("pipeline", innerStages);
  }

  /**
   * Split metadata + resolved inner-predicate text for a single decomposition
   * clause — passed as a list to {@link #build} so the builder emits one
   * {@code $unionWith} (or an inline sub-pipeline) per entry.
   */
  public record SplitPiece(MongoSplitCollectionMetadata split, String innerPredicate) {}
}
