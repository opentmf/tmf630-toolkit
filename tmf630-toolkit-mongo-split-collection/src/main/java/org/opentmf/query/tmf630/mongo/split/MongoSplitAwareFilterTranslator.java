package org.opentmf.query.tmf630.mongo.split;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Mongo mirror of {@code JsonbSplitAwareFilterTranslator}. Given a JsonPath
 * {@code filter=} expression that targets a {@link Tmf630MongoSplitCollection} field,
 * returns a Mongo {@link Criteria} to be applied at the <em>parent</em> endpoint that
 * matches the parents whose split children satisfy the inner predicate.
 *
 * <p>The MVP implementation performs a two-step resolution:
 *
 * <ol>
 *   <li>Translate the inner predicate to a {@link Criteria} against the child
 *       collection using the configured {@link MongoInnerPredicateTranslator} (the
 *       default is {@link SimpleMongoInnerPredicateTranslator}).
 *   <li>Query the child collection distinct-on {@code parentIdField} to collect the
 *       set of parent ids that have at least one matching child, then return
 *       {@code Criteria.where("_id").in(<those ids>)}.
 * </ol>
 *
 * <p>This is intentionally simpler than the full {@code $lookup}-based aggregation
 * router planned for Phase (d.3) — it works today, with correct semantics, at the
 * cost of an extra distinct query per request. For workloads where that cost matters
 * the d.3 router will replace this codepath with a single-round-trip pipeline.
 *
 * <p>Supported shapes:
 *
 * <ul>
 *   <li><strong>Parent-only</strong> filter ({@code $[?(@.status == 'X')]}) — this
 *       translator returns {@code null} to signal "not a split-aware case; use your
 *       normal filter path". Callers should combine that null-return with their
 *       usual {@code filter=} translator.
 *   <li><strong>Top-level array correlation into one split field</strong>
 *       ({@code $[?(@.items[?(@.state == 'X')])]}) — routed to the child collection.
 * </ul>
 *
 * <p>Rejected with a clear message (deferred to later d.x cuts):
 *
 * <ul>
 *   <li><strong>Compound predicates mixing parent and split fields at the top level</strong>
 *       ({@code $[?(@.status == 'X' && @.items[?(...)])]}) — needs the generic
 *       predicate splitter that will land alongside c.2/c.3's full cut.
 * </ul>
 */
public class MongoSplitAwareFilterTranslator {

  private final MongoSplitEntityRegistry registry;
  private final MongoOperations mongoOperations;
  private final MongoInnerPredicateTranslator innerTranslator;
  private final MongoSplitPipelineBuilder pipelineBuilder;

  public MongoSplitAwareFilterTranslator(
      MongoSplitEntityRegistry registry,
      MongoOperations mongoOperations,
      MongoInnerPredicateTranslator innerTranslator) {
    this(
        registry,
        mongoOperations,
        innerTranslator,
        new ParentFirstLookupPipelineBuilder(innerTranslator));
  }

  public MongoSplitAwareFilterTranslator(
      MongoSplitEntityRegistry registry,
      MongoOperations mongoOperations,
      MongoInnerPredicateTranslator innerTranslator,
      MongoSplitPipelineBuilder pipelineBuilder) {
    this.registry = registry;
    this.mongoOperations = mongoOperations;
    this.innerTranslator = innerTranslator;
    this.pipelineBuilder = pipelineBuilder;
  }

  /**
   * Attempts to translate the given {@code filter=} JsonPath expression to a Mongo
   * {@link Criteria} for use at the parent endpoint.
   *
   * @return the resolved parent-side criterion, or {@code null} if the filter does
   *     not reference any split field — in that case the caller should fall back to
   *     its normal filter translator
   */
  public Criteria translate(Class<?> parentType, String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parentType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630MongoSplitBacked mapping registered for "
                            + parentType.getName()));
    if (metadata.splits().isEmpty()) return null;

    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      Pattern pattern = topLevelCorrelationPattern(split.fieldName());
      Matcher matcher = pattern.matcher(filterExpression);
      if (matcher.matches()) {
        return resolveViaChildLookup(split, matcher.group(1));
      }
    }

    // Reject compound predicates that reference any split field alongside parent
    // fields — the caller is asking for something the MVP router can't produce.
    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      String needle = "@." + split.fieldName() + "[";
      if (filterExpression.contains(needle)) {
        throw new TmfFilteringException(
            "Filter '"
                + filterExpression
                + "' references split field '"
                + split.fieldName()
                + "' outside the supported top-level array-correlation shape "
                + "'$[?(@." + split.fieldName() + "[?(...)])]'. "
                + "Split the request into a parent filter plus a sub-endpoint call, "
                + "or restructure the URL.");
      }
    }
    return null;
  }

  /**
   * Phase (d.3 + d.4) primary entry point — returns a single-round-trip
   * {@link Aggregation} pipeline on the parent collection that yields exactly the
   * parents matching the split-side predicate, or {@code null} if the filter has no
   * split reference (caller falls back to its normal filter path).
   *
   * <p>Prefer this over {@link #translate(Class, String)} when your read path can
   * accept an aggregation instead of a {@link Criteria} — one round-trip vs. two,
   * and never risks a {@code $in} list overflowing at pathological cardinalities.
   */
  public Aggregation translateAsPipeline(Class<?> parentType, String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parentType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630MongoSplitBacked mapping registered for "
                            + parentType.getName()));
    if (metadata.splits().isEmpty()) return null;

    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      Matcher matcher = topLevelCorrelationPattern(split.fieldName()).matcher(filterExpression);
      if (matcher.matches()) {
        return pipelineBuilder.build(split, matcher.group(1));
      }
    }
    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      if (filterExpression.contains("@." + split.fieldName() + "[")) {
        throw new TmfFilteringException(
            "Filter '"
                + filterExpression
                + "' references split field '"
                + split.fieldName()
                + "' outside the supported top-level array-correlation shape "
                + "'$[?(@." + split.fieldName() + "[?(...)])]'.");
      }
    }
    return null;
  }

  private Criteria resolveViaChildLookup(MongoSplitCollectionMetadata split, String innerRaw) {
    Criteria childCriteria = innerTranslator.translate(innerRaw);
    Query childQuery = new Query(childCriteria);
    List<Object> parentIds =
        new ArrayList<>(
            new HashSet<>(
                mongoOperations
                    .query(Object.class)
                    .inCollection(split.childCollection())
                    .distinct(split.parentIdField())
                    .matching(childQuery)
                    .all()));
    if (parentIds.isEmpty()) {
      // Guaranteed-empty parent set: return a criterion nothing matches.
      return Criteria.where("_id").in(List.of());
    }
    return Criteria.where("_id").in(parentIds);
  }

  private static Pattern topLevelCorrelationPattern(String splitFieldName) {
    // Matches both $[?(@.<splitField>[?(<inner>)])] and its bare wrapper form
    // [?(@.<splitField>[?(<inner>)])] (to mirror JSONB routing) via one optional-$
    // prefix. Single capture group holds <inner> — deterministic group numbering
    // regardless of matcher-internal alternation ordering.
    String pattern =
        "^\\s*\\$?\\[\\?\\(\\s*@\\."
            + Pattern.quote(splitFieldName)
            + "\\[\\?\\((.+)\\)\\]\\s*\\)\\]\\s*$";
    return Pattern.compile(pattern, Pattern.DOTALL);
  }
}
