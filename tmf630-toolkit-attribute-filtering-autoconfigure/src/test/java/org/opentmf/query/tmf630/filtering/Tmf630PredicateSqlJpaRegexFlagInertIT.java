package org.opentmf.query.tmf630.filtering;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

/**
 * Pins that the deprecated {@code regex.allow-jpa-like-semantics} flag is inert since 3.4.0:
 * with it set, regex on a JPA root behaves exactly as without it — the LIKE-expressible subset
 * renders, everything else is a 400. Before 3.4.0 the flag routed patterns to querydsl's
 * {@code regexToLike}, where {@code %p%} leaked through as SQL wildcards and {@code ^p} threw an
 * unmapped exception; neither can happen any more, flag or no flag.
 */
@SpringBootTest(
    classes = Tmf630PredicateSqlJpaRegexFlagInertIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.SqlSearchEntity=transformationId,status",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.regex.allow-jpa-like-semantics=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlJpaRegexFlagInertIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SqlSearchEntityRepository repository;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    repository.saveAll(
        List.of(
            entity("abc", "NEW"), entity("p", "DONE"), entity("50% off", "FAILED")));
  }

  @Test
  @DisplayName("flag set: bare literal is still CONTAINS, not exact match")
  void bareLiteralIsContains() throws Exception {
    mockMvc
        .perform(get("/sql-search").param("transformationId.regex", "b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].transformationId").value("abc"));
  }

  @Test
  @DisplayName("flag set: SQL wildcards typed by the caller are still literals")
  void wildcardsAreLiterals() throws Exception {
    mockMvc
        .perform(get("/sql-search").param("transformationId.regex", "%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].transformationId").value("50% off"));
  }

  @Test
  @DisplayName("flag set: anchors and classes outside the subset are still a 400, not a 500")
  void outsideSubsetIsStillRejected() throws Exception {
    for (String pattern : List.of("a+", "[ab]", "\\d")) {
      mockMvc
          .perform(get("/sql-search").param("transformationId.regex", pattern))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value(containsString("Supported subset on JPA")));
    }
    mockMvc
        .perform(get("/sql-search").param("filter", "$[?(@.status =~ /^N.*/)]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  private static SqlSearchEntity entity(String transformationId, String status) {
    SqlSearchEntity entity = new SqlSearchEntity();
    entity.setTransformationId(transformationId);
    entity.setStatus(status);
    entity.setCreatedOn(Instant.parse("2025-01-01T00:00:00Z"));
    entity.setPriority(1);
    return entity;
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
