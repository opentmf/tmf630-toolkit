package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.DBRef;
import com.querydsl.core.types.Predicate;
import com.querydsl.mongodb.document.MongodbDocumentSerializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = Tmf630PredicateMongoIT.TestApp.class,
    properties = {
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.MongoSearchEntity=id,href,category,externalId,requestedStartDate,state,externalReference.id,externalReference.name",
      "opentmf.tmf630.attribute-filtering.allowNestedPaths=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@Testcontainers
class Tmf630PredicateMongoIT {

  private static final Path REAL_DATASET_PATH =
      Path.of(
          "/home/gokhan/prj/iot-solutionhub/api-adapters/sdn-service-order-adapter/src/test/resources/payload/uc-list-service-order-response.json");

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JsonPathFilterPredicateBuilder jsonPathFilterPredicateBuilder;
  @Autowired private Tmf630AttributeFilteringProperties filteringProperties;

  private final org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver fieldPathResolver =
      new org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver();

  private static final Set<String> ALLOWLIST =
      Set.of(
          "id",
          "href",
          "category",
          "externalId",
          "requestedStartDate",
          "state",
          "externalReference.id",
          "externalReference.name");

  @BeforeEach
  void setUp() throws Exception {
    mongoTemplate.dropCollection("mongo_search_entity");
    String json = Files.readString(REAL_DATASET_PATH);
    JsonNode root = objectMapper.readTree(json);
    List<Document> documents = new ArrayList<>();
    for (JsonNode node : root) {
      documents.add(Document.parse(node.toString()));
    }
    mongoTemplate.getDb().getCollection("mongo_search_entity").insertMany(documents);
  }

  @Test
  void supportsComplexGroupedJsonPathWithDefaultAndMergeOnMongo() throws Exception {
    mockMvc
        .perform(
            get("/mongo-search")
                .param("category.eq", "SDWAN service order")
                .param(
                    "filter",
                    "$[?((@.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215245d7fce055f32210d79' || @.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215e8252c2b0673ea905954') && (@.state == 'acknowledged' && @.externalId == 'BSS748'))]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void supportsAttributePlusJsonPathWithOrMergeOnMongo() throws Exception {
    mockMvc
        .perform(
            get("/mongo-search")
                .param("href.eq", "/tmf-api/serviceOrdering/v4/serviceOrder/621524e67fce055f32210d7a")
                .param(
                    "filter",
                    "$[?((@.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215e0662c2b0673ea905950') || (@.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215e69e2c2b0673ea905953' && @.state == 'acknowledged'))]")
                .param("filter.combineWithAttributes", "OR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void supportsMixedAttributeOperatorsWithJsonPathOnMongo() throws Exception {
    mockMvc
        .perform(
            get("/mongo-search")
                .param("href.likei", "%/serviceOrder/6215e%")
                .param("id.gte", "6215e0000000000000000000")
                .param(
                    "filter",
                    "$[?(@.state == 'acknowledged' && (@.category == 'SDWAN service order' || @.externalId == 'BSS748'))]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5));
  }

  @Test
  void acceptsArrayCorrelationJsonPathSyntaxOnMongo() throws Exception {
    mockMvc
        .perform(
            get("/mongo-search")
                .param(
                    "filter",
                    "$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]"))
        .andExpect(status().isOk());
  }

  @Test
  void enforcesStrictSameElementArrayCorrelationWithExplicitElemMatch() {
    long count =
        countDocumentsByFilter(
            "$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]");
    assertEquals(10, count);
  }

  @Test
  void returnsNoResultsForCrossElementMismatchWithElemMatch() {
    long count =
        countDocumentsByFilter(
            "$[?(@.externalReference[?(@.name == 'MARKET_ACCOUNT_ID' && @.id == 'OPCO-ORDER-012')])]");
    assertEquals(0, count);
  }

  @Test
  void rejectsInvalidJsonPathOnMongoFlow() throws Exception {
    mockMvc
        .perform(get("/mongo-search").param("filter", "$.status"))
        .andExpect(status().isBadRequest());
  }

  @SpringBootApplication
  @Import(MongoSearchController.class)
  static class TestApp {}

  private long countDocumentsByFilter(String filterExpression) {
    Predicate predicate =
        jsonPathFilterPredicateBuilder.build(
            MongoSearchEntity.class,
            fieldPathResolver.createRootPath(MongoSearchEntity.class),
            filterExpression,
            ALLOWLIST,
            filteringProperties.toSettings());
    Document query = (Document) new NoRefMongoDocumentSerializer().handle(predicate);
    return mongoTemplate.getDb().getCollection("mongo_search_entity").countDocuments(query);
  }

  private static class NoRefMongoDocumentSerializer extends MongodbDocumentSerializer {
    @Override
    protected DBRef asReference(Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected boolean isReference(com.querydsl.core.types.Path<?> path) {
      return false;
    }
  }
}
