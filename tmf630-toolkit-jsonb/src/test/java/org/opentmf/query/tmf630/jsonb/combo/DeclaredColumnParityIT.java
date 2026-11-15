package org.opentmf.query.tmf630.jsonb.combo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.jsonb.JsonbDeclaredColumn;
import org.opentmf.query.tmf630.jsonb.JsonbEntityMetadata;
import org.opentmf.query.tmf630.jsonb.JsonbEntityRegistry;
import org.opentmf.query.tmf630.jsonb.JsonbSortBuilder;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbClauseBuilder;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * A declared column answers as the payload does (ledger #4256): the combination suite's documents
 * in {@link ComboRow} and in its declared twin {@link DeclaredComboRow} (every scalar kind, at the
 * top level and nested, also in a column filled from the same payload), and every URL operator and
 * both sorts of those kinds asked of both, under the DEFAULT settings. The answers must be the
 * same, status and rows (sorts: the order, ties broken by id). The one difference, by construction:
 * where the payload answers 500 (a stored value the field's cast cannot read, under
 * {@code cast-mode=strict}), the column holds NULL and the declared twin answers.
 */
@SpringBootTest(
    classes = Tmf630JsonbCombinationIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa=true"
    })
@ActiveProfiles("test")
class DeclaredColumnParityIT {

  private static final Logger LOG = LoggerFactory.getLogger(DeclaredColumnParityIT.class);

  private static final List<ComboKind> SCALARS =
      List.of(
          ComboKind.STRING,
          ComboKind.INTEGER,
          ComboKind.LONG,
          ComboKind.DECIMAL,
          ComboKind.DOUBLE,
          ComboKind.BOOLEAN,
          ComboKind.DATE,
          ComboKind.DATE_TIME,
          ComboKind.PLAIN_ENUM,
          ComboKind.WIRE_ENUM);

  private static final List<ComboPosition> POSITIONS =
      List.of(ComboPosition.TOP, ComboPosition.NESTED);

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;
  @Autowired private FieldPathResolver fieldPathResolver;
  @Autowired private JsonbEntityRegistry registry;

  private record Answer(int status, String text) {}

  @Test
  void everyCellOfADeclaredScalarAnswersAsThePayload() {
    seed();
    JsonbEntityMetadata declared = registry.forDomainType(DeclaredComboDomain.class).orElseThrow();
    List<String> differ = new ArrayList<>();
    int cells = 0;
    int errorToAnswer = 0;
    int onColumn = 0;
    for (ComboKind kind : SCALARS) {
      for (ComboPosition position : POSITIONS) {
        for (TmfOperator op : TmfOperator.values()) {
          Map<String, String[]> params = ComboRequests.urlParams(position, kind, op);
          Answer payload = ask(ComboDomain.class, ComboRow.TABLE, params);
          Answer column = ask(DeclaredComboDomain.class, DeclaredComboRow.TABLE, params);
          cells++;
          onColumn += sql(DeclaredComboDomain.class, params).contains(columnOf(position, kind)) ? 1 : 0;
          if (payload.status() == 500 && column.status() == 200) {
            errorToAnswer++;
          } else if (!payload.equals(column)) {
            differ.add(ComboRequests.request(params) + " :: payload " + payload + " :: column " + column);
          }
        }
        for (Sort.Direction direction : Sort.Direction.values()) {
          String path = ComboRequests.sortPath(position, kind);
          Answer payload = sorted(ComboRow.TABLE, path, direction, Optional.empty());
          Answer column =
              sorted(
                  DeclaredComboRow.TABLE,
                  path,
                  direction,
                  declared.declaredColumn(path).map(JsonbDeclaredColumn::column));
          cells++;
          if (payload.status() == 500 && column.status() == 200) {
            errorToAnswer++;
          } else if (!payload.equals(column)) {
            differ.add("sort " + direction + " " + path + " :: payload " + payload + " :: column " + column);
          }
        }
      }
    }
    LOG.info(
        "DECLARED-PARITY {} cells; {} on the column; {} differ; {} answered where the payload failed",
        cells, onColumn, differ.size(), errorToAnswer);
    assertThat(differ).as("cells where the declared column answers otherwise").isEmpty();
    assertThat(onColumn).as("cells sent to the declared column").isPositive();
  }

  private void seed() {
    Map<String, JsonNode> rows = new LinkedHashMap<>();
    ComboSeed.documents(rows, new LinkedHashMap<>());
    ComboSeed.write(jdbc, rows, new LinkedHashMap<>());
    StringBuilder ddl = new StringBuilder("CREATE TABLE " + DeclaredComboRow.TABLE
        + " (id VARCHAR(80) PRIMARY KEY, payload JSONB NOT NULL");
    StringBuilder select = new StringBuilder("SELECT id, payload");
    StringBuilder names = new StringBuilder("id, payload");
    for (String[] column : DeclaredComboRow.COLUMNS) {
      for (String prefix : List.of("top_", "n_")) {
        String name = prefix + column[0];
        String text = "top_".equals(prefix) ? "payload->>'" + column[0] + "'" : "payload#>>'{n," + column[0] + "}'";
        ddl.append(", ").append(name).append(' ').append(column[1]);
        names.append(", ").append(name);
        select.append(", ").append(read(text, column[1]));
      }
    }
    jdbc.sql("DROP TABLE IF EXISTS " + DeclaredComboRow.TABLE).update();
    jdbc.sql(ddl.append(')').toString()).update();
    jdbc.sql("INSERT INTO " + DeclaredComboRow.TABLE + " (" + names + ") " + select + " FROM "
            + ComboRow.TABLE)
        .update();
  }

  /** The payload's text as the column's type reads it; NULL for text the type cannot read. */
  private static String read(String text, String sqlType) {
    return "text".equals(sqlType)
        ? text
        : "CASE WHEN pg_input_is_valid(" + text + ", '" + sqlType + "') THEN (" + text + ")::"
            + sqlType + " END";
  }

  private static String columnOf(ComboPosition position, ComboKind kind) {
    return (position == ComboPosition.TOP ? "top_" : "n_") + kind.key();
  }

  private String sql(Class<?> domain, Map<String, String[]> params) {
    try {
      return clauseBuilder.build(domain, params).sql();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private Answer ask(Class<?> domain, String table, Map<String, String[]> params) {
    JsonbClause where;
    try {
      where = clauseBuilder.build(domain, params);
    } catch (TmfFilteringException refused) {
      return new Answer(400, refused.getMessage());
    }
    try {
      List<String> ids =
          jdbc.sql("SELECT id FROM " + table + " WHERE " + where.sql() + " ORDER BY id")
              .params(where.params())
              .query(String.class)
              .list();
      return new Answer(200, String.join(",", ids));
    } catch (DataAccessException driver) {
      return new Answer(500, "");
    }
  }

  private Answer sorted(
      String table, String path, Sort.Direction direction, Optional<String> column) {
    try {
      Class<?> type = fieldPathResolver.resolve(ComboDomain.class, path, true).javaType();
      TmfSort sort =
          new TmfSort(
              List.of(
                  new TmfSortTerm(direction, TmfSortTerm.Kind.PLAIN, path),
                  new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "id")));
      JsonbClause order =
          sortBuilder.buildOrderByClause(
              sort, f -> path.equals(f) ? type : String.class, p -> path.equals(p) ? column : Optional.empty());
      List<String> ids =
          jdbc.sql("SELECT id FROM " + table + " ORDER BY " + order.sql())
              .params(order.params())
              .query(String.class)
              .list();
      return new Answer(200, String.join(",", ids));
    } catch (TmfFilteringException refused) {
      return new Answer(400, refused.getMessage());
    } catch (DataAccessException driver) {
      return new Answer(500, "");
    }
  }
}
