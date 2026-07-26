package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.mongo.split.it.MongoSplitOrder;
import org.opentmf.query.tmf630.mongo.split.it.MongoSplitOrderItem;
import org.opentmf.query.tmf630.mongo.split.it.MongoSplitOrderItemSubController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase (d.7 MVP): parity IT for the Mongo split-and-merge feature set. The fixtures
 * mirror the JSONB module's {@code SplitOrderDomain}/{@code SplitOrderItem}, so the
 * expected behaviors are identical to
 * {@code Tmf630JsonbSplitCollectionIT}'s equivalent scenarios.
 *
 * <p>Scope: exercises d.1 (annotation + registry + write hook) and d.5 (sub-endpoint
 * controller) end-to-end. Read-merge is verified via
 * {@link Tmf630MongoSplitReadMerger}.
 *
 * <p>Out of scope for this MVP cut (deferred to later d.x sub-milestones):
 * <ul>
 *   <li>d.2 &mdash; split-aware URL filter routing (the generic predicate-splitter is
 *       still c.2/c.3-style specific to Postgres).
 *   <li>d.3 &mdash; three-shape aggregation router (item-first / parent-first /
 *       {@code $unionWith}).
 *   <li>d.4 &mdash; {@code $lookup} inner-pipeline emission.
 *   <li>d.6 &mdash; PATCH ops beyond {@code saveWithSplits} (per-item modify/remove).
 * </ul>
 */
@SpringBootTest(classes = Tmf630MongoSplitCollectionIT.TestApp.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class Tmf630MongoSplitCollectionIT {

  @Container
  static final MongoDBContainer mongo =
      new MongoDBContainer(DockerImageName.parse("mongo:6.0"));

  @DynamicPropertySource
  static void mongoProps(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MongoOperations mongoOperations;
  @Autowired private Tmf630MongoSplitWriteExecutor writeExecutor;
  @Autowired private Tmf630MongoSplitReadMerger readMerger;
  @Autowired private MongoSplitAwareFilterTranslator splitAwareFilter;
  @Autowired private Tmf630MongoSplitChildCounter childCounter;
  @Autowired private MockMvc mockMvc;
  @Autowired private MongoSplitEntityRegistry registry;

  @BeforeEach
  void wipe() {
    mongoOperations.dropCollection("split_order");
    mongoOperations.dropCollection("split_order_item");
  }

  @Test
  @DisplayName("registry picks up @Tmf630MongoSplitBacked domain and its splits")
  void registryDiscoversAnnotatedDomain() {
    MongoSplitEntityMetadata metadata =
        registry.forParentType(MongoSplitOrder.class).orElseThrow();
    assertThat(metadata.parentCollection()).isEqualTo("split_order");
    assertThat(metadata.splits()).hasSize(1);
    MongoSplitCollectionMetadata split = metadata.splits().get(0);
    assertThat(split.fieldName()).isEqualTo("items");
    assertThat(split.childCollection()).isEqualTo("split_order_item");
    assertThat(split.childType()).isEqualTo(MongoSplitOrderItem.class);
    assertThat(split.maxInlineItems()).isEqualTo(100);
    assertThat(split.parentIdField()).isEqualTo("parentId");
    assertThat(split.itemIdField()).isEqualTo("itemId");
    assertThat(split.itemOrderField()).isEqualTo("itemOrder");
  }

  @Test
  @DisplayName("saveWithSplits round-trips parent + children")
  void saveWithSplitsRoundTrip() {
    MongoSplitOrder order = order("R1", "OPEN", List.of(item("a", "P"), item("b", "S")));
    writeExecutor.saveWithSplits(order);

    // Parent is persisted without the items field embedded.
    org.bson.Document parentDoc =
        mongoOperations
            .getCollection("split_order")
            .find(new org.bson.Document("_id", "R1"))
            .first();
    assertThat(parentDoc).isNotNull();
    assertThat(parentDoc.get("items")).isNull();

    // Children land in split_order_item with parentId back-ref and itemOrder.
    long childCount =
        mongoOperations.count(
            new Query(Criteria.where("parentId").is("R1")), "split_order_item");
    assertThat(childCount).isEqualTo(2);

    // Read merge reconstitutes the domain: children in original order.
    MongoSplitOrder loaded =
        mongoOperations.findById("R1", MongoSplitOrder.class, "split_order");
    readMerger.merge(loaded);
    assertThat(loaded).isNotNull();
    assertThat(loaded.getItems()).extracting(MongoSplitOrderItem::getId).containsExactly("a", "b");
    assertThat(loaded.getItems()).extracting(MongoSplitOrderItem::getState).containsExactly("P", "S");
  }

  @Test
  @DisplayName("resave replaces all children (full-doc PATCH semantics)")
  void resaveReplacesAllChildren() {
    writeExecutor.saveWithSplits(order("R2", "OPEN", List.of(item("a", "P"), item("b", "S"))));
    writeExecutor.saveWithSplits(order("R2", "COMPLETED", List.of(item("c", "S"))));

    long count =
        mongoOperations.count(
            new Query(Criteria.where("parentId").is("R2")), "split_order_item");
    assertThat(count).isEqualTo(1);
    MongoSplitOrder loaded =
        mongoOperations.findById("R2", MongoSplitOrder.class, "split_order");
    readMerger.merge(loaded);
    assertThat(loaded.getStatus()).isEqualTo("COMPLETED");
    assertThat(loaded.getItems())
        .extracting(MongoSplitOrderItem::getId)
        .containsExactly("c");
  }

  @Test
  @DisplayName("appendChild adds a single item with correct positional itemOrder")
  void appendChildOptimisation() {
    writeExecutor.saveWithSplits(order("R3", "OPEN", List.of(item("a", "P"), item("b", "S"))));
    writeExecutor.appendChild(MongoSplitOrder.class, "R3", item("c", "S"));

    List<org.bson.Document> children =
        mongoOperations
            .getCollection("split_order_item")
            .find(new org.bson.Document("parentId", "R3"))
            .sort(new org.bson.Document("itemOrder", 1))
            .into(new ArrayList<>());
    assertThat(children).hasSize(3);
    assertThat(children.get(2).getString("itemId")).isEqualTo("c");
    assertThat(children.get(2).getInteger("itemOrder")).isEqualTo(2);
  }

  @Test
  @DisplayName("child items without explicit id get positional fallback id")
  void autoAssignedChildIds() {
    MongoSplitOrderItem noId = new MongoSplitOrderItem();
    noId.setState("X");
    writeExecutor.saveWithSplits(order("R4", "OPEN", List.of(noId, item("named", "Y"))));

    List<org.bson.Document> children =
        mongoOperations
            .getCollection("split_order_item")
            .find(new org.bson.Document("parentId", "R4"))
            .sort(new org.bson.Document("itemOrder", 1))
            .into(new ArrayList<>());
    assertThat(children.get(0).getString("itemId")).isEqualTo("i-0");
    assertThat(children.get(1).getString("itemId")).isEqualTo("named");
  }

  @Test
  @DisplayName("sub-endpoint returns paginated children for a parent")
  void subEndpointPaged() throws Exception {
    writeExecutor.saveWithSplits(
        order(
            "R5",
            "OPEN",
            List.of(item("a", "P"), item("b", "P"), item("c", "S"), item("d", "S"))));

    mockMvc
        .perform(get("/split-orders/R5/items?page=0&size=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(4))
        .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$.content[0].id").value("a"))
        .andExpect(jsonPath("$.content[1].id").value("b"));

    mockMvc
        .perform(get("/split-orders/R5/items?page=1&size=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value("c"))
        .andExpect(jsonPath("$.content[1].id").value("d"));
  }

  @Test
  @DisplayName("sub-endpoint returns single child or 404")
  void subEndpointSingleChild() throws Exception {
    writeExecutor.saveWithSplits(order("R6", "OPEN", List.of(item("a", "P"))));

    mockMvc
        .perform(get("/split-orders/R6/items/a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("a"))
        .andExpect(jsonPath("$.state").value("P"));

    mockMvc.perform(get("/split-orders/R6/items/nope")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("empty child list is round-tripped as an empty items list, not null")
  void emptyChildList() {
    writeExecutor.saveWithSplits(order("R7", "OPEN", List.of()));
    MongoSplitOrder loaded =
        mongoOperations.findById("R7", MongoSplitOrder.class, "split_order");
    readMerger.merge(loaded);
    assertThat(loaded.getItems()).isEmpty();
  }

  @Test
  @DisplayName(
      "split-aware filter routes item-side predicate to child collection, returns parent-side criteria")
  void splitAwareFilterRoutesToChildCollection() {
    // Two parents. Only R-B has an item in state 'PENDING' — filter should pick R-B.
    writeExecutor.saveWithSplits(
        order("R-A", "OPEN", List.of(item("a1", "SHIPPED"), item("a2", "SHIPPED"))));
    writeExecutor.saveWithSplits(
        order("R-B", "OPEN", List.of(item("b1", "PENDING"), item("b2", "SHIPPED"))));

    org.springframework.data.mongodb.core.query.Criteria criteria =
        splitAwareFilter.translate(
            MongoSplitOrder.class, "$[?(@.items[?(@.state == 'PENDING')])]");
    assertThat(criteria).isNotNull();

    List<MongoSplitOrder> matched =
        mongoOperations.find(
            new org.springframework.data.mongodb.core.query.Query(criteria),
            MongoSplitOrder.class,
            "split_order");
    assertThat(matched).extracting(MongoSplitOrder::getId).containsExactly("R-B");
  }

  @Test
  @DisplayName(
      "split-aware filter with same-element correlation semantics — item must satisfy full predicate")
  void splitAwareFilterSameElementSemantics() {
    // A single item in R-C is PENDING, but its priority is 3 (not > 5). No item in
    // R-C satisfies BOTH conditions on a single item, so the parent should NOT match.
    writeExecutor.saveWithSplits(
        order("R-C", "OPEN", List.of(itemP("p1", "PENDING", 3), itemP("p2", "SHIPPED", 8))));

    org.springframework.data.mongodb.core.query.Criteria criteria =
        splitAwareFilter.translate(
            MongoSplitOrder.class,
            "$[?(@.items[?(@.state == 'PENDING' && @.priority > 5)])]");
    List<MongoSplitOrder> matched =
        mongoOperations.find(
            new org.springframework.data.mongodb.core.query.Query(criteria),
            MongoSplitOrder.class,
            "split_order");
    assertThat(matched).isEmpty();

    // Now add R-D with a single item that DOES satisfy both — it should match.
    writeExecutor.saveWithSplits(
        order("R-D", "OPEN", List.of(itemP("d1", "PENDING", 9))));
    matched =
        mongoOperations.find(
            new org.springframework.data.mongodb.core.query.Query(
                splitAwareFilter.translate(
                    MongoSplitOrder.class,
                    "$[?(@.items[?(@.state == 'PENDING' && @.priority > 5)])]")),
            MongoSplitOrder.class,
            "split_order");
    assertThat(matched).extracting(MongoSplitOrder::getId).containsExactly("R-D");
  }

  @Test
  @DisplayName(
      "split-aware filter with no matching children returns a criteria that matches zero parents")
  void splitAwareFilterEmptyChildMatch() {
    writeExecutor.saveWithSplits(
        order("R-E", "OPEN", List.of(item("e1", "SHIPPED"))));

    org.springframework.data.mongodb.core.query.Criteria criteria =
        splitAwareFilter.translate(
            MongoSplitOrder.class, "$[?(@.items[?(@.state == 'NEVER')])]");
    List<MongoSplitOrder> matched =
        mongoOperations.find(
            new org.springframework.data.mongodb.core.query.Query(criteria),
            MongoSplitOrder.class,
            "split_order");
    assertThat(matched).isEmpty();
  }

  @Test
  @DisplayName("read-merge on an untracked type is a no-op (safe to call in generic paths)")
  void readMergeOnUntrackedType() {
    // A plain POJO that isn't @Tmf630MongoSplitBacked. Merger returns it unchanged.
    Object untracked = new Object();
    assertThat(readMerger.merge(untracked)).isSameAs(untracked);
  }

  @Test
  @DisplayName("read-merge caps at maxInlineItems even when the child collection has more")
  void readMergeRespectsInlineCap() {
    // maxInlineItems on the fixture is 100. Seed 120 to prove the cap fires.
    List<MongoSplitOrderItem> children = new ArrayList<>();
    for (int i = 0; i < 120; i++) children.add(item("i-" + i, "P"));
    writeExecutor.saveWithSplits(order("R8", "OPEN", children));

    MongoSplitOrder loaded =
        mongoOperations.findById("R8", MongoSplitOrder.class, "split_order");
    readMerger.merge(loaded);
    assertThat(loaded.getItems()).hasSize(100);
    assertThat(loaded.getItems().get(0).getId()).isEqualTo("i-0");
    assertThat(loaded.getItems().get(99).getId()).isEqualTo("i-99");
  }

  @Test
  @DisplayName(
      "childCounter.count: single-parent true count + truncated flag reflects inline cap")
  void childCounterSingleParent() {
    // 2 items — well under 100 cap. Not truncated.
    writeExecutor.saveWithSplits(
        order("CC1", "OPEN", List.of(item("a", "P"), item("b", "S"))));
    Tmf630MongoSplitChildCounter.ChildCountInfo info =
        childCounter.count(MongoSplitOrder.class, "CC1", MongoSplitOrderItem.class);
    assertThat(info.trueCount()).isEqualTo(2L);
    assertThat(info.maxInlineItems()).isEqualTo(100);
    assertThat(info.truncated()).isFalse();

    // 150 items — exceeds cap. Truncated.
    List<MongoSplitOrderItem> many = new ArrayList<>();
    for (int i = 0; i < 150; i++) many.add(item("i-" + i, "P"));
    writeExecutor.saveWithSplits(order("CC2", "OPEN", many));
    Tmf630MongoSplitChildCounter.ChildCountInfo bigInfo =
        childCounter.count(MongoSplitOrder.class, "CC2", MongoSplitOrderItem.class);
    assertThat(bigInfo.trueCount()).isEqualTo(150L);
    assertThat(bigInfo.truncated()).isTrue();
  }

  @Test
  @DisplayName("childCounter.countAll: batch returns count per parent, zero for missing parents")
  void childCounterBatch() {
    writeExecutor.saveWithSplits(order("CB1", "OPEN", List.of(item("i", "P"))));
    writeExecutor.saveWithSplits(
        order("CB2", "OPEN", List.of(item("a", "P"), item("b", "P"), item("c", "S"))));

    Map<String, Long> counts =
        childCounter.countAll(
            MongoSplitOrder.class,
            List.of("CB1", "CB2", "CB-MISSING"),
            MongoSplitOrderItem.class);
    assertThat(counts).containsEntry("CB1", 1L).containsEntry("CB2", 3L).containsEntry("CB-MISSING", 0L);
  }

  @Test
  @DisplayName(
      "OR shape: parent || split via $unionWith returns union of matches (parent + one split)")
  void unionWithParentPlusSplit() {
    // U1: CANCELLED order (matches parent side). Item is SHIPPED (doesn't match split side).
    writeExecutor.saveWithSplits(order("U1", "CANCELLED", List.of(item("i", "SHIPPED"))));
    // U2: OPEN order with a PENDING item (matches split side only).
    writeExecutor.saveWithSplits(order("U2", "OPEN", List.of(item("i", "PENDING"))));
    // U3: OPEN order with a SHIPPED item (matches neither).
    writeExecutor.saveWithSplits(order("U3", "OPEN", List.of(item("i", "SHIPPED"))));

    SplitAwareAggregation packaged =
        splitAwareFilter.translateAsUnionWithAggregation(
            MongoSplitOrder.class,
            "$[?(@.status == 'CANCELLED' || @.items[?(@.state == 'PENDING')])]");
    assertThat(packaged).isNotNull();
    assertThat(packaged.targetCollection()).isEqualTo("split_order");

    List<MongoSplitOrder> matched =
        mongoOperations
            .aggregate(packaged.pipeline(), packaged.targetCollection(), MongoSplitOrder.class)
            .getMappedResults();
    assertThat(matched)
        .extracting(MongoSplitOrder::getId)
        .containsExactlyInAnyOrder("U1", "U2");
  }

  @Test
  @DisplayName(
      "OR shape: two split correlations OR'd — no parent-only clause → starts on first child collection")
  void unionWithTwoSplitsNoParent() {
    // The fixture only has one split (items). Fake a two-split filter by targeting
    // items with two different-state disjuncts — the decomposer treats each as a
    // separate split correlation (different-item semantics).
    writeExecutor.saveWithSplits(order("V1", "OPEN", List.of(item("i", "PENDING"))));
    writeExecutor.saveWithSplits(order("V2", "OPEN", List.of(item("i", "REJECTED"))));
    writeExecutor.saveWithSplits(order("V3", "OPEN", List.of(item("i", "SHIPPED"))));

    SplitAwareAggregation packaged =
        splitAwareFilter.translateAsUnionWithAggregation(
            MongoSplitOrder.class,
            "$[?(@.items[?(@.state == 'PENDING')] || @.items[?(@.state == 'REJECTED')])]");
    assertThat(packaged).isNotNull();
    // No parent-only clause → target = first split's child collection.
    assertThat(packaged.targetCollection()).isEqualTo("split_order_item");

    List<MongoSplitOrder> matched =
        mongoOperations
            .aggregate(packaged.pipeline(), packaged.targetCollection(), MongoSplitOrder.class)
            .getMappedResults();
    assertThat(matched)
        .extracting(MongoSplitOrder::getId)
        .containsExactlyInAnyOrder("V1", "V2");
  }

  @Test
  @DisplayName("OR shape: dedup keeps a single row per parent even when multiple disjuncts match")
  void unionWithDedupsMultiMatch() {
    // W1 matches BOTH sides (CANCELLED and has a PENDING item). Must appear once.
    writeExecutor.saveWithSplits(order("W1", "CANCELLED", List.of(item("i", "PENDING"))));

    SplitAwareAggregation packaged =
        splitAwareFilter.translateAsUnionWithAggregation(
            MongoSplitOrder.class,
            "$[?(@.status == 'CANCELLED' || @.items[?(@.state == 'PENDING')])]");
    List<MongoSplitOrder> matched =
        mongoOperations
            .aggregate(packaged.pipeline(), packaged.targetCollection(), MongoSplitOrder.class)
            .getMappedResults();
    assertThat(matched).extracting(MongoSplitOrder::getId).containsExactly("W1");
  }

  @Test
  @DisplayName(
      "d.3 item-first: single-split filter routes to child collection and returns matching parents")
  void itemFirstPipelineReturnsMatchingParents() {
    writeExecutor.saveWithSplits(
        order("IF-A", "OPEN", List.of(item("a1", "SHIPPED"), item("a2", "SHIPPED"))));
    writeExecutor.saveWithSplits(
        order("IF-B", "OPEN", List.of(item("b1", "PENDING"), item("b2", "SHIPPED"))));
    writeExecutor.saveWithSplits(
        order("IF-C", "OPEN", List.of(item("c1", "PENDING"))));

    SplitAwareAggregation packaged =
        splitAwareFilter.translateAsItemFirstAggregation(
            MongoSplitOrder.class, "$[?(@.items[?(@.state == 'PENDING')])]");
    assertThat(packaged).isNotNull();
    // Item-first pipelines target the CHILD collection.
    assertThat(packaged.targetCollection()).isEqualTo("split_order_item");

    List<MongoSplitOrder> matched =
        mongoOperations
            .aggregate(packaged.pipeline(), packaged.targetCollection(), MongoSplitOrder.class)
            .getMappedResults();
    assertThat(matched)
        .extracting(MongoSplitOrder::getId)
        .containsExactlyInAnyOrder("IF-B", "IF-C");
  }

  @Test
  @DisplayName(
      "d.3 item-first: parent-only filter throws with actionable message pointing to parent-first")
  void itemFirstRejectsParentOnly() {
    org.junit.jupiter.api.Assertions.assertThrows(
        org.opentmf.query.tmf630.filtering.TmfFilteringException.class,
        () ->
            splitAwareFilter.translateAsItemFirstAggregation(
                MongoSplitOrder.class, "$[?(@.status == 'OPEN' && @.items[?(@.state == 'X')])]"));
  }

  @Test
  @DisplayName(
      "d.3 item-first: multi-split filter throws with actionable message pointing to parent-first")
  void itemFirstRejectsMultiSplit() {
    org.junit.jupiter.api.Assertions.assertThrows(
        org.opentmf.query.tmf630.filtering.TmfFilteringException.class,
        () ->
            splitAwareFilter.translateAsItemFirstAggregation(
                MongoSplitOrder.class,
                "$[?(@.items[?(@.state == 'A')] && @.items[?(@.state == 'B')])]"));
  }

  @Test
  @DisplayName(
      "d.2 full: compound filter parent + split conjunction returns intersection (criteria form)")
  void compoundParentAndSplitCriteria() {
    writeExecutor.saveWithSplits(
        order("Q1", "OPEN", List.of(item("i1", "PENDING"))));
    writeExecutor.saveWithSplits(
        order("Q2", "COMPLETED", List.of(item("i1", "PENDING"))));
    writeExecutor.saveWithSplits(
        order("Q3", "OPEN", List.of(item("i1", "SHIPPED"))));

    org.springframework.data.mongodb.core.query.Criteria where =
        splitAwareFilter.translate(
            MongoSplitOrder.class,
            "$[?(@.status == 'OPEN' && @.items[?(@.state == 'PENDING')])]");
    List<MongoSplitOrder> matched =
        mongoOperations.find(
            new org.springframework.data.mongodb.core.query.Query(where),
            MongoSplitOrder.class,
            "split_order");
    assertThat(matched).extracting(MongoSplitOrder::getId).containsExactly("Q1");
  }

  @Test
  @DisplayName(
      "d.3+d.4 full: compound filter parent + split via pipeline emits $match + $lookup, "
          + "returns intersection")
  void compoundParentAndSplitPipeline() {
    writeExecutor.saveWithSplits(
        order("QP1", "OPEN", List.of(item("i1", "PENDING"))));
    writeExecutor.saveWithSplits(
        order("QP2", "COMPLETED", List.of(item("i1", "PENDING"))));
    writeExecutor.saveWithSplits(
        order("QP3", "OPEN", List.of(item("i1", "SHIPPED"))));

    org.springframework.data.mongodb.core.aggregation.Aggregation pipeline =
        splitAwareFilter.translateAsPipeline(
            MongoSplitOrder.class,
            "$[?(@.status == 'OPEN' && @.items[?(@.state == 'PENDING')])]");
    List<MongoSplitOrder> matched =
        mongoOperations.aggregate(pipeline, "split_order", MongoSplitOrder.class).getMappedResults();
    assertThat(matched).extracting(MongoSplitOrder::getId).containsExactly("QP1");
  }

  @Test
  @DisplayName(
      "d.3+d.4 translateAsPipeline: single-round-trip $lookup pipeline returns matching parents")
  void pipelineRoutesToLookupAndReturnsMatchingParents() {
    writeExecutor.saveWithSplits(
        order("P-A", "OPEN", List.of(item("a1", "SHIPPED"), item("a2", "SHIPPED"))));
    writeExecutor.saveWithSplits(
        order("P-B", "OPEN", List.of(item("b1", "PENDING"), item("b2", "SHIPPED"))));
    writeExecutor.saveWithSplits(
        order("P-C", "OPEN", List.of(item("c1", "PENDING"))));

    org.springframework.data.mongodb.core.aggregation.Aggregation pipeline =
        splitAwareFilter.translateAsPipeline(
            MongoSplitOrder.class, "$[?(@.items[?(@.state == 'PENDING')])]");
    assertThat(pipeline).isNotNull();

    List<MongoSplitOrder> matched =
        mongoOperations.aggregate(pipeline, "split_order", MongoSplitOrder.class).getMappedResults();
    assertThat(matched)
        .extracting(MongoSplitOrder::getId)
        .containsExactlyInAnyOrder("P-B", "P-C");
  }

  @Test
  @DisplayName(
      "d.3+d.4 translateAsPipeline: same-element correlation semantics preserved via inner pipeline $match")
  void pipelineSameElementSemantics() {
    // Only P-Y has a single item satisfying both PENDING and priority > 5.
    writeExecutor.saveWithSplits(
        order("P-X", "OPEN", List.of(itemP("x1", "PENDING", 3), itemP("x2", "SHIPPED", 8))));
    writeExecutor.saveWithSplits(
        order("P-Y", "OPEN", List.of(itemP("y1", "PENDING", 9))));

    org.springframework.data.mongodb.core.aggregation.Aggregation pipeline =
        splitAwareFilter.translateAsPipeline(
            MongoSplitOrder.class,
            "$[?(@.items[?(@.state == 'PENDING' && @.priority > 5)])]");
    List<MongoSplitOrder> matched =
        mongoOperations.aggregate(pipeline, "split_order", MongoSplitOrder.class).getMappedResults();
    assertThat(matched).extracting(MongoSplitOrder::getId).containsExactly("P-Y");
  }

  @Test
  @DisplayName(
      "d.3+d.4 translateAsPipeline: no matching child produces an empty result (not an error)")
  void pipelineNoMatchProducesEmpty() {
    writeExecutor.saveWithSplits(
        order("P-Z", "OPEN", List.of(item("z1", "SHIPPED"))));
    org.springframework.data.mongodb.core.aggregation.Aggregation pipeline =
        splitAwareFilter.translateAsPipeline(
            MongoSplitOrder.class, "$[?(@.items[?(@.state == 'IMPOSSIBLE')])]");
    List<MongoSplitOrder> matched =
        mongoOperations.aggregate(pipeline, "split_order", MongoSplitOrder.class).getMappedResults();
    assertThat(matched).isEmpty();
  }

  @Test
  @DisplayName(
      "d.3+d.4 translateAsPipeline: parent-only filter returns null (caller uses normal path)")
  void pipelineParentOnlyReturnsNull() {
    org.springframework.data.mongodb.core.aggregation.Aggregation pipeline =
        splitAwareFilter.translateAsPipeline(
            MongoSplitOrder.class, "$[?(@.status == 'OPEN')]");
    assertThat(pipeline).isNull();
  }

  @Test
  @DisplayName("d.6 updateChild: replaces one child's payload in place, preserves itemOrder")
  void updateChildReplacesPayloadOnly() {
    writeExecutor.saveWithSplits(
        order("U1", "OPEN", List.of(item("a", "X"), item("b", "Y"), item("c", "Z"))));

    int modified =
        writeExecutor.updateChild(MongoSplitOrder.class, "U1", "b", item("b", "MODIFIED"));
    assertThat(modified).isEqualTo(1);

    List<org.bson.Document> after =
        mongoOperations
            .getCollection("split_order_item")
            .find(new org.bson.Document("parentId", "U1"))
            .sort(new org.bson.Document("itemOrder", 1))
            .into(new ArrayList<>());
    assertThat(after).hasSize(3);
    assertThat(after.get(1).get("itemId")).isEqualTo("b");
    assertThat(after.get(1).get("itemOrder")).isEqualTo(1);
    org.bson.Document bPayload = (org.bson.Document) after.get(1).get("payload");
    assertThat(bPayload.getString("state")).isEqualTo("MODIFIED");
  }

  @Test
  @DisplayName("d.6 updateChild: returns 0 for non-existent (parent, item)")
  void updateChildMissingReturnsZero() {
    writeExecutor.saveWithSplits(order("U2", "OPEN", List.of(item("a", "X"))));
    int modified =
        writeExecutor.updateChild(MongoSplitOrder.class, "U2", "nope", item("nope", "V"));
    assertThat(modified).isZero();
  }

  @Test
  @DisplayName("d.6 removeChild: deletes one child, leaves the rest alone")
  void removeChildDeletesOne() {
    writeExecutor.saveWithSplits(
        order("D1", "OPEN", List.of(item("a", "X"), item("b", "Y"), item("c", "Z"))));
    int removed =
        writeExecutor.removeChild(MongoSplitOrder.class, "D1", "b", MongoSplitOrderItem.class);
    assertThat(removed).isEqualTo(1);

    List<String> remaining =
        mongoOperations
            .getCollection("split_order_item")
            .find(new org.bson.Document("parentId", "D1"))
            .sort(new org.bson.Document("itemOrder", 1))
            .into(new ArrayList<>())
            .stream()
            .map(d -> (String) d.get("itemId"))
            .toList();
    assertThat(remaining).containsExactly("a", "c");
  }

  @Test
  @DisplayName("d.6 removeChild: returns 0 for non-existent (parent, item)")
  void removeChildMissingReturnsZero() {
    writeExecutor.saveWithSplits(order("D2", "OPEN", List.of(item("a", "X"))));
    int removed =
        writeExecutor.removeChild(MongoSplitOrder.class, "D2", "nope", MongoSplitOrderItem.class);
    assertThat(removed).isZero();
  }

  @Test
  @DisplayName("d.6 reindexChildren: compacts itemOrder to 0..N-1 after gaps")
  void reindexChildrenCompactsOrder() {
    writeExecutor.saveWithSplits(
        order(
            "R1",
            "OPEN",
            List.of(item("a", "X"), item("b", "Y"), item("c", "Z"), item("d", "W"))));
    writeExecutor.removeChild(MongoSplitOrder.class, "R1", "b", MongoSplitOrderItem.class);
    writeExecutor.removeChild(MongoSplitOrder.class, "R1", "c", MongoSplitOrderItem.class);

    List<Integer> ordersBefore =
        mongoOperations
            .getCollection("split_order_item")
            .find(new org.bson.Document("parentId", "R1"))
            .sort(new org.bson.Document("itemOrder", 1))
            .into(new ArrayList<>())
            .stream()
            .map(d -> (Integer) d.get("itemOrder"))
            .toList();
    assertThat(ordersBefore).containsExactly(0, 3);

    writeExecutor.reindexChildren(MongoSplitOrder.class, "R1", MongoSplitOrderItem.class);

    List<Integer> ordersAfter =
        mongoOperations
            .getCollection("split_order_item")
            .find(new org.bson.Document("parentId", "R1"))
            .sort(new org.bson.Document("itemOrder", 1))
            .into(new ArrayList<>())
            .stream()
            .map(d -> (Integer) d.get("itemOrder"))
            .toList();
    assertThat(ordersAfter).containsExactly(0, 1);
  }

  @Test
  @DisplayName(
      "d.6 saveWithSplitsReconciled: adds new, updates changed, removes absent — final state matches saveWithSplits")
  void reconciledSaveConvergesToSameFinalState() {
    writeExecutor.saveWithSplits(
        order("R2", "OPEN", List.of(item("a", "X"), item("b", "Y"), item("c", "Z"))));

    // Inbound: modify b, drop c, add d. a unchanged.
    MongoSplitOrder inbound =
        order("R2", "OPEN", List.of(item("a", "X"), item("b", "MOD"), item("d", "NEW")));
    writeExecutor.saveWithSplitsReconciled(inbound);

    List<org.bson.Document> after =
        mongoOperations
            .getCollection("split_order_item")
            .find(new org.bson.Document("parentId", "R2"))
            .sort(new org.bson.Document("itemOrder", 1))
            .into(new ArrayList<>());
    assertThat(after)
        .extracting(d -> (String) d.get("itemId"))
        .containsExactly("a", "b", "d");
    assertThat(((org.bson.Document) after.get(1).get("payload")).getString("state"))
        .isEqualTo("MOD");
  }

  // --- helpers ---

  private static MongoSplitOrder order(String id, String status, List<MongoSplitOrderItem> items) {
    MongoSplitOrder order = new MongoSplitOrder();
    order.setId(id);
    order.setStatus(status);
    order.setItems(items);
    return order;
  }

  private static MongoSplitOrderItem item(String id, String state) {
    MongoSplitOrderItem item = new MongoSplitOrderItem();
    item.setId(id);
    item.setState(state);
    return item;
  }

  private static MongoSplitOrderItem itemP(String id, String state, int priority) {
    MongoSplitOrderItem item = new MongoSplitOrderItem();
    item.setId(id);
    item.setState(state);
    item.setPriority(priority);
    return item;
  }

  @SpringBootApplication(scanBasePackageClasses = MongoSplitOrderItemSubController.class)
  static class TestApp {}
}
