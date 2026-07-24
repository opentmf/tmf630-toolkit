package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NULLISH isnull-semantics on the JPA/SQL path. SQL scalars have only NULL / non-NULL
 * for a "no value" state, and {@code NOT IN (NULL)} is UNKNOWN under SQL trilean logic —
 * ANDing it into IS_NOT_NULL would poison the query and return zero rows. So the
 * {@link org.opentmf.query.tmf630.filtering.predicate.PredicateFactory} backend-detects
 * the root entity's annotations and only widens the predicate on Mongo {@code @Document}
 * roots. What we enforce here: the NULLISH toggle is a functional no-op on JPA — same
 * row set as MISSING_ONLY, same {@code IS NULL} / {@code IS NOT NULL} SQL shape,
 * no query poisoning.
 */
@SpringBootTest(
    classes = Tmf630PredicateSqlNullishIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector",
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.SqlSearchEntity=transformationId,status,modifiedBy,createdOn,priority",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT",
      "opentmf.tmf630.attribute-filtering.isnull-semantics=NULLISH"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlNullishIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SqlSearchEntityRepository repository;

  @BeforeEach
  void setUp() {
    SqlCaptureInspector.clear();
    repository.deleteAll();
    repository.saveAll(
        List.of(
            entity("abc", "NEW", null, Instant.parse("2025-01-01T00:00:00Z"), 1),
            entity("def", "DONE", "alice", Instant.parse("2025-02-01T00:00:00Z"), 2),
            entity("xyz", "FAILED", "bob", Instant.parse("2025-03-01T00:00:00Z"), 3)));
    SqlCaptureInspector.clear();
  }

  @Test
  @DisplayName("NULLISH mode still matches the SQL NULL row for a scalar column")
  void nullishScalarMatchesSqlNullRow() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("modifiedBy.isnull", "")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].transformationId").value("abc"));

    // Backend-detect kicked in: the root is a JPA @Entity, not a Mongo @Document, so the
    // widening was skipped and Hibernate rendered a plain IS NULL — no NOT IN (NULL)
    // poison, no zero-row surprise.
    String sql = firstSelectSql();
    assertThat(sql).contains(" is null");
  }

  @Test
  @DisplayName("NULLISH IS_NOT_NULL under JPA is a plain IS NOT NULL and matches all non-NULL rows")
  void nullishIsNotNullMatchesNonNullRows() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("modifiedBy.isnotnull", "")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));

    String sql = firstSelectSql();
    assertThat(sql).contains(" is not null");
  }

  private static SqlSearchEntity entity(
      String transformationId,
      String status,
      String modifiedBy,
      Instant createdOn,
      Integer priority) {
    SqlSearchEntity entity = new SqlSearchEntity();
    entity.setTransformationId(transformationId);
    entity.setStatus(status);
    entity.setModifiedBy(modifiedBy);
    entity.setCreatedOn(createdOn);
    entity.setPriority(priority);
    return entity;
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
      scanBasePackageClasses = SqlSearchController.class,
      exclude = {
        MongoAutoConfiguration.class,
        DataMongoAutoConfiguration.class,
        DataMongoRepositoriesAutoConfiguration.class
      })
  static class TestApp {}
}
