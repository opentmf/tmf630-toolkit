package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.filtering.it.RegexParityFixture;
import org.opentmf.query.tmf630.filtering.it.sql.SqlSearchController;
import org.opentmf.query.tmf630.filtering.it.sql.SqlSearchEntity;
import org.opentmf.query.tmf630.filtering.it.sql.SqlSearchEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JPA leg of the three-backend regex parity battery (see {@link RegexParityFixture}). Every
 * in-subset pattern must return exactly the ids the fixture expects — the same ids the Mongo
 * and JSONB legs assert for the same patterns — and every out-of-subset pattern must be a 400
 * naming the subset, never a 500 and never a silently different row set.
 */
@SpringBootTest(
    classes = Tmf630PredicateSqlJpaRegexParityIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.SqlSearchEntity=transformationId,status,modifiedBy",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlJpaRegexParityIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SqlSearchEntityRepository repository;
  @Autowired private JsonMapper jsonMapper;

  @BeforeEach
  void seed() {
    repository.deleteAll();
    RegexParityFixture.VALUES.forEach(
        (id, value) -> {
          SqlSearchEntity entity = new SqlSearchEntity();
          entity.setTransformationId(id);
          entity.setModifiedBy(value);
          entity.setStatus(RegexParityFixture.status(id));
          entity.setCreatedOn(Instant.parse("2025-01-01T00:00:00Z"));
          entity.setPriority(1);
          repository.save(entity);
        });
  }

  static Stream<Arguments> subsetCases() {
    return RegexParityFixture.subsetCases();
  }

  static Stream<Arguments> outsideSubsetCases() {
    return RegexParityFixture.outsideSubsetCases();
  }

  @ParameterizedTest(name = "{0}: modifiedBy.{1}={2}")
  @MethodSource("subsetCases")
  void inSubsetPatternReturnsTheSameRowsAsTheRegexBackends(
      String label, String op, String pattern, Set<String> expected) throws Exception {
    String body =
        mockMvc
            .perform(get("/sql-search").param("modifiedBy." + op, pattern))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(ids(body)).as(label).isEqualTo(expected);
  }

  @ParameterizedTest(name = "modifiedBy.regex={0} → 400")
  @MethodSource("outsideSubsetCases")
  void outsideSubsetPatternIsRejectedNamingTheSubset(String pattern, Set<String> ignored)
      throws Exception {
    mockMvc
        .perform(get("/sql-search").param("modifiedBy.regex", pattern))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("Supported subset on JPA")));
    mockMvc
        .perform(get("/sql-search").param("modifiedBy.regexi", pattern))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("Supported subset on JPA")));
  }

  @Test
  @DisplayName("Part 6 =~ with || across two fields — the consumer's URL shape")
  void orAcrossFieldsThroughJsonPath() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/sql-search")
                    .param("filter", "$[?(@.modifiedBy =~ /.*t.*/i || @.status =~ /^TEST$/)]"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(ids(body)).isEqualTo(RegexParityFixture.OR_ACROSS_FIELDS);
  }

  @Test
  @DisplayName("Part 6 =~ outside the subset inside a || is still a 400")
  void orAcrossFieldsOutsideSubsetIsRejected() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("filter", "$[?(@.modifiedBy =~ /t+/i || @.status == 'TEST')]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("Supported subset on JPA")));
  }

  private Set<String> ids(String body) {
    Set<String> ids = new HashSet<>();
    for (JsonNode node : jsonMapper.readTree(body)) {
      ids.add(node.path("transformationId").asString());
    }
    return ids;
  }

  @SpringBootApplication(
      scanBasePackageClasses = SqlSearchController.class,
      exclude = {
        MongoAutoConfiguration.class,
        DataMongoAutoConfiguration.class,
        DataMongoRepositoriesAutoConfiguration.class
      })
  static class TestApp {}
}
