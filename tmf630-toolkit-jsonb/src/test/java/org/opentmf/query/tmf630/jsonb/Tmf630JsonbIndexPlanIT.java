package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves by PLAN that the {@code filter=} queries the toolkit sends are served by the {@code GIN
 * (payload jsonb_path_ops)} index an adopter is told to create, and that under
 * {@code opentmf.tmf630.jsonb.equality=containment} (the opt-in this class runs with) the URL
 * equalities are too. The default's equality, served by a field's own expression index, is proven
 * by {@link Tmf630JsonbIndexPlanDefaultsIT}.
 *
 * <p>The table holds {@value #ROWS} rows seeded server-side and is analysed, and no planner
 * setting is touched, so the planner's own choice is what is tested. The WHERE clause is built
 * through the adopter path ({@link Tmf630JsonbClauseBuilder} with a {@code filter} parameter) and
 * run through a {@link Tmf630JsonbFilterExecutor} whose connections record every statement it
 * prepares; each recorded statement (the page SELECT and the COUNT) is then explained with the
 * same bind parameters. An index-served filter must show a Bitmap Index Scan on {@value
 * #GIN_INDEX} itself, so neither a sequential scan nor a primary-key plan can pass. Every case
 * also returns exactly the rows the seed formula demands.
 */
@SpringBootTest(
    classes = Tmf630JsonbIndexPlanIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.jsonb.equality=containment"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbIndexPlanIT {

  static final int ROWS = 20_000;
  private static final String TABLE = JsonbIndexedRow.TABLE;
  private static final String GIN_INDEX = TABLE + "_payload_gin";
  private static final String CITY = "@.receiver.postalAddress.city";
  private static final String K3_V10 = "@.characteristic[?(@.name == 'k3' && @.value == 'v10')]";

  /**
   * Row {@code g} (1..ROWS) has id {@code r<g>}, city {@code City<g % 500>}, priority {@code g %
   * 300}, two characteristics: {@code {k<g % 7>, v<g % 1000>}} and {@code {z, w<g % 3>}}, and two
   * items whose tag arrays hold {@code t<g % 50>} and {@code u<g % 5>}.
   */
  static final String SEED_SQL =
      """
      INSERT INTO jsonb_indexed_row (id, payload)
      SELECT 'r' || g, jsonb_build_object(
          'id', 'r' || g,
          'status', (ARRAY['NEW', 'DONE', 'FAILED', 'OPEN'])[1 + g % 4],
          'priority', g % 300,
          'receiver', jsonb_build_object('postalAddress',
              jsonb_build_object('city', 'City' || (g % 500), 'houseNumber', g % 1000)),
          'characteristic', jsonb_build_array(
              jsonb_build_object('name', 'k' || (g % 7), 'value', 'v' || (g % 1000)),
              jsonb_build_object('name', 'z', 'value', 'w' || (g % 3))),
          'item', jsonb_build_array(
              jsonb_build_object('state', 'OPEN', 'tag', jsonb_build_array(
                  jsonb_build_object('code', 't' || (g % 50)))),
              jsonb_build_object('state', 'DONE', 'tag', jsonb_build_array(
                  jsonb_build_object('code', 'u' || (g % 5))))))
      FROM generate_series(1, ?) AS g
      """;

  @Autowired private DataSource dataSource;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbEntityRegistry registry;
  @Autowired private JsonbSortBuilder sortBuilder;
  @Autowired private ObjectMapper objectMapper;

  private final List<String> prepared = new CopyOnWriteArrayList<>();
  private JdbcClient jdbc;
  private Tmf630JsonbFilterExecutor recordingExecutor;

  @BeforeAll
  void seedIndexAndAnalyse() {
    jdbc = JdbcClient.create(dataSource);
    jdbc.sql("TRUNCATE " + TABLE).update();
    jdbc.sql(SEED_SQL).param(ROWS).update();
    jdbc.sql("CREATE INDEX IF NOT EXISTS " + GIN_INDEX + " ON " + TABLE
            + " USING gin (payload jsonb_path_ops)")
        .update();
    jdbc.sql("ANALYZE " + TABLE).update();
    recordingExecutor =
        new Tmf630JsonbFilterExecutor(
            JdbcClient.create(new RecordingDataSource(dataSource, prepared)),
            objectMapper,
            registry,
            sortBuilder);
  }

  static Stream<Arguments> indexServedFilters() {
    return Stream.of(
        Arguments.of(
            "equality on a three-level path",
            "$[?(" + CITY + " == 'City42')]",
            (IntPredicate) g -> g % 500 == 42),
        Arguments.of(
            "|| of two equalities",
            "$[?(" + CITY + " == 'City42' || " + CITY + " == 'City43')]",
            (IntPredicate) g -> g % 500 == 42 || g % 500 == 43),
        Arguments.of(
            "&& of an equality and a range",
            "$[?(" + CITY + " == 'City42' && @.priority > 250)]",
            (IntPredicate) g -> g % 500 == 42 && g % 300 > 250),
        Arguments.of(
            "array correlation on one element",
            "$[?(" + K3_V10 + ")]",
            (IntPredicate) g -> g % 7 == 3 && g % 1000 == 10),
        Arguments.of(
            "array correlation does not pair values of different elements",
            "$[?(@.characteristic[?(@.name == 'z' && @.value == 'v10')])]",
            (IntPredicate) g -> false),
        Arguments.of(
            "nested array correlation",
            "$[?(@.item[?(@.state == 'OPEN' && @.tag[?(@.code == 't7')])])]",
            (IntPredicate) g -> g % 50 == 7),
        Arguments.of(
            "nested array correlation does not cross items",
            "$[?(@.item[?(@.state == 'DONE' && @.tag[?(@.code == 't7')])])]",
            (IntPredicate) g -> false),
        Arguments.of(
            "array correlation || an equality",
            "$[?(" + K3_V10 + " || " + CITY + " == 'City42')]",
            (IntPredicate) g -> (g % 7 == 3 && g % 1000 == 10) || g % 500 == 42));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("indexServedFilters")
  @DisplayName("filter= with an equality: SELECT and COUNT use a Bitmap Index Scan on the GIN index")
  void filterWithAnEqualityIsServedByTheGinIndex(
      String name, String filter, IntPredicate expected) {
    List<Explained> plans = runAndExplain(filter, expected);

    assertThat(plans).hasSize(2);
    for (Explained plan : plans) {
      assertThat(bitmapIndexScansOn(plan.plan(), GIN_INDEX))
          .as("plan of %s%n%s", plan.sql(), plan.plan())
          .isPositive();
    }
  }

  static Stream<Arguments> indexServedEqualities() {
    return Stream.of(
        Arguments.of(
            "String equality on a three-level path",
            "receiver.postalAddress.city",
            "City42",
            (IntPredicate) g -> g % 500 == 42),
        Arguments.of(
            "Integer equality", "priority", "42", (IntPredicate) g -> g % 300 == 42),
        Arguments.of(
            "IN on a three-level path",
            "receiver.postalAddress.city.in",
            "City42,City43",
            (IntPredicate) g -> g % 500 == 42 || g % 500 == 43),
        Arguments.of(
            "EQ through an array, any element",
            "characteristic.name",
            "k3",
            (IntPredicate) g -> g % 7 == 3),
        Arguments.of(
            "EQ through an array inside an array",
            "item.tag.code",
            "t7",
            (IntPredicate) g -> g % 50 == 7),
        Arguments.of(
            "IN through an array",
            "characteristic.value.in",
            "v10,v11",
            (IntPredicate) g -> g % 1000 == 10 || g % 1000 == 11));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("indexServedEqualities")
  @DisplayName("equality=containment: SELECT and COUNT use a Bitmap Index Scan on the GIN index")
  void attributeEqualityIsServedByTheGinIndex(
      String name, String param, String value, IntPredicate expected) {
    List<Explained> plans = runAndExplain(Map.of(param, new String[] {value}), expected);

    assertThat(plans).hasSize(2);
    for (Explained plan : plans) {
      assertThat(bitmapIndexScansOn(plan.plan(), GIN_INDEX))
          .as("plan of %s%n%s", plan.sql(), plan.plan())
          .isPositive();
    }
  }

  @Test
  @DisplayName("filter= with only a range is not served by the GIN index: a sequential scan")
  void rangeOnlyFilterStillScans() {
    List<Explained> plans = runAndExplain("$[?(@.priority > 290)]", g -> g % 300 > 290);

    assertThat(plans).hasSize(2);
    for (Explained plan : plans) {
      assertThat(bitmapIndexScansOn(plan.plan(), GIN_INDEX))
          .as("plan of %s%n%s", plan.sql(), plan.plan())
          .isZero();
      assertThat(seqScansOn(plan.plan(), TABLE))
          .as("plan of %s%n%s", plan.sql(), plan.plan())
          .isPositive();
    }
  }

  /**
   * Runs {@code filter} through the adopter path, asserts the exact rows, and explains every
   * statement the executor prepared against the table, with the WHERE clause's bind parameters.
   * Logs the rows found against those of the seed formula on a mismatch.
   */
  private List<Explained> runAndExplain(String filter, IntPredicate expected) {
    return runAndExplain(Map.of("filter", new String[] {filter}), expected);
  }

  /** As {@link #runAndExplain(String, IntPredicate)}, for any query parameters. */
  private List<Explained> runAndExplain(Map<String, String[]> params, IntPredicate expected) {
    JsonbClause where = clauseBuilder.build(JsonbIndexedDomain.class, params);
    prepared.clear();

    Page<JsonbIndexedDomain> page =
        recordingExecutor.findAll(
            JsonbIndexedDomain.class, where, TmfSort.empty(), Pageable.unpaged(), f -> String.class);

    List<String> expectedIds =
        IntStream.rangeClosed(1, ROWS).filter(expected).mapToObj(g -> "r" + g).toList();
    assertThat(page.getContent())
        .extracting(JsonbIndexedDomain::id)
        .containsExactlyInAnyOrderElementsOf(expectedIds);
    assertThat(page.getTotalElements()).isEqualTo(expectedIds.size());

    List<Explained> plans = new ArrayList<>();
    for (String sql : prepared) {
      if (sql.contains(" FROM " + TABLE + " ")) {
        String json =
            jdbc.sql("EXPLAIN (FORMAT JSON) " + sql)
                .params(where.params())
                .query(String.class)
                .single();
        plans.add(new Explained(sql, objectMapper.readTree(json).get(0).path("Plan")));
      }
    }
    return plans;
  }

  private static long bitmapIndexScansOn(JsonNode plan, String indexName) {
    return nodes(plan)
        .filter(n -> "Bitmap Index Scan".equals(n.path("Node Type").asString()))
        .filter(n -> indexName.equals(n.path("Index Name").asString()))
        .count();
  }

  private static long seqScansOn(JsonNode plan, String tableName) {
    return nodes(plan)
        .filter(n -> "Seq Scan".equals(n.path("Node Type").asString()))
        .filter(n -> tableName.equals(n.path("Relation Name").asString()))
        .count();
  }

  private static Stream<JsonNode> nodes(JsonNode plan) {
    Stream<JsonNode> children =
        plan.path("Plans").valueStream().flatMap(Tmf630JsonbIndexPlanIT::nodes);
    return Stream.concat(Stream.of(plan), children);
  }

  private record Explained(String sql, JsonNode plan) {}

  /** Records the SQL of every statement prepared on its connections. */
  private static final class RecordingDataSource extends DelegatingDataSource {

    private final List<String> sink;

    RecordingDataSource(DataSource target, List<String> sink) {
      super(target);
      this.sink = sink;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection target = super.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())
                    && args != null
                    && args[0] instanceof String sql) {
                  sink.add(sql);
                }
                try {
                  return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
    }
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
