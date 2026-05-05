package org.opentmf.query.tmf630.mongo;

import com.querydsl.core.types.Predicate;
import com.querydsl.mongodb.document.MongodbDocumentSerializer;
import java.util.ArrayList;
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
    this.querySerializer = new NoRefDocumentSerializer();
    this.fieldResolver = resolveFieldResolver(mongoTemplate);
  }

  private static MongoFieldResolver resolveFieldResolver(MongoTemplate mongoTemplate) {
    if (mongoTemplate.getConverter().getMappingContext() instanceof MongoMappingContext ctx) {
      return new MongoFieldResolver(ctx);
    }
    return MongoFieldResolver.passthrough();
  }

  public <T> Page<T> findAll(
      Class<T> entityClass, Predicate filter, TmfSort sort, Pageable pageable) {
    String collectionName = mongoTemplate.getCollectionName(entityClass);

    List<AggregationOperation> matchStages = buildMatchStages(filter);
    long total = count(matchStages, collectionName);

    List<AggregationOperation> stages = new ArrayList<>(matchStages);

    Document addFieldsDoc = new Document();
    List<String> syntheticKeys = new ArrayList<>();
    List<Sort.Order> sortOrders = new ArrayList<>();
    int counter = 0;
    for (TmfSortTerm term : sort.terms()) {
      switch (term.kind()) {
        case JSONPATH -> {
          String key = "_sortKey" + counter++;
          JsonPathSortAst.SortPath ast = jsonPathParser.parse(term.expression());
          addFieldsDoc.append(
              key, AggregationKeyTranslator.translate(ast, fieldResolver, entityClass));
          syntheticKeys.add(key);
          sortOrders.add(new Sort.Order(term.direction(), key));
        }
        case SIMPLE_RICH -> {
          String key = "_sortKey" + counter++;
          JsonPathSortAst.SortPath ast = simpleRichParser.parse(term.expression());
          addFieldsDoc.append(
              key, AggregationKeyTranslator.translate(ast, fieldResolver, entityClass));
          syntheticKeys.add(key);
          sortOrders.add(new Sort.Order(term.direction(), key));
        }
        case PLAIN -> sortOrders.add(new Sort.Order(term.direction(), term.expression()));
      }
    }

    if (!addFieldsDoc.isEmpty()) {
      Document addFieldsStage = new Document("$addFields", addFieldsDoc);
      stages.add(ctx -> addFieldsStage);
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
