package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = Tmf630PredicateSqlIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector",
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.SqlSearchEntity=transformationId,status,modifiedBy,createdOn,priority",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SqlSearchEntityRepository repository;

  @BeforeEach
  void setUp() {
    SqlCaptureInspector.clear();
    repository.deleteAll();
    repository.saveAll(List.of(entity("abc", "NEW", null, Instant.parse("2025-01-01T00:00:00Z"), 1),
        entity("def", "DONE", "alice", Instant.parse("2025-02-01T00:00:00Z"), 2),
        entity("xyz", "FAILED", "bob", Instant.parse("2025-03-01T00:00:00Z"), 3)));
    SqlCaptureInspector.clear();
  }

  @Test
  @DisplayName("eq / ne / eqi / nei are reflected in SQL")
  void equalityFamilyOperandsAreReflected() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("transformationId.eq", "abc")
                .param("status.ne", "FAILED")
                .param("modifiedBy.eqi", "alice")
                .param("status.nei", "done")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(" where ");
    assertThat(sql).contains("=");
    assertThat(sql).containsAnyOf("<>", "!=");
    assertThat(sql).contains("lower(");
  }

  @Test
  @DisplayName("gt / gte / lt / lte / between are reflected in SQL")
  void rangeOperandsAreReflected() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("priority.gt", "0")
                .param("priority.gte", "1")
                .param("priority.lt", "10")
                .param("priority.lte", "9")
                .param("createdOn.between", "2025-01-01T00:00:00Z", "2025-12-31T23:59:59Z")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(">");
    assertThat(sql).contains("<");
    assertThat(sql).contains(">=");
    assertThat(sql).contains("<=");
    assertThat(sql).contains("between");
  }

  @Test
  @DisplayName("in / nin / isnull / isnotnull are reflected in SQL")
  void setAndNullOperandsAreReflected() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("transformationId.in", "abc", "def")
                .param("status.nin", "FAILED", "UNKNOWN")
                .param("modifiedBy.isnull", "")
                .param("status.isnotnull", "")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(" in ");
    assertThat(sql).contains(" not in ");
    assertThat(sql).contains(" is null");
    assertThat(sql).contains(" is not null");
  }

  @Test
  @DisplayName("like family + regex are reflected in SQL")
  void patternOperandsAreReflected() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("transformationId.like", "%ab%")
                .param("transformationId.likei", "%AB%")
                .param("status.contains", "NE")
                .param("status.containsi", "do")
                .param("status.startswith", "D")
                .param("status.startswithi", "d")
                .param("status.endswith", "E")
                .param("status.endswithi", "e")
                .param("transformationId.regex", "a.*")
                .param("transformationId.regexi", "A.*")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains("like");
    assertThat(sql).contains("lower(");
    // Querydsl StringPath.matches is rendered as LIKE for this stack/dialect.
    assertThat(sql).contains(" like ");
  }

  @Test
  @DisplayName("Combined operands remain visible in single generated SQL")
  void combinedOperandsProduceCombinedSql() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("transformationId.eq", "abc")
                .param("priority.gte", "1")
                .param("status.in", "NEW", "DONE")
                .param("modifiedBy.isnull", "")
                .param("transformationId.regex", "a.*")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(" where ");
    assertThat(sql).contains("=");
    assertThat(sql).contains(">=");
    assertThat(sql).contains(" in ");
    assertThat(sql).contains(" is null");
    assertThat(sql).contains(" like ");
  }

  @Test
  @DisplayName("jsonPath filter is reflected in generated SQL")
  void jsonPathFilterIsReflectedInSql() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("filter", "$[?(@.status == 'NEW' && @.priority >= 1)]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains("status");
    assertThat(sql).contains("priority");
    assertThat(sql).contains(" where ");
  }

  @Test
  @DisplayName("invalid jsonPath filter returns bad request")
  void invalidJsonPathFilterReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("filter", "$.status")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  // ---------- 2.1.4 spec-alignment features on the JPA/SQL path ----------
  //
  // The toolkit's filter parsing lives at the resolver / JsonPathFilterPredicateBuilder
  // level and produces standard QueryDSL Predicates — so these forms are backend-neutral
  // by construction and must land on JPA the same way they land on Mongo. These tests
  // enforce that "both backends" rule for the SQL path.

  @Test
  @DisplayName("Part 1 §4.4 semicolon OR (value shorthand) folds into an OR of equalities")
  void semicolonValueListFoldsToOr() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                .param("transformationId", "abc;def")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));

    // Row-level check is the acid test: both 'abc' and 'def' rows must come back. The SQL
    // shape check just confirms the OR fold reached QueryDSL — the exact SQL text depends
    // on the dialect (Hibernate reshuffles equalities into IN(?,?) on the same column).
    String sql = firstSelectSql();
    assertThat(sql).contains(" where ");
    assertThat(sql).containsAnyOf(" or ", " in ");
  }

  @Test
  @DisplayName("Part 1 §4.4 semicolon OR (repeated-pair form) strips the redundant key prefix")
  void semicolonRepeatedPairStripsRedundantKey() throws Exception {
    mockMvc
        .perform(
            get("/sql-search")
                // ?transformationId=abc;transformationId=def — one URL param carrying the whole
                // semicolon-tail in its value.
                .param("transformationId", "abc;transformationId=def")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));

    String sql = firstSelectSql();
    assertThat(sql).containsAnyOf(" or ", " in ");
  }

  @Test
  @DisplayName("Part 1 §4.4 URL-encoded operator literals in the param name map to suffix operators")
  void encodedOperatorNamesMapToSuffixOperators() throws Exception {
    // The servlet container decodes %3E → '>' before the resolver runs, so we simulate the
    // already-decoded param name here. Under the resolver's normalizer this rewrites to
    // priority.gt / .gte / .lte / .lt.
    mockMvc
        .perform(
            get("/sql-search")
                .param("priority>", "0")
                .param("priority>=", "1")
                .param("priority<=", "9")
                .param("priority<", "10")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(">");
    assertThat(sql).contains("<");
    assertThat(sql).contains(">=");
    assertThat(sql).contains("<=");
  }

  @Test
  @DisplayName("Part 6 =~ regex in JSONPath filter lands on JPA as a LIKE/matches predicate")
  void regexEqualsTildeInFilterLandsOnJpa() throws Exception {
    // QueryDSL-JPA's MATCHES_IC serializer rejects patterns with regex anchors (^, $) since
    // it converts them to LIKE. Use an anchor-free pattern; the spec's own =~ examples
    // (e.g. /Resol.*?/i) are also anchor-free.
    mockMvc
        .perform(
            get("/sql-search")
                .param("filter", "$[?(@.status =~ /D.*/i)]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    String sql = firstSelectSql();
    assertThat(sql).contains(" where ");
    assertThat(sql).contains("status");
    // Case-insensitive regex on JPA goes through Ops.MATCHES_IC → lower(...) LIKE lower(?)
    // in Hibernate's default rendering for this dialect.
    assertThat(sql).containsAnyOf(" like ", " matches ");
  }

  private static SqlSearchEntity entity(
      String transformationId, String status, String modifiedBy, Instant createdOn, Integer priority) {
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
            .collect(Collectors.toList());
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
