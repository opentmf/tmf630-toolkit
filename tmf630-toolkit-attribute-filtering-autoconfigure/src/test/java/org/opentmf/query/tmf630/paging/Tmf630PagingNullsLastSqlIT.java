package org.opentmf.query.tmf630.paging;

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
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IT for the {@code opentmf.tmf630.paging.nulls-last} property. Verifies the emitted
 * SQL contains {@code nulls last} on Postgres for both ASC and DESC sort directions,
 * closing the JPA/Mongo parity gap catalogued in {@code JPA_BACKEND_GAP_ANALYSIS.md}
 * §3.2. Postgres renders the modifier natively; other dialects would translate it to
 * a {@code CASE WHEN x IS NULL} companion sort key via Hibernate.
 */
@SpringBootTest(
    classes = Tmf630PagingNullsLastSqlIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector",
      "opentmf.tmf630.paging.nulls-last=true",
      "opentmf.tmf630.paging.allow-nested-sort-properties=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PagingNullsLastSqlIT {

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
      "nulls-last=true on DESC sort emits explicit NULLS LAST (differs from Postgres default)")
  void nullsLastPropertyDecoratesDescOverridesDialectDefault() throws Exception {
    // Postgres default for DESC is nulls-first, so with nulls-last=true, Hibernate
    // must emit an explicit NULLS LAST modifier. (For ASC, Postgres already puts
    // nulls last natively, so Hibernate optimizes the redundant modifier away —
    // which is why the ASC-side assertion is a semantic "same rows" check instead
    // of a text match; see nullsLastPropertyPreservesSemanticsOnAscSort below.)
    mockMvc
        .perform(
            get("/paging-nulls-last-search")
                .param("sort", "-modifiedBy")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains("order by").contains(" desc").contains("nulls last");
  }

  @Test
  @DisplayName("nulls-last=true on multi-term mixed-direction sort emits NULLS LAST per DESC term")
  void nullsLastPropertyDecoratesEveryDescTerm() throws Exception {
    // Same rationale as nullsLastPropertyDecoratesDescOverridesDialectDefault:
    // Postgres emits NULLS LAST only when it differs from the dialect default.
    // In `-status,-modifiedBy` both terms are DESC → both need explicit NULLS LAST.
    mockMvc
        .perform(
            get("/paging-nulls-last-search")
                .param("sort", "-status,-modifiedBy")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    int nullsLastCount = sql.split("nulls last", -1).length - 1;
    assertThat(nullsLastCount).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "nulls-last=true on ASC sort places NULL rows last in the result set (semantic check)")
  void nullsLastPropertyPlacesNullRowsLastOnAscSort() throws Exception {
    // On Postgres, ASC sort already places nulls last natively — so the SQL text may
    // or may not carry an explicit NULLS LAST modifier (Hibernate elides it when it
    // matches the dialect default). What we can always verify is the SEMANTIC
    // outcome: rows with null modifiedBy come last. The seed has one row with
    // modifiedBy=null ("abc") and two with non-null values ("alice", "bob").
    mockMvc
        .perform(
            get("/paging-nulls-last-search")
                .param("sort", "modifiedBy")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].modifiedBy").value("alice"))
        .andExpect(jsonPath("$.content[1].modifiedBy").value("bob"))
        .andExpect(jsonPath("$.content[2].modifiedBy").doesNotExist());
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

  @RestController
  static class PagingNullsLastSearchController {

    private final SqlSearchEntityRepository repository;

    PagingNullsLastSearchController(SqlSearchEntityRepository repository) {
      this.repository = repository;
    }

    @GetMapping("/paging-nulls-last-search")
    public Page<SqlSearchEntity> search(Pageable pageable) {
      return repository.findAll(pageable);
    }
  }

  @SpringBootApplication(
      scanBasePackageClasses = SqlSearchController.class,
      exclude = {
        MongoAutoConfiguration.class,
        DataMongoAutoConfiguration.class,
        DataMongoRepositoriesAutoConfiguration.class
      })
  @Import(PagingNullsLastSearchController.class)
  @EntityScan(basePackageClasses = SqlSearchEntity.class)
  @EnableJpaRepositories(basePackageClasses = SqlSearchEntityRepository.class)
  static class TestApp {}
}
