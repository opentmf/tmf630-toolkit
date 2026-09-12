package org.opentmf.query.tmf630.mongo.split;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Combinator;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Decomposition;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.SplitClauseRef;
import org.opentmf.query.tmf630.mongo.split.UnionWithAggregationPipelineBuilder.SplitPiece;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Mongo mirror of {@code JsonbSplitAwareFilterTranslator}. Given a JsonPath
 * {@code filter=} expression that references any {@link Tmf630MongoSplitCollection}
 * field, returns a parent-side {@link Criteria} (or {@link Aggregation} pipeline) that
 * enforces both the parent-only clauses and the split correlations against MongoDB.
 *
 * <p>Compound filter support is delegated to
 * {@link org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer} — the same
 * decomposer the JSONB backend uses. Each translation path (criteria form and
 * pipeline form) walks the decomposition and composes native building blocks:
 * parent-side clauses become top-level {@link Criteria}, split-side clauses become
 * either {@code _id IN (...)} sub-queries (criteria form) or {@code $lookup} stages
 * (pipeline form).
 *
 * <p>Supported input shapes:
 *
 * <ul>
 *   <li>Parent-only ({@code $[?(@.status == 'X')]}) → single parent-side criterion.
 *   <li>Single split correlation ({@code $[?(@.items[?(@.state == 'X')])]}) → single
 *       split-side criterion or {@code $lookup} pipeline.
 *   <li>Top-level {@code &&} conjunction of parent + one or more splits →
 *       {@code parentCriteria AND _id IN (...) [AND _id IN (...)]}, or the pipeline
 *       equivalent with a top-level {@code $match} plus one {@code $lookup} per split.
 * </ul>
 *
 * <p>Rejected via the decomposer with an actionable message:
 *
 * <ul>
 *   <li>Top-level {@code ||} mixing parent-side and split-side.
 *   <li>Nested boolean subgroups containing a split reference.
 * </ul>
 */
public class MongoSplitAwareFilterTranslator {

  private final MongoSplitEntityRegistry registry;
  private final MongoOperations mongoOperations;
  private final MongoInnerPredicateTranslator innerTranslator;
  private final MongoSplitPipelineBuilder pipelineBuilder;
  private final MongoInnerPredicateTranslator parentTranslator;
  private final ItemFirstAggregationPipelineBuilder itemFirstBuilder;
  private final UnionWithAggregationPipelineBuilder unionWithBuilder;

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
    // Parent-side criteria have their fields at the top level of the parent doc, not
    // under a "payload" wrapper. Instantiate a parallel translator with an empty
    // prefix for parent-only clauses coming out of the decomposer.
    this.parentTranslator =
        new SimpleMongoInnerPredicateTranslator(
            SimpleMongoInnerPredicateTranslator.PARENT_TOP_LEVEL_PREFIX);
    this.itemFirstBuilder = new ItemFirstAggregationPipelineBuilder(innerTranslator);
    this.unionWithBuilder =
        new UnionWithAggregationPipelineBuilder(innerTranslator, this.parentTranslator);
  }

  /**
   * Attempts to translate the given {@code filter=} JsonPath expression to a Mongo
   * {@link Criteria} for use at the parent endpoint. Returns {@code null} when the
   * filter has no split reference and no parent clause (i.e. blank) — caller should
   * fall back to its normal filter path when the return is {@code null}.
   */
  public Criteria translate(Class<?> parentType, String filterExpression) {
    // Preserve the old "caller falls back to its normal filter translator" contract:
    // when the filter references no split field, the caller's full TMF grammar is a
    // strict superset of our SimpleMongoInnerPredicateTranslator, so let the caller
    // handle it. We only take over when there's split-side work to do.
    AndShapedPreflight pf =
        preflightAndShaped(
            parentType,
            filterExpression,
            "OR-shaped compound filters cannot be expressed as a single Criteria — the "
                + "criteria form can't UNION across collections. Use "
                + "translateAsUnionWithAggregation(...) to get a $unionWith pipeline "
                + "instead.");
    if (pf == null) return null;
    MongoSplitEntityMetadata metadata = pf.metadata();
    Decomposition decomposition = pf.decomposition();

    List<Criteria> conjuncts = new ArrayList<>();
    decomposition
        .parentOnlyFilter()
        .ifPresent(f -> conjuncts.add(parentTranslator.translate(unwrapForParent(f))));
    for (SplitClauseRef ref : decomposition.splitClauses()) {
      MongoSplitCollectionMetadata split = requireSplit(metadata, ref.splitFieldName());
      conjuncts.add(resolveViaChildLookup(split, ref.innerPredicate()));
    }
    return combineWithAnd(conjuncts);
  }

  /**
   * Phase (d.3 + d.4) — returns a single-round-trip {@link Aggregation} pipeline on
   * the parent collection. Returns {@code null} if the expression is blank.
   *
   * <p>Emits at most:
   *
   * <pre>
   * [ {$match: &lt;parent-only criteria&gt;},     // if parent-only clauses exist
   *   {$lookup: {...}}, {$match: {__M__: {$ne: []}}}, {$project: {__M__: 0}},
   *   ...one $lookup/$match/$project trio per split clause... ]
   * </pre>
   */
  public Aggregation translateAsPipeline(Class<?> parentType, String filterExpression) {
    // Symmetric to translate(): the caller's normal filter path is a superset of ours
    // for parent-only clauses, so short-circuit when there's no split-side work.
    AndShapedPreflight pf =
        preflightAndShaped(
            parentType,
            filterExpression,
            "OR-shaped compound filters cannot be composed as a parent-first $lookup "
                + "chain — use translateAsUnionWithAggregation(...) to get the "
                + "$unionWith shape instead.");
    if (pf == null) return null;
    MongoSplitEntityMetadata metadata = pf.metadata();
    Decomposition decomposition = pf.decomposition();

    List<AggregationOperation> stages = new ArrayList<>();
    Optional<String> parentOnlyFilter = decomposition.parentOnlyFilter();
    if (parentOnlyFilter.isPresent()) {
      Document parentBson =
          parentTranslator
              .translate(unwrapForParent(parentOnlyFilter.get()))
              .getCriteriaObject();
      stages.add(ctx -> new Document("$match", parentBson));
    }
    for (SplitClauseRef ref : decomposition.splitClauses()) {
      MongoSplitCollectionMetadata split = requireSplit(metadata, ref.splitFieldName());
      Aggregation piece = pipelineBuilder.build(split, ref.innerPredicate());
      // Merge the piece's stages into the outer pipeline so we get one aggregation.
      for (AggregationOperation op : piece.getPipeline().getOperations()) {
        stages.add(op);
      }
    }
    if (stages.isEmpty()) return null;
    return Aggregation.newAggregation(stages);
  }

  /**
   * <strong>Recommended entry point for Mongo consumers.</strong> Dispatches on the
   * filter's decomposition shape and returns a {@link SplitAwareAggregation}
   * uniformly, so callers don't have to know which shape their filter needs.
   *
   * <p>Routing table (deterministic, no perf-based decisions):
   *
   * <ul>
   *   <li>Blank filter, or filter with no split reference → returns {@code null}
   *       (caller falls back to its normal filter translator; ours is a strict
   *       subset for parent-only grammar).
   *   <li>AND-shaped decomposition with at least one split → parent-first
   *       {@code $lookup} pipeline via
   *       {@link #translateAsPipeline(Class, String)}, targeting the parent
   *       collection.
   *   <li>OR-shaped decomposition with at least one split → {@code $unionWith}
   *       pipeline via {@link #translateAsUnionWithAggregation(Class, String)};
   *       the returned packaging includes the correct target collection
   *       (parent or first split's child, depending on whether a parent-only
   *       conjunct is present).
   * </ul>
   *
   * <p>Item-first (see {@link #translateAsItemFirstAggregation(Class, String)}) is
   * intentionally never auto-selected by the router — it's a per-query performance
   * override that callers opt into explicitly when measurement shows parent-first
   * is a bottleneck for their data.
   *
   * <p>Execute the returned pipeline uniformly:
   *
   * <pre>{@code
   * SplitAwareAggregation packaged = translator.route(ParentType.class, filter);
   * if (packaged == null) {
   *   // fall back to your normal (parent-only) filter path
   * } else {
   *   List<ParentType> results = mongoOperations
   *       .aggregate(packaged.pipeline(), packaged.targetCollection(), ParentType.class)
   *       .getMappedResults();
   * }
   * }</pre>
   */
  public SplitAwareAggregation route(Class<?> parentType, String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata = requireMetadata(parentType);
    if (metadata.splits().isEmpty()) return null;

    Decomposition decomposition =
        TmfSplitFilterDecomposer.decompose(filterExpression, splitFieldNames(metadata));
    if (decomposition.isEmpty() || decomposition.splitClauses().isEmpty()) {
      return null;
    }
    if (decomposition.combinator() == Combinator.OR) {
      return translateAsUnionWithAggregation(parentType, filterExpression);
    }
    Aggregation pipeline = translateAsPipeline(parentType, filterExpression);
    if (pipeline == null) return null;
    return new SplitAwareAggregation(pipeline, metadata.parentCollection());
  }

  /**
   * Phase (d.3) — {@code $unionWith} variant for OR-shaped compound filters.
   * Returns a {@link SplitAwareAggregation} packaging the pipeline together with the
   * target collection (parent when a parent-only clause exists; the first split's
   * child collection otherwise).
   *
   * <p>Only accepts filters whose decomposition combinator is
   * {@link Combinator#OR}. AND-shaped filters (including parent-only) are rejected
   * here — use {@link #translateAsPipeline(Class, String)} or
   * {@link #translate(Class, String)} for those.
   *
   * <p><strong>Transaction caveat:</strong> Mongo forbids {@code $unionWith} inside a
   * multi-document transaction. If the caller runs under a
   * {@code MongoTransactionManager}-managed transaction the pipeline execution must
   * happen outside that transaction. Parent-first {@code $lookup} composition
   * (transaction-legal) cannot express OR across collections in a single pipeline,
   * so callers who need both OR-of-splits and full transactional atomicity would
   * have to fall back to multiple queries.
   */
  public SplitAwareAggregation translateAsUnionWithAggregation(
      Class<?> parentType, String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata = requireMetadata(parentType);
    if (metadata.splits().isEmpty()) return null;

    Decomposition decomposition =
        TmfSplitFilterDecomposer.decompose(filterExpression, splitFieldNames(metadata));
    if (decomposition.isEmpty()) return null;
    if (decomposition.combinator() != Combinator.OR) {
      throw new TmfFilteringException(
          "$unionWith pipeline shape only accepts OR-shaped compound filters; the "
              + "input decomposes to combinator "
              + decomposition.combinator()
              + ". Use translateAsPipeline(...) for parent-first $lookup or "
              + "translateAsItemFirstAggregation(...) for item-first shapes.");
    }
    if (decomposition.splitClauses().isEmpty()) {
      throw new TmfFilteringException(
          "$unionWith pipeline shape requires at least one split correlation; the "
              + "input has none. Use your normal filter path for parent-only "
              + "expressions.");
    }
    List<SplitPiece> pieces = new ArrayList<>(decomposition.splitClauses().size());
    for (SplitClauseRef ref : decomposition.splitClauses()) {
      MongoSplitCollectionMetadata split = requireSplit(metadata, ref.splitFieldName());
      pieces.add(new SplitPiece(split, ref.innerPredicate()));
    }
    return unionWithBuilder.build(
        metadata.parentCollection(),
        decomposition.parentOnlyFilter().map(MongoSplitAwareFilterTranslator::unwrapForParent),
        pieces);
  }

  /**
   * Phase (d.3) — item-first pipeline variant. Returns a
   * {@link SplitAwareAggregation} that packages the pipeline together with its
   * target collection (the split's child collection, not the parent). Prefer this
   * over {@link #translateAsPipeline(Class, String)} when the parent set is large
   * and the child filter is very selective — item-first scans matching children
   * only, groups by parent id, then joins back, whereas parent-first with
   * {@code $lookup} must visit every parent doc.
   *
   * <p><strong>Supported shape:</strong> exactly one top-level split correlation
   * with no parent-only conjunct — {@code $[?(@.<splitField>[?(<inner>)])]}. Any
   * other decomposition (compound parent+split, multi-split, or parent-only) is
   * rejected here; use {@link #translateAsPipeline(Class, String)} for those.
   *
   * @return the packaged aggregation, or {@code null} if the filter is blank or
   *     the parent has no splits declared.
   */
  public SplitAwareAggregation translateAsItemFirstAggregation(
      Class<?> parentType, String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata = requireMetadata(parentType);
    if (metadata.splits().isEmpty()) return null;

    Decomposition decomposition =
        TmfSplitFilterDecomposer.decompose(filterExpression, splitFieldNames(metadata));
    if (decomposition.isEmpty()) return null;

    if (decomposition.parentOnlyFilter().isPresent()) {
      throw new TmfFilteringException(
          "Item-first pipeline shape does not support parent-only conjuncts; "
              + "the input filter has one. Use translateAsPipeline(...) for "
              + "compound parent + split filters.");
    }
    if (decomposition.splitClauses().size() != 1) {
      throw new TmfFilteringException(
          "Item-first pipeline shape supports exactly one split correlation; got "
              + decomposition.splitClauses().size()
              + ". Use translateAsPipeline(...) for multi-split filters.");
    }
    SplitClauseRef ref = decomposition.splitClauses().get(0);
    MongoSplitCollectionMetadata split = requireSplit(metadata, ref.splitFieldName());
    Aggregation pipeline =
        itemFirstBuilder.build(metadata.parentCollection(), split, ref.innerPredicate());
    return new SplitAwareAggregation(pipeline, split.childCollection());
  }

  /**
   * Unwraps the {@code $[?( ... )]} the decomposer emits, so the parent-side
   * {@link MongoInnerPredicateTranslator} receives just the leaf-or-compound body
   * (its input grammar is inner-predicate text, not a full JsonPath wrapper).
   */
  private static String unwrapForParent(String wrappedParentFilter) {
    String s = wrappedParentFilter.trim();
    if (s.startsWith("$[?(") && s.endsWith(")]")) {
      return s.substring(4, s.length() - 2).trim();
    }
    if (s.startsWith("[?(") && s.endsWith(")]")) {
      return s.substring(3, s.length() - 2).trim();
    }
    return s;
  }

  private MongoSplitEntityMetadata requireMetadata(Class<?> parentType) {
    return registry
        .forParentType(parentType)
        .orElseThrow(
            () ->
                new TmfFilteringException(
                    "No @Tmf630MongoSplitBacked mapping registered for " + parentType.getName()));
  }

  /**
   * Shared preflight for the AND-shaped entry points (both {@link #translate} and
   * {@link #translateAsPipeline}): rejects blank input, unregistered types, and empty
   * or non-split-touching decompositions with a {@code null} return so the caller can
   * hand off to its normal (parent-only) filter path; throws when the shape is OR-only
   * with a message describing the correct entry point.
   *
   * @return {@code null} to short-circuit the caller, or a {@link AndShapedPreflight}
   *     carrying the resolved metadata + decomposition
   */
  private AndShapedPreflight preflightAndShaped(
      Class<?> parentType, String filterExpression, String orShapeErrorMessage) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata = requireMetadata(parentType);
    if (metadata.splits().isEmpty()) return null;
    Decomposition decomposition =
        TmfSplitFilterDecomposer.decompose(filterExpression, splitFieldNames(metadata));
    if (decomposition.isEmpty() || decomposition.splitClauses().isEmpty()) return null;
    if (decomposition.combinator() == Combinator.OR) {
      throw new TmfFilteringException(orShapeErrorMessage);
    }
    return new AndShapedPreflight(metadata, decomposition);
  }

  private record AndShapedPreflight(
      MongoSplitEntityMetadata metadata, Decomposition decomposition) {}

  private static MongoSplitCollectionMetadata requireSplit(
      MongoSplitEntityMetadata metadata, String fieldName) {
    return metadata.splits().stream()
        .filter(s -> s.fieldName().equals(fieldName))
        .findFirst()
        .orElseThrow(
            () ->
                new TmfFilteringException(
                    "No split metadata for field '"
                        + fieldName
                        + "' on "
                        + metadata.parentType().getSimpleName()));
  }

  private static Set<String> splitFieldNames(MongoSplitEntityMetadata metadata) {
    return metadata.splits().stream()
        .map(MongoSplitCollectionMetadata::fieldName)
        .collect(Collectors.toSet());
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

  private static Criteria combineWithAnd(List<Criteria> conjuncts) {
    if (conjuncts.isEmpty()) return null;
    if (conjuncts.size() == 1) return conjuncts.get(0);
    return new Criteria().andOperator(conjuncts.toArray(Criteria[]::new));
  }
}
