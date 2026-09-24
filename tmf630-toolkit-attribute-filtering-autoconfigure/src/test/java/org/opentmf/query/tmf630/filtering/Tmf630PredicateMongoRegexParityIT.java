package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.filtering.it.RegexParityFixture;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MongoDB leg of the three-backend regex parity battery (see {@link RegexParityFixture}).
 * Mongo runs a real regex ({@code $regex}); the point of this leg is that for every in-subset
 * pattern it returns exactly the ids the JPA leg returns through its LIKE translation, and that
 * the out-of-subset patterns, which JPA rejects, keep working here as real regex.
 */
@SpringBootTest(
    classes = Tmf630PredicateMongoRegexParityIT.TestApp.class,
    properties = {
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.MongoSearchEntity=id,externalId,state",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@Testcontainers
class Tmf630PredicateMongoRegexParityIT {

  private static final String COLLECTION = "mongo_search_entity";

  @Container static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private JsonMapper jsonMapper;

  @BeforeEach
  void seed() {
    mongoTemplate.getDb().getCollection(COLLECTION).drop();
    List<Document> documents =
        RegexParityFixture.VALUES.entrySet().stream()
            .map(
                e ->
                    new Document("_id", e.getKey())
                        .append("externalId", e.getValue())
                        .append("state", RegexParityFixture.status(e.getKey())))
            .toList();
    mongoTemplate.getDb().getCollection(COLLECTION).insertMany(documents);
  }

  static Stream<Arguments> subsetCases() {
    return RegexParityFixture.subsetCases();
  }

  static Stream<Arguments> outsideSubsetCases() {
    return RegexParityFixture.outsideSubsetCases();
  }

  @ParameterizedTest(name = "{0}: externalId.{1}={2}")
  @MethodSource("subsetCases")
  void inSubsetPatternReturnsTheSameRowsAsJpa(
      String label, String op, String pattern, Set<String> expected) throws Exception {
    String body =
        mockMvc
            .perform(get("/mongo-search").param("externalId." + op, pattern))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(ids(body)).as(label).isEqualTo(expected);
  }

  @ParameterizedTest(name = "externalId.regex={0} runs as real regex here")
  @MethodSource("outsideSubsetCases")
  void outsideSubsetPatternIsRealRegexOnMongo(String pattern, Set<String> expected)
      throws Exception {
    // Documented divergence — JPA answers 400 for these patterns, see the JPA leg
    // Tmf630PredicateSqlJpaRegexParityIT, whereas a real regex engine simply evaluates them.
    String body =
        mockMvc
            .perform(get("/mongo-search").param("externalId.regex", pattern))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(ids(body)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Part 6 =~ with || across two fields — the consumer's URL shape")
  void orAcrossFieldsThroughJsonPath() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/mongo-search")
                    .param("filter", "$[?(@.externalId =~ /.*t.*/i || @.state =~ /^TEST$/)]"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(ids(body)).isEqualTo(RegexParityFixture.OR_ACROSS_FIELDS);
  }

  private Set<String> ids(String body) {
    Set<String> ids = new HashSet<>();
    for (JsonNode node : jsonMapper.readTree(body)) {
      ids.add(node.path("id").asString());
    }
    return ids;
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
