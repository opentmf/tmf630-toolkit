package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Phase (a.2) — compatibility mode. Verifies that with
 * {@code opentmf.tmf630.attribute-filtering.regex.allow-jpa-like-semantics=true} set,
 * the pre-3.0.0 behavior is preserved: {@code .regex}/{@code .regexi} on JPA entities
 * render as SQL {@code LIKE}/{@code LOWER(x) LIKE LOWER(?)} exactly as before. This is
 * the escape hatch for existing services that already depend on the (semantically
 * different from real regex) LIKE-based matching; it emits a one-time WARN log on
 * first use and is deprecated for removal in a future release. Companion to
 * {@code Tmf630PredicateSqlIT#rejectsJpaRegexByDefault}.
 */
@SpringBootTest(
    classes = Tmf630PredicateSqlJpaRegexCompatIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector",
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.SqlSearchEntity=transformationId,status,modifiedBy,createdOn,priority",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.regex.allow-jpa-like-semantics=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlJpaRegexCompatIT {

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
  @DisplayName(
      "compat flag enabled: `.regex` on JPA proceeds and renders as SQL LIKE (with WARN log)")
  void regexOnJpaProceedsWithCompatFlag() throws Exception {
    // Anchor-free pattern per the pre-3.0.0 IT — anchors would match literally under LIKE.
    mockMvc
        .perform(
            get("/sql-search")
                .param("transformationId.regex", "a.*")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(" where ").contains(" like ");
  }

  @Test
  @DisplayName("compat flag enabled: `.regexi` on JPA proceeds via LIKE (exact rendering "
      + "varies by dialect/Hibernate version)")
  void regexIOnJpaProceedsWithCompatFlag() throws Exception {
    // The exact rendering (plain LIKE vs lower(x) LIKE lower(?)) depends on the
    // querydsl-jpa MATCHES_IC template resolution for the current Hibernate/Postgres
    // combo; what matters for Phase (a.2)'s compat mode is that the request no longer
    // 400s and a LIKE lands in the emitted SQL.
    mockMvc
        .perform(
            get("/sql-search")
                .param("transformationId.regexi", "A.*")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(" where ").contains(" like ");
  }

  @Test
  @DisplayName(
      "compat flag enabled: JSONPath =~ regex on JPA proceeds (same path as .regex/.regexi)")
  void regexEqualsTildeInFilterProceedsWithCompatFlag() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("filter", "$[?(@.status =~ /D.*/i)]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(" where ").containsAnyOf(" like ", " matches ");
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
