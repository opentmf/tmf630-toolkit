package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.mongodb.DBRef;
import com.querydsl.core.types.Predicate;
import com.querydsl.mongodb.document.MongodbDocumentSerializer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoSearchController;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoSearchEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = Tmf630PredicateMongoIT.TestApp.class,
    properties = {
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.MongoSearchEntity=id,href,category,externalId,requestedStartDate,state,externalReference.id,externalReference.name",
      "opentmf.tmf630.attribute-filtering.allowNestedPathsDocdb=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@Testcontainers
class Tmf630PredicateMongoIT {

  private static final String DATASET_RESOURCE = "fixtures/uc-list-service-order-response.json";

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private JsonMapper jsonMapper;
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
  void setUp() {
    mongoTemplate.dropCollection("mongo_search_entity");
    String json = readClasspathUtf8();
    JsonNode root = jsonMapper.readTree(json);
    List<Document> documents = new ArrayList<>();
    for (JsonNode node : root) {
      documents.add(Document.parse(node.toString()));
    }
    // Three extra docs scoped to a unique category, each carrying a single
    // externalReference{name='SORT_KEY', id=01|02|03}. These exist so JsonPath-sort
    // assertions have varying values to order by; their distinct category keeps them
    // disjoint from every other test's filter.
    documents.add(sortFixtureDoc("aa1", "01"));
    documents.add(sortFixtureDoc("aa2", "03"));
    documents.add(sortFixtureDoc("aa3", "02"));
    documents.add(coerceFixtureDoc("co1", "3"));
    documents.add(coerceFixtureDoc("co2", "20"));
    documents.add(coerceFixtureDoc("co3", "100"));
    documents.add(coerceFixtureDoc("co4", "500"));
    mongoTemplate.getDb().getCollection("mongo_search_entity").insertMany(documents);
  }

  private static Document coerceFixtureDoc(String id, String numericString) {
    // ExternalReference element with name='COERCE' carries a `characteristics`
    // array of {value: <numeric-string>} pairs. The path
    // externalReference[name=COERCE].characteristics.value crosses an array
    // intermediate (`characteristics`), which is the shape the colleague's
    // pre-release audit identified as silently sorting in insertion order
    // under the outer-wrapper form.
    return new Document("_id", id)
        .append("category", "COERCE_IT")
        .append(
            "externalReference",
            List.of(
                new Document("name", "COERCE")
                    .append("_id", "coerce-ref-" + id)
                    .append(
                        "characteristics",
                        List.of(new Document("value", numericString)))));
  }

  private static Document sortFixtureDoc(String id, String sortKeyValue) {
    // Nested externalReference.id is stored under BSON `_id` because Spring Data Mongo
    // auto-promotes any `id` property — including on nested mapped classes — to `_id`.
    // The correlated-sort executor's MongoFieldResolver applies the same translation,
    // so the seed must use `_id` here for the JsonPath sort to find a value to order by.
    // Each doc has TWO externalReference elements; only one matches name='SORT_KEY'.
    // This mirrors realistic productOffering data where prodSpecCharValueUse holds
    // multiple entries and only one corresponds to the requested characteristic id.
    return new Document("_id", id)
        .append("category", "FILTER_SORT_IT")
        .append(
            "externalReference",
            List.of(
                new Document("name", "OTHER").append("_id", "noise-" + id),
                new Document("name", "SORT_KEY")
                    .append("_id", "ref-" + id)
                    .append("metadata", new Document("value", sortKeyValue))));
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
  void acceptsJsonPathWildcardAsTransparentProjection() {
    // `[*]` is canonical JsonPath syntax for "all elements of this array." Mongo's
    // BSON path-equality auto-projects across arrays, so the with- and without-[*]
    // forms match the same documents. The toolkit strips `[*]` at parse time and
    // the resulting query is byte-identical — proven here against a real Mongo
    // container by counting documents both ways and asserting equality.
    long withWildcard =
        countDocumentsByFilter("$[?(@.externalReference[*].name == 'ORDER_REFERENCE')]");
    long withoutWildcard =
        countDocumentsByFilter("$[?(@.externalReference.name == 'ORDER_REFERENCE')]");

    assertEquals(withoutWildcard, withWildcard);
    assertTrue(withWildcard > 0, "Expected at least one matching document");
  }

  @Test
  void acceptsBracketStarBeforeArrayCorrelationPredicate() {
    // `arr[*][?(...)]` is the canonical "project-then-filter" JsonPath shape.
    // After `[*]` is stripped, the expression is identical to `arr[?(...)]` —
    // the toolkit's standard same-element correlation form translated to
    // `$elemMatch` on Mongo.
    long withWildcard =
        countDocumentsByFilter(
            "$[?(@.externalReference[*][?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]");
    long withoutWildcard =
        countDocumentsByFilter(
            "$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]");

    assertEquals(withoutWildcard, withWildcard);
    assertEquals(10, withWildcard);
  }

  @Test
  void rejectsInvalidJsonPathOnMongoFlow() throws Exception {
    mockMvc
        .perform(get("/mongo-search").param("filter", "$.status"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void combinesArrayCorrelationFilterWithCorrelatedSortOnMongo() throws Exception {
    // Reproduces the colleague's report: an array-correlation filter on the same
    // field that the correlated sort traverses returns an empty page when the two
    // are combined, even though each works in isolation. Mirrors her shape:
    //   filter = prodSpecCharValueUse[?(@.id=='RC_OFFER_TYPE')]
    //   sort   = prodSpecCharValueUse[id=RC_OFFER_TYPE].<...>.value
    // Filter syntax: sub-array shorthand (bare wrapper). Sort syntax: simple-rich.
    // Filter targets `id` (auto-promoted to `_id` in BSON storage). This is the
    // crux of the colleague's report: her filter is `prodSpecCharValueUse[?(@.id=='RC_OFFER_TYPE')]`
    // which depends on the serializer translating `id` → `_id` to match real data.
    String filter =
        "externalReference[?(@.id == 'ref-aa1' || @.id == 'ref-aa2' || @.id == 'ref-aa3')]";
    String sortAsc = "externalReference[name=SORT_KEY].metadata.value";

    // Sanity: the same filter on the same endpoint returns all 3 docs when sort is
    // absent (find() path via Spring Data + standard QueryDSL Mongo serializer).
    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));

    // The bug: with the correlated sort added, the filter silently matches nothing
    // because the executor's NoRefDocumentSerializer skips the `id` → `_id`
    // auto-promotion that Spring Data's wrapped serializer applies on the find()
    // path. Same Predicate, two different BSON `$match` documents.
    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "+" + sortAsc))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("aa1"))
        .andExpect(jsonPath("$[1].id").value("aa3"))
        .andExpect(jsonPath("$[2].id").value("aa2"));

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "-" + sortAsc))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("aa2"))
        .andExpect(jsonPath("$[1].id").value("aa3"))
        .andExpect(jsonPath("$[2].id").value("aa1"));
  }

  @Test
  void combinesJsonPathFilterWithJsonPathSortOnMongo() throws Exception {
    // Companion to combinesJsonPathFilterWithSortOnMongo, but the sort term itself
    // is a correlated JsonPath expression — exercising the (Predicate, TmfRichPageable)
    // controller routing through Tmf630MongoCorrelatedSortExecutor.
    String filter = "$[?(@.category == 'FILTER_SORT_IT')]";
    String sortAsc = "$.externalReference[?(@.name == 'SORT_KEY')].metadata.value";

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "+" + sortAsc))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("aa1"))
        .andExpect(jsonPath("$[1].id").value("aa3"))
        .andExpect(jsonPath("$[2].id").value("aa2"));

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "-" + sortAsc))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("aa2"))
        .andExpect(jsonPath("$[1].id").value("aa3"))
        .andExpect(jsonPath("$[2].id").value("aa1"));
  }

  @Test
  void combinesJsonPathFilterWithSortOnMongo() throws Exception {
    // Reproduces the colleague's report: combining ?filter=<jsonpath>&sort=<key> on the
    // same Mongo endpoint must return populated, ordered results — not an empty page.
    String hrefLow = "/tmf-api/serviceOrdering/v4/serviceOrder/6215245d7fce055f32210d79";
    String hrefHigh = "/tmf-api/serviceOrdering/v4/serviceOrder/6215e8252c2b0673ea905954";
    String filter = "$[?(@.href == '" + hrefLow + "' || @.href == '" + hrefHigh + "')]";

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "-href"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].href").value(hrefHigh))
        .andExpect(jsonPath("$[1].href").value(hrefLow));

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "+href"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].href").value(hrefLow))
        .andExpect(jsonPath("$[1].href").value(hrefHigh));
  }

  @Test
  void acceptsOuterNumWrapperOnSimpleRichSort() throws Exception {
    // Simple-rich correlated sort with the coercion wrapper placed at the OUTER
    // level (TMF630 §4.7 allows both forms). The toolkit previously rejected
    // this form with "must contain at least one [...] hop"; it should now parse
    // identically to the inner-wrapper form arr[X].num(leaf) and produce the
    // same numeric ordering at the DB level.
    String filter = "$[?(@.category == 'FILTER_SORT_IT')]";
    String sortAsc = "num(externalReference[name=SORT_KEY].metadata.value)";

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "+" + sortAsc))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("aa1"))
        .andExpect(jsonPath("$[1].id").value("aa3"))
        .andExpect(jsonPath("$[2].id").value("aa2"));
  }

  @Test
  void outerNumWrapperWithArrayIntermediateSortsNumericallyLikeInnerForm() throws Exception {
    // Regression for the silent-drop bug found during 2.1.0-SNAPSHOT pre-release
    // testing. When the dotted leaf path of an outer-wrapper sort crosses an
    // array-typed intermediate (here `characteristics`), Mongo's path expression
    // auto-projects through the array and returns an array of values — which
    // then makes the wrapping $convert yield null and silently breaks the sort.
    // The translator must wrap such paths in $arrayElemAt before $convert so
    // both the inner-wrapper and outer-wrapper forms produce identical numeric
    // ordering. Lexicographic order of "3"/"20"/"100"/"500" is "100"<"20"<"3"<"500";
    // numeric order is 3<20<100<500. The assertions below pin numeric ordering.
    String filter = "$[?(@.category == 'COERCE_IT')]";
    String innerSort = "externalReference[name=COERCE].characteristics.num(value)";
    String outerSort = "num(externalReference[name=COERCE].characteristics.value)";

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "+" + innerSort))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].id").value("co1"))
        .andExpect(jsonPath("$[1].id").value("co2"))
        .andExpect(jsonPath("$[2].id").value("co3"))
        .andExpect(jsonPath("$[3].id").value("co4"));

    mockMvc
        .perform(get("/mongo-search-paged").param("filter", filter).param("sort", "+" + outerSort))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].id").value("co1"))
        .andExpect(jsonPath("$[1].id").value("co2"))
        .andExpect(jsonPath("$[2].id").value("co3"))
        .andExpect(jsonPath("$[3].id").value("co4"));
  }

  @SpringBootApplication(
      scanBasePackageClasses = MongoSearchController.class,
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
      })
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

  private static String readClasspathUtf8() {
    try (InputStream in =
        Tmf630PredicateMongoIT.class.getClassLoader().getResourceAsStream(DATASET_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Classpath resource not found: " + DATASET_RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
