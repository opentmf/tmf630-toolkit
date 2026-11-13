package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Under the DEFAULT {@code equality=extraction}, a URL equality emits the 3.5.2 expression, so an
 * expression index made on exactly that expression serves it, as the design (§2.2) tells adopters:
 * {@code ((payload#>>'{receiver,postalAddress,city}'))} for a String field and
 * {@code (((payload->>'priority')::bigint))} for an Integer field. Proven by the plan, on the rows of
 * {@link Tmf630JsonbIndexPlanIT}, with a GIN index on the payload present too.
 */
@SpringBootTest(
    classes = Tmf630JsonbIndexPlanDefaultsIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbIndexPlanDefaultsIT {

  private static final String TABLE = JsonbIndexedRow.TABLE;
  private static final String CITY_INDEX = TABLE + "_city_expr";
  private static final String PRIORITY_INDEX = TABLE + "_priority_expr";

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private ObjectMapper objectMapper;

  @BeforeAll
  void seedIndexesAndAnalyse() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    jdbc.sql(Tmf630JsonbIndexPlanIT.SEED_SQL).param(Tmf630JsonbIndexPlanIT.ROWS).update();
    jdbc.sql("CREATE INDEX IF NOT EXISTS " + TABLE + "_payload_gin_d ON " + TABLE
            + " USING gin (payload jsonb_path_ops)")
        .update();
    jdbc.sql("CREATE INDEX IF NOT EXISTS " + CITY_INDEX + " ON " + TABLE
            + " ((payload#>>'{receiver,postalAddress,city}'))")
        .update();
    jdbc.sql("CREATE INDEX IF NOT EXISTS " + PRIORITY_INDEX + " ON " + TABLE
            + " (((payload->>'priority')::bigint))")
        .update();
    jdbc.sql("ANALYZE " + TABLE).update();
  }

  static Stream<Arguments> equalities() {
    return Stream.of(
        Arguments.of("receiver.postalAddress.city", "City42", CITY_INDEX, 40),
        Arguments.of("receiver.postalAddress.city.in", "City42,City43", CITY_INDEX, 80),
        Arguments.of("priority", "42", PRIORITY_INDEX, 67));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("equalities")
  void theFieldsOwnExpressionIndexServesTheDefaultEquality(
      String param, String value, String index, int rows) {
    JsonbClause where =
        clauseBuilder.build(JsonbIndexedDomain.class, Map.of(param, new String[] {value}));
    String select = "SELECT id FROM " + TABLE + " WHERE " + where.sql();

    List<String> ids = jdbc.sql(select).params(where.params()).query(String.class).list();
    String planText =
        String.join(
            "\n",
            jdbc.sql("EXPLAIN (FORMAT JSON) " + select)
                .params(where.params())
                .query(String.class)
                .list());
    JsonNode plan = objectMapper.readTree(planText);

    assertThat(ids).as(where.sql()).hasSize(rows);
    assertThat(indexScansOn(plan, index)).as("plan of %s%n%s", select, planText).isPositive();
  }

  /** Index Scans and Bitmap Index Scans on {@code index} in an {@code EXPLAIN (FORMAT JSON)} result. */
  private static long indexScansOn(JsonNode explain, String index) {
    return explain.valueStream()
        .flatMap(root -> nodes(root.path("Plan")))
        .filter(n -> index.equals(n.path("Index Name").asString()))
        .count();
  }

  private static Stream<JsonNode> nodes(JsonNode plan) {
    Stream<JsonNode> children =
        plan.path("Plans").valueStream().flatMap(Tmf630JsonbIndexPlanDefaultsIT::nodes);
    return Stream.concat(Stream.of(plan), children);
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
