package org.opentmf.query.tmf630.mongo.split;

import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Strategy for translating the inner JsonPath predicate of a split correlation (e.g.
 * {@code @.state == 'PENDING'} inside {@code $[?(@.items[?(@.state == 'PENDING')])]})
 * into a Mongo {@link Criteria} suitable for querying the child collection.
 *
 * <p>Kept as a strategy so consumers with richer grammar needs (regex,
 * existence, functions, array indexing) can plug in a fuller implementation
 * without forking the split-aware translator. The default
 * {@link SimpleMongoInnerPredicateTranslator} supports the common subset:
 * equality, comparison operators, boolean {@code &&}/{@code ||}, string and
 * numeric literals, dotted field paths.
 */
@FunctionalInterface
public interface MongoInnerPredicateTranslator {

  /**
   * Translates the given JsonPath inner-predicate expression into a Mongo
   * {@link Criteria}.
   *
   * @param expression the raw text of the inner predicate, without the surrounding
   *     {@code ?(...)} wrapper
   * @return a criterion that matches child documents satisfying the expression
   * @throws org.opentmf.query.tmf630.filtering.TmfFilteringException if the expression
   *     is malformed or uses an unsupported grammar feature
   */
  Criteria translate(String expression);
}
