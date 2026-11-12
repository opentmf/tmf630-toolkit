package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.opentmf.query.tmf630.jsonb.it.JsonbTestRow;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedDomain;
import org.opentmf.query.tmf630.jsonb.it.JsonbTypedRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins, per scalar type, which rows an equality on a JSONB payload finds: the extraction form the
 * legacy {@link JsonbPredicateFactory#build} still emits ({@code payload->>'x' = ?}, cast by the
 * domain type), and what the URL grammar now emits on an object path ({@code payload @> ?::jsonb}
 * for String, Enum, the integer and decimal types and Boolean; the extraction for the date-time
 * types and for any path through an array).
 *
 * <p>Containment is type-exact, as on the Mongo backend: a value stored in another JSON type than
 * the field declares is no longer found. Each such case is a cell with both row sets pinned. The
 * rows deliberately hold such values; the IT selects ids only and never deserialises a payload.
 */
@SpringBootTest(
    classes = Tmf630JsonbEqualityParityIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbEqualityParityIT {

  private static final String TABLE = JsonbTypedRow.TABLE;
  private static final Set<String> ERROR = Set.of("<the query fails>");

  private static final Map<String, String> ROWS = new LinkedHashMap<>();

  static {
    ROWS.put("S1", "{\"s\":\"42\"}");
    ROWS.put("S2", "{\"s\":42}");
    ROWS.put("S3", "{\"s\":true}");
    ROWS.put("S4", "{\"s\":\"true\"}");
    ROWS.put("S5", "{\"s\":42.0}");
    ROWS.put("S6", "{\"s\":\"a\\\"b\"}");
    ROWS.put("S7", "{\"s\":\"x\"}");
    ROWS.put("N1", "{\"n\":42}");
    ROWS.put("N2", "{\"n\":\"42\"}");
    ROWS.put("N3", "{\"n\":42.0}");
    ROWS.put("N4", "{\"n\":\"abc\"}");
    ROWS.put("N5", "{\"n\":\" 42\"}");
    ROWS.put("N6", "{\"n\":43}");
    ROWS.put("L1", "{\"l\":9007199254740993}");
    ROWS.put("L2", "{\"l\":9007199254740992}");
    ROWS.put("D1", "{\"d\":1.5}");
    ROWS.put("D2", "{\"d\":1.50}");
    ROWS.put("D3", "{\"d\":\"1.5\"}");
    ROWS.put("F1", "{\"f\":0.1}");
    ROWS.put("F2", "{\"f\":0.2}");
    ROWS.put("B1", "{\"b\":true}");
    ROWS.put("B2", "{\"b\":\"true\"}");
    ROWS.put("B3", "{\"b\":\"yes\"}");
    ROWS.put("B4", "{\"b\":1}");
    ROWS.put("B5", "{\"b\":false}");
    ROWS.put("E1", "{\"e\":\"RED\"}");
    ROWS.put("E2", "{\"e\":\"GREEN\"}");
    ROWS.put("LD1", "{\"ld\":\"2025-01-01\"}");
    ROWS.put("LD2", "{\"ld\":\"2025-01-01T00:00:00\"}");
    ROWS.put("LD3", "{\"ld\":\"2025-01-02\"}");
    ROWS.put("T1", "{\"t\":\"2025-01-01T08:00:00Z\"}");
    ROWS.put("T2", "{\"t\":\"2025-01-01T10:00:00+02:00\"}");
    ROWS.put("T3", "{\"t\":\"2025-01-01T08:00:00.000Z\"}");
    ROWS.put("T4", "{\"t\":\"2025-01-01T09:00:00Z\"}");
    ROWS.put("A1", "{\"a\":{\"b\":\"x\"}}");
    ROWS.put("A2", "{\"a\":{\"b\":\"y\"}}");
    ROWS.put("A3", "{\"a\":[{\"b\":\"x\"}]}");
    ROWS.put("I1", "{\"items\":[{\"b\":\"x\"}]}");
    ROWS.put("I2", "{\"items\":[{\"b\":\"y\"},{\"b\":\"x\"}]}");
    ROWS.put("G1", "{\"tags\":[\"x\",\"y\"]}");
  }

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbPredicateFactory predicateFactory;

  private final FieldPathResolver resolver = new FieldPathResolver();
  private final ValueConverter converter =
      new ValueConverter(new DefaultFormattingConversionService());

  @BeforeAll
  void seed() {
    jdbc.sql("TRUNCATE " + TABLE).update();
    ROWS.forEach(
        (id, payload) ->
            jdbc.sql("INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?::jsonb)")
                .params(id, payload)
                .update());
  }

  /** Label, query parameter, its value, rows the URL grammar finds, rows the extraction finds. */
  static Stream<Arguments> cells() {
    return Stream.of(
        // ---- SAME: the payload stores the field's own JSON type ----
        cell("String stored as a string", "s", "x", Set.of("S7"), Set.of("S7")),
        cell("String with a double quote", "s", "a\"b", Set.of("S6"), Set.of("S6")),
        cell("Long beyond 2^53", "l", "9007199254740993", Set.of("L1"), Set.of("L1")),
        cell("Double 0.1", "f", "0.1", Set.of("F1"), Set.of("F1")),
        cell("Boolean false", "b", "false", Set.of("B5"), Set.of("B5")),
        cell("Enum by its name", "e", "RED", Set.of("E1"), Set.of("E1")),
        cell("nested object path", "a.b", "x", Set.of("A1"), Set.of("A1")),
        // ---- DIFFERENT, the new contract: another JSON type than the field declares ----
        cell("String field holding a number", "s", "42", Set.of("S1"), Set.of("S1", "S2")),
        cell("String field holding a boolean", "s", "true", Set.of("S4"), Set.of("S3", "S4")),
        cell("String field holding a decimal", "s", "42.0", Set.of(), Set.of("S5")),
        cell("IN on a String field", "s.in", "x,42", Set.of("S1", "S7"), Set.of("S1", "S2", "S7")),
        cell(
            "BigDecimal by value, not a string",
            "d",
            "1.5",
            Set.of("D1", "D2"),
            Set.of("D1", "D2", "D3")),
        cell(
            "Boolean true, not its string spellings",
            "b",
            "true",
            Set.of("B1"),
            Set.of("B1", "B2", "B3", "B4")),
        // ---- the 500 that goes: 42.0 and "abc" in an Integer field failed the bigint cast ----
        cell("Integer, with 42.0 and \"abc\" stored", "n", "42", Set.of("N1", "N3"), ERROR),
        cell("IN on an Integer field", "n.in", "42,43", Set.of("N1", "N3", "N6"), ERROR),
        // ---- unchanged: date-time types keep the extraction and match by instant ----
        cell(
            "LocalDate by date",
            "ld",
            "2025-01-01",
            Set.of("LD1", "LD2"),
            Set.of("LD1", "LD2")),
        cell(
            "OffsetDateTime by instant",
            "t",
            "2025-01-01T08:00:00Z",
            Set.of("T1", "T2", "T3"),
            Set.of("T1", "T2", "T3")),
        // ---- unchanged: a path through an array keeps the extraction ----
        cell("path through an array", "items.b", "x", Set.of(), Set.of()),
        cell("positional index", "items[0].b", "x", Set.of("I1"), Set.of("I1")),
        cell("array leaf", "tags", "x", Set.of(), Set.of()));
  }

  private static Arguments cell(
      String label, String param, String value, Set<String> urlGrammar, Set<String> extraction) {
    return Arguments.of(label, param, value, urlGrammar, extraction);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cells")
  void urlGrammarFindsTheContractRows(
      String label, String param, String value, Set<String> urlGrammar, Set<String> extraction) {
    JsonbClause where =
        clauseBuilder.build(JsonbTypedDomain.class, Map.of(param, new String[] {value}));

    assertThat(ids(where)).as(where.sql()).containsExactlyInAnyOrderElementsOf(urlGrammar);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cells")
  void extractionFormIsPinned(
      String label, String param, String value, Set<String> urlGrammar, Set<String> extraction) {
    JsonbClause where = extractionClause(param, value);

    if (ERROR.equals(extraction)) {
      assertThatThrownBy(() -> ids(where)).isInstanceOf(DataAccessException.class);
    } else {
      assertThat(ids(where)).as(where.sql()).containsExactlyInAnyOrderElementsOf(extraction);
    }
  }

  /** The clause the legacy factory methods build for {@code param=value}: always the extraction. */
  private JsonbClause extractionClause(String param, String value) {
    boolean in = param.endsWith(".in");
    String path = in ? param.substring(0, param.length() - ".in".length()) : param;
    ResolvedField field = resolver.resolve(JsonbTypedDomain.class, path, true);
    if (!in) {
      Object typed = converter.convert(value, field.javaType(), field.fieldPath());
      return predicateFactory.build(TmfOperator.EQ, field.fieldPath(), field.javaType(), typed);
    }
    List<Object> typed = new ArrayList<>();
    Arrays.stream(value.split(","))
        .forEach(v -> typed.add(converter.convert(v, field.javaType(), field.fieldPath())));
    return predicateFactory.buildMulti(TmfOperator.IN, field.fieldPath(), field.javaType(), typed);
  }

  private List<String> ids(JsonbClause where) {
    return jdbc.sql("SELECT id FROM " + TABLE + " WHERE " + where.sql())
        .params(where.params())
        .query(String.class)
        .list();
  }

  @SpringBootApplication(scanBasePackageClasses = JsonbTestRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
