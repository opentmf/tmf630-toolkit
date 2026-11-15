package org.opentmf.query.tmf630.jsonb;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PROBE for the design note docs/JSONB_DECLARED_COLUMN.md (TOOLKIT-JSONB-DATE-FIELD-INDEXABLE-1,
 * ledger #4250): what PostgreSQL does with a real column copied from the payload, against today's
 * JSON expression. Plain SQL, the forms the toolkit would send; runs only with
 * {@code -Dcolumn.probe=true}. Prints {@code COLPROBE} lines.
 */
@SpringBootTest(
    classes = DeclaredColumnProbeIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "column.probe", matches = "true")
class DeclaredColumnProbeIT {

  private static final int ROWS = Integer.getInteger("column.probe.rows", 200_000);
  private static final String MONTH_A = "'2025-06-01T00:00:00Z'";
  private static final String MONTH_B = "'2025-07-01T00:00:00Z'";

  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper mapper;

  @Test
  void probe() throws SQLException {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      out("version " + scalar(s, "SHOW server_version") + ", rows " + ROWS);
      fill(s);
      sizes(s);
      partitionedForms(s);
      flatForms(s);
      writeSide(s);
    }
  }

  // ---- tables -----------------------------------------------------------------------------

  private void fill(Statement s) throws SQLException {
    s.execute("DROP TABLE IF EXISTS cm_part, cm_flat CASCADE");
    s.execute(
        "CREATE TABLE cm_part (id text NOT NULL, sent_at timestamptz NOT NULL, state text, owner text,"
            + " payload jsonb NOT NULL, PRIMARY KEY (id, sent_at)) PARTITION BY RANGE (sent_at)");
    for (int m = 1; m <= 12; m++) {
      s.execute(
          String.format(
              "CREATE TABLE cm_part_%02d PARTITION OF cm_part FOR VALUES FROM"
                  + " ('2025-%02d-01T00:00:00Z') TO ('%s')",
              m, m, m == 12 ? "2026-01-01T00:00:00Z" : String.format("2025-%02d-01T00:00:00Z", m + 1)));
    }
    String rows =
        "SELECT 'r' || g AS id,"
            + " timestamptz '2025-01-01T00:00:00Z' + (g % 365) * interval '1 day' + (g % 86400) * interval '1 second' AS sent_at,"
            + " (ARRAY['NEW','SENT','FAILED','CANCELLED'])[1 + g % 4] AS state,"
            + " 'client:' || (g % 1000) AS owner"
            + " FROM generate_series(1, " + ROWS + ") AS g";
    String payload =
        "jsonb_build_object('id', id, 'state', state, 'owner', owner, 'sendTime',"
            + " to_char(sent_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
            + " 'receiver', jsonb_build_object('email', 'u' || id || '@example.test'))";
    s.execute("INSERT INTO cm_part SELECT id, sent_at, state, owner, " + payload + " FROM (" + rows + ") x");
    s.execute("CREATE INDEX cm_part_sent_at ON cm_part (sent_at)");
    s.execute("CREATE INDEX cm_part_state ON cm_part (state)");
    s.execute("CREATE INDEX cm_part_owner ON cm_part (owner)");
    s.execute(
        "CREATE TABLE cm_flat (id text PRIMARY KEY, sent_at timestamptz, state text, owner text,"
            + " payload jsonb NOT NULL)");
    s.execute("INSERT INTO cm_flat SELECT id, sent_at, state, owner, payload FROM cm_part");
    s.execute("CREATE INDEX cm_flat_owner ON cm_flat (owner)");
    s.execute("CREATE INDEX cm_flat_sent_at ON cm_flat (sent_at)");
    s.execute("CREATE INDEX cm_flat_state ON cm_flat (state)");
    s.execute("VACUUM ANALYZE cm_part");
    s.execute("VACUUM ANALYZE cm_flat");
  }

  private void sizes(Statement s) throws SQLException {
    out("size cm_flat table " + scalar(s, "SELECT pg_size_pretty(pg_table_size('cm_flat'))")
        + ", sent_at index " + scalar(s, "SELECT pg_size_pretty(pg_relation_size('cm_flat_sent_at'))")
        + ", state index " + scalar(s, "SELECT pg_size_pretty(pg_relation_size('cm_flat_state'))"));
  }

  // ---- partitioned ------------------------------------------------------------------------

  private void partitionedForms(Statement s) throws SQLException {
    String range = "sent_at >= $1::timestamptz AND sent_at < $2::timestamptz";
    String jsonRange =
        "(payload->>'sendTime')::timestamptz >= $1::timestamptz"
            + " AND (payload->>'sendTime')::timestamptz < $2::timestamptz";
    String args = "(" + MONTH_A + ", " + MONTH_B + ")";
    plan(s, "PART column range page", "SELECT payload FROM cm_part WHERE " + range
        + " ORDER BY sent_at DESC LIMIT 20", args);
    plan(s, "PART column range count", "SELECT count(*) FROM cm_part WHERE " + range, args);
    plan(s, "PART json range page (today)", "SELECT payload FROM cm_part WHERE " + jsonRange
        + " ORDER BY (payload->>'sendTime')::timestamptz DESC LIMIT 20", args);
    plan(s, "PART column sort page", "SELECT payload FROM cm_part WHERE $1::int = $1"
        + " ORDER BY sent_at DESC LIMIT 20", "(1)");
    plan(s, "PART without the field, json owner count", "SELECT count(*) FROM cm_part WHERE"
        + " payload->>'owner' = $1", "('client:7')");
    plan(s, "FLAT without the field, json owner count", "SELECT count(*) FROM cm_flat WHERE"
        + " payload->>'owner' = $1", "('client:7')");
    plan(s, "PART owner column count", "SELECT count(*) FROM cm_part WHERE owner = $1", "('client:7')");
    plan(s, "PART owner column + one month", "SELECT count(*) FROM cm_part WHERE owner = $1 AND"
        + " sent_at >= $2::timestamptz AND sent_at < $3::timestamptz",
        "('client:7', " + MONTH_A + ", " + MONTH_B + ")");
  }

  // ---- unpartitioned ------------------------------------------------------------------------

  private void flatForms(Statement s) throws SQLException {
    String args = "(" + MONTH_A + ", " + MONTH_B + ")";
    plan(s, "FLAT column range page", "SELECT payload FROM cm_flat WHERE sent_at >= $1::timestamptz"
        + " AND sent_at < $2::timestamptz ORDER BY sent_at DESC LIMIT 20", args);
    plan(s, "FLAT column range count", "SELECT count(*) FROM cm_flat WHERE sent_at >= $1::timestamptz"
        + " AND sent_at < $2::timestamptz", args);
    plan(s, "FLAT json range page (today)", "SELECT payload FROM cm_flat WHERE"
        + " (payload->>'sendTime')::timestamptz >= $1::timestamptz AND"
        + " (payload->>'sendTime')::timestamptz < $2::timestamptz"
        + " ORDER BY (payload->>'sendTime')::timestamptz DESC LIMIT 20", args);
    plan(s, "FLAT json range count (today)", "SELECT count(*) FROM cm_flat WHERE"
        + " (payload->>'sendTime')::timestamptz >= $1::timestamptz AND"
        + " (payload->>'sendTime')::timestamptz < $2::timestamptz", args);
    plan(s, "FLAT column sort page", "SELECT payload FROM cm_flat WHERE $1::int = $1"
        + " ORDER BY sent_at DESC LIMIT 20", "(1)");
    plan(s, "FLAT json sort page (today)", "SELECT payload FROM cm_flat WHERE $1::int = $1"
        + " ORDER BY (payload->>'sendTime')::timestamptz DESC LIMIT 20", "(1)");
    plan(s, "FLAT column eq owner", "SELECT count(*) FROM cm_flat WHERE owner = $1", "('client:7')");
    plan(s, "FLAT json eq owner (today, no index)", "SELECT count(*) FROM cm_flat WHERE"
        + " payload->>'owner' = $1", "('client:7')");
    plan(s, "FLAT drift check (rows where column and payload differ)", "SELECT count(*) FROM cm_flat"
        + " WHERE $1::int = $1 AND (sent_at IS DISTINCT FROM (payload->>'sendTime')::timestamptz"
        + " OR owner IS DISTINCT FROM payload->>'owner')", "(1)");
  }

  // ---- how the column could be filled on the database side --------------------------------

  private void writeSide(Statement ignored) {
    String fill =
        "CREATE OR REPLACE FUNCTION cm_fill() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " NEW.sent_at := (NEW.payload->>'sendTime')::timestamptz; RETURN NEW; END $$";
    attempt("one UPDATE sets the payload and the column together (flat)",
        "UPDATE cm_flat SET payload = jsonb_set(payload, '{sendTime}', to_jsonb('2025-04-04T00:00:00Z'::text)),"
            + " sent_at = '2025-04-04T00:00:00Z' WHERE id = 'r10'");
    read("flat r10 after the one UPDATE",
        "SELECT sent_at || ' / ' || (payload->>'sendTime') FROM cm_flat WHERE id = 'r10'");
    attempt("one UPDATE moving the row to another partition (partitioned)",
        "UPDATE cm_part SET payload = jsonb_set(payload, '{sendTime}', to_jsonb('2025-11-11T00:00:00Z'::text)),"
            + " sent_at = '2025-11-11T00:00:00Z' WHERE id = 'r10'");
    read("partition of r10 after the one UPDATE",
        "SELECT tableoid::regclass || ' ' || sent_at FROM cm_part WHERE id = 'r10'");
    attempt("generated text column",
        "ALTER TABLE cm_flat ADD COLUMN g_state text GENERATED ALWAYS AS (payload->>'state') STORED");
    attempt("generated timestamptz column",
        "ALTER TABLE cm_flat ADD COLUMN g_sent timestamptz GENERATED ALWAYS AS"
            + " ((payload->>'sendTime')::timestamptz) STORED");
    attempt("partition key on a generated column",
        "CREATE TABLE cm_gen (id text, payload jsonb, s text GENERATED ALWAYS AS (payload->>'state')"
            + " STORED) PARTITION BY LIST (s)");
    attempt("trigger function", fill);
    attempt("BEFORE trigger on the flat table",
        "CREATE TRIGGER cm_flat_fill BEFORE INSERT OR UPDATE ON cm_flat FOR EACH ROW EXECUTE FUNCTION cm_fill()");
    attempt("flat insert filled by the trigger",
        "INSERT INTO cm_flat (id, payload) VALUES ('t3', '{\"sendTime\":\"2025-03-03T00:00:00Z\"}')");
    attempt("flat jsonb_set update refilled by the trigger",
        "UPDATE cm_flat SET payload = jsonb_set(payload, '{sendTime}', '\"2025-04-04T00:00:00Z\"')"
            + " WHERE id = 't3'");
    read("flat t3 after the update", "SELECT sent_at::text FROM cm_flat WHERE id = 't3'");
    attempt("BEFORE trigger on the partitioned table",
        "CREATE TRIGGER cm_part_fill BEFORE INSERT OR UPDATE ON cm_part FOR EACH ROW EXECUTE FUNCTION cm_fill()");
    attempt("insert with the key left NULL, trigger to fill it",
        "INSERT INTO cm_part (id, sent_at, state, payload) VALUES ('t1', NULL, 'NEW',"
            + " '{\"sendTime\":\"2025-03-03T00:00:00Z\"}')");
    attempt("insert with a wrong key, trigger to correct it across partitions",
        "INSERT INTO cm_part (id, sent_at, state, payload) VALUES ('t2', '2025-01-05T00:00:00Z', 'NEW',"
            + " '{\"sendTime\":\"2025-03-03T00:00:00Z\"}')");
  }

  private void attempt(String label, String sql) {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      s.execute(sql);
      out("WRITE " + label + ": OK");
    } catch (SQLException e) {
      out("WRITE " + label + ": " + e.getMessage().lines().findFirst().orElse(""));
    }
  }

  private void read(String label, String sql) {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      out("WRITE " + label + " = " + scalar(s, sql));
    } catch (SQLException e) {
      out("WRITE " + label + ": " + e.getMessage().lines().findFirst().orElse(""));
    }
  }

  // ---- helpers ------------------------------------------------------------------------------

  /** PREPAREs {@code sql} and explains its execution under custom, auto (sixth run) and generic plans. */
  private void plan(Statement s, String label, String sql, String args) throws SQLException {
    for (String mode : List.of("force_custom_plan", "auto", "force_generic_plan")) {
      s.execute("SET plan_cache_mode = " + mode);
      s.execute("DEALLOCATE ALL");
      s.execute("PREPARE q AS " + sql);
      if ("auto".equals(mode)) {
        for (int k = 0; k < 6; k++) {
          s.execute("EXECUTE q" + args);
        }
      }
      JsonNode root;
      try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE q" + args)) {
        rs.next();
        root = mapper.readTree(rs.getString(1)).get(0);
      }
      JsonNode p = root.path("Plan");
      List<String> scans = new ArrayList<>();
      nodes(p).filter(n -> n.has("Relation Name"))
          .forEach(n -> scans.add(n.path("Node Type").asString() + ":" + n.path("Relation Name").asString()));
      long removed = nodes(p).mapToLong(n -> n.path("Subplans Removed").asLong()).sum();
      boolean merge = nodes(p).anyMatch(n -> "Merge Append".equals(n.path("Node Type").asString()));
      out(String.format(
          "PLAN %-42s %-18s %8.2f ms  buffers %7d  rows %7d  relations %2d  subplans-removed %2d%s  %s",
          label, mode, root.path("Execution Time").asDouble(),
          p.path("Shared Hit Blocks").asLong() + p.path("Shared Read Blocks").asLong(),
          p.path("Actual Rows").asLong(), scans.size(), removed, merge ? "  merge-append" : "",
          summarize(scans)));
      s.execute("DEALLOCATE ALL");
    }
    s.execute("RESET plan_cache_mode");
  }

  private static String summarize(List<String> scans) {
    return scans.stream().map(x -> x.substring(0, x.indexOf(':'))).distinct().toList().toString();
  }

  private static Stream<JsonNode> nodes(JsonNode plan) {
    return Stream.concat(
        Stream.of(plan), plan.path("Plans").valueStream().flatMap(DeclaredColumnProbeIT::nodes));
  }

  private static String scalar(Statement s, String sql) throws SQLException {
    try (ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static void out(String line) {
    System.out.println("COLPROBE " + line);
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
