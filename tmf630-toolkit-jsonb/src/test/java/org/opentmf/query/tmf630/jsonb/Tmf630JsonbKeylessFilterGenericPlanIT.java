package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A {@code filter=} under the plan production runs it with. pgjdbc server-prepares a statement
 * after five executions and PostgreSQL then plans it generically, without the jsonpath; every
 * {@code filter=}-only query on a table shares one SQL text, so the generic plan is the steady
 * state. This test forces it ({@code plan_cache_mode = force_generic_plan}) only on its own test
 * connection, to reproduce what such a prepared statement gets. The library itself never sets a
 * plan mode and never hints: it sends the operator or the function, and the server plans.
 *
 * <p>Under it a filter the GIN index has no key for (a range, a negation, {@code !=}, an
 * {@code ||} with a branch without an equality) must read no more buffers than the plain scan of
 * the table: it keeps the function form, which the index never serves. A filter with a key keeps
 * its Bitmap Index Scan. Both answer the rows the seed formula demands.
 */
@SpringBootTest(
    classes = Tmf630JsonbKeylessFilterGenericPlanIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbKeylessFilterGenericPlanIT {

  private static final Logger LOG = LoggerFactory.getLogger(Tmf630JsonbKeylessFilterGenericPlanIT.class);
  private static final String TABLE = JsonbIndexedRow.TABLE;
  private static final String CITY = "@.receiver.postalAddress.city";

  @Autowired private DataSource dataSource;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private ObjectMapper objectMapper;

  private long plainScanBuffers;

  @BeforeAll
  void seedIndexAndMeasureThePlainScan() throws SQLException {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    jdbc.sql("TRUNCATE " + TABLE).update();
    jdbc.sql(Tmf630JsonbIndexPlanIT.SEED_SQL).param(Tmf630JsonbIndexPlanIT.ROWS).update();
    jdbc.sql("CREATE INDEX IF NOT EXISTS " + TABLE + "_payload_gin_g ON " + TABLE
            + " USING gin (payload jsonb_path_ops)")
        .update();
    jdbc.sql("ANALYZE " + TABLE).update();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      explain(statement, "SELECT count(*) FROM " + TABLE);
      plainScanBuffers =
          buffers(explain(statement, "SELECT count(*) FROM " + TABLE).path("Plan"));
    }
    LOG.info("plain scan of {} rows: {} buffers", Tmf630JsonbIndexPlanIT.ROWS, plainScanBuffers);
  }

  static Stream<Arguments> keylessFilters() {
    return Stream.of(
        Arguments.of("$[?(@.priority > 290)]", (IntPredicate) g -> g % 300 > 290),
        Arguments.of("$[?(@.status != 'NEW')]", (IntPredicate) g -> g % 4 != 0),
        Arguments.of("$[?(!(" + CITY + " == 'City42'))]", (IntPredicate) g -> g % 500 != 42),
        Arguments.of(
            "$[?(" + CITY + " == 'City42' || @.priority > 290)]",
            (IntPredicate) g -> g % 500 == 42 || g % 300 > 290),
        Arguments.of(
            "$[?(@.characteristic[?(@.value != 'v10')])]", (IntPredicate) g -> true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keylessFilters")
  void aKeylessFilterReadsNoMoreThanThePlainScan(String filter, IntPredicate expected)
      throws SQLException {
    JsonNode plan = genericPlan(filter, expected);
    LOG.info("generic plan, keyless {}: {} buffers", filter, buffers(plan));

    assertThat(bitmapIndexScans(plan)).as("generic plan of %s%n%s", filter, plan).isZero();
    assertThat(buffers(plan))
        .as("buffers of %s against the plain scan's%n%s", filter, plan)
        .isLessThanOrEqualTo(plainScanBuffers);
  }

  static Stream<Arguments> keyedFilters() {
    return Stream.of(
        Arguments.of("$[?(" + CITY + " == 'City42')]", (IntPredicate) g -> g % 500 == 42),
        Arguments.of(
            "$[?(" + CITY + " == 'City42' || " + CITY + " == 'City43')]",
            (IntPredicate) g -> g % 500 == 42 || g % 500 == 43),
        Arguments.of(
            "$[?(" + CITY + " == 'City42' && @.priority > 250)]",
            (IntPredicate) g -> g % 500 == 42 && g % 300 > 250),
        Arguments.of(
            "$[?(@.characteristic[?(@.name == 'k3' && @.value == 'v10')])]",
            (IntPredicate) g -> g % 7 == 3 && g % 1000 == 10));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keyedFilters")
  void aKeyedFilterKeepsItsIndexScan(String filter, IntPredicate expected) throws SQLException {
    JsonNode plan = genericPlan(filter, expected);
    LOG.info("generic plan, keyed {}: {} buffers", filter, buffers(plan));

    assertThat(bitmapIndexScans(plan)).as("generic plan of %s%n%s", filter, plan).isPositive();
  }

  /**
   * Prepares {@code SELECT id … WHERE <clause>} with the jsonpath as {@code $1}, forces the generic
   * plan, asserts the rows and returns the plan of {@code EXPLAIN (ANALYZE, BUFFERS)}.
   */
  private JsonNode genericPlan(String filter, IntPredicate expected) throws SQLException {
    JsonbClause where =
        clauseBuilder.build(JsonbIndexedDomain.class, Map.of("filter", new String[] {filter}));
    assertThat(where.params()).hasSize(1);
    String jsonPath = (String) where.params().get(0);
    String condition = where.sql().replace("@??", "@?").replace("?::jsonpath", "$1::jsonpath");
    String argument = "'" + jsonPath.replace("'", "''") + "'";
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET plan_cache_mode = force_generic_plan");
      statement.execute("DEALLOCATE ALL");
      statement.execute(
          "PREPARE keyless_q(text) AS SELECT id FROM " + TABLE + " WHERE " + condition);
      try {
        assertThat(count(statement, argument))
            .as(condition)
            .isEqualTo(IntStream.rangeClosed(1, Tmf630JsonbIndexPlanIT.ROWS).filter(expected).count());
        return explain(statement, "EXECUTE keyless_q(" + argument + ")").path("Plan");
      } finally {
        statement.execute("DEALLOCATE ALL");
        statement.execute("RESET plan_cache_mode");
      }
    }
  }

  private static long count(Statement statement, String argument) throws SQLException {
    long rows = 0;
    try (ResultSet rs = statement.executeQuery("EXECUTE keyless_q(" + argument + ")")) {
      while (rs.next()) {
        rows++;
      }
    }
    return rows;
  }

  private JsonNode explain(Statement statement, String sql) throws SQLException {
    try (ResultSet rs =
        statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
      rs.next();
      return objectMapper.readTree(rs.getString(1)).get(0);
    }
  }

  /** The shared buffers the plan's top node read: hit plus read. */
  private static long buffers(JsonNode plan) {
    return plan.path("Shared Hit Blocks").asLong() + plan.path("Shared Read Blocks").asLong();
  }

  private static long bitmapIndexScans(JsonNode plan) {
    return nodes(plan)
        .filter(n -> "Bitmap Index Scan".equals(n.path("Node Type").asString()))
        .count();
  }

  private static Stream<JsonNode> nodes(JsonNode plan) {
    Stream<JsonNode> children =
        plan.path("Plans").valueStream().flatMap(Tmf630JsonbKeylessFilterGenericPlanIT::nodes);
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
