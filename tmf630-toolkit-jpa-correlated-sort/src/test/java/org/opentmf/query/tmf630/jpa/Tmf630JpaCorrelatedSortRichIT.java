package org.opentmf.query.tmf630.jpa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.jpa.it.SortableCharacteristic;
import org.opentmf.query.tmf630.jpa.it.SortableParent;
import org.opentmf.query.tmf630.jpa.it.SortableParentController;
import org.opentmf.query.tmf630.jpa.it.SortableParentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for the pragmatic-parity extensions to {@link
 * Tmf630JpaCorrelatedSortExecutor}: multi-hop chains, explicit {@code min()} / {@code max()}
 * aggregators, and the direction-aware implicit reducer that mirrors the Mongo executor's
 * {@code $min}/{@code $max} behavior.
 *
 * <p>Seed shape: three parents, each with several characteristics, and (for the
 * multi-hop tests) qualifiers hanging off some of those characteristics. Values are
 * chosen so that lexicographic and numeric comparisons agree.
 */
@SpringBootTest(
    classes = Tmf630JpaCorrelatedSortRichIT.RichTestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630JpaCorrelatedSortRichIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SortableParentRepository repository;

  @BeforeEach
  void setUp() {
    repository.deleteAll();

    // Multi-match: each parent has two characteristics with the same name "score",
    // different values. Implicit MIN (ASC) / MAX (DESC) picks the extreme.
    //   Alpha:   score=50, score=90    → min=50, max=90
    //   Beta:    score=70               → min=70, max=70
    //   Gamma:   score=30, score=60    → min=30, max=60
    // Multi-hop: each parent has a "price" characteristic that carries a "currency"
    // qualifier — distinct values so ordering has no ties.
    //   Alpha: price → currency=USD
    //   Beta:  price → currency=EUR
    //   Gamma: price → currency=TRY

    SortableParent alpha = parent("Alpha");
    alpha.addCharacteristic("score", "50");
    alpha.addCharacteristic("score", "90");
    SortableCharacteristic alphaPrice = alpha.addCharacteristic("price", "100");
    alphaPrice.addQualifier("currency", "USD");

    SortableParent beta = parent("Beta");
    beta.addCharacteristic("score", "70");
    SortableCharacteristic betaPrice = beta.addCharacteristic("price", "200");
    betaPrice.addQualifier("currency", "EUR");

    SortableParent gamma = parent("Gamma");
    gamma.addCharacteristic("score", "30");
    gamma.addCharacteristic("score", "60");
    SortableCharacteristic gammaPrice = gamma.addCharacteristic("price", "150");
    gammaPrice.addQualifier("currency", "TRY");

    repository.saveAll(List.of(alpha, beta, gamma));
  }

  @Test
  @DisplayName(
      "single-hop with multi-match children — ASC picks MIN implicitly: Gamma(30), Alpha(50), Beta(70)")
  void singleHopMultiMatchAscendingPicksMin() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "characteristics[name=score].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Gamma"))
        .andExpect(jsonPath("$.content[1].name").value("Alpha"))
        .andExpect(jsonPath("$.content[2].name").value("Beta"));
  }

  @Test
  @DisplayName(
      "single-hop with multi-match children — DESC picks MAX implicitly: Alpha(90), Beta(70), Gamma(60)")
  void singleHopMultiMatchDescendingPicksMax() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "-characteristics[name=score].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Alpha"))
        .andExpect(jsonPath("$.content[1].name").value("Beta"))
        .andExpect(jsonPath("$.content[2].name").value("Gamma"));
  }

  @Test
  @DisplayName(
      "explicit max() with ASC direction: sort ascending by each parent's max score → Gamma(60), Beta(70), Alpha(90)")
  void explicitMaxAscendingDirection() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "max(characteristics[name=score].value)")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Gamma"))
        .andExpect(jsonPath("$.content[1].name").value("Beta"))
        .andExpect(jsonPath("$.content[2].name").value("Alpha"));
  }

  @Test
  @DisplayName(
      "explicit min() with DESC direction: sort descending by each parent's min score → Beta(70), Alpha(50), Gamma(30)")
  void explicitMinDescendingDirection() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "-min(characteristics[name=score].value)")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Beta"))
        .andExpect(jsonPath("$.content[1].name").value("Alpha"))
        .andExpect(jsonPath("$.content[2].name").value("Gamma"));
  }

  @Test
  @DisplayName(
      "two-hop chain ASC: sort by price-characteristic's currency-qualifier value →"
          + " EUR(Beta), TRY(Gamma), USD(Alpha)")
  void twoHopChainAscending() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param(
                    "sort",
                    "characteristics[name=price].qualifiers[name=currency].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Beta"))
        .andExpect(jsonPath("$.content[1].name").value("Gamma"))
        .andExpect(jsonPath("$.content[2].name").value("Alpha"));
  }

  @Test
  @DisplayName(
      "two-hop chain DESC: reversed currency order → USD(Alpha), TRY(Gamma), EUR(Beta)")
  void twoHopChainDescending() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param(
                    "sort",
                    "-characteristics[name=price].qualifiers[name=currency].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Alpha"))
        .andExpect(jsonPath("$.content[1].name").value("Gamma"))
        .andExpect(jsonPath("$.content[2].name").value("Beta"));
  }

  @Test
  @DisplayName("coercion num() still rejected at HTTP boundary")
  void rejectsCoercion() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "num(characteristics[name=score].value)")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  private static SortableParent parent(String name) {
    SortableParent p = new SortableParent();
    p.setName(name);
    return p;
  }

  @SpringBootApplication(scanBasePackageClasses = SortableParentController.class)
  static class RichTestApp {}
}
