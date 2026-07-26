package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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

  @SpringBootApplication(scanBasePackageClasses = MongoSplitOrderItemSubController.class)
  static class TestApp {}
}
