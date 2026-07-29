package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderDomain;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderItem;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderRow;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderRowRepository;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase (c.4) IT — verifies split-collection read-merge against real Postgres. Parent
 * rows live in {@code split_order_row} with payloads that carry {@code id} and
 * {@code status}; child items live in {@code split_order_item} with (parent_id,
 * item_id, item_order, payload). The executor fetches children per parent and merges
 * them into the deserialized {@link SplitOrderDomain}.
 *
 * <p>Write-side (POST split, cascade delete) is exercised here via direct JDBC insert
 * — the auto-splitting persistence hook lands in a later c.x sub-milestone.
 */
@SpringBootTest(
    classes = Tmf630JsonbSplitCollectionIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@Transactional
class Tmf630JsonbSplitCollectionIT {

  @Autowired private Tmf630JsonbFilterExecutor executor;
  @Autowired private Tmf630JsonbWriteExecutor writeExecutor;
  @Autowired private JsonbSplitAwareFilterTranslator splitAwareTranslator;
  @Autowired private Tmf630JsonbSplitChildCounter childCounter;
  @Autowired private SplitOrderRowRepository repository;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    // Create schema by hand — split_order_item has FK + composite PK + ordering
    // index. Simpler than teaching Hibernate to manage this shape.
    jdbcClient.sql("DROP TABLE IF EXISTS split_order_item CASCADE").update();
    jdbcClient.sql("DROP TABLE IF EXISTS split_order_row CASCADE").update();
    jdbcClient
        .sql(
            "CREATE TABLE split_order_row ("
                + "  id VARCHAR(50) PRIMARY KEY,"
                + "  payload JSONB NOT NULL)")
        .update();
    jdbcClient
        .sql(
            "CREATE TABLE split_order_item ("
                + "  parent_id VARCHAR(50) NOT NULL"
                + "    REFERENCES split_order_row(id) ON DELETE CASCADE,"
                + "  item_id VARCHAR(50) NOT NULL,"
                + "  item_order INTEGER NOT NULL,"
                + "  payload JSONB NOT NULL,"
                + "  PRIMARY KEY (parent_id, item_id))")
        .update();
    jdbcClient
        .sql("CREATE INDEX split_order_item_order_idx ON split_order_item (parent_id, item_order)")
        .update();
  }

  @Test
  @DisplayName(
      "c.4 read merge: a parent with 3 split children reads back as one merged domain "
          + "object with items in item_order")
  void readMergeCanonical() throws Exception {
    seedParent("O1", "OPEN");
    seedItem("O1", "1", 0, "PENDING");
    seedItem("O1", "2", 1, "SHIPPED");
    seedItem("O1", "3", 2, "DELIVERED");

    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class,
            JsonbClause.alwaysTrue(),
            TmfSort.empty(),
            Pageable.unpaged(),
            field -> String.class);
    assertThat(page.getContent()).hasSize(1);
    SplitOrderDomain order = page.getContent().get(0);
    assertThat(order.getId()).isEqualTo("O1");
    assertThat(order.getStatus()).isEqualTo("OPEN");
    assertThat(order.getItems())
        .extracting(SplitOrderItem::getId)
        .containsExactly("1", "2", "3");
    assertThat(order.getItems())
        .extracting(SplitOrderItem::getState)
        .containsExactly("PENDING", "SHIPPED", "DELIVERED");
  }

  @Test
  @DisplayName("c.4 read merge: item_order preserves ordering even if inserted out of order")
  void readMergeRespectsItemOrder() throws Exception {
    seedParent("O2", "OPEN");
    seedItem("O2", "b", 1, "B");
    seedItem("O2", "c", 2, "C");
    seedItem("O2", "a", 0, "A"); // inserted last, item_order=0 → should come first

    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class,
            JsonbClause.alwaysTrue(),
            TmfSort.empty(),
            Pageable.unpaged(),
            field -> String.class);
    assertThat(page.getContent().get(0).getItems())
        .extracting(SplitOrderItem::getId)
        .containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("c.4 read merge: parent with zero children yields empty items list, not null")
  void readMergeParentWithoutChildren() throws Exception {
    seedParent("O3", "CLOSED");
    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class,
            JsonbClause.alwaysTrue(),
            TmfSort.empty(),
            Pageable.unpaged(),
            field -> String.class);
    assertThat(page.getContent().get(0).getItems()).isEmpty();
  }

  @Test
  @DisplayName("c.4 read merge: multiple parents each get their own children merged")
  void readMergeMultipleParents() throws Exception {
    seedParent("O4", "OPEN");
    seedParent("O5", "OPEN");
    seedItem("O4", "1", 0, "PENDING");
    seedItem("O5", "1", 0, "SHIPPED");
    seedItem("O5", "2", 1, "DELIVERED");

    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class,
            JsonbClause.alwaysTrue(),
            TmfSort.empty(),
            Pageable.unpaged(),
            field -> String.class);
    assertThat(page.getContent()).hasSize(2);
    SplitOrderDomain o4 =
        page.getContent().stream().filter(o -> "O4".equals(o.getId())).findFirst().orElseThrow();
    SplitOrderDomain o5 =
        page.getContent().stream().filter(o -> "O5".equals(o.getId())).findFirst().orElseThrow();
    assertThat(o4.getItems()).hasSize(1);
    assertThat(o5.getItems()).hasSize(2);
  }

  @Test
  @DisplayName("c.4 read merge: ON DELETE CASCADE removes children with parent")
  void deleteParentCascadesToChildren() throws Exception {
    seedParent("O6", "OPEN");
    seedItem("O6", "1", 0, "PENDING");
    seedItem("O6", "2", 1, "SHIPPED");

    Integer childCountBefore =
        jdbcClient
            .sql("SELECT COUNT(*) FROM split_order_item WHERE parent_id = ?")
            .param(1, "O6")
            .query(Integer.class)
            .single();
    assertThat(childCountBefore).isEqualTo(2);

    repository.deleteById("O6");
    // Force the JPA persistence context to flush the DELETE — otherwise the
    // JdbcClient count query below runs against the un-flushed connection and
    // sees the pre-delete row set. The ON DELETE CASCADE happens at Postgres
    // level, so once the parent DELETE reaches the wire, the FK triggers
    // synchronously and the children are gone before COUNT(*) runs.
    repository.flush();

    Integer childCountAfter =
        jdbcClient
            .sql("SELECT COUNT(*) FROM split_order_item WHERE parent_id = ?")
            .param(1, "O6")
            .query(Integer.class)
            .single();
    assertThat(childCountAfter).isZero();
  }

  @Test
  @DisplayName(
      "c.6 saveWithSplits: persists parent + children atomically, extracting split field")
  void saveWithSplitsRoundTrip() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("W1");
    order.setStatus("OPEN");
    SplitOrderItem item1 = new SplitOrderItem();
    item1.setId("i-1");
    item1.setState("PENDING");
    SplitOrderItem item2 = new SplitOrderItem();
    item2.setId("i-2");
    item2.setState("SHIPPED");
    order.setItems(List.of(item1, item2));

    writeExecutor.saveWithSplits(order);

    // Parent payload should NOT contain items — extracted before persist.
    String parentPayload =
        jdbcClient
            .sql("SELECT payload::text FROM split_order_row WHERE id = ?")
            .param(1, "W1")
            .query(String.class)
            .single();
    // Postgres formats JSONB textually with a space after the colon; assert on the
    // key/value pair independently rather than the exact character sequence.
    assertThat(parentPayload).contains("\"status\"").contains("\"OPEN\"").doesNotContain("\"items\"");

    // Children should be in the child table with item_order set.
    Integer childCount =
        jdbcClient
            .sql("SELECT COUNT(*) FROM split_order_item WHERE parent_id = ?")
            .param(1, "W1")
            .query(Integer.class)
            .single();
    assertThat(childCount).isEqualTo(2);

    // Round-trip via the filter executor: should see the merged domain.
    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class,
            JsonbClause.alwaysTrue(),
            TmfSort.empty(),
            Pageable.unpaged(),
            field -> String.class);
    assertThat(page.getContent()).hasSize(1);
    SplitOrderDomain loaded = page.getContent().get(0);
    assertThat(loaded.getStatus()).isEqualTo("OPEN");
    assertThat(loaded.getItems()).extracting(SplitOrderItem::getId).containsExactly("i-1", "i-2");
    assertThat(loaded.getItems())
        .extracting(SplitOrderItem::getState)
        .containsExactly("PENDING", "SHIPPED");
  }

  @Test
  @DisplayName("c.6 saveWithSplits: re-save with fewer children full-replaces the child table")
  void saveWithSplitsFullReplacesChildren() {
    SplitOrderDomain first = new SplitOrderDomain();
    first.setId("W2");
    first.setStatus("OPEN");
    first.setItems(List.of(item("a", "S1"), item("b", "S2"), item("c", "S3")));
    writeExecutor.saveWithSplits(first);
    assertThat(childCount("W2")).isEqualTo(3);

    // Re-save with only 1 item.
    SplitOrderDomain second = new SplitOrderDomain();
    second.setId("W2");
    second.setStatus("CLOSED");
    second.setItems(List.of(item("z", "SZ")));
    writeExecutor.saveWithSplits(second);

    assertThat(childCount("W2")).isEqualTo(1);
    String statusAfter =
        jdbcClient
            .sql("SELECT payload->>'status' FROM split_order_row WHERE id = ?")
            .param(1, "W2")
            .query(String.class)
            .single();
    assertThat(statusAfter).isEqualTo("CLOSED");
  }

  @Test
  @DisplayName(
      "c.6 saveWithSplits: children without an 'id' field get the positional index as id")
  void saveWithSplitsAutoAssignsChildIds() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("W3");
    order.setStatus("OPEN");
    SplitOrderItem noId1 = new SplitOrderItem();
    noId1.setState("A");
    SplitOrderItem noId2 = new SplitOrderItem();
    noId2.setState("B");
    order.setItems(List.of(noId1, noId2));

    writeExecutor.saveWithSplits(order);

    List<String> childIds =
        jdbcClient
            .sql(
                "SELECT item_id FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "W3")
            .query(String.class)
            .list();
    assertThat(childIds).containsExactly("0", "1");
  }

  @Test
  @DisplayName(
      "c.2/c.3: item-side filter at the parent endpoint compiles to EXISTS subquery "
          + "and returns only matching parents")
  void splitAwareFilterMatchesOnChildField() {
    // Seed: two parents with different child states.
    SplitOrderDomain o1 = new SplitOrderDomain();
    o1.setId("F1");
    o1.setStatus("OPEN");
    o1.setItems(List.of(item("i1", "PENDING"), item("i2", "SHIPPED")));
    writeExecutor.saveWithSplits(o1);

    SplitOrderDomain o2 = new SplitOrderDomain();
    o2.setId("F2");
    o2.setStatus("OPEN");
    o2.setItems(List.of(item("i1", "SHIPPED"), item("i2", "DELIVERED")));
    writeExecutor.saveWithSplits(o2);

    // Filter: match parents that have at least one item in state=PENDING. Only F1
    // qualifies. F2's items are SHIPPED / DELIVERED.
    JsonbClause where =
        splitAwareTranslator.translate(
            SplitOrderDomain.class, "$[?(@.items[?(@.state == 'PENDING')])]");
    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class, where, TmfSort.empty(), Pageable.unpaged(),
            field -> String.class);
    assertThat(page.getContent()).extracting(SplitOrderDomain::getId).containsExactly("F1");
  }

  @Test
  @DisplayName(
      "c.2/c.3: same-element correlation — multi-condition item predicate matches only when "
          + "one SINGLE item satisfies ALL conditions")
  void splitAwareFilterSameElementCorrelation() {
    // F3 has items {i1: PENDING price=100} and {i2: SHIPPED price=200}. No single
    // item is (PENDING && price > 150). Filter should exclude F3.
    SplitOrderDomain f3 = new SplitOrderDomain();
    f3.setId("F3");
    f3.setStatus("OPEN");
    SplitOrderItem i1 = new SplitOrderItem();
    i1.setId("i1");
    i1.setState("PENDING");
    SplitOrderItem i2 = new SplitOrderItem();
    i2.setId("i2");
    i2.setState("SHIPPED");
    f3.setItems(List.of(i1, i2));
    writeExecutor.saveWithSplits(f3);

    // First: match parents with an item state=PENDING. Should include F3.
    JsonbClause wherePresent =
        splitAwareTranslator.translate(
            SplitOrderDomain.class, "$[?(@.items[?(@.state == 'PENDING')])]");
    Page<SplitOrderDomain> presentPage =
        executor.findAll(
            SplitOrderDomain.class, wherePresent, TmfSort.empty(),
            Pageable.unpaged(), field -> String.class);
    assertThat(presentPage.getContent()).extracting(SplitOrderDomain::getId).contains("F3");

    // Then: match parents with a SINGLE item that is BOTH PENDING and id='i2'. Should
    // NOT match — F3's i1 is PENDING but not id='i2'; F3's i2 is id='i2' but SHIPPED.
    JsonbClause whereNoMatch =
        splitAwareTranslator.translate(
            SplitOrderDomain.class,
            "$[?(@.items[?(@.state == 'PENDING' && @.id == 'i2')])]");
    Page<SplitOrderDomain> noMatchPage =
        executor.findAll(
            SplitOrderDomain.class, whereNoMatch, TmfSort.empty(),
            Pageable.unpaged(), field -> String.class);
    assertThat(noMatchPage.getContent()).isEmpty();
  }

  @Test
  @DisplayName(
      "c.2/c.3 full: compound filter mixing parent + split conjunction returns intersection")
  void splitAwareCompoundParentAndSplit() {
    SplitOrderDomain a = new SplitOrderDomain();
    a.setId("K1");
    a.setStatus("OPEN");
    a.setItems(List.of(item("i1", "PENDING")));
    writeExecutor.saveWithSplits(a);

    SplitOrderDomain b = new SplitOrderDomain();
    b.setId("K2");
    b.setStatus("COMPLETED");
    b.setItems(List.of(item("i1", "PENDING")));
    writeExecutor.saveWithSplits(b);

    SplitOrderDomain c = new SplitOrderDomain();
    c.setId("K3");
    c.setStatus("OPEN");
    c.setItems(List.of(item("i1", "SHIPPED")));
    writeExecutor.saveWithSplits(c);

    // Want status=OPEN AND at least one item is PENDING. K1 matches; K2 wrong status,
    // K3 wrong item state.
    JsonbClause where =
        splitAwareTranslator.translate(
            SplitOrderDomain.class,
            "$[?(@.status == 'OPEN' && @.items[?(@.state == 'PENDING')])]");
    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class, where, TmfSort.empty(),
            Pageable.unpaged(), field -> String.class);
    assertThat(page.getContent()).extracting(SplitOrderDomain::getId).containsExactly("K1");
  }

  @Test
  @DisplayName(
      "childCounter.count: single-parent true count + truncated flag reflects inline cap")
  void childCounterSingleParentTruncationFlag() {
    // Seed 2 items — well under the 100 cap. Not truncated.
    SplitOrderDomain small = new SplitOrderDomain();
    small.setId("CC1");
    small.setStatus("OPEN");
    small.setItems(List.of(item("a", "P"), item("b", "S")));
    writeExecutor.saveWithSplits(small);
    Tmf630JsonbSplitChildCounter.ChildCountInfo info =
        childCounter.count(SplitOrderDomain.class, "CC1", SplitOrderItem.class);
    assertThat(info.trueCount()).isEqualTo(2L);
    assertThat(info.maxInlineItems()).isEqualTo(100);
    assertThat(info.truncated()).isFalse();

    // Seed 150 items — exceeds the 100 cap. Truncated.
    SplitOrderDomain big = new SplitOrderDomain();
    big.setId("CC2");
    big.setStatus("OPEN");
    List<SplitOrderItem> many = new java.util.ArrayList<>();
    for (int i = 0; i < 150; i++) many.add(item("i-" + i, "P"));
    big.setItems(many);
    writeExecutor.saveWithSplits(big);
    Tmf630JsonbSplitChildCounter.ChildCountInfo bigInfo =
        childCounter.count(SplitOrderDomain.class, "CC2", SplitOrderItem.class);
    assertThat(bigInfo.trueCount()).isEqualTo(150L);
    assertThat(bigInfo.truncated()).isTrue();
  }

  @Test
  @DisplayName("childCounter.countAll: batch returns count per parent, zero for missing parents")
  void childCounterBatchReturnsPerParentAndZeroForMissing() {
    SplitOrderDomain o1 = new SplitOrderDomain();
    o1.setId("CB1");
    o1.setStatus("OPEN");
    o1.setItems(List.of(item("i", "P")));
    writeExecutor.saveWithSplits(o1);
    SplitOrderDomain o2 = new SplitOrderDomain();
    o2.setId("CB2");
    o2.setStatus("OPEN");
    o2.setItems(List.of(item("a", "P"), item("b", "P"), item("c", "S")));
    writeExecutor.saveWithSplits(o2);

    Map<String, Long> counts =
        childCounter.countAll(
            SplitOrderDomain.class, List.of("CB1", "CB2", "CB-MISSING"), SplitOrderItem.class);
    assertThat(counts).containsEntry("CB1", 1L).containsEntry("CB2", 3L).containsEntry("CB-MISSING", 0L);
  }

  @Test
  @DisplayName(
      "OR support: compound filter parent-side OR split-side returns union of matches")
  void splitAwareCompoundParentOrSplit() {
    // OR1: status=CANCELLED. Matches on parent side.
    SplitOrderDomain o1 = new SplitOrderDomain();
    o1.setId("OR1");
    o1.setStatus("CANCELLED");
    o1.setItems(List.of(item("i1", "SHIPPED")));
    writeExecutor.saveWithSplits(o1);
    // OR2: status=OPEN with a PENDING item. Matches on split side.
    SplitOrderDomain o2 = new SplitOrderDomain();
    o2.setId("OR2");
    o2.setStatus("OPEN");
    o2.setItems(List.of(item("i1", "PENDING")));
    writeExecutor.saveWithSplits(o2);
    // OR3: neither cancelled nor any pending item. Should NOT match.
    SplitOrderDomain o3 = new SplitOrderDomain();
    o3.setId("OR3");
    o3.setStatus("OPEN");
    o3.setItems(List.of(item("i1", "SHIPPED")));
    writeExecutor.saveWithSplits(o3);

    JsonbClause where =
        splitAwareTranslator.translate(
            SplitOrderDomain.class,
            "$[?(@.status == 'CANCELLED' || @.items[?(@.state == 'PENDING')])]");
    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class, where, TmfSort.empty(),
            Pageable.unpaged(), field -> String.class);
    assertThat(page.getContent())
        .extracting(SplitOrderDomain::getId)
        .containsExactlyInAnyOrder("OR1", "OR2");
  }

  @Test
  @DisplayName(
      "c.2/c.3 full: two split correlations AND'd — different-item semantics per clause")
  void splitAwareCompoundTwoSplitClauses() {
    // K4 has one PENDING item and one SHIPPED item — different items each satisfying
    // one clause. Compound "some item PENDING AND some item SHIPPED" should match.
    SplitOrderDomain k4 = new SplitOrderDomain();
    k4.setId("K4");
    k4.setStatus("OPEN");
    k4.setItems(List.of(item("i1", "PENDING"), item("i2", "SHIPPED")));
    writeExecutor.saveWithSplits(k4);

    // K5 has only PENDING items — should NOT match the compound.
    SplitOrderDomain k5 = new SplitOrderDomain();
    k5.setId("K5");
    k5.setStatus("OPEN");
    k5.setItems(List.of(item("i1", "PENDING"), item("i2", "PENDING")));
    writeExecutor.saveWithSplits(k5);

    JsonbClause where =
        splitAwareTranslator.translate(
            SplitOrderDomain.class,
            "$[?(@.items[?(@.state == 'PENDING')] && @.items[?(@.state == 'SHIPPED')])]");
    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class, where, TmfSort.empty(),
            Pageable.unpaged(), field -> String.class);
    assertThat(page.getContent()).extracting(SplitOrderDomain::getId).containsExactly("K4");
  }

  @Test
  @DisplayName("c.6 appendChild: inserts one child without touching parent or other children")
  void appendChildAtEndOfCollection() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("W4");
    order.setStatus("OPEN");
    order.setItems(List.of(item("x", "X1"), item("y", "Y1")));
    writeExecutor.saveWithSplits(order);
    assertThat(childCount("W4")).isEqualTo(2);

    SplitOrderItem newItem = item("z", "Z1");
    writeExecutor.appendChild(SplitOrderDomain.class, "W4", newItem);

    List<String> childIds =
        jdbcClient
            .sql(
                "SELECT item_id FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "W4")
            .query(String.class)
            .list();
    assertThat(childIds).containsExactly("x", "y", "z");
  }

  @Test
  @DisplayName("c.6-full updateChild: replaces one child's payload in place, preserves order")
  void updateChildReplacesPayloadOnly() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("U1");
    order.setStatus("OPEN");
    order.setItems(List.of(item("a", "X"), item("b", "Y"), item("c", "Z")));
    writeExecutor.saveWithSplits(order);

    SplitOrderItem updated = item("b", "MODIFIED");
    int touched = writeExecutor.updateChild(SplitOrderDomain.class, "U1", "b", updated);
    assertThat(touched).isEqualTo(1);

    List<String> statesInOrder =
        jdbcClient
            .sql(
                "SELECT payload->>'state' FROM split_order_item "
                    + "WHERE parent_id = ? ORDER BY item_order")
            .param(1, "U1")
            .query(String.class)
            .list();
    assertThat(statesInOrder).containsExactly("X", "MODIFIED", "Z");
  }

  @Test
  @DisplayName("c.6-full updateChild: returns 0 for a non-existent (parent, item)")
  void updateChildMissingReturnsZero() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("U2");
    order.setStatus("OPEN");
    order.setItems(List.of(item("a", "X")));
    writeExecutor.saveWithSplits(order);

    int touched =
        writeExecutor.updateChild(SplitOrderDomain.class, "U2", "nope", item("nope", "V"));
    assertThat(touched).isZero();
  }

  @Test
  @DisplayName("c.6-full removeChild: deletes one row, leaves others alone")
  void removeChildDeletesOne() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("D1");
    order.setStatus("OPEN");
    order.setItems(List.of(item("a", "X"), item("b", "Y"), item("c", "Z")));
    writeExecutor.saveWithSplits(order);
    assertThat(childCount("D1")).isEqualTo(3);

    int removed = writeExecutor.removeChild(SplitOrderDomain.class, "D1", "b", SplitOrderItem.class);
    assertThat(removed).isEqualTo(1);
    assertThat(childCount("D1")).isEqualTo(2);

    List<String> remaining =
        jdbcClient
            .sql("SELECT item_id FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "D1")
            .query(String.class)
            .list();
    assertThat(remaining).containsExactly("a", "c");
  }

  @Test
  @DisplayName("c.6-full removeChild: returns 0 for a non-existent (parent, item)")
  void removeChildMissingReturnsZero() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("D2");
    order.setStatus("OPEN");
    order.setItems(List.of(item("a", "X")));
    writeExecutor.saveWithSplits(order);

    int removed = writeExecutor.removeChild(SplitOrderDomain.class, "D2", "nope", SplitOrderItem.class);
    assertThat(removed).isZero();
    assertThat(childCount("D2")).isEqualTo(1);
  }

  @Test
  @DisplayName("c.6-full reindexChildren: compacts item_order to 0..N-1 after gaps")
  void reindexChildrenCompactsOrder() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("R1");
    order.setStatus("OPEN");
    order.setItems(List.of(item("a", "X"), item("b", "Y"), item("c", "Z"), item("d", "W")));
    writeExecutor.saveWithSplits(order);
    // Delete two — leaves item_order gaps at 1 and 2.
    writeExecutor.removeChild(SplitOrderDomain.class, "R1", "b", SplitOrderItem.class);
    writeExecutor.removeChild(SplitOrderDomain.class, "R1", "c", SplitOrderItem.class);

    List<Integer> ordersBefore =
        jdbcClient
            .sql("SELECT item_order FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "R1")
            .query(Integer.class)
            .list();
    assertThat(ordersBefore).containsExactly(0, 3);

    writeExecutor.reindexChildren(SplitOrderDomain.class, "R1", SplitOrderItem.class);

    List<Integer> ordersAfter =
        jdbcClient
            .sql("SELECT item_order FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "R1")
            .query(Integer.class)
            .list();
    assertThat(ordersAfter).containsExactly(0, 1);
  }

  @Test
  @DisplayName(
      "c.6-full saveWithSplitsReconciled: identical inbound produces zero payload UPDATEs")
  void reconciledSaveNoOpForIdenticalInput() {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("R2");
    order.setStatus("OPEN");
    order.setItems(List.of(item("a", "X"), item("b", "Y")));
    writeExecutor.saveWithSplits(order);

    // Snapshot payloads before the reconciled save.
    List<String> before =
        jdbcClient
            .sql("SELECT payload::text FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "R2")
            .query(String.class)
            .list();

    // Re-save the identical instance via reconciler.
    writeExecutor.saveWithSplitsReconciled(order);

    List<String> after =
        jdbcClient
            .sql("SELECT payload::text FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "R2")
            .query(String.class)
            .list();
    assertThat(after).isEqualTo(before);
    assertThat(childCount("R2")).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "c.6-full saveWithSplitsReconciled: adds new, updates changed, removes absent — final state matches saveWithSplits")
  void reconciledSaveConvergesToSameFinalState() {
    SplitOrderDomain original = new SplitOrderDomain();
    original.setId("R3");
    original.setStatus("OPEN");
    original.setItems(List.of(item("a", "X"), item("b", "Y"), item("c", "Z")));
    writeExecutor.saveWithSplits(original);

    // Inbound: modify b's state, drop c, add d. a is unchanged.
    SplitOrderDomain inbound = new SplitOrderDomain();
    inbound.setId("R3");
    inbound.setStatus("OPEN");
    inbound.setItems(List.of(item("a", "X"), item("b", "MOD"), item("d", "NEW")));
    writeExecutor.saveWithSplitsReconciled(inbound);

    List<String> ids =
        jdbcClient
            .sql("SELECT item_id FROM split_order_item WHERE parent_id = ? ORDER BY item_order")
            .param(1, "R3")
            .query(String.class)
            .list();
    assertThat(ids).containsExactly("a", "b", "d");

    String bState =
        jdbcClient
            .sql(
                "SELECT payload->>'state' FROM split_order_item "
                    + "WHERE parent_id = ? AND item_id = ?")
            .param(1, "R3")
            .param(2, "b")
            .query(String.class)
            .single();
    assertThat(bState).isEqualTo("MOD");
  }

  private static SplitOrderItem item(String id, String state) {
    SplitOrderItem item = new SplitOrderItem();
    item.setId(id);
    item.setState(state);
    return item;
  }

  private Integer childCount(String parentId) {
    return jdbcClient
        .sql("SELECT COUNT(*) FROM split_order_item WHERE parent_id = ?")
        .param(1, parentId)
        .query(Integer.class)
        .single();
  }

  private void seedParent(String id, String status) {
    // Parent's payload holds id + status only — no items.
    String payload =
        "{\"id\":\"" + id + "\",\"status\":\"" + status + "\"}";
    jdbcClient
        .sql("INSERT INTO split_order_row (id, payload) VALUES (?, ?::jsonb)")
        .param(1, id)
        .param(2, payload)
        .update();
  }

  private void seedItem(String parentId, String itemId, int order, String state) throws Exception {
    SplitOrderItem item = new SplitOrderItem();
    item.setId(itemId);
    item.setState(state);
    String payload = objectMapper.writeValueAsString(item);
    jdbcClient
        .sql(
            "INSERT INTO split_order_item (parent_id, item_id, item_order, payload) "
                + "VALUES (?, ?, ?, ?::jsonb)")
        .param(1, parentId)
        .param(2, itemId)
        .param(3, order)
        .param(4, payload)
        .update();
  }

  @SpringBootApplication(scanBasePackageClasses = SplitOrderRow.class)
  static class TestApp {
    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
