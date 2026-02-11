package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = Tmf630PredicateJpaParityIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=jdbc:tc:postgresql:18.1-alpine:///db",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.JpaServiceOrderEntity=id,href,category,externalId,requestedStartDate,state",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateJpaParityIT {

  private static final Path REAL_DATASET_PATH =
      Path.of(
          "/home/gokhan/prj/iot-solutionhub/api-adapters/sdn-service-order-adapter/src/test/resources/payload/uc-list-service-order-response.json");

  @Autowired private MockMvc mockMvc;
  @Autowired private JpaServiceOrderRepository repository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() throws Exception {
    repository.deleteAll();
    String json = Files.readString(REAL_DATASET_PATH);
    JsonNode root = objectMapper.readTree(json);
    List<JpaServiceOrderEntity> entities = new ArrayList<>();
    for (JsonNode node : root) {
      JpaServiceOrderEntity entity = new JpaServiceOrderEntity();
      entity.setId(node.path("id").asText());
      entity.setHref(node.path("href").asText());
      entity.setCategory(node.path("category").asText());
      entity.setExternalId(node.path("externalId").asText());
      entity.setRequestedStartDate(node.path("requestedStartDate").asText());
      entity.setState(node.path("state").asText());
      entities.add(entity);
    }
    repository.saveAll(entities);
  }

  @Test
  void supportsComplexGroupedJsonPathWithDefaultAndMergeOnJpa() throws Exception {
    mockMvc
        .perform(
            get("/jpa-search")
                .param("category.eq", "SDWAN service order")
                .param(
                    "filter",
                    "$[?((@.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215245d7fce055f32210d79' || @.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215e8252c2b0673ea905954') && (@.state == 'acknowledged' && @.externalId == 'BSS748'))]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void supportsAttributePlusJsonPathWithOrMergeOnJpa() throws Exception {
    mockMvc
        .perform(
            get("/jpa-search")
                .param("href.eq", "/tmf-api/serviceOrdering/v4/serviceOrder/621524e67fce055f32210d7a")
                .param(
                    "filter",
                    "$[?((@.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215e0662c2b0673ea905950') || (@.href == '/tmf-api/serviceOrdering/v4/serviceOrder/6215e69e2c2b0673ea905953' && @.state == 'acknowledged'))]")
                .param("filter.combineWithAttributes", "OR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void supportsMixedAttributeOperatorsWithJsonPathOnJpa() throws Exception {
    mockMvc
        .perform(
            get("/jpa-search")
                .param("href.likei", "%/serviceOrder/6215e%")
                .param("id.gte", "6215e0000000000000000000")
                .param(
                    "filter",
                    "$[?(@.state == 'acknowledged' && (@.category == 'SDWAN service order' || @.externalId == 'BSS748'))]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5));
  }

  @Test
  void rejectsInvalidJsonPathOnJpaFlow() throws Exception {
    mockMvc.perform(get("/jpa-search").param("filter", "$.state")).andExpect(status().isBadRequest());
  }

  @Test
  void rejectsArrayCorrelationJsonPathOnJpaFlow() throws Exception {
    mockMvc
        .perform(
            get("/jpa-search")
                .param(
                    "filter",
                    "$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]"))
        .andExpect(status().isBadRequest());
  }

  @SpringBootApplication
  @Import(JpaServiceOrderController.class)
  static class TestApp {}
}
