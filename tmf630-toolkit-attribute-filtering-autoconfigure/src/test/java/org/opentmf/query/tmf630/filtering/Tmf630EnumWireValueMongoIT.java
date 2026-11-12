package org.opentmf.query.tmf630.filtering;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoEnumEntity;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoEnumEntity.Clash;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoEnumEntity.Phase;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoEnumEntity.Stage;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoEnumRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Enum filter values through the URL grammar on the Mongo backend. Before 3.6.0 an enum with a
 * {@code @JsonValue} wire value and no String factory, or with {@code @JsonProperty} on its
 * constants, could be named only by its constant: its wire value was a 400. The converter now also
 * reads the text the way the service's mapper reads that enum, after the factory and the name.
 */
@SpringBootTest(classes = Tmf630EnumWireValueMongoIT.TestApp.class)
@AutoConfigureMockMvc
@Testcontainers
class Tmf630EnumWireValueMongoIT {

  @Container static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoEnumRepository repository;

  @BeforeEach
  void seed() {
    repository.deleteAll();
    repository.saveAll(
        List.of(
            new MongoEnumEntity("M1", Stage.IN_PROGRESS, Phase.ONE, Clash.LEFT),
            new MongoEnumEntity("M2", Stage.DONE, Phase.TWO, Clash.RIGHT),
            new MongoEnumEntity("M3", Stage.IN_PROGRESS, Phase.TWO, Clash.LEFT)));
  }

  @ParameterizedTest(name = "{0}={1} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "stage     | inProgress       | [\"M1\",\"M3\"]",
        "stage     | IN_PROGRESS      | [\"M1\",\"M3\"]",
        "stage.in  | 'inProgress,done' | [\"M1\",\"M2\",\"M3\"]",
        "stage.ne  | done             | [\"M1\",\"M3\"]",
        "phase     | phase-one        | [\"M1\"]",
        "phase     | ONE              | [\"M1\"]",
        "clash     | LEFT             | [\"M1\",\"M3\"]"
      })
  void enumTextFindsItsConstant(String param, String value, String expectedIds) throws Exception {
    mockMvc
        .perform(get("/mongo-enum").param(param, value))
        .andExpect(status().isOk())
        .andExpect(content().json(expectedIds, true));
  }

  @ParameterizedTest(name = "{0}={1} -> 400")
  @CsvSource(delimiter = '|', value = {"stage | bogus", "stage | INPROGRESS"})
  void unknownEnumTextIsRefused(String param, String value) throws Exception {
    mockMvc
        .perform(get("/mongo-enum").param(param, value))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("could not be parsed")))
        .andExpect(content().string(containsString("stage")));
  }

  @SpringBootApplication(
      scanBasePackageClasses = MongoEnumEntity.class,
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
      })
  static class TestApp {}
}
