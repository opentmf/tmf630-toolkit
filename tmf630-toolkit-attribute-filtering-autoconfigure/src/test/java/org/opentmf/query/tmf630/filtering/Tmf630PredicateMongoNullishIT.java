package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoSearchController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * REQ-1 (NULLISH isnull-semantics) end-to-end on Mongo. The four "no value" shapes are
 * seeded as raw {@link Document}s so we hit the exact BSON shapes callers persist:
 * value present, field missing, field explicitly {@code null}, and empty array
 * {@code []}. Under NULLISH the widening on the plain find path covers missing OR
 * explicit null; empty-array widening is not applied here because Spring Data's
 * {@code QueryMapper} strips {@code $size} / typed empty-list clauses during its
 * post-serialization type-mapping pass (verified end-to-end via the driver command
 * log during development). MISSING_ONLY baseline is covered by the existing
 * {@code Tmf630PredicateMongoIT} suite — this IT pins the widening the toggle turns on.
 */
@SpringBootTest(
    classes = Tmf630PredicateMongoNullishIT.TestApp.class,
    properties = {
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.MongoSearchEntity=id,category,externalReference",
      "opentmf.tmf630.attribute-filtering.allowNestedPathsDocdb=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT",
      "opentmf.tmf630.attribute-filtering.isnull-semantics=NULLISH"
    })
@AutoConfigureMockMvc
@Testcontainers
class Tmf630PredicateMongoNullishIT {

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void seedFourNullStates() {
    mongoTemplate.dropCollection("mongo_search_entity");
    Document present =
        new Document("_id", "present")
            .append("category", "SDWAN")
            .append(
                "externalReference",
                List.of(new Document("name", "ref").append("_id", "r1")));
    Document missing = new Document("_id", "missing");
    Document explicitNull =
        new Document("_id", "explicit-null")
            .append("category", null)
            .append("externalReference", null);
    Document emptyArray =
        new Document("_id", "empty-array")
            .append("category", "SDWAN")
            .append("externalReference", List.of());
    mongoTemplate
        .getDb()
        .getCollection("mongo_search_entity")
        .insertMany(Arrays.asList(present, missing, explicitNull, emptyArray));
  }

  @Test
  @DisplayName("NULLISH ?category.isnull matches missing AND explicit null (scalar)")
  void nullishScalarIsnullMatchesMissingAndExplicitNull() throws Exception {
    mockMvc
        .perform(get("/mongo-search").param("category.isnull", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath("$[*].id", Matchers.containsInAnyOrder("missing", "explicit-null")));
  }

  @Test
  @DisplayName("NULLISH ?category.isnotnull is the exact complement of the widened .isnull")
  void nullishScalarIsnotnullIsExactComplement() throws Exception {
    mockMvc
        .perform(get("/mongo-search").param("category.isnotnull", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath("$[*].id", Matchers.containsInAnyOrder("present", "empty-array")));
  }

  @Test
  @DisplayName("NULLISH ?externalReference.isnull matches missing AND explicit null on arrays too")
  void nullishArrayIsnullMatchesMissingAndExplicitNull() throws Exception {
    mockMvc
        .perform(get("/mongo-search").param("externalReference.isnull", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath("$[*].id", Matchers.containsInAnyOrder("missing", "explicit-null")));
  }

  @Test
  @DisplayName("NULLISH ?externalReference.isnotnull excludes missing/null; empty-array survives")
  void nullishArrayIsnotnullExcludesMissingAndNullOnly() throws Exception {
    // Empty-array widening is intentionally left out of the widened predicate — see
    // Javadoc on PredicateFactory#nullish. So {"externalReference":[]} counts as
    // "not null" on the plain find path. Callers needing the third state combine
    // this with an explicit size-based predicate at the repository level.
    mockMvc
        .perform(get("/mongo-search").param("externalReference.isnotnull", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath("$[*].id", Matchers.containsInAnyOrder("present", "empty-array")));
  }

  @SpringBootApplication(
      scanBasePackageClasses = MongoSearchController.class,
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
      })
  static class TestApp {}
}
