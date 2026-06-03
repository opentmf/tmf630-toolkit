package org.opentmf.query.tmf630.mongo;

import com.querydsl.core.types.Predicate;
import com.querydsl.mongodb.document.MongodbDocumentSerializer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

public class Tmf630MongoCorrelatedSortExecutor {

  private final MongoTemplate mongoTemplate;
  private final JsonPathSortParser jsonPathParser;
  private final SimpleRichSortParser simpleRichParser;
  private final MongodbDocumentSerializer querySerializer;
  private final MongoFieldResolver fieldResolver;

  public Tmf630MongoCorrelatedSortExecutor(MongoTemplate mongoTemplate, String defaultSimpleRichKey) {
    this.mongoTemplate = mongoTemplate;
    this.jsonPathParser = new JsonPathSortParser();
    this.simpleRichParser = new SimpleRichSortParser(defaultSimpleRichKey);
    MongoMappingContext mappingContext = resolveMappingContext(mongoTemplate);
    this.querySerializer = new NoRefDocumentSerializer(mappingContext);
    this.fieldResolver =
        mappingContext != null
            ? new MongoFieldResolver(mappingContext)
            : MongoFieldResolver.passthrough();
  }

  private static MongoMappingContext resolveMappingContext(MongoTemplate mongoTemplate) {
    if (mongoTemplate.getConverter().getMappingContext() instanceof MongoMappingContext ctx) {
      return ctx;
    }
    return null;
  }

  public <T> Page<T> findAll(
      Class<T> entityClass, Predicate filter, TmfSort sort, Pageable pageable) {
    String collectionName = mongoTemplate.getCollectionName(entityClass);

    List<AggregationOperation> matchStages = buildMatchStages(filter);
    long total = count(matchStages, collectionName);

    List<AggregationOperation> stages = new ArrayList<>(matchStages);

    Document sortKeysDoc = new Document();
    Document hasKeysDoc = new Document();
    List<String> syntheticKeys = new ArrayList<>();
    List<Sort.Order> sortOrders = new ArrayList<>();
    int counter = 0;
    for (TmfSortTerm term : sort.terms()) {
      String sortKey = "_sortKey" + counter;
      String hasKey = "_hasKey" + counter;
      counter++;
      Object sortKeyExpression =
          switch (term.kind()) {
            case JSONPATH -> AggregationKeyTranslator.translate(
                jsonPathParser.parse(term.expression()), fieldResolver, entityClass);
            case SIMPLE_RICH -> AggregationKeyTranslator.translate(
                simpleRichParser.parse(term.expression()), fieldResolver, entityClass);
            case PLAIN -> plainSortKeyExpression(entityClass, term.expression());
          };
      sortKeysDoc.append(sortKey, sortKeyExpression);
      // Companion key is computed in a separate $addFields stage so it can reference
      // the just-emitted _sortKeyN. Within a single $addFields stage, expressions
      // resolve against the input document and a sibling field reference comes back
      // null — hence the two-stage split. $ifNull folds Mongo's MISSING (absent
      // field) and explicit null together: $_sortKey0 evaluates to MISSING when the
      // sort key expression resolves to undefined (e.g. a path through a doc that
      // doesn't carry the field), and MISSING is not equal to null under $ne, which
      // would otherwise let the row sneak into the present-key bucket.
      hasKeysDoc.append(
          hasKey,
          new Document(
              "$cond",
              Arrays.asList(
                  new Document(
                      "$eq",
                      Arrays.asList(
                          new Document("$ifNull", Arrays.asList("$" + sortKey, null)), null)),
                  1,
                  0)));
      syntheticKeys.add(sortKey);
      syntheticKeys.add(hasKey);
      sortOrders.add(new Sort.Order(Sort.Direction.ASC, hasKey));
      sortOrders.add(new Sort.Order(term.direction(), sortKey));
    }

    if (!sortKeysDoc.isEmpty()) {
      Document sortKeysStage = new Document("$addFields", sortKeysDoc);
      Document hasKeysStage = new Document("$addFields", hasKeysDoc);
      stages.add(ctx -> sortKeysStage);
      stages.add(ctx -> hasKeysStage);
    }

    if (!sortOrders.isEmpty()) {
      stages.add(Aggregation.sort(Sort.by(sortOrders)));
    }

    if (!syntheticKeys.isEmpty()) {
      Document projectDoc = new Document();
      for (String key : syntheticKeys) {
        projectDoc.append(key, 0);
      }
      Document projectStage = new Document("$project", projectDoc);
      stages.add(ctx -> projectStage);
    }

    if (pageable.isPaged()) {
      stages.add(Aggregation.skip(pageable.getOffset()));
      stages.add(Aggregation.limit(pageable.getPageSize()));
    }

    Aggregation dataAgg = Aggregation.newAggregation(stages);
    AggregationResults<T> result = mongoTemplate.aggregate(dataAgg, collectionName, entityClass);
    return new PageImpl<>(result.getMappedResults(), pageable, total);
  }

  private Object plainSortKeyExpression(Class<?> entityClass, String dottedJavaPath) {
    String pathExpr = "$" + fieldResolver.resolveBsonPath(entityClass, dottedJavaPath);
    // A plain dotted path whose intermediate segments include a collection-typed
    // property auto-projects to an array under Mongo's expression context. Two
    // such terms in one $sort trigger "cannot sort with keys that are parallel
    // arrays" (BadValue, code 2). Reduce to the first element so each _sortKeyN
    // stays scalar.
    if (fieldResolver.hasArrayIntermediate(entityClass, dottedJavaPath)) {
      return new Document("$arrayElemAt", Arrays.asList(pathExpr, 0));
    }
    return pathExpr;
  }

  private List<AggregationOperation> buildMatchStages(Predicate filter) {
    if (filter == null) {
      return List.of();
    }
    Object handled = querySerializer.handle(filter);
    if (!(handled instanceof Document doc) || doc.isEmpty()) {
      return List.of();
    }
    Document matchStage = new Document("$match", doc);
    return List.of(ctx -> matchStage);
  }

  private long count(List<AggregationOperation> matchStages, String collectionName) {
    List<AggregationOperation> countStages = new ArrayList<>(matchStages);
    countStages.add(Aggregation.count().as("total"));
    Aggregation countAgg = Aggregation.newAggregation(countStages);
    AggregationResults<Document> result =
        mongoTemplate.aggregate(countAgg, collectionName, Document.class);
    if (result.getMappedResults().isEmpty()) {
      return 0L;
    }
    Object total = result.getMappedResults().get(0).get("total");
    return total instanceof Number n ? n.longValue() : 0L;
  }
}
