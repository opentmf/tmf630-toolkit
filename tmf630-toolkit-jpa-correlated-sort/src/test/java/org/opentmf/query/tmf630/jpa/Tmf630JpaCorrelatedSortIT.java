package org.opentmf.query.tmf630.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Phase (a.4) integration test — JPA correlated sort via {@link Tmf630JpaCorrelatedSortExecutor}.
 * Exercises the {@code sort=hopField[key=value].leafField} grammar against a Postgres-backed
 * parent entity with a JOIN-mapped {@code @OneToMany} characteristic collection. Verifies:
 * plain sort still works, simple-rich correlated sort produces the expected ordering
 * (parents sorted by their {@code characteristics[name=price].value}), rejections for
 * out-of-scope grammar (JsonPath sort, wildcards, positional index).
 */
@SpringBootTest(
    classes = Tmf630JpaCorrelatedSortIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630JpaCorrelatedSortIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SortableParentRepository repository;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    SortableParent alpha = parent("Alpha");
    alpha.addCharacteristic("color", "red");
    alpha.addCharacteristic("price", "300");

    SortableParent beta = parent("Beta");
    beta.addCharacteristic("color", "blue");
    beta.addCharacteristic("price", "100");

    SortableParent gamma = parent("Gamma");
    gamma.addCharacteristic("color", "green");
    gamma.addCharacteristic("price", "200");

    repository.saveAll(List.of(alpha, beta, gamma));
  }

  @Test
  @DisplayName("plain sort=name still works through the correlated-sort executor")
  void plainSortStillWorks() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "name")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Alpha"))
        .andExpect(jsonPath("$.content[1].name").value("Beta"))
        .andExpect(jsonPath("$.content[2].name").value("Gamma"));
  }

  @Test
  @DisplayName(
      "simple-rich sort=characteristics[name=price].value orders parents by the price"
          + " characteristic's value, ascending")
  void simpleRichSortAscendingByCharacteristicValue() throws Exception {
    // Prices as strings: "100" < "200" < "300" lexicographically (which happens to match
    // numeric here because all are three digits with a leading '1'/'2'/'3').
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "characteristics[name=price].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Beta"))
        .andExpect(jsonPath("$.content[1].name").value("Gamma"))
        .andExpect(jsonPath("$.content[2].name").value("Alpha"));
  }

  @Test
  @DisplayName(
      "simple-rich sort=-characteristics[name=price].value orders parents by price desc")
  void simpleRichSortDescendingByCharacteristicValue() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "-characteristics[name=price].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Alpha"))
        .andExpect(jsonPath("$.content[1].name").value("Gamma"))
        .andExpect(jsonPath("$.content[2].name").value("Beta"));
  }

  @Test
  @DisplayName("multi-term sort composes: -characteristics[name=color].value, name asc")
  void multiTermMixedSort() throws Exception {
    // color values: alpha=red, beta=blue, gamma=green
    // DESC on color: red, green, blue → Alpha, Gamma, Beta
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "-characteristics[name=color].value,name")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Alpha"))
        .andExpect(jsonPath("$.content[1].name").value("Gamma"))
        .andExpect(jsonPath("$.content[2].name").value("Beta"));
  }

  @Test
  @DisplayName("JsonPath sort grammar rejected with clear message (deferred out of Phase a.4)")
  void rejectsJsonPathSortGrammar() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "$.characteristics[?(@.name=='price')].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("positional [N] in sort rejected with clear message")
  void rejectsPositionalIndexInSort() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "characteristics[0].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("wildcard [*] in sort rejected with clear message")
  void rejectsWildcardInSort() throws Exception {
    mockMvc
        .perform(
            get("/sortable-parents")
                .param("sort", "characteristics[*].value")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  private static SortableParent parent(String name) {
    SortableParent p = new SortableParent();
    p.setName(name);
    return p;
  }

  @SpringBootApplication(scanBasePackageClasses = SortableParentController.class)
  static class TestApp {}
}
