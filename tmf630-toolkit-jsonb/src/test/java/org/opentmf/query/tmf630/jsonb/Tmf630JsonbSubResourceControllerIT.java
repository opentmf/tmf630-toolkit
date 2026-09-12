package org.opentmf.query.tmf630.jsonb;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.config.Tmf630ExceptionHandlingAutoConfiguration;
import org.opentmf.query.tmf630.config.Tmf630FieldSelectionAutoConfiguration;
import org.opentmf.query.tmf630.config.Tmf630WebMvcConfigurer;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderDomain;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderItem;
import org.opentmf.query.tmf630.jsonb.it.SplitOrderItemSubController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase (c.5) IT — verifies the {@link Tmf630JsonbSubResourceController} inheritance
 * pattern lands the expected HTTP routes and paged responses against real Postgres.
 * Uses {@link SplitOrderItemSubController} as the concrete sub-endpoint at
 * {@code /split-orders/{parentId}/items}.
 */
@SpringBootTest(
    classes = Tmf630JsonbSubResourceControllerIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630JsonbSubResourceControllerIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private Tmf630JsonbWriteExecutor writeExecutor;

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

    SplitOrderDomain order = new SplitOrderDomain();
    order.setId("SUB1");
    order.setStatus("OPEN");
    order.setItems(
        List.of(
            item("i-1", "PENDING"),
            item("i-2", "SHIPPED"),
            item("i-3", "DELIVERED"),
            item("i-4", "RETURNED")));
    writeExecutor.saveWithSplits(order);
  }

  @Test
  @DisplayName(
      "c.5 sub-endpoint: GET /split-orders/{parentId}/items returns paged children")
  void listChildrenPaged() throws Exception {
    mockMvc
        .perform(get("/split-orders/SUB1/items?page=0&size=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$.totalElements").value(4))
        .andExpect(jsonPath("$.content[0].id").value("i-1"))
        .andExpect(jsonPath("$.content[1].id").value("i-2"));
  }

  @Test
  @DisplayName("c.5 sub-endpoint: subsequent page returns remaining children")
  void listChildrenSecondPage() throws Exception {
    mockMvc
        .perform(get("/split-orders/SUB1/items?page=1&size=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$.content[0].id").value("i-3"))
        .andExpect(jsonPath("$.content[1].id").value("i-4"));
  }

  @Test
  @DisplayName("c.5 sub-endpoint: GET /split-orders/{parentId}/items/{itemId} returns one child")
  void getOneChild() throws Exception {
    mockMvc
        .perform(get("/split-orders/SUB1/items/i-2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("i-2"))
        .andExpect(jsonPath("$.state").value("SHIPPED"));
  }

  @Test
  @DisplayName("c.5 sub-endpoint: 404 when the itemId does not exist for the parent")
  void getMissingItem() throws Exception {
    mockMvc.perform(get("/split-orders/SUB1/items/does-not-exist")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("c.5 sub-endpoint: empty list when parent has no children")
  void listForParentWithoutChildren() throws Exception {
    // A different parent with zero children.
    jdbcClient
        .sql("INSERT INTO split_order_row (id, payload) VALUES (?, ?::jsonb)")
        .param(1, "SUB2")
        .param(2, "{\"id\":\"SUB2\",\"status\":\"OPEN\"}")
        .update();
    mockMvc
        .perform(get("/split-orders/SUB2/items"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(0)))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  private static SplitOrderItem item(String id, String state) {
    SplitOrderItem item = new SplitOrderItem();
    item.setId(id);
    item.setState(state);
    return item;
  }

  // Pinned to the environment these assertions were written for: the plain Spring Data Page
  // JSON of listChildren. The paging autoconfiguration is on this module's test classpath
  // since 3.2.0 (for the sorted parity endpoints), and its @Tmf630Response advice would
  // unwrap the Page into a plain array with a 206 — the production shape.
  @SpringBootApplication(
      scanBasePackageClasses = SplitOrderItemSubController.class,
      exclude = {
        Tmf630WebMvcConfigurer.class,
        Tmf630ExceptionHandlingAutoConfiguration.class,
        Tmf630FieldSelectionAutoConfiguration.class
      })
  static class TestApp {
    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
