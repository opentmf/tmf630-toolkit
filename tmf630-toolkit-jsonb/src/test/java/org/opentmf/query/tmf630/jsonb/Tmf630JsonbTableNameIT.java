package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The table-name forms the startup check accepts also WORK against PostgreSQL: each row entity
 * does one write through {@link Tmf630JsonbWriteExecutor} and one filtered read through {@link
 * Tmf630JsonbFilterExecutor}, with the name written into the SQL exactly as declared.
 *
 * <p>Whitespace at the two ends of the name ({@code " ws_row "}) is the case a reviewer asked to
 * prove: the module writes the name as it is, as since 3.0, so the SQL here ({@code INSERT INTO
 * ws_row (…)}, {@code FROM ws_row WHERE}, with the spaces) is the SQL 3.5.2 writes for it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbTableNameIT {

  private static final String URL = "jdbc:tc:postgresql:18.1-alpine:///db";

  private SingleConnectionDataSource dataSource;
  private JdbcClient jdbc;
  private final ObjectMapper mapper = new ObjectMapper();

  /** The payload of every row entity here. */
  public record NameDoc(String id, String name) {}

  @BeforeAll
  void createTables() {
    dataSource = new SingleConnectionDataSource(URL, true);
    jdbc = JdbcClient.create(dataSource);
    for (String ddl :
        List.of(
            "CREATE SCHEMA tn",
            "CREATE TABLE ws_row (id text PRIMARY KEY, payload jsonb NOT NULL)",
            "CREATE TABLE \"Quoted Row\" (id text PRIMARY KEY, payload jsonb NOT NULL)",
            "CREATE TABLE \"MixedRow\" (id text PRIMARY KEY, payload jsonb NOT NULL)",
            "CREATE TABLE \"dot.row\" (id text PRIMARY KEY, payload jsonb NOT NULL)",
            "CREATE TABLE tn.schema_row (id text PRIMARY KEY, payload jsonb NOT NULL)",
            "CREATE TABLE tn.\"Quoted Schema Row\" (id text PRIMARY KEY, payload jsonb NOT NULL)",
            "CREATE TABLE tn.three_row (id text PRIMARY KEY, payload jsonb NOT NULL)")) {
      jdbc.sql(ddl).update();
    }
  }

  @AfterAll
  void close() {
    dataSource.destroy();
  }

  static Stream<Arguments> rows() {
    return Stream.of(
        Arguments.of(WhitespaceEndsRow.class, " ws_row "),
        Arguments.of(QuotedSpaceRow.class, "\"Quoted Row\""),
        Arguments.of(QuotedMixedCaseRow.class, "\"MixedRow\""),
        Arguments.of(QuotedDotRow.class, "\"dot.row\""),
        Arguments.of(SchemaRow.class, "tn.schema_row"),
        Arguments.of(QuotedSchemaRow.class, "tn.\"Quoted Schema Row\""),
        Arguments.of(ThreePartRow.class, "db.tn.three_row"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("rows")
  void writesAndReadsThroughTheNameAsDeclared(Class<?> rowType, String tableName) {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(rowType);
    assertThat(metadata.tableName()).isEqualTo(tableName);
    JsonbEntityRegistry registry = new JsonbEntityRegistry();
    registry.register(metadata);
    Tmf630JsonbWriteExecutor writer = new Tmf630JsonbWriteExecutor(jdbc, mapper, registry);
    Tmf630JsonbFilterExecutor reader =
        new Tmf630JsonbFilterExecutor(
            jdbc, mapper, registry, new JsonbSortBuilder(new JsonbPathExtractor("payload"), false));

    writer.saveWithSplits(new NameDoc("A", "alpha"));
    writer.saveWithSplits(new NameDoc("B", "bravo"));
    Page<NameDoc> page =
        reader.findAll(
            NameDoc.class,
            JsonbClause.of("payload->>'name' = ?", "bravo"),
            new TmfSort(List.of()),
            PageRequest.of(0, 10),
            field -> String.class);

    assertThat(page.getContent()).containsExactly(new NameDoc("B", "bravo"));
  }

  @Table(name = " ws_row ")
  @Tmf630JsonbBacked(domainType = NameDoc.class)
  static class WhitespaceEndsRow {
    @Id String id;
    JsonNode payload;
  }

  @Table(name = "\"Quoted Row\"")
  @Tmf630JsonbBacked(domainType = NameDoc.class)
  static class QuotedSpaceRow {
    @Id String id;
    JsonNode payload;
  }

  @Table(name = "\"MixedRow\"")
  @Tmf630JsonbBacked(domainType = NameDoc.class)
  static class QuotedMixedCaseRow {
    @Id String id;
    JsonNode payload;
  }

  @Table(name = "\"dot.row\"")
  @Tmf630JsonbBacked(domainType = NameDoc.class)
  static class QuotedDotRow {
    @Id String id;
    JsonNode payload;
  }

  @Table(name = "tn.schema_row")
  @Tmf630JsonbBacked(domainType = NameDoc.class)
  static class SchemaRow {
    @Id String id;
    JsonNode payload;
  }

  @Table(name = "tn.\"Quoted Schema Row\"")
  @Tmf630JsonbBacked(domainType = NameDoc.class)
  static class QuotedSchemaRow {
    @Id String id;
    JsonNode payload;
  }

  @Table(name = "db.tn.three_row")
  @Tmf630JsonbBacked(domainType = NameDoc.class)
  static class ThreePartRow {
    @Id String id;
    JsonNode payload;
  }
}
