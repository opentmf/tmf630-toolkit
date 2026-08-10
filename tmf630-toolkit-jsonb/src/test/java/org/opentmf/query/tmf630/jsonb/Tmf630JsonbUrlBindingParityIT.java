package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.parity.ParityController;
import org.opentmf.query.tmf630.jsonb.it.parity.ParityDomain;
import org.opentmf.query.tmf630.jsonb.it.parity.ParityEntity;
import org.opentmf.query.tmf630.jsonb.it.parity.ParityEntityRepository;
import org.opentmf.query.tmf630.jsonb.it.parity.ParityRow;
import org.opentmf.query.tmf630.jsonb.it.parity.ParityRowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Work item 1 (JSONB URL-binding bridge) — the same-URLs-same-results parity battery. One
 * application context hosts BOTH terminals of the shared {@code Tmf630FilterParser}
 * grammar over identically-seeded data in one Postgres: {@code /parity/jpa} executes the
 * QueryDSL predicate ({@code @QuerydslPredicate}), {@code /parity/jsonb} the
 * {@code JsonbClause} ({@code @Tmf630JsonbFilter}). Every case asserts three things: the
 * two endpoints return the same HTTP status, the same id set, and (for 200s) the id set
 * the URL semantically demands.
 *
 * <p>The one DOCUMENTED divergence — {@code .regex} rejecting on JPA (LIKE-semantics
 * guard) while running natively on JSONB — is pinned as such in its own test.
 */
@SpringBootTest(
    classes = Tmf630JsonbUrlBindingParityIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630JsonbUrlBindingParityIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ParityEntityRepository entityRepository;
  @Autowired private ParityRowRepository rowRepository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void seed() {
    rowRepository.deleteAll();
    entityRepository.deleteAll();
    save("P1", "NEW", 1, null, "2025-01-01T00:00:00Z");
    save("P2", "DONE", 2, "alice", "2025-02-01T00:00:00Z");
    save("P3", "FAILED", 3, "bob", "2025-03-01T00:00:00Z");
    save("P4", "NEW", 9, "carol", "2025-04-01T00:00:00Z");
  }

  static Stream<Arguments> parityCases() {
    return Stream.of(
        Arguments.of("no filter", Map.of(), "[\"P1\",\"P2\",\"P3\",\"P4\"]"),
        Arguments.of("implicit eq", Map.of("status", List.of("NEW")), "[\"P1\",\"P4\"]"),
        Arguments.of(
            "implicit eq csv OR", Map.of("status", List.of("NEW,DONE")), "[\"P1\",\"P2\",\"P4\"]"),
        Arguments.of(
            "implicit eq semicolon OR with same-key prefix strip",
            Map.of("status", List.of("NEW;status=DONE")),
            "[\"P1\",\"P2\",\"P4\"]"),
        Arguments.of(
            "repeated implicit eq combines per combineRepeatedValues (OR)",
            Map.of("status", List.of("NEW", "DONE")),
            "[\"P1\",\"P2\",\"P4\"]"),
        Arguments.of(
            "explicit .eq keeps comma literal", Map.of("status.eq", List.of("NEW,DONE")), "[]"),
        Arguments.of("ne", Map.of("status.ne", List.of("NEW")), "[\"P2\",\"P3\"]"),
        Arguments.of("eqi", Map.of("status.eqi", List.of("new")), "[\"P1\",\"P4\"]"),
        Arguments.of("nei", Map.of("status.nei", List.of("new")), "[\"P2\",\"P3\"]"),
        Arguments.of("gt numeric", Map.of("priority.gt", List.of("1")), "[\"P2\",\"P3\",\"P4\"]"),
        Arguments.of("gte numeric", Map.of("priority.gte", List.of("2")), "[\"P2\",\"P3\",\"P4\"]"),
        Arguments.of("lt numeric", Map.of("priority.lt", List.of("3")), "[\"P1\",\"P2\"]"),
        Arguments.of("lte numeric", Map.of("priority.lte", List.of("2")), "[\"P1\",\"P2\"]"),
        Arguments.of(
            "between numeric", Map.of("priority.between", List.of("2,3")), "[\"P2\",\"P3\"]"),
        Arguments.of(
            "in csv", Map.of("status.in", List.of("NEW,FAILED")), "[\"P1\",\"P3\",\"P4\"]"),
        Arguments.of(
            "in repeated params",
            Map.of("status.in", List.of("NEW", "FAILED")),
            "[\"P1\",\"P3\",\"P4\"]"),
        Arguments.of("nin csv", Map.of("status.nin", List.of("NEW,FAILED")), "[\"P2\"]"),
        Arguments.of("isnull", Map.of("modifiedBy.isnull", List.of("")), "[\"P1\"]"),
        Arguments.of(
            "isnotnull", Map.of("modifiedBy.isnotnull", List.of("")), "[\"P2\",\"P3\",\"P4\"]"),
        Arguments.of("like", Map.of("status.like", List.of("NE%")), "[\"P1\",\"P4\"]"),
        Arguments.of("likei", Map.of("status.likei", List.of("ne%")), "[\"P1\",\"P4\"]"),
        Arguments.of("contains", Map.of("status.contains", List.of("EW")), "[\"P1\",\"P4\"]"),
        Arguments.of("containsi", Map.of("status.containsi", List.of("ew")), "[\"P1\",\"P4\"]"),
        Arguments.of("startswith", Map.of("status.startswith", List.of("NE")), "[\"P1\",\"P4\"]"),
        Arguments.of("endswith", Map.of("status.endswith", List.of("ED")), "[\"P3\"]"),
        Arguments.of(
            "gt on ISO-8601 date string",
            Map.of("createdOn.gt", List.of("2025-01-15T00:00:00Z")),
            "[\"P2\",\"P3\",\"P4\"]"),
        Arguments.of(
            "§4.4 encoded operator literal, value in value slot",
            Map.of("priority>", List.of("1")),
            "[\"P2\",\"P3\",\"P4\"]"),
        Arguments.of(
            "§4.4 encoded operator literal, value embedded in name",
            Map.of("priority<=2", List.of("")),
            "[\"P1\",\"P2\"]"),
        Arguments.of(
            "§4.4 encoded operator ORing form",
            Map.of(
                "createdOn>2025-01-15T00:00:00Z;createdOn>2025-03-15T00:00:00Z", List.of("")),
            "[\"P2\",\"P3\",\"P4\"]"),
        Arguments.of(
            "JsonPath filter",
            Map.of("filter", List.of("$[?(@.status == \"NEW\")]")),
            "[\"P1\",\"P4\"]"),
        Arguments.of(
            "JsonPath filter OR-combined with attributes",
            Map.of(
                "status", List.of("DONE"),
                "filter", List.of("$[?(@.priority > 2)]"),
                "filter.combineWithAttributes", List.of("OR")),
            "[\"P2\",\"P3\",\"P4\"]"),
        Arguments.of(
            "JsonPath filter AND-combined with attributes (default)",
            Map.of(
                "status", List.of("DONE"),
                "filter", List.of("$[?(@.priority > 2)]")),
            "[]"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("parityCases")
  @DisplayName("same URL → same results on both terminals")
  void sameUrlSameResults(String label, Map<String, List<String>> params, String expectedIds)
      throws Exception {
    MvcResult jpa = perform("/parity/jpa", params);
    MvcResult jsonb = perform("/parity/jsonb", params);

    assertThat(jpa.getResponse().getStatus()).as("JPA status for [%s]", label).isEqualTo(200);
    assertThat(jsonb.getResponse().getStatus()).as("JSONB status for [%s]", label).isEqualTo(200);
    assertThat(jpa.getResponse().getContentAsString())
        .as("JPA ids for [%s]", label)
        .isEqualTo(expectedIds);
    assertThat(jsonb.getResponse().getContentAsString())
        .as("JSONB ids for [%s]", label)
        .isEqualTo(expectedIds);
  }

  static Stream<Arguments> rejectionParityCases() {
    return Stream.of(
        Arguments.of("unknown field REJECT", Map.of("bogusField", List.of("1"))),
        Arguments.of(
            "invalid filter.combineWithAttributes",
            Map.of(
                "status", List.of("NEW"),
                "filter", List.of("$[?(@.priority > 2)]"),
                "filter.combineWithAttributes", List.of("BOGUS"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectionParityCases")
  @DisplayName("same URL → same 400 rejection on both terminals")
  void sameUrlSameRejection(String label, Map<String, List<String>> params) throws Exception {
    assertThat(perform("/parity/jpa", params).getResponse().getStatus())
        .as("JPA status for [%s]", label)
        .isEqualTo(400);
    assertThat(perform("/parity/jsonb", params).getResponse().getStatus())
        .as("JSONB status for [%s]", label)
        .isEqualTo(400);
  }

  @Test
  @DisplayName(
      "documented divergence: .regex rejects on JPA (LIKE-semantics guard) but runs natively "
          + "on JSONB")
  void regexDivergenceIsPinned() throws Exception {
    Map<String, List<String>> params = Map.of("status.regex", List.of("^NE.*"));
    mockMvc.perform(withParams(get("/parity/jpa"), params)).andExpect(status().isBadRequest());
    MvcResult jsonb = perform("/parity/jsonb", params);
    assertThat(jsonb.getResponse().getStatus()).isEqualTo(200);
    assertThat(jsonb.getResponse().getContentAsString()).isEqualTo("[\"P1\",\"P4\"]");
  }

  private MvcResult perform(String path, Map<String, List<String>> params) throws Exception {
    return mockMvc.perform(withParams(get(path), params)).andReturn();
  }

  private static MockHttpServletRequestBuilder withParams(
      MockHttpServletRequestBuilder builder, Map<String, List<String>> params) {
    params.forEach((name, values) -> builder.param(name, values.toArray(String[]::new)));
    return builder;
  }

  private void save(String id, String status, int priority, String modifiedBy, String createdOn) {
    ParityEntity entity = new ParityEntity();
    entity.setId(id);
    entity.setStatus(status);
    entity.setPriority(priority);
    entity.setModifiedBy(modifiedBy);
    entity.setCreatedOn(createdOn);
    entityRepository.save(entity);

    ParityDomain domain = new ParityDomain();
    domain.setId(id);
    domain.setStatus(status);
    domain.setPriority(priority);
    domain.setModifiedBy(modifiedBy);
    domain.setCreatedOn(createdOn);
    ParityRow row = new ParityRow();
    row.setId(id);
    row.setPayload(objectMapper.writeValueAsString(domain));
    rowRepository.save(row);
  }

  @SpringBootApplication(scanBasePackageClasses = ParityController.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
