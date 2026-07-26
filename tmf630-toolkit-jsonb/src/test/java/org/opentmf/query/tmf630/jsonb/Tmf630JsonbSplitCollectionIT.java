package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

  private void seedParent(String id, String status) throws Exception {
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
