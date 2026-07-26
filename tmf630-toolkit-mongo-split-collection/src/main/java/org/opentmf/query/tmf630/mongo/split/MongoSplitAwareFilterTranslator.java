package org.opentmf.query.tmf630.mongo.split;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Decomposition;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.SplitClauseRef;
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
  }

  /**
   * Attempts to translate the given {@code filter=} JsonPath expression to a Mongo
   * {@link Criteria} for use at the parent endpoint. Returns {@code null} when the
   * filter has no split reference and no parent clause (i.e. blank) — caller should
   * fall back to its normal filter path when the return is {@code null}.
   */
  public Criteria translate(Class<?> parentType, String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata = requireMetadata(parentType);
    if (metadata.splits().isEmpty()) return null;

    Decomposition decomposition =
        TmfSplitFilterDecomposer.decompose(filterExpression, splitFieldNames(metadata));
    if (decomposition.isEmpty()) return null;
    // Preserve the old "caller falls back to its normal filter translator" contract:
    // when the filter references no split field, the caller's full TMF grammar is a
    // strict superset of our SimpleMongoInnerPredicateTranslator, so let the caller
    // handle it. We only take over when there's split-side work to do.
    if (decomposition.splitClauses().isEmpty()) return null;

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
   * [ {$match: <parent-only criteria>},           // if parent-only clauses exist
   *   {$lookup: {...}}, {$match: {__M__: {$ne: []}}}, {$project: {__M__: 0}},
   *   ...one $lookup/$match/$project trio per split clause... ]
   * </pre>
   */
  public Aggregation translateAsPipeline(Class<?> parentType, String filterExpression) {
    if (filterExpression == null || filterExpression.isBlank()) return null;
    MongoSplitEntityMetadata metadata = requireMetadata(parentType);
    if (metadata.splits().isEmpty()) return null;

    Decomposition decomposition =
        TmfSplitFilterDecomposer.decompose(filterExpression, splitFieldNames(metadata));
    if (decomposition.isEmpty()) return null;
    // Symmetric to translate(): the caller's normal filter path is a superset of ours
    // for parent-only clauses, so short-circuit when there's no split-side work.
    if (decomposition.splitClauses().isEmpty()) return null;

    List<AggregationOperation> stages = new ArrayList<>();
    if (decomposition.parentOnlyFilter().isPresent()) {
      Document parentBson =
          parentTranslator
              .translate(unwrapForParent(decomposition.parentOnlyFilter().get()))
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
