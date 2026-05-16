package org.opentmf.query.tmf630.mongo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@SpringBootTest(classes = Tmf630CorrelatedSortHttpIT.TestApp.class)
@AutoConfigureMockMvc
@Testcontainers
class Tmf630CorrelatedSortHttpIT {

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.dropCollection(RawOrder.class);

    // Three products with different price values; product 3 has no `price` characteristic.
    mongoTemplate.insertAll(
        List.of(
            new Product(
                "1",
                List.of(
                    new Characteristic("price", 20.5),
                    new Characteristic("color", "blue"),
                    new Characteristic("size", "large"))),
            new Product(
                "2",
                List.of(
                    new Characteristic("price", 18),
                    new Characteristic("color", "blue"),
                    new Characteristic("size", "small"))),
            new Product(
                "3",
                List.of(
                    new Characteristic("stock", "none"),
                    new Characteristic("color", "blue"),
                    new Characteristic("size", "small")))));
  }

  // ---------- Plain-sort path (find() without aggregation) ----------

  @Test
  void plainAscendingByIdReturnsTmf630Response() throws Exception {
    mockMvc
        .perform(get("/products").param("sort", "+id"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(header().string("X-Result-Count", "3"))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("1"))
        .andExpect(jsonPath("$[1].id").value("2"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  @Test
  void plainDescendingByIdReturnsReversedOrder() throws Exception {
    mockMvc
        .perform(get("/products").param("sort", "-id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("3"))
        .andExpect(jsonPath("$[2].id").value("1"));
  }

  // ---------- JSONPATH grammar ----------

  @Test
  void jsonPathSingleLevelAscendingByCorrelatedPriceValue() throws Exception {
    // Doc 3 has no `price` characteristic; the executor's nulls-last policy puts
    // missing-key docs at the tail regardless of direction.
    mockMvc
        .perform(
            get("/products")
                .param("sort", "$.characteristic[?(@.name == 'price')].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("2"))
        .andExpect(jsonPath("$[1].id").value("1"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  @Test
  void jsonPathSingleLevelDescendingByCorrelatedPriceValue() throws Exception {
    mockMvc
        .perform(
            get("/products")
                .param("sort", "-$.characteristic[?(@.name == 'price')].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("1"))
        .andExpect(jsonPath("$[1].id").value("2"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  @Test
  void jsonPathOrPredicate() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("a", List.of(new Characteristic("price", 5))),
            new Product("b", List.of(new Characteristic("cost", 10))),
            new Product("c", List.of(new Characteristic("color", "red")))));

    // Doc `c` has neither `price` nor `cost`; predicate returns no element, sort
    // key is null and the row lands last in ASC under the nulls-last policy.
    mockMvc
        .perform(
            get("/products")
                .param(
                    "sort",
                    "$.characteristic[?(@.name == 'price' || @.name == 'cost')].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("a"))
        .andExpect(jsonPath("$[1].id").value("b"))
        .andExpect(jsonPath("$[2].id").value("c"));
  }

  @Test
  void jsonPathAndPredicate() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("a", List.of(new Characteristic("price", 5))),
            new Product("b", List.of(new Characteristic("price", 10))),
            new Product("c", List.of(new Characteristic("price", 20)))));

    // Same-element AND predicate. Doc `a` has price=5 (filtered out by `value > 7`)
    // so its sort key is null and lands last under the nulls-last policy. b (10) and
    // c (20) sort ASC by value.
    mockMvc
        .perform(
            get("/products")
                .param(
                    "sort",
                    "$.characteristic[?(@.name == 'price' && @.value > 7)].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("b"))
        .andExpect(jsonPath("$[1].id").value("c"))
        .andExpect(jsonPath("$[2].id").value("a"));
  }

  @Test
  void jsonPathTwoLevelNestedCorrelation() throws Exception {
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.insertAll(
        List.of(
            new Order(
                "B",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "B-event")))))),
            new Order(
                "A",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "A-event")))))),
            new Order(
                "C",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "C-event"))))))));

    mockMvc
        .perform(
            get("/orders")
                .param(
                    "sort",
                    "$.serviceOrderItem[?(@.id == 'A100')].service"
                        + ".serviceCharacteristic[?(@.name == 'kafkaEventId')].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("A"))
        .andExpect(jsonPath("$[1].id").value("B"))
        .andExpect(jsonPath("$[2].id").value("C"));
  }

  @Test
  void jsonPathWildcardIsAcceptedAsTransparentProjection() throws Exception {
    // ASC with nulls last: 2 (18), 1 (20.5), 3 (no price → null).
    mockMvc
        .perform(
            get("/products")
                .param("sort", "$.characteristic[?(@.name == 'price')][*].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("2"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  // ---------- Simple-rich grammar ----------

  @Test
  void simpleRichExplicitKeyEqualsValue() throws Exception {
    // ASC with nulls last: 2 (18), 1 (20.5), 3 (no price → null).
    mockMvc
        .perform(get("/products").param("sort", "characteristic[name=price].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("2"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  @Test
  void simpleRichDefaultKeyShorthandUsesIdByDefault() throws Exception {
    // The default-key for the bare-value bracket is `id`. The Characteristic class
    // doesn't have an `id` field, so a bare `[price]` would not match — exercising
    // the default-key with a known-id resource (RawOrderItem) makes more sense here.
    mongoTemplate.dropCollection(RawOrder.class);
    mongoTemplate.insertAll(
        List.of(
            new RawOrder(
                "X",
                List.of(
                    new RawOrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "X-event")))))),
            new RawOrder(
                "Y",
                List.of(
                    new RawOrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "Y-event"))))))));

    mockMvc
        .perform(
            get("/raw-orders")
                .param("sort", "serviceOrderItem[A100].service.serviceCharacteristic.value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("X"))
        .andExpect(jsonPath("$[1].id").value("Y"));
  }

  @Test
  void simpleRichTrailingPathCrossesObjectIntermediate() throws Exception {
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.insertAll(
        List.of(
            new Order(
                "B",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "B-event")))))),
            new Order(
                "A",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "A-event"))))))));

    // `service` is an object, not an array. Trailing dotted path Mongo-traverses it.
    mockMvc
        .perform(
            get("/orders")
                .param(
                    "sort",
                    "serviceOrderItem[id=A100].service.serviceCharacteristic.value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("A"))
        .andExpect(jsonPath("$[1].id").value("B"));
  }

  @Test
  void simpleRichTwoExplicitHopsWithPredicates() throws Exception {
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.insertAll(
        List.of(
            new Order(
                "B",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "B-event")))))),
            new Order(
                "A",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "A-event"))))))));

    mockMvc
        .perform(
            get("/orders")
                .param(
                    "sort",
                    "serviceOrderItem[id=A100].service.serviceCharacteristic[name=kafkaEventId].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("A"))
        .andExpect(jsonPath("$[1].id").value("B"));
  }

  @Test
  void simpleRichMaxAggregator() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product(
                "low",
                List.of(new Characteristic("score", 5), new Characteristic("score", 10))),
            new Product(
                "high",
                List.of(new Characteristic("score", 100), new Characteristic("score", 1)))));

    mockMvc
        .perform(
            get("/products")
                .param("sort", "+characteristic[name=score].max(value)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("low"))
        .andExpect(jsonPath("$[1].id").value("high"));
  }

  @Test
  void simpleRichMinAggregator() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product(
                "low",
                List.of(new Characteristic("score", 5), new Characteristic("score", 10))),
            new Product(
                "high",
                List.of(new Characteristic("score", 100), new Characteristic("score", 1)))));

    mockMvc
        .perform(
            get("/products")
                .param("sort", "+characteristic[name=score].min(value)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("high"))
        .andExpect(jsonPath("$[1].id").value("low"));
  }

  @Test
  void simpleRichStrCoercionAcrossMixedTypes() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("a", List.of(new Characteristic("price", 100))),
            new Product("b", List.of(new Characteristic("price", "50"))),
            new Product("c", List.of(new Characteristic("price", "abc")))));

    mockMvc
        .perform(
            get("/products")
                .param("sort", "+characteristic[name=price].str(value)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("a"))
        .andExpect(jsonPath("$[1].id").value("b"))
        .andExpect(jsonPath("$[2].id").value("c"));
  }

  @Test
  void simpleRichNumCoercionFailureYieldsNullSortingLastAscending() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("a", List.of(new Characteristic("price", 100))),
            new Product("b", List.of(new Characteristic("price", "50"))),
            new Product("c", List.of(new Characteristic("price", "abc")))));

    // c's "abc" fails num() → null sort key, lands last under the nulls-last policy;
    // b (50) < a (100) come first.
    mockMvc
        .perform(
            get("/products")
                .param("sort", "+characteristic[name=price].num(value)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("b"))
        .andExpect(jsonPath("$[1].id").value("a"))
        .andExpect(jsonPath("$[2].id").value("c"));
  }

  @Test
  void simpleRichComposedAggregatorOverCoercion() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product(
                "a",
                List.of(
                    new Characteristic("price", 100),
                    new Characteristic("price", "abc"))),
            new Product(
                "b",
                List.of(
                    new Characteristic("price", "50"),
                    new Characteristic("price", "9")))));

    // `max(str(value))` — coerce each element to string first, then take max
    // lexicographically: "100", "abc" → "abc"; "50", "9" → "9". So a > b.
    mockMvc
        .perform(
            get("/products")
                .param("sort", "+characteristic[name=price].max(str(value))"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("b"))
        .andExpect(jsonPath("$[1].id").value("a"));
  }

  // ---------- Field resolver (PR 6) ----------

  @Test
  void fieldResolverHandlesNestedIdWithoutFieldAnnotation() throws Exception {
    mongoTemplate.dropCollection(RawOrder.class);
    mongoTemplate.insertAll(
        List.of(
            new RawOrder(
                "X",
                List.of(
                    new RawOrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "X-event")))))),
            new RawOrder(
                "Y",
                List.of(
                    new RawOrderItem(
                        "A100",
                        new Service(
                            List.of(new Characteristic("kafkaEventId", "Y-event"))))))));

    // RawOrderItem.id has no @Field("id"); BSON stores it as `_id`. The user-facing
    // [id=A100] predicate must still match. The resolver translates `id` → `_id`
    // automatically based on the entity model.
    mockMvc
        .perform(
            get("/raw-orders")
                .param(
                    "sort",
                    "serviceOrderItem[id=A100].service.serviceCharacteristic[name=kafkaEventId].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("X"))
        .andExpect(jsonPath("$[1].id").value("Y"));
  }

  // ---------- Mixed plain + correlated terms ----------

  @Test
  void mixedPlainAndCorrelatedTermsInOneSort() throws Exception {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("a", List.of(new Characteristic("price", 10))),
            new Product("b", List.of(new Characteristic("price", 10))),
            new Product("c", List.of(new Characteristic("price", 5)))));

    mockMvc
        .perform(
            get("/products")
                .param(
                    "sort",
                    "-$.characteristic[?(@.name == 'price')].value,+id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("a"))
        .andExpect(jsonPath("$[1].id").value("b"))
        .andExpect(jsonPath("$[2].id").value("c"));
  }

  // ---------- TMF630 response shaping ----------

  @Test
  void pagingProduces206WithContentRangeAndTotalCountHeaders() throws Exception {
    mockMvc
        .perform(
            get("/products")
                .param("sort", "+id")
                .param("offset", "0")
                .param("limit", "2"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "items 1-2/3"))
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(header().string("X-Result-Count", "2"))
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void pagingProduces206WithCorrelatedSortToo() throws Exception {
    mockMvc
        .perform(
            get("/products")
                .param("sort", "$.characteristic[?(@.name == 'price')].value")
                .param("offset", "0")
                .param("limit", "2"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "items 1-2/3"))
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void fieldsParameterAppliesFieldSelectionToResponse() throws Exception {
    mockMvc
        .perform(
            get("/products")
                .param("sort", "$.characteristic[?(@.name == 'price')].value")
                .param("fields", "id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").exists())
        .andExpect(jsonPath("$[0].characteristic").doesNotExist());
  }

  @Test
  void emptyResultProduces416WhenOffsetExceedsTotal() throws Exception {
    mockMvc
        .perform(
            get("/products")
                .param("sort", "+id")
                .param("offset", "100")
                .param("limit", "10"))
        .andExpect(status().isRequestedRangeNotSatisfiable())
        .andExpect(jsonPath("$.code").value("416"));
  }

  // ---------- Two-parameter TmfRichPageable shape (recommended) ----------

  @Test
  void tmfPageablePlainSortGoesThroughFindPath() throws Exception {
    mockMvc
        .perform(get("/products-rich").param("sort", "+id"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(jsonPath("$[0].id").value("1"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  @Test
  void tmfPageableJsonPathSortGoesThroughAggregationPath() throws Exception {
    // ASC with nulls last: 2 (18), 1 (20.5), 3 (no price → null).
    mockMvc
        .perform(
            get("/products-rich")
                .param("sort", "$.characteristic[?(@.name == 'price')].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("2"))
        .andExpect(jsonPath("$[1].id").value("1"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  @Test
  void tmfPageableSimpleRichSortGoesThroughAggregationPath() throws Exception {
    // ASC with nulls last: 2 (18), 1 (20.5), 3 (no price → null).
    mockMvc
        .perform(get("/products-rich").param("sort", "characteristic[name=price].value"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("2"))
        .andExpect(jsonPath("$[2].id").value("3"));
  }

  @Test
  void tmfPageableProduces206WithCorrelatedSortAndPaging() throws Exception {
    mockMvc
        .perform(
            get("/products-rich")
                .param("sort", "$.characteristic[?(@.name == 'price')].value")
                .param("offset", "0")
                .param("limit", "2"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "items 1-2/3"))
        .andExpect(jsonPath("$.length()").value(2));
  }

  // ---------- Plain-Sort controller rejects correlated terms ----------

  @Test
  void plainSortControllerRejectsJsonPathTerm() throws Exception {
    mockMvc
        .perform(
            get("/products-plain")
                .param("sort", "$.characteristic[?(@.name == 'price')].value"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void plainSortControllerRejectsSimpleRichTerm() throws Exception {
    mockMvc
        .perform(
            get("/products-plain").param("sort", "characteristic[name=price].value"))
        .andExpect(status().isBadRequest());
  }

  @SpringBootApplication
  static class TestApp {}
}
