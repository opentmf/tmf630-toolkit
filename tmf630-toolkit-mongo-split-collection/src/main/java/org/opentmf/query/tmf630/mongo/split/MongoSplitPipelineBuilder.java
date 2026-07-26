package org.opentmf.query.tmf630.mongo.split;

import org.springframework.data.mongodb.core.aggregation.Aggregation;

/**
 * Strategy for turning a {@code filter=} JsonPath expression that references a
 * split-collection field into a single-round-trip Mongo {@link Aggregation} pipeline
 * on the parent collection. The pipeline returns exactly the parents that satisfy the
 * split-side predicate.
 *
 * <p>Implementations correspond to the three shapes documented in Phase (d.3) of the
 * V3 roadmap:
 * <ul>
 *   <li><strong>Parent-first with {@code $lookup}</strong> — this MVP cut's default
 *       ({@link ParentFirstLookupPipelineBuilder}). Efficient when the parent set is
 *       already narrow or when the split filter is highly selective.
 *   <li><strong>Item-first with {@code $group + $lookup back}</strong> — reserved for
 *       a follow-up when the parent set is large and the child filter is very
 *       selective; item-first avoids scanning parents that have no matching child.
 *   <li><strong>{@code $unionWith} fallback</strong> — reserved for a follow-up when
 *       the pipeline must run outside a transaction and needs to compose across
 *       collections without a {@code $lookup}.
 * </ul>
 */
@FunctionalInterface
public interface MongoSplitPipelineBuilder {

  /**
   * Builds the aggregation pipeline that, when run against the parent collection,
   * returns parents matching the split correlation encoded in {@code inner}.
   *
   * @param split the metadata for the referenced split collection
   * @param innerRaw the inner JsonPath predicate text (without the surrounding
   *     {@code ?(...)} wrapper), to be translated into a child-side criteria
   * @return the aggregation pipeline the caller can execute via
   *     {@code MongoOperations.aggregate(...)} against the parent collection
   */
  Aggregation build(MongoSplitCollectionMetadata split, String innerRaw);
}
