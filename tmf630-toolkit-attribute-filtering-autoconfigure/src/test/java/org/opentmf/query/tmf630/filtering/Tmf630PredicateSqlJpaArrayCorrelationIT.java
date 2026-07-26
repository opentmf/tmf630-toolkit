package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderController;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderEntity;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderItem;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase (a.3) — JPA array correlation for JOIN-mapped associations. Verifies that
 * {@code filter=$[?(@.items[?(@.state=='X')])]} on a JPA {@code @Entity} rooted
 * query no longer returns 400 (as it did pre-3.0.0) but instead compiles to a
 * correlated {@code EXISTS} subquery via QueryDSL's {@code CollectionPath.any()}
 * mechanism. See {@code JPA_BACKEND_GAP_ANALYSIS.md} §3.3.
 */
@SpringBootTest(
    classes = Tmf630PredicateSqlJpaArrayCorrelationIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector",
      "opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlJpaArrayCorrelationIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SqlOrderRepository repository;

  @BeforeEach
  void setUp() {
    SqlCaptureInspector.clear();
    repository.deleteAll();
    SqlOrderEntity a = new SqlOrderEntity();
    a.setReference("ORD-A");
    a.setStatus("OPEN");
    a.addItem(newItem("Pending", "SKU-1"));
    a.addItem(newItem("Shipped", "SKU-2"));

    SqlOrderEntity b = new SqlOrderEntity();
    b.setReference("ORD-B");
    b.setStatus("OPEN");
    b.addItem(newItem("Shipped", "SKU-3"));

    SqlOrderEntity c = new SqlOrderEntity();
    c.setReference("ORD-C");
    c.setStatus("CLOSED");
    c.addItem(newItem("Pending", "SKU-4"));

    repository.saveAll(List.of(a, b, c));
    SqlCaptureInspector.clear();
  }

  @Test
  @DisplayName("Phase (a.3): array correlation on @OneToMany matches parent via EXISTS subquery")
  void arrayCorrelationOnOneToManyMatchesParent() throws Exception {
    // ORD-A and ORD-C have items with state=Pending; ORD-B does not.
    mockMvc
        .perform(
            get("/sql-orders")
                .param("filter", "$[?(@.items[?(@.state == 'Pending')])]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));

    String sql = firstSelectSql();
    assertThat(sql).contains(" where ").contains("exists");
  }

  @Test
  @DisplayName(
      "Phase (a.3): correlated multi-condition array match — same element must satisfy both")
  void arrayCorrelationRequiresSameElementForAllConditions() throws Exception {
    // ORD-A has {Pending,SKU-1} and {Shipped,SKU-2} — no single item matches
    // (Pending AND SKU-2). Same-element correlation via JPAExpressions EXISTS subquery
    // must reject ORD-A (differs from QueryDSL's naive `.any()` which produces two
    // separate EXISTS clauses, matching cross-element).
    mockMvc
        .perform(
            get("/sql-orders")
                .param("filter", "$[?(@.items[?(@.state == 'Pending' && @.sku == 'SKU-2')])]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));

    // Sanity on emitted SQL: single EXISTS with both predicates conjoined against the
    // same alias, not two separate EXISTS.
    String sql = firstSelectSql();
    int existsCount = sql.split("exists", -1).length - 1;
    assertThat(existsCount).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Phase (a.3): array correlation composes with parent-side attribute filters via AND")
  void arrayCorrelationComposesWithParentFilter() throws Exception {
    // Parent filter status=OPEN + array match items.state=Pending → only ORD-A.
    mockMvc
        .perform(
            get("/sql-orders")
                .param("status", "OPEN")
                .param("filter", "$[?(@.items[?(@.state == 'Pending')])]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].reference").value("ORD-A"));
  }

  private static SqlOrderItem newItem(String state, String sku) {
    SqlOrderItem item = new SqlOrderItem();
    item.setState(state);
    item.setSku(sku);
    return item;
  }

  private static String firstSelectSql() {
    List<String> selects =
        SqlCaptureInspector.snapshot().stream()
            .filter(sql -> sql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT"))
            .map(sql -> sql.toLowerCase(Locale.ROOT))
            .toList();
    assertThat(selects).isNotEmpty();
    return selects.get(0);
  }

  @SpringBootApplication(
      scanBasePackageClasses = SqlOrderController.class,
      exclude = {
        MongoAutoConfiguration.class,
        DataMongoAutoConfiguration.class,
        DataMongoRepositoriesAutoConfiguration.class
      })
  static class TestApp {}
}
