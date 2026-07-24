package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.it.jpa.JpaServiceOrderController;
import org.opentmf.query.tmf630.filtering.it.jpa.JpaServiceOrderEntity;
import org.opentmf.query.tmf630.filtering.it.jpa.JpaServiceOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
      "opentmf.tmf630.attribute-filtering.allowlist.entities.JpaServiceOrderEntity=id,href,category,externalId,requestedStartDate,state,tags",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateJpaParityIT {

  private static final String DATASET_RESOURCE = "fixtures/uc-list-service-order-response.json";

  @Autowired private MockMvc mockMvc;
  @Autowired private JpaServiceOrderRepository repository;
  @Autowired private JsonMapper jsonMapper;

  @BeforeEach
  void setUp() throws Exception {
    repository.deleteAll();
    String json = readClasspathUtf8(DATASET_RESOURCE);
    JsonNode root = jsonMapper.readTree(json);
    List<JpaServiceOrderEntity> entities = new ArrayList<>();
    int index = 0;
    for (JsonNode node : root) {
      JpaServiceOrderEntity entity = new JpaServiceOrderEntity();
      entity.setId(node.path("id").asString());
      entity.setHref(node.path("href").asString());
      entity.setCategory(node.path("category").asString());
      entity.setExternalId(node.path("externalId").asString());
      entity.setRequestedStartDate(node.path("requestedStartDate").asString());
      entity.setState(node.path("state").asString());
      // Deterministic length() fixture: exactly one entity carries 3 tags and one
      // carries 2 tags; the rest have 0. Anchoring on index keeps counts stable if
      // the fixture is reordered.
      if (index == 0) {
        entity.setTags(new ArrayList<>(List.of("a", "b", "c")));
      } else if (index == 1) {
        entity.setTags(new ArrayList<>(List.of("x", "y")));
      }
      entities.add(entity);
      index++;
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
  void lengthEqualsMatchesEntitiesWithExactlyThatManyElements() throws Exception {
    // The setUp seeds index 0 with 3 tags and index 1 with 2 tags; pinning by their
    // hrefs proves length()==N matches the intended entity and not neighbours.
    mockMvc
        .perform(get("/jpa-search").param("filter", "$[?(@.tags.length()==3)]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("6215245d7fce055f32210d79"));

    mockMvc
        .perform(get("/jpa-search").param("filter", "$[?(@.tags.length()==2)]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("621524e67fce055f32210d7a"));
  }

  @Test
  void lengthEqualsZeroExcludesTaggedEntities() throws Exception {
    // Pin the response via the fixture's known href for the first entity — index 0
    // is tagged with 3 elements, so length()==0 combined with that href must return
    // no rows (proves length()==0 does not silently match a non-empty collection).
    mockMvc
        .perform(
            get("/jpa-search")
                .param("href.eq", "/tmf-api/serviceOrdering/v4/serviceOrder/6215245d7fce055f32210d79")
                .param("filter", "$[?(@.tags.length()==0)]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void rejectsLengthOnScalarLeafJsonPathOnJpaFlow() throws Exception {
    mockMvc
        .perform(get("/jpa-search").param("filter", "$[?(@.state.length()==5)]"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsLengthWithNonEqualsComparatorJsonPathOnJpaFlow() throws Exception {
    mockMvc
        .perform(get("/jpa-search").param("filter", "$[?(@.tags.length()>0)]"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsPositionalIndexJsonPathOnJpaFlow() throws Exception {
    mockMvc
        .perform(get("/jpa-search").param("filter", "$[?(@.externalReference[0].id == 'X')]"))
        .andExpect(status().isBadRequest());
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

  @SpringBootApplication(
      scanBasePackageClasses = JpaServiceOrderController.class,
      exclude = {
        MongoAutoConfiguration.class,
        DataMongoAutoConfiguration.class,
        DataMongoRepositoriesAutoConfiguration.class
      })
  static class TestApp {}

  private static String readClasspathUtf8(String resource) {
    try (InputStream in =
        Tmf630PredicateJpaParityIT.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Classpath resource not found: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
