package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRowRepository;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase (b.5) end-to-end IT — proves {@link Tmf630JsonbFilterExecutor} wires the b.2
 * predicate factory, the b.4 sort builder, and the b.1 entity registry together into a
 * working paged filter query against a real Postgres JSONB column via {@link
 * org.springframework.jdbc.core.simple.JdbcClient}.
 *
 * <p>Auto-config side: relies on the module's own auto-config to construct the
 * {@link Tmf630JsonbFilterExecutor}, {@link JsonbPredicateFactory}, {@link JsonbSortBuilder},
 * and {@link JsonbEntityRegistry} beans from context.
 */
@SpringBootTest(
    classes = Tmf630JsonbFilterExecutorIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "debug=true"
    })
@ActiveProfiles("test")
class Tmf630JsonbFilterExecutorIT {

  @Autowired private Tmf630JsonbFilterExecutor executor;
  @Autowired private JsonbPredicateFactory predicateFactory;
  @Autowired private JsonbJsonPathTranslator jsonPathTranslator;
  @Autowired private JsonbTestRowRepository repository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void seed() throws Exception {
    repository.deleteAll();
    save("A", "NEW", 1, "alice", "2025-01-01");
    save("B", "DONE", 5, "bob", "2025-02-01");
    save("C", "FAILED", 3, null, "2025-03-01");
    save("D", "NEW", 9, "carol", "2025-04-01");
  }

  @Test
  @DisplayName("unfiltered findAll returns all rows in insertion order (via PK), paged")
  void unfilteredReturnsAll() {
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, JsonbClause.alwaysTrue(), TmfSort.empty(),
            PageRequest.of(0, 10), any());
    assertThat(page.getTotalElements()).isEqualTo(4);
    assertThat(page.getContent()).hasSize(4);
  }

  @Test
  @DisplayName("EQ on status filters to matching rows via payload->>'status' = ?")
  void equalityFilter() {
    JsonbClause where =
        predicateFactory.build(TmfOperator.EQ, "status", String.class, "NEW");
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, where, TmfSort.empty(), Pageable.unpaged(), any());
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).extracting(JsonbTestDomain::getStatus).containsOnly("NEW");
  }

  @Test
  @DisplayName("GT on numeric priority casts to ::bigint and returns matching rows")
  void numericGreaterThan() {
    JsonbClause where =
        predicateFactory.build(TmfOperator.GT, "priority", Integer.class, 3);
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, where, TmfSort.empty(), Pageable.unpaged(), any());
    assertThat(page.getContent())
        .extracting(JsonbTestDomain::getPriority)
        .containsExactlyInAnyOrder(5, 9);
  }

  @Test
  @DisplayName("IN clause matches any-of values")
  void inClause() {
    JsonbClause where =
        predicateFactory.buildMulti(
            TmfOperator.IN, "status", String.class, List.of("NEW", "FAILED"));
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, where, TmfSort.empty(), Pageable.unpaged(), any());
    assertThat(page.getTotalElements()).isEqualTo(3);
  }

  @Test
  @DisplayName("IS_NULL under MISSING_ONLY on top-level key matches only rows where the key is absent")
  void isNullMissingOnly() {
    // Seed row 'C' has modifiedBy=null which we set via the domain POJO — but
    // because JsonbTestDomain uses @JsonInclude(NON_NULL), the modifiedBy field
    // is entirely absent from the serialised JSON payload of row 'C'. So the
    // top-level 'payload ? modifiedBy' key-existence check returns false for C.
    JsonbClause where = predicateFactory.buildNoValue(TmfOperator.IS_NULL, "modifiedBy");
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, where, TmfSort.empty(), Pageable.unpaged(), any());
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getId()).isEqualTo("C");
  }

  @Test
  @DisplayName("ORDER BY priority DESC sorts rows by numeric priority descending")
  void sortByPriorityDesc() {
    TmfSort sort =
        new TmfSort(
            List.of(new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "priority")));
    Function<String, Class<?>> resolver =
        field -> "priority".equals(field) ? Integer.class : String.class;
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, JsonbClause.alwaysTrue(), sort, Pageable.unpaged(), resolver);
    assertThat(page.getContent())
        .extracting(JsonbTestDomain::getPriority)
        .containsExactly(9, 5, 3, 1);
  }

  @Test
  @DisplayName("paging returns the requested slice and correct total")
  void paging() {
    Page<JsonbTestDomain> firstPage =
        executor.findAll(
            JsonbTestDomain.class, JsonbClause.alwaysTrue(), TmfSort.empty(),
            PageRequest.of(0, 2), any());
    assertThat(firstPage.getTotalElements()).isEqualTo(4);
    assertThat(firstPage.getContent()).hasSize(2);
    assertThat(firstPage.getTotalPages()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Phase (b.3): JsonPath filter=$[?(...)] compiles to jsonb_path_exists at runtime")
  void jsonPathFilterEqualityWorksAgainstPostgres() {
    JsonbClause where = jsonPathTranslator.translate("$[?(@.status == 'NEW')]");
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, where, TmfSort.empty(), Pageable.unpaged(), any());
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent())
        .extracting(JsonbTestDomain::getStatus)
        .containsOnly("NEW");
  }

  @Test
  @DisplayName(
      "Phase (b.3): JsonPath filter with compound predicate lands on Postgres correctly")
  void jsonPathFilterCompound() {
    JsonbClause where =
        jsonPathTranslator.translate("$[?(@.status == 'NEW' && @.priority > 5)]");
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, where, TmfSort.empty(), Pageable.unpaged(), any());
    // Only ORD-D has status=NEW AND priority > 5 (priority = 9).
    assertThat(page.getContent())
        .extracting(JsonbTestDomain::getId)
        .containsExactly("D");
  }

  @Test
  @DisplayName("combined filter + sort + paging composes cleanly")
  void combined() {
    JsonbClause where =
        predicateFactory.build(TmfOperator.EQ, "status", String.class, "NEW");
    TmfSort sort =
        new TmfSort(
            List.of(new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "priority")));
    Function<String, Class<?>> resolver =
        field -> "priority".equals(field) ? Integer.class : String.class;
    Page<JsonbTestDomain> page =
        executor.findAll(
            JsonbTestDomain.class, where, sort, PageRequest.of(0, 10), resolver);
    // Only rows A and D have status=NEW. Sorted by priority ASC: A (1), D (9).
    assertThat(page.getContent())
        .extracting(JsonbTestDomain::getId)
        .containsExactly("A", "D");
  }

  private void save(
      String id, String status, int priority, String modifiedBy, String createdOn)
      throws Exception {
    JsonbTestDomain domain = new JsonbTestDomain();
    domain.setId(id);
    domain.setStatus(status);
    domain.setPriority(priority);
    domain.setModifiedBy(modifiedBy);
    domain.setCreatedOn(createdOn);
    JsonbTestRow row = new JsonbTestRow();
    row.setId(id);
    row.setPayload(objectMapper.writeValueAsString(domain));
    repository.save(row);
  }

  private static Function<String, Class<?>> any() {
    return field -> String.class;
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
