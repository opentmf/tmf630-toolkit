package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@SpringBootTest(classes = Tmf630MongoCorrelatedSortIT.TestApp.class)
@Testcontainers
class Tmf630MongoCorrelatedSortIT {

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private Tmf630MongoCorrelatedSortExecutor executor;

  @BeforeEach
  void setUp() {
    mongoTemplate.dropCollection(Product.class);
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

  @Test
  void sortsAscendingByCorrelatedPriceValueWithDocsLackingPriceLast() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristic[?(@.name == 'price')].value")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    assertEquals(3, page.getTotalElements());
    List<String> ids = page.getContent().stream().map(Product::getId).toList();
    // Doc 3 has no `price` characteristic; the executor pairs every sort key with a
    // _hasKey companion sorted ASC ahead of it, so missing keys land last regardless
    // of direction.
    assertEquals(List.of("2", "1", "3"), ids);
  }

  @Test
  void sortsDescendingByCorrelatedPriceValueWithDocsLackingPriceLast() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.DESC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristic[?(@.name == 'price')].value")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    List<String> ids = page.getContent().stream().map(Product::getId).toList();
    assertEquals(List.of("1", "2", "3"), ids);
  }

  @Test
  void appliesPagingToAggregationResult() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.DESC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristic[?(@.name == 'price')].value")));

    Page<Product> firstPage =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 2));
    assertEquals(3, firstPage.getTotalElements());
    assertEquals(2, firstPage.getContent().size());
    assertEquals(List.of("1", "2"), firstPage.getContent().stream().map(Product::getId).toList());

    Page<Product> secondPage =
        executor.findAll(Product.class, null, sort, PageRequest.of(1, 2));
    assertEquals(3, secondPage.getTotalElements());
    assertEquals(1, secondPage.getContent().size());
    assertEquals("3", secondPage.getContent().get(0).getId());
  }

  @Test
  void mixedPlainAndCorrelatedTermsSortByBothInOrder() {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product(
                "A",
                List.of(new Characteristic("price", 10))),
            new Product(
                "B",
                List.of(new Characteristic("price", 10))),
            new Product(
                "C",
                List.of(new Characteristic("price", 5)))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.DESC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristic[?(@.name == 'price')].value"),
                new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "id")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    assertEquals(List.of("A", "B", "C"), page.getContent().stream().map(Product::getId).toList());
  }

  @Test
  void sortsByTwoLevelNestedCorrelation() {
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.insertAll(
        List.of(
            new Order(
                "B",
                List.of(
                    new OrderItem(
                        "B100",
                        new Service(
                            List.of(
                                new Characteristic("kafkaEventId", "B123"),
                                new Characteristic("color", "blue")))))),
            new Order(
                "A",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(
                                new Characteristic("kafkaEventId", "A123"),
                                new Characteristic("color", "red")))))),
            new Order(
                "C",
                List.of(
                    new OrderItem(
                        "C100",
                        new Service(
                            List.of(
                                new Characteristic("kafkaEventId", "C123"),
                                new Characteristic("color", "green"))))))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.serviceOrderItem[?(@.id == 'A100' || @.id == 'B100' || @.id == 'C100')]"
                        + ".service.serviceCharacteristic[?(@.name == 'kafkaEventId')]"
                        + ".value")));

    Page<Order> page =
        executor.findAll(Order.class, null, sort, PageRequest.of(0, 10));

    List<String> ids = page.getContent().stream().map(Order::getId).toList();
    assertEquals(List.of("A", "B", "C"), ids);
  }

  @Test
  void sortsBySimpleRichExplicitKeyEqualsValue() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.DESC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristic[name=price].value")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    List<String> ids = page.getContent().stream().map(Product::getId).toList();
    assertEquals("1", ids.get(0));
    assertEquals("2", ids.get(1));
  }

  @Test
  void sortsAscendingByMaxAggregatorAcrossMatchingElements() {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product(
                "A",
                List.of(
                    new Characteristic("score", 10),
                    new Characteristic("score", 30),
                    new Characteristic("score", 20))),
            new Product("B", List.of(new Characteristic("score", 5), new Characteristic("score", 100))),
            new Product("C", List.of(new Characteristic("score", 50)))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristic[name=score].max(value)")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    assertEquals(
        List.of("A", "C", "B"),
        page.getContent().stream().map(Product::getId).toList());
  }

  @Test
  void sortsAscendingByMinAggregatorAcrossMatchingElements() {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product(
                "A",
                List.of(
                    new Characteristic("score", 10),
                    new Characteristic("score", 30),
                    new Characteristic("score", 20))),
            new Product("B", List.of(new Characteristic("score", 5), new Characteristic("score", 100))),
            new Product("C", List.of(new Characteristic("score", 50)))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristic[name=score].min(value)")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    assertEquals(
        List.of("B", "A", "C"),
        page.getContent().stream().map(Product::getId).toList());
  }

  @Test
  void coercesMixedTypeValuesToStringForLexicographicSort() {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("A", List.of(new Characteristic("price", 100))),
            new Product("B", List.of(new Characteristic("price", "50"))),
            new Product("C", List.of(new Characteristic("price", "abc")))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristic[name=price].str(value)")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    assertEquals(
        List.of("A", "B", "C"),
        page.getContent().stream().map(Product::getId).toList());
  }

  @Test
  void numCoercionFailureYieldsNullSortingNullLastAscending() {
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("A", List.of(new Characteristic("price", 100))),
            new Product("B", List.of(new Characteristic("price", "50"))),
            new Product("C", List.of(new Characteristic("price", "abc")))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristic[name=price].num(value)")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    List<String> ids = page.getContent().stream().map(Product::getId).toList();
    // C's value "abc" fails num() coercion → null sort key. With the _hasKey
    // companion sort, null keys land last on ASC; B (50) < A (100) come first.
    assertEquals("B", ids.get(0));
    assertEquals("A", ids.get(1));
    assertEquals("C", ids.get(2));
  }

  @Test
  void jsonPathWildcardIsAcceptedAsTransparentProjection() {
    // The canonical JsonPath wildcard `[*]` and the Mongo-style trailing path produce
    // identical aggregation results once the parser strips `[*]`. Run the same data
    // through both forms and confirm the order matches.
    TmfSort withWildcard =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristic[?(@.name == 'price')].value")));
    TmfSort withoutWildcard =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristic[?(@.name == 'price')][*].value")));

    Page<Product> a =
        executor.findAll(Product.class, null, withWildcard, PageRequest.of(0, 10));
    Page<Product> b =
        executor.findAll(Product.class, null, withoutWildcard, PageRequest.of(0, 10));

    List<String> aIds = a.getContent().stream().map(Product::getId).toList();
    List<String> bIds = b.getContent().stream().map(Product::getId).toList();
    assertEquals(aIds, bIds);
  }

  @Test
  void resolverHandlesNestedIdWithoutFieldAnnotation() {
    // RawOrderItem has NO @Field("id") on its `id` field — Spring Data writes it
    // as `_id` in BSON by default. PR 6's MongoFieldResolver consults the mapping
    // context at translation time and rewrites the user-facing `id` predicate to
    // `_id`. End to end: predicates like serviceOrderItem[id=A100] match correctly
    // even though the BSON key is `_id`.
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

    // Confirm that the BSON really uses `_id` for the nested item — proves the
    // resolver is doing real work below, not just passing through.
    org.bson.Document rawDoc =
        mongoTemplate
            .getCollection(mongoTemplate.getCollectionName(RawOrder.class))
            .find(new org.bson.Document("_id", "X"))
            .first();
    org.bson.Document nestedItem =
        rawDoc.getList("serviceOrderItem", org.bson.Document.class).get(0);
    assertEquals("A100", nestedItem.get("_id"));
    assertEquals(null, nestedItem.get("id"));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "serviceOrderItem[id=A100].service.serviceCharacteristic.value")));

    Page<RawOrder> page =
        executor.findAll(RawOrder.class, null, sort, PageRequest.of(0, 10));

    List<String> ids = page.getContent().stream().map(RawOrder::getId).toList();
    assertEquals(List.of("X", "Y"), ids);
  }

  @Test
  void simpleRichTraversesObjectIntermediateInTrailingPath() {
    // The trailing path serviceOrderItem[A100].service.serviceCharacteristic.value
    // crosses an object intermediate (`service`) before reaching the inner array
    // (`serviceCharacteristic`). Auto-traversal is required — naked-hop semantics
    // would attempt $filter on the object and fail.
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.insertAll(
        List.of(
            new Order(
                "B",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(
                                new Characteristic("kafkaEventId", "B-event"),
                                new Characteristic("color", "blue")))))),
            new Order(
                "A",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(
                                new Characteristic("kafkaEventId", "A-event"),
                                new Characteristic("color", "red")))))),
            new Order(
                "C",
                List.of(
                    new OrderItem(
                        "A100",
                        new Service(
                            List.of(
                                new Characteristic("kafkaEventId", "C-event"),
                                new Characteristic("color", "green"))))))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "serviceOrderItem[A100].service.serviceCharacteristic.value")));

    Page<Order> page =
        executor.findAll(Order.class, null, sort, PageRequest.of(0, 10));

    // Sort key for each doc is the leaf path through serviceCharacteristic (a
    // collection-typed intermediate). The translator wraps the per-element leaf
    // in $arrayElemAt so the synthetic _sortKey0 stays scalar — first element of
    // each Service's serviceCharacteristic[*].value is the kafkaEventId value:
    // "A-event" < "B-event" < "C-event".
    assertEquals(
        List.of("A", "B", "C"),
        page.getContent().stream().map(Order::getId).toList());
  }

  @Test
  void syntheticKeysAreStrippedFromReturnedDocuments() {
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.JSONPATH,
                    "$.characteristic[?(@.name == 'price')].value")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    for (Product p : page.getContent()) {
      assertTrue(p.getId() != null && !p.getId().isEmpty());
    }
  }

  @Test
  void plainSortAscPutsMissingFieldRowsLast() {
    // Colleague's repro of the nulls-position divergence: a plain top-level sort
    // term against a dataset where some rows omit the field. Mongo's BSON natural
    // order would put the missing-field row first in ASC — the executor's
    // _hasKey companion overrides this to nulls-last regardless of direction.
    mongoTemplate.dropCollection(Product.class);
    mongoTemplate.insertAll(
        List.of(
            new Product("apple", List.of(new Characteristic("price", 1)), "apple"),
            new Product("zebra", List.of(new Characteristic("price", 1)), "zebra"),
            new Product("missing", List.of(new Characteristic("price", 1)), null)));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "description")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    assertEquals(
        List.of("apple", "zebra", "missing"),
        page.getContent().stream().map(Product::getId).toList());
  }

  @Test
  void correlatedSortAscPutsRowsLackingMatchingArrayElementLast() {
    // Colleague's repro of the correlated-form nulls-position divergence: rows
    // where the predicate matches no element in the target array land last in
    // ASC because the _hasKey companion is ASC ahead of the (null) sort key.
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "characteristic[name=price].value")));

    Page<Product> page =
        executor.findAll(Product.class, null, sort, PageRequest.of(0, 10));

    // Doc 3 carries `stock` and `color`/`size` characteristics but no `price`;
    // the correlated filter returns no element, sort key resolves null, doc lands
    // last in ASC.
    assertEquals(
        List.of("2", "1", "3"),
        page.getContent().stream().map(Product::getId).toList());
  }

  @Test
  void multipleCorrelatedTermsWithArrayIntermediateLeavesDoNotTriggerParallelArraysError() {
    // Colleague's repro of the parallel-arrays bug: two correlated sort terms,
    // each filtering the same outer array by a different predicate, and each
    // descending into an inner collection-typed intermediate at the leaf
    // (`service.serviceCharacteristic.value`). Without per-leaf scalar reduction
    // both _sortKey0 and _sortKey1 carry auto-projected arrays, and Mongo
    // refuses the multi-key $sort with "cannot sort with keys that are parallel
    // arrays" (BadValue, code 2). The translator wraps each leaf in
    // $arrayElemAt so both keys stay scalar.
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.insertAll(
        List.of(
            new Order(
                "A",
                List.of(
                    new OrderItem(
                        "RC_OFFER_TYPE",
                        new Service(List.of(new Characteristic("type", "Z")))),
                    new OrderItem(
                        "STARTING_PRICE",
                        new Service(List.of(new Characteristic("price", "200")))))),
            new Order(
                "B",
                List.of(
                    new OrderItem(
                        "RC_OFFER_TYPE",
                        new Service(List.of(new Characteristic("type", "X")))),
                    new OrderItem(
                        "STARTING_PRICE",
                        new Service(List.of(new Characteristic("price", "300")))))),
            new Order(
                "C",
                List.of(
                    new OrderItem(
                        "RC_OFFER_TYPE",
                        new Service(List.of(new Characteristic("type", "Y")))),
                    new OrderItem(
                        "STARTING_PRICE",
                        new Service(List.of(new Characteristic("price", "100"))))))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "serviceOrderItem[id=RC_OFFER_TYPE].service.serviceCharacteristic.value"),
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "serviceOrderItem[id=STARTING_PRICE].service.serviceCharacteristic.value")));

    Page<Order> page =
        executor.findAll(Order.class, null, sort, PageRequest.of(0, 10));

    // First term values (RC_OFFER_TYPE): A=Z, B=X, C=Y → ASC sort: X, Y, Z → B, C, A
    assertEquals(
        List.of("B", "C", "A"),
        page.getContent().stream().map(Order::getId).toList());
  }

  @Test
  void multipleCorrelatedTermsResolveTieUsingSecondTerm() {
    // Same multi-term shape as above, but with seed data that ties the first
    // term so the second term decides the order. Confirms both keys are
    // honoured by $sort rather than silently collapsed.
    mongoTemplate.dropCollection(Order.class);
    mongoTemplate.insertAll(
        List.of(
            new Order(
                "A",
                List.of(
                    new OrderItem(
                        "RC_OFFER_TYPE",
                        new Service(List.of(new Characteristic("type", "X")))),
                    new OrderItem(
                        "STARTING_PRICE",
                        new Service(List.of(new Characteristic("price", "200")))))),
            new Order(
                "B",
                List.of(
                    new OrderItem(
                        "RC_OFFER_TYPE",
                        new Service(List.of(new Characteristic("type", "X")))),
                    new OrderItem(
                        "STARTING_PRICE",
                        new Service(List.of(new Characteristic("price", "100")))))),
            new Order(
                "C",
                List.of(
                    new OrderItem(
                        "RC_OFFER_TYPE",
                        new Service(List.of(new Characteristic("type", "Y")))),
                    new OrderItem(
                        "STARTING_PRICE",
                        new Service(List.of(new Characteristic("price", "999"))))))));

    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "serviceOrderItem[id=RC_OFFER_TYPE].service.serviceCharacteristic.value"),
                new TmfSortTerm(
                    Sort.Direction.ASC,
                    TmfSortTerm.Kind.SIMPLE_RICH,
                    "serviceOrderItem[id=STARTING_PRICE].service.serviceCharacteristic.value")));

    Page<Order> page =
        executor.findAll(Order.class, null, sort, PageRequest.of(0, 10));

    // First term: A=X, B=X, C=Y → A and B tie at X, C sorts after.
    // Second term among A and B: A=200, B=100 → B before A.
    // Final: B, A, C.
    assertEquals(
        List.of("B", "A", "C"),
        page.getContent().stream().map(Order::getId).toList());
  }

  @SpringBootApplication
  static class TestApp {}
}
