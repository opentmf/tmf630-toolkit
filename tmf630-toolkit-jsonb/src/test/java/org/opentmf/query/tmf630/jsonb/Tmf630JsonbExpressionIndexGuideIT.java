package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbIndexedRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The README's index guide, expression-index part, under the DEFAULT settings: which URL operators
 * and sorts an index on the exact expression the toolkit sends serves, which a variant serves
 * ({@code text_pattern_ops}, {@code LOWER(…)}), which none serves, and that PostgreSQL refuses an
 * index on the date and time casts. Plans are made for the bound values, as a JDBC
 * {@code EXPLAIN} with parameters is; each case also returns the rows the seed formula demands.
 * The indexes are dropped afterwards, so the other plan tests on this table see none of them.
 */
@SpringBootTest(
    classes = Tmf630JsonbExpressionIndexGuideIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbExpressionIndexGuideIT {

  private static final String TABLE = JsonbIndexedRow.TABLE;
  private static final String CITY_PATH = "payload#>>'{receiver,postalAddress,city}'";
  private static final String CITY = TABLE + "_guide_city";
  private static final String CITY_PATTERN = TABLE + "_guide_city_pattern";
  private static final String CITY_LOWER = TABLE + "_guide_city_lower";
  private static final String PRIORITY = TABLE + "_guide_priority";
  private static final List<String> INDEXES = List.of(CITY, CITY_PATTERN, CITY_LOWER, PRIORITY);

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;
  @Autowired private ObjectMapper objectMapper;

  @BeforeAll
  void seedIndexesAndAnalyse() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    jdbc.sql(Tmf630JsonbIndexPlanIT.SEED_SQL).param(Tmf630JsonbIndexPlanIT.ROWS).update();
    createIndex(CITY, "((" + CITY_PATH + "))");
    createIndex(CITY_PATTERN, "((" + CITY_PATH + ") text_pattern_ops)");
    createIndex(CITY_LOWER, "(LOWER(" + CITY_PATH + "))");
    createIndex(PRIORITY, "(((payload->>'priority')::bigint))");
    jdbc.sql("ANALYZE " + TABLE).update();
  }

  @AfterAll
  void dropIndexes() {
    INDEXES.forEach(index -> jdbc.sql("DROP INDEX IF EXISTS " + index).update());
  }

  static Stream<Arguments> served() {
    return Stream.of(
        Arguments.of("priority.gt", "295", List.of(PRIORITY), (IntPredicate) g -> g % 300 > 295),
        Arguments.of("priority.lt", "3", List.of(PRIORITY), (IntPredicate) g -> g % 300 < 3),
        Arguments.of(
            "priority.in",
            "42,43",
            List.of(PRIORITY),
            (IntPredicate) g -> g % 300 == 42 || g % 300 == 43),
        Arguments.of(
            "receiver.postalAddress.city.startswith",
            "City42",
            List.of(CITY_PATTERN),
            (IntPredicate) g -> String.valueOf(g % 500).startsWith("42")),
        Arguments.of(
            "receiver.postalAddress.city.like",
            "City42%",
            List.of(CITY_PATTERN),
            (IntPredicate) g -> String.valueOf(g % 500).startsWith("42")),
        Arguments.of(
            "receiver.postalAddress.city.regex",
            "^City42$",
            List.of(CITY_PATTERN),
            (IntPredicate) g -> g % 500 == 42),
        Arguments.of(
            "receiver.postalAddress.city.eqi",
            "city42",
            List.of(CITY_LOWER),
            (IntPredicate) g -> g % 500 == 42),
        Arguments.of(
            "receiver.postalAddress.city",
            "City42",
            List.of(CITY, CITY_PATTERN),
            (IntPredicate) g -> g % 500 == 42));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("served")
  void anExpressionIndexServes(
      String param, String value, List<String> indexes, IntPredicate expected) {
    JsonNode plan = countPlan(param, value, expected);

    assertThat(indexScans(plan).filter(indexes::contains).count())
        .as("plan of %s=%s%n%s", param, value, plan)
        .isPositive();
  }

  static Stream<Arguments> neverServed() {
    return Stream.of(
        Arguments.of("receiver.postalAddress.city.ne", "City42", (IntPredicate) g -> g % 500 != 42),
        Arguments.of(
            "receiver.postalAddress.city.nin",
            "City42,City43",
            (IntPredicate) g -> g % 500 != 42 && g % 500 != 43),
        Arguments.of(
            "receiver.postalAddress.city.contains",
            "ty42",
            (IntPredicate) g -> String.valueOf(g % 500).startsWith("42")),
        Arguments.of(
            "receiver.postalAddress.city.endswith",
            "y42",
            (IntPredicate) g -> g % 500 == 42),
        Arguments.of(
            "receiver.postalAddress.city.regex", "y42$", (IntPredicate) g -> g % 500 == 42),
        Arguments.of("receiver.postalAddress.city.isnull", "true", (IntPredicate) g -> false));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("neverServed")
  void noExpressionIndexServes(String param, String value, IntPredicate expected) {
    JsonNode plan = countPlan(param, value, expected);

    assertThat(indexScans(plan).filter(INDEXES::contains).count())
        .as("plan of %s=%s%n%s", param, value, plan)
        .isZero();
  }

  static Stream<Arguments> sorts() {
    return Stream.of(
        Arguments.of("priority", Sort.Direction.ASC, PRIORITY),
        Arguments.of("priority", Sort.Direction.DESC, PRIORITY),
        Arguments.of("receiver.postalAddress.city", Sort.Direction.ASC, CITY),
        Arguments.of("receiver.postalAddress.city", Sort.Direction.DESC, CITY));
  }

  @ParameterizedTest(name = "sort={0} {1}")
  @MethodSource("sorts")
  void aSortedPageIsServedByTheFieldsIndex(String field, Sort.Direction direction, String index) {
    TmfSort sort = new TmfSort(List.of(new TmfSortTerm(direction, TmfSortTerm.Kind.PLAIN, field)));
    JsonbClause orderBy =
        sortBuilder.buildOrderByClause(
            sort, f -> "priority".equals(f) ? Integer.class : String.class);
    String select = "SELECT id FROM " + TABLE + " ORDER BY " + orderBy.sql() + " LIMIT 20";

    JsonNode plan = explain(select, orderBy.params());

    assertThat(indexScans(plan).filter(index::equals).count())
        .as("plan of %s%n%s", select, plan)
        .isPositive();
  }

  @ParameterizedTest
  @ValueSource(strings = {"date", "time", "timestamp", "timestamptz"})
  void postgresRefusesAnIndexOnTheDateAndTimeCasts(String cast) {
    String create =
        "CREATE INDEX " + TABLE + "_guide_cast ON " + TABLE + " (((payload->>'x')::" + cast + "))";

    StatementSpec statement = jdbc.sql(create);

    assertThatThrownBy(statement::update)
        .hasStackTraceContaining("functions in index expression must be marked IMMUTABLE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"bigint", "numeric", "boolean"})
  void postgresAcceptsAnIndexOnTheNumberAndBooleanCasts(String cast) {
    String index = TABLE + "_guide_cast_" + cast;

    createIndex(index, "(((payload->>'x')::" + cast + "))");
    long created =
        jdbc.sql("SELECT count(*) FROM pg_indexes WHERE indexname = ?")
            .param(index)
            .query(Long.class)
            .single();
    jdbc.sql("DROP INDEX " + index).update();

    assertThat(created).isOne();
  }

  private void createIndex(String name, String expression) {
    jdbc.sql("CREATE INDEX IF NOT EXISTS " + name + " ON " + TABLE + " " + expression).update();
  }

  /** The plan of {@code SELECT count(*)} for one URL filter, after asserting its count. */
  private JsonNode countPlan(String param, String value, IntPredicate expected) {
    JsonbClause where =
        clauseBuilder.build(JsonbIndexedDomain.class, Map.of(param, new String[] {value}));
    String count = "SELECT count(*) FROM " + TABLE + " WHERE " + where.sql();
    long rows = jdbc.sql(count).params(where.params()).query(Long.class).single();

    assertThat(rows)
        .as(where.sql())
        .isEqualTo(IntStream.rangeClosed(1, Tmf630JsonbIndexPlanIT.ROWS).filter(expected).count());
    return explain(count, where.params());
  }

  private JsonNode explain(String sql, List<Object> params) {
    String json =
        jdbc.sql("EXPLAIN (FORMAT JSON) " + sql).params(params).query(String.class).single();
    return objectMapper.readTree(json).get(0).path("Plan");
  }

  /** The index names of every Index Scan and Bitmap Index Scan in the plan. */
  private static Stream<String> indexScans(JsonNode plan) {
    return nodes(plan)
        .filter(n -> n.has("Index Name"))
        .map(n -> n.path("Index Name").asString());
  }

  private static Stream<JsonNode> nodes(JsonNode plan) {
    Stream<JsonNode> children =
        plan.path("Plans").valueStream().flatMap(Tmf630JsonbExpressionIndexGuideIT::nodes);
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
