package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.jsonb.it.DeclaredMessage;
import org.opentmf.query.tmf630.jsonb.it.DeclaredMessageRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link Tmf630JsonbColumn} on a real table (ledger #4256): a URL range and a sort on the declared
 * {@code sendTime} use the column {@code sent_at}, the partition key of a table partitioned by
 * month, so PostgreSQL scans one partition; the null tests keep reading the payload under the
 * default {@code MISSING_ONLY}; a string operator on the date keeps the payload's text; the text
 * column {@code owner} takes every operator; {@link Tmf630JsonbColumnCheck} finds a row whose
 * column and payload differ. Rows: {@code m1}…{@code m6} one per month January to June 2025,
 * {@code absent} without a send time, {@code null} with a JSON null; owners {@code client:1} for
 * odd months, {@code client:2} for even ones.
 */
@SpringBootTest(
    classes = Tmf630JsonbDeclaredColumnIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
class Tmf630JsonbDeclaredColumnIT {

  private static final String TABLE = DeclaredMessageRow.TABLE;

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private Tmf630JsonbFilterExecutor executor;
  @Autowired private Tmf630JsonbColumnCheck check;
  @Autowired private ObjectMapper mapper;

  @BeforeEach
  void seed() {
    jdbc.sql("DROP TABLE IF EXISTS " + TABLE).update();
    jdbc.sql(
            "CREATE TABLE " + TABLE + " (id text NOT NULL, payload jsonb NOT NULL,"
                + " sent_at timestamptz, owner text) PARTITION BY RANGE (sent_at)")
        .update();
    for (int m = 1; m <= 6; m++) {
      jdbc.sql(
              String.format(
                  "CREATE TABLE %s_2025_%02d PARTITION OF %s FOR VALUES FROM ('2025-%02d-01T00:00:00Z')"
                      + " TO ('2025-%02d-01T00:00:00Z')",
                  TABLE, m, TABLE, m, m + 1))
          .update();
    }
    jdbc.sql("CREATE TABLE " + TABLE + "_default PARTITION OF " + TABLE + " DEFAULT").update();
    jdbc.sql("CREATE INDEX ON " + TABLE + " (sent_at)").update();
    for (int m = 1; m <= 6; m++) {
      String time = String.format("2025-%02d-15T08:00:00Z", m);
      insert("m" + m, "{\"id\":\"m" + m + "\",\"sendTime\":\"" + time + "\",\"owner\":\"client:"
          + (2 - m % 2) + "\",\"state\":\"SENT\"}", time, "client:" + (2 - m % 2));
    }
    insert("absent", "{\"id\":\"absent\",\"owner\":\"client:1\"}", null, "client:1");
    insert("null", "{\"id\":\"null\",\"sendTime\":null,\"owner\":\"client:1\"}", null, "client:1");
    jdbc.sql("ANALYZE " + TABLE).update();
  }

  private void insert(String id, String payload, String sentAt, String owner) {
    jdbc.sql("INSERT INTO " + TABLE + " (id, payload, sent_at, owner) VALUES (?, ?::jsonb, ?::timestamptz, ?)")
        .params(id, payload, sentAt, owner)
        .update();
  }

  @Test
  void aRangeOnTheDeclaredPathComparesTheColumnAndScansOnePartition() {
    JsonbClause where =
        clauseBuilder.build(
            DeclaredMessage.class,
            Map.of(
                "sendTime.gte", new String[] {"2025-03-01T00:00:00Z"},
                "sendTime.lt", new String[] {"2025-04-01T00:00:00Z"}));

    assertThat(where.sql()).contains("sent_at >= ?").contains("sent_at < ?").doesNotContain("payload");
    assertThat(ids(where)).containsExactly("m3");
    assertThat(scannedRelations(where)).containsExactly(TABLE + "_2025_03");
  }

  @Test
  void aSortOnTheDeclaredPathOrdersByTheColumn() {
    TmfSort newestFirst =
        new TmfSort(List.of(new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "sendTime")));

    Page<DeclaredMessage> page =
        executor.findAll(
            DeclaredMessage.class,
            clauseBuilder.build(DeclaredMessage.class, Map.of("owner", new String[] {"client:2"})),
            newestFirst,
            PageRequest.of(0, 2),
            f -> String.class);

    assertThat(page.getContent()).extracting(DeclaredMessage::id).containsExactly("m6", "m4");
    assertThat(page.getTotalElements()).isEqualTo(3);
  }

  @Test
  void theNullTestsReadThePayloadUnderTheDefault() {
    JsonbClause absent =
        clauseBuilder.build(DeclaredMessage.class, Map.of("sendTime.isnull", new String[] {"true"}));

    assertThat(absent.sql()).doesNotContain("sent_at");
    assertThat(ids(absent)).containsExactly("absent");
  }

  @Test
  void aStringOperatorOnTheDateKeepsThePayload() {
    JsonbClause contains =
        clauseBuilder.build(
            DeclaredMessage.class, Map.of("sendTime.contains", new String[] {"2025-06-15T08:00:00Z"}));

    assertThat(contains.sql()).startsWith("payload->>'sendTime' LIKE").doesNotContain("sent_at");
    assertThat(ids(contains)).doesNotContain("absent", "null");
  }

  @Test
  void aTextColumnTakesEveryOperator() {
    JsonbClause owner =
        clauseBuilder.build(DeclaredMessage.class, Map.of("owner.startswith", new String[] {"client:2"}));

    assertThat(owner.sql()).isEqualTo("owner LIKE ? || '%' ESCAPE '!'");
    assertThat(ids(owner)).containsExactly("m2", "m4", "m6");
  }

  @Test
  void aPathThatIsNotDeclaredKeepsThePayload() {
    JsonbClause state = clauseBuilder.build(DeclaredMessage.class, Map.of("state", new String[] {"SENT"}));

    assertThat(state.sql()).isEqualTo("payload->>'state' = ?");
  }

  @Test
  void theCheckFindsARowWhoseColumnDiffers() {
    assertThat(check.mismatches(DeclaredMessageRow.class)).isEmpty();

    jdbc.sql("UPDATE " + TABLE + " SET owner = 'client:9' WHERE id = 'm1'").update();
    jdbc.sql(
            "UPDATE " + TABLE + " SET payload = jsonb_set(payload, '{sendTime}', '\"2025-02-20T00:00:00Z\"')"
                + " WHERE id = 'm2'")
        .update();

    assertThat(check.mismatches(DeclaredMessageRow.class)).containsExactly("m1", "m2");
  }

  private List<String> ids(JsonbClause where) {
    return jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql() + " ORDER BY id")
        .params(where.params())
        .query(String.class)
        .list();
  }

  /** The relations the plan of the clause scans. */
  private List<String> scannedRelations(JsonbClause where) {
    String json =
        jdbc.sql("EXPLAIN (FORMAT JSON) SELECT id FROM " + TABLE + " WHERE " + where.sql())
            .params(where.params())
            .query(String.class)
            .single();
    List<String> relations = new ArrayList<>();
    nodes(mapper.readTree(json).get(0).path("Plan"))
        .filter(n -> n.has("Relation Name"))
        .forEach(n -> relations.add(n.path("Relation Name").asString()));
    return relations;
  }

  private static Stream<JsonNode> nodes(JsonNode plan) {
    return Stream.concat(
        Stream.of(plan), plan.path("Plans").valueStream().flatMap(Tmf630JsonbDeclaredColumnIT::nodes));
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
