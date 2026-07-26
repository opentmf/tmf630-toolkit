package org.opentmf.query.tmf630.mongo.split;

import org.springframework.data.mongodb.core.aggregation.Aggregation;

/**
 * Packaged aggregation + target-collection pair returned by
 * {@link MongoSplitAwareFilterTranslator#translateAsItemFirstAggregation(Class, String)}
 * — needed because the item-first pipeline shape targets the child collection rather
 * than the parent, so the caller can't hard-code the target the way parent-first
 * consumers do.
 *
 * <p>Callers execute the packaged aggregation with:
 *
 * <pre>{@code
 * SplitAwareAggregation packaged = translator.translateAsItemFirstAggregation(...);
 * List<Parent> matches =
 *     mongoOperations
 *         .aggregate(packaged.pipeline(), packaged.targetCollection(), Parent.class)
 *         .getMappedResults();
 * }</pre>
 */
public record SplitAwareAggregation(Aggregation pipeline, String targetCollection) {}
