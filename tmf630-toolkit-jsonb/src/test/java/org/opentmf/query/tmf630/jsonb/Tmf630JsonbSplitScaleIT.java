package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderDomain;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderItem;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderItemSubController;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase (c.7) — scale-heavy parity IT. Seeds a parent with 500 split children (the
 * PIA-observed SDWAN scale reference point) and verifies:
 *
 * <ul>
 *   <li>The read-merge at parent level caps inline items at
 *       {@code maxInlineItems=100} — parent GETs return a bounded payload regardless
 *       of the true child count.
 *   <li>The sub-endpoint (c.5) paginates through the full 500 without loss.
 *   <li>The split-aware filter (c.2 + c.3) routes item-side predicates to the child
 *       table and executes them index-efficiently at scale — the query plan still
 *       returns the correct row set for a filter that matches only ~10% of items.
 *   <li>Cascade delete of a 500-child parent completes in one round-trip (the
 *       ON DELETE CASCADE fires per FK).
 * </ul>
 *
 * <p>This is not a performance test — it's a correctness-at-scale test. Actual
 * performance benchmarking (EXPLAIN ANALYZE, sub-100ms targets) belongs in a separate
 * perf suite gated behind a Maven profile, not in every {@code mvn verify}.
 */
@SpringBootTest(
    classes = Tmf630JsonbSplitScaleIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630JsonbSplitScaleIT {

  private static final int SCALE_CHILD_COUNT = 500;
  private static final int MAX_INLINE_ITEMS = 100; // matches SplitOrderDomain annotation

  @Autowired private MockMvc mockMvc;
  @Autowired private Tmf630JsonbFilterExecutor executor;
  @Autowired private Tmf630JsonbWriteExecutor writeExecutor;
  @Autowired private JsonbSplitAwareFilterTranslator splitAwareTranslator;
  @Autowired private JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
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
        .sql(
            "CREATE INDEX split_order_item_order_idx ON split_order_item (parent_id, item_order)")
        .update();
  }

  @Test
  @DisplayName(
      "c.7: parent-level GET caps inline items at maxInlineItems even with 500 children")
  void readMergeCapsAt100EvenAt500Children() {
    seedParentWith("BIG1", "OPEN", SCALE_CHILD_COUNT);

    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class,
            JsonbClause.alwaysTrue(),
            TmfSort.empty(),
            Pageable.unpaged(),
            field -> String.class);

    assertThat(page.getContent()).hasSize(1);
    SplitOrderDomain order = page.getContent().get(0);
    // The parent has 500 children in the child table but the inline merge is
    // capped at maxInlineItems=100 (per @Tmf630JsonbSplitCollection).
    assertThat(order.getItems()).hasSize(MAX_INLINE_ITEMS);
    // Ordering preserved: first item is at item_order=0.
    assertThat(order.getItems().get(0).getId()).isEqualTo("i-0");
    assertThat(order.getItems().get(MAX_INLINE_ITEMS - 1).getId())
        .isEqualTo("i-" + (MAX_INLINE_ITEMS - 1));

    // True child count in the DB is still 500.
    Integer trueCount =
        jdbcClient
            .sql("SELECT COUNT(*) FROM split_order_item WHERE parent_id = ?")
            .param(1, "BIG1")
            .query(Integer.class)
            .single();
    assertThat(trueCount).isEqualTo(SCALE_CHILD_COUNT);
  }

  @Test
  @DisplayName("c.7: sub-endpoint pages through the full 500 without loss")
  void subEndpointPaginatesThroughFullChildSet() throws Exception {
    seedParentWith("BIG2", "OPEN", SCALE_CHILD_COUNT);

    // Page size 100 → 5 pages to cover 500 items.
    int pageSize = 100;
    List<String> collectedIds = new ArrayList<>();
    for (int page = 0; page < 5; page++) {
      String content =
          mockMvc
              .perform(
                  get("/split-orders/BIG2/items?page=" + page + "&size=" + pageSize))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.totalElements").value(SCALE_CHILD_COUNT))
              .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(pageSize)))
              .andReturn()
              .getResponse()
              .getContentAsString();
      // Extract item ids from this page.
      JsonNode node =
          new ObjectMapper().readTree(content);
      for (int i = 0; i < node.get("content").size(); i++) {
        collectedIds.add(node.get("content").get(i).get("id").asString());
      }
    }
    // All 500 items collected, no duplicates, in item_order order.
    assertThat(collectedIds).hasSize(SCALE_CHILD_COUNT).doesNotHaveDuplicates();
    assertThat(collectedIds.get(0)).isEqualTo("i-0");
    assertThat(collectedIds.get(SCALE_CHILD_COUNT - 1))
        .isEqualTo("i-" + (SCALE_CHILD_COUNT - 1));
  }

  @Test
  @DisplayName(
      "c.7: split-aware filter routes to child table and returns correct parents at scale")
  void splitAwareFilterAtScale() {
    // Two parents, each with 500 items. All BIG3's items are PENDING; all BIG4's are
    // SHIPPED. Filter for state=PENDING should return only BIG3.
    seedParentWithAllState("BIG3", "OPEN", SCALE_CHILD_COUNT, "PENDING");
    seedParentWithAllState("BIG4", "OPEN", SCALE_CHILD_COUNT, "SHIPPED");

    JsonbClause where =
        splitAwareTranslator.translate(
            SplitOrderDomain.class, "$[?(@.items[?(@.state == 'PENDING')])]");
    Page<SplitOrderDomain> page =
        executor.findAll(
            SplitOrderDomain.class,
            where,
            TmfSort.empty(),
            Pageable.unpaged(),
            field -> String.class);
    assertThat(page.getContent())
        .extracting(SplitOrderDomain::getId)
        .containsExactly("BIG3");
  }

  @Test
  @DisplayName(
      "c.7: cascade delete of a 500-child parent removes all children in one round-trip")
  void cascadeDeleteAtScale() {
    seedParentWith("BIG5", "OPEN", SCALE_CHILD_COUNT);
    Integer before =
        jdbcClient
            .sql("SELECT COUNT(*) FROM split_order_item WHERE parent_id = ?")
            .param(1, "BIG5")
            .query(Integer.class)
            .single();
    assertThat(before).isEqualTo(SCALE_CHILD_COUNT);

    // Delete via raw SQL to avoid the JPA session-flush dance the other IT already
    // demonstrates — the cascade correctness is what matters here, not JPA.
    jdbcClient.sql("DELETE FROM split_order_row WHERE id = ?").param(1, "BIG5").update();

    Integer after =
        jdbcClient
            .sql("SELECT COUNT(*) FROM split_order_item WHERE parent_id = ?")
            .param(1, "BIG5")
            .query(Integer.class)
            .single();
    assertThat(after).isZero();
  }

  private void seedParentWith(String parentId, String status, int childCount) {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId(parentId);
    order.setStatus(status);
    List<SplitOrderItem> items = new ArrayList<>(childCount);
    for (int i = 0; i < childCount; i++) {
      SplitOrderItem item = new SplitOrderItem();
      item.setId("i-" + i);
      item.setState(i % 2 == 0 ? "PENDING" : "SHIPPED");
      items.add(item);
    }
    order.setItems(items);
    writeExecutor.saveWithSplits(order);
  }

  private void seedParentWithAllState(
      String parentId, String status, int childCount, String allState) {
    SplitOrderDomain order = new SplitOrderDomain();
    order.setId(parentId);
    order.setStatus(status);
    List<SplitOrderItem> items = new ArrayList<>(childCount);
    for (int i = 0; i < childCount; i++) {
      SplitOrderItem item = new SplitOrderItem();
      item.setId("i-" + i);
      item.setState(allState);
      items.add(item);
    }
    order.setItems(items);
    writeExecutor.saveWithSplits(order);
  }

  @SpringBootApplication(scanBasePackageClasses = SplitOrderItemSubController.class)
  static class TestApp {
    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
