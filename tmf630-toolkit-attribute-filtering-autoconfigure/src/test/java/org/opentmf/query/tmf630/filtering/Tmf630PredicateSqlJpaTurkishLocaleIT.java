package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Under a Turkish default locale every case-insensitive operator on a JPA entity finds a stored
 * upper-case value from a filter value in capitals. Before the fix Querydsl lower-cased the value
 * in Java under the default locale ({@code ITEM} became {@code ıtem}) while PostgreSQL lowered
 * the column to {@code item}, so the list came back empty. The locale is set inside the test and
 * restored after it.
 */
@SpringBootTest(
    classes = Tmf630PredicateSqlJpaTurkishLocaleIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.SqlSearchEntity=transformationId,status",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlJpaTurkishLocaleIT {

  private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

  @Autowired private MockMvc mockMvc;
  @Autowired private SqlSearchEntityRepository repository;

  private Locale before;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    repository.saveAll(List.of(entity("t-1", "ACTIVE ITEM"), entity("t-2", "DONE")));
    before = Locale.getDefault();
    Locale.setDefault(TURKISH);
  }

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(before);
  }

  @ParameterizedTest(name = "status.{0}={1} finds the stored ''ACTIVE ITEM'' under tr-TR")
  @CsvSource({
    "eqi, ACTIVE ITEM",
    "likei, ACTIVE%",
    "containsi, VE IT",
    "startswithi, ACTIVE I",
    "endswithi, E ITEM",
    "regexi, ACTIVE I"
  })
  void capitalsFindTheUpperCaseRow(String operator, String value) throws Exception {
    mockMvc
        .perform(get("/sql-search").param("status." + operator, value))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].transformationId").value("t-1"));
  }

  @Test
  void neiInCapitalsExcludesTheUpperCaseRow() throws Exception {
    mockMvc
        .perform(get("/sql-search").param("status.nei", "ACTIVE ITEM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].transformationId").value("t-2"));
  }

  @Test
  void lowerCaseValueStillFindsTheRow() throws Exception {
    mockMvc
        .perform(get("/sql-search").param("status.eqi", "active item"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].transformationId").value("t-1"));
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
