package org.opentmf.query.tmf630.jsonb.combo;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.querydsl.core.types.Predicate;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentmf.query.tmf630.filtering.Tmf630PredicateArgumentResolver;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.jsonb.JsonbSortBuilder;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbClauseBuilder;
import org.opentmf.query.tmf630.jsonb.combo.ComboOracle.Found;
import org.opentmf.query.tmf630.jsonb.combo.ComboPosition.Step;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * TOOLKIT-JSONB-COMBINATION-SUITE-1: a generated combination suite of the JSONB filter, sort and
 * count paths on the module's real PostgreSQL.
 *
 * <p>Axes: the declared type ({@link ComboKind}) × the JSON value stored (declared type, another
 * type, unreadable text, JSON null, the key absent) × the 23 {@link TmfOperator}s × the entry (URL
 * attribute operator, {@code filter=} JsonPath) × the position ({@link ComboPosition}) × the entity
 * shape (not split, split); plus sort ascending and descending on each field.
 *
 * <p>It runs twice: this class under the module's DEFAULTS (the 3.5.2 behaviour of every switch),
 * {@link Tmf630JsonbCombinationOptInsIT} with every opt-in on ({@code equality=containment},
 * {@code cast-mode=lenient}). Findings listed in {@code combination/expected-<mode>.tsv} are
 * EXPECTED: a behaviour held for a ruling, kept by one, or the default of a switch, with its
 * reason. Each must keep failing exactly as listed; an unexpected finding, or a listed one that
 * passes or answers differently, fails the test.
 *
 * <p>Two verdicts per cell: (1) the answer is rows or the toolkit's own 400, never a driver error,
 * and the toolkit refuses exactly what the QueryDSL terminal refuses for the same URL; (2) the
 * rows equal an oracle computed in Java from the seeded JSON: {@link ComboOracle} for the URL
 * operators, Jayway (the toolkit's own JsonPath library) for {@code filter=}, the declared type's
 * order for sorts. Every finding is written to {@code target/combination/<mode>/} and the test fails with
 * their count, so a run lists all of them at once.
 */
@SpringBootTest(
    classes = Tmf630JsonbCombinationIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      // the QueryDSL reference runs on a neutral root, whose nested-path policy is the JPA one
      "opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa=true"
    })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tmf630JsonbCombinationIT {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final List<ComboPosition> NOT_SPLIT =
      List.of(
          ComboPosition.TOP,
          ComboPosition.NESTED,
          ComboPosition.ARRAY,
          ComboPosition.ARRAY_IN_ARRAY);

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;
  @Autowired private Tmf630PredicateArgumentResolver reference;
  @Autowired private FieldPathResolver fieldPathResolver;

  private final Map<String, JsonNode> rows = new LinkedHashMap<>();
  private final Map<String, JsonNode> splitRows = new LinkedHashMap<>();
  private final List<Finding> findings = new ArrayList<>();
  private final Map<String, Integer> skipped = new TreeMap<>();
  private int cells;

  /** One failing cell. */
  record Finding(
      String verdict,
      String shape,
      ComboPosition position,
      ComboKind kind,
      String entry,
      String operator,
      String request,
      String sql,
      String detail) {}

  // ---- seeding ----------------------------------------------------------------------------

  @BeforeAll
  void seed() {
    jdbc.sql("DROP TABLE IF EXISTS " + ComboSplitRow.CHILD_TABLE + " CASCADE").update();
    jdbc.sql("DROP TABLE IF EXISTS " + ComboSplitRow.TABLE + " CASCADE").update();
    jdbc.sql("DROP TABLE IF EXISTS " + ComboRow.TABLE + " CASCADE").update();
    jdbc.sql("CREATE TABLE " + ComboRow.TABLE + " (id VARCHAR(80) PRIMARY KEY, payload JSONB NOT NULL)")
        .update();
    jdbc.sql(
            "CREATE TABLE " + ComboSplitRow.TABLE
                + " (id VARCHAR(80) PRIMARY KEY, payload JSONB NOT NULL)")
        .update();
    jdbc.sql(
            "CREATE TABLE " + ComboSplitRow.CHILD_TABLE + " (parent_id VARCHAR(80) NOT NULL"
                + " REFERENCES " + ComboSplitRow.TABLE + "(id) ON DELETE CASCADE,"
                + " item_id VARCHAR(50) NOT NULL, item_order INTEGER NOT NULL,"
                + " payload JSONB NOT NULL, PRIMARY KEY (parent_id, item_id))")
        .update();
    for (ComboKind kind : ComboKind.values()) {
      for (String[] stored : kind.storedValues()) {
        ObjectNode leaf = JSON.createObjectNode().set(kind.key(), JSON.readTree(stored[1]));
        for (ComboPosition position : NOT_SPLIT) {
          String id = position.name().toLowerCase() + "." + kind.key() + "." + stored[0];
          ObjectNode doc = position.payload(id, leaf);
          rows.put(id, doc);
          insert(ComboRow.TABLE, id, doc);
        }
        String id = "split." + kind.key() + "." + stored[0];
        ObjectNode doc = ComboPosition.SPLIT.payload(id, leaf);
        splitRows.put(id, doc);
        insert(ComboSplitRow.TABLE, id, JSON.createObjectNode().put("id", id));
        List<JsonNode> items = doc.get("items").valueStream().toList();
        for (int order = 0; order < items.size(); order++) {
          jdbc.sql(
                  "INSERT INTO " + ComboSplitRow.CHILD_TABLE
                      + " (parent_id, item_id, item_order, payload) VALUES (?, ?, ?, ?::jsonb)")
              .params(id, "c" + order, order, items.get(order).toString())
              .update();
        }
      }
    }
  }

  private void insert(String table, String id, JsonNode payload) {
    jdbc.sql("INSERT INTO " + table + " (id, payload) VALUES (?, ?::jsonb)")
        .params(id, payload.toString())
        .update();
  }

  // ---- the run ----------------------------------------------------------------------------

  @Test
  void everyCombinationAnswersAndMatchesItsOracle() throws IOException {
    long start = System.nanoTime();
    for (ComboKind kind : ComboKind.values()) {
      for (ComboPosition position : NOT_SPLIT) {
        everyOperator(false, position, kind);
        everySort(position, kind);
      }
      everyOperator(true, ComboPosition.SPLIT, kind);
    }
    double seconds = (System.nanoTime() - start) / 1e9;
    Map<String, String[]> expected = loadExpected();
    List<Finding> unexpected = new ArrayList<>();
    Map<String, Integer> expectedByReason = new TreeMap<>();
    for (Finding f : findings) {
      String[] pinned = expected.remove(key(f));
      if (pinned != null && pinned[0].equals(f.detail())) {
        expectedByReason.merge(pinned[1], 1, Integer::sum);
      } else {
        unexpected.add(f);
      }
    }
    String summary = writeReport(seconds, unexpected, expectedByReason, expected.keySet());
    assertThat(unexpected).as(summary).isEmpty();
    assertThat(expected.keySet())
        .as("EXPECTED findings that no longer fail as listed (the behaviour changed): " + summary)
        .isEmpty();
  }

  /** {@code verdict|shape|position|kind|entry|operator|request} of a finding. */
  private static String key(Finding f) {
    return String.join("|", f.verdict(), f.shape(), f.position().name(), f.kind().name(),
        f.entry(), f.operator(), f.request());
  }

  /** The run's name: {@code defaults} here, the opt-ins in the subclass. */
  protected String mode() {
    return "defaults";
  }

  private Path reportDir() {
    return Path.of("target", "combination", mode());
  }

  /** The EXPECTED findings: key to {detail, reason}. */
  private Map<String, String[]> loadExpected() throws IOException {
    Map<String, String[]> expected = new LinkedHashMap<>();
    try (InputStream in =
        Tmf630JsonbCombinationIT.class.getResourceAsStream(
            "/combination/expected-" + mode() + ".tsv")) {
      if (in == null) {
        return expected;
      }
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] c = line.split("\t", -1);
        expected.put(String.join("|", c[0], c[1], c[2], c[3], c[4], c[5], c[6]),
            new String[] {c[7], c[8]});
      }
    }
    return expected;
  }

  private void everyOperator(boolean split, ComboPosition position, ComboKind kind) {
    for (TmfOperator operator : TmfOperator.values()) {
      urlCell(split, position, kind, operator);
      filterCell(split, position, kind, operator);
    }
    for (String[] word : JAYWAY_OPERATORS_NOT_IN_THE_GRAMMAR) {
      jaywayWordCell(split, position, kind, word[0], word[1]);
    }
  }

  /**
   * Jayway's filter operators the toolkit's {@code filter=} grammar does not have (README: "What
   * filter= does NOT support (rejected with 400)"), each with a value of its kind. Both the
   * QueryDSL terminal and JSONB must answer the toolkit's 400.
   */
  private static final String[][] JAYWAY_OPERATORS_NOT_IN_THE_GRAMMAR = {
    {"in", "['x', 'y']"}, {"nin", "['x']"}, {"size", "2"}, {"empty", "true"},
    {"contains", "'x'"}, {"anyof", "['x']"}, {"noneof", "['x']"}, {"subsetof", "['x']"},
    {"all", "['x']"}, {"exists", "true"}, {"type", "'string'"}, {"matches", "'x'"},
    {"IN", "['x']"}, {"===", "'x'"}, {"!==", "'x'"}
  };

  private void jaywayWordCell(
      boolean split, ComboPosition position, ComboKind kind, String word, String value) {
    List<Step> steps = new ArrayList<>(position.steps());
    String leafKey = kind.key();
    if (kind == ComboKind.OBJECT_LIST) {
      steps.add(new Step("lo", true));
      leafKey = "code";
    }
    String expression =
        "$[?(" + nest(steps, 0, "@.", leafKey, "%s " + word + " " + value) + ")]";
    evaluate(split, position, kind, "FILTER", "JAYWAY_" + word, Map.of("filter",
        new String[] {expression}), Optional.empty());
  }

  // ---- URL attribute operators ------------------------------------------------------------

  private void urlCell(boolean split, ComboPosition position, ComboKind kind, TmfOperator op) {
    List<String> query = urlQuery(kind, op);
    String value = op.isNoValueOperator() ? "true" : String.join(",", query);
    String name = position.urlPrefix() + kind.leafPath() + "." + op.suffix();
    Map<String, String[]> params = Map.of(name, new String[] {value});
    Set<String> expected =
        ids(split, doc -> ComboOracle.matches(op, kind, ComboOracle.find(doc, position, kind), query));
    evaluate(split, position, kind, "URL", op.name(), params, Optional.of(expected));
  }

  /** The query values of a URL operator, from the kind's probe. */
  private static List<String> urlQuery(ComboKind kind, TmfOperator op) {
    ComboKind.Probe p = kind.probe();
    return switch (op) {
      case EQ, NE -> List.of(p.eq());
      case EQI, NEI -> List.of(ComboKind.upper(p.eq()));
      case GT, GTE, LT, LTE -> List.of(p.above());
      case BETWEEN -> List.of(p.low(), p.high());
      case IN, NIN -> List.of(p.inA(), p.inB());
      case IS_NULL, IS_NOT_NULL -> List.of();
      case LIKE -> List.of(p.prefix() + "%");
      case LIKEI -> List.of(ComboKind.upper(p.prefix()) + "%");
      case CONTAINS -> List.of(p.infix());
      case CONTAINSI -> List.of(ComboKind.upper(p.infix()));
      case STARTS_WITH -> List.of(p.prefix());
      case STARTS_WITHI -> List.of(ComboKind.upper(p.prefix()));
      case ENDS_WITH -> List.of(p.suffix());
      case ENDS_WITHI -> List.of(ComboKind.upper(p.suffix()));
      case REGEX -> List.of("^" + p.prefix());
      case REGEXI -> List.of("^" + ComboKind.upper(p.prefix()));
    };
  }

  // ---- filter= JsonPath -------------------------------------------------------------------

  private void filterCell(boolean split, ComboPosition position, ComboKind kind, TmfOperator op) {
    Optional<String> leaf = filterLeaf(kind, op);
    if (leaf.isEmpty()) {
      skip("FILTER_HAS_NO_FORM_FOR_" + op.name());
      return;
    }
    List<Step> steps = new ArrayList<>(position.steps());
    String leafKey = kind.key();
    if (kind == ComboKind.OBJECT_LIST) {
      steps.add(new Step("lo", true));
      leafKey = "code";
    }
    String expression = "$[?(" + nest(steps, 0, "@.", leafKey, leaf.get()) + ")]";
    Map<String, String[]> params = Map.of("filter", new String[] {expression});
    Optional<Set<String>> expected;
    if (op == TmfOperator.REGEX || op == TmfOperator.REGEXI) {
      // Jayway full-matches =~; the toolkit's =~ searches, as .regex and Mongo's $regex (3.4.0)
      List<String> query = urlQuery(kind, op);
      expected =
          Optional.of(
              ids(split, doc -> ComboOracle.matches(op, kind, ComboOracle.find(doc, position, kind), query)));
      skip("FILTER_REGEX_JUDGED_BY_SEARCH_NOT_JAYWAY_FULL_MATCH");
    } else {
      expected = filterOracle(split, steps, leafKey, leaf.get());
    }
    evaluate(split, position, kind, "FILTER", op.name(), params, expected);
  }

  /** The predicate template of a {@code filter=} operator, {@code %s} standing for the path. */
  private static Optional<String> filterLeaf(ComboKind kind, TmfOperator op) {
    ComboKind.Probe p = kind.probe();
    return Optional.ofNullable(
        switch (op) {
          case EQ -> "%s == " + kind.filterLiteral(p.eq());
          case NE -> "%s != " + kind.filterLiteral(p.eq());
          case GT -> "%s > " + kind.filterLiteral(p.above());
          case GTE -> "%s >= " + kind.filterLiteral(p.above());
          case LT -> "%s < " + kind.filterLiteral(p.above());
          case LTE -> "%s <= " + kind.filterLiteral(p.above());
          case BETWEEN ->
              "%s >= " + kind.filterLiteral(p.low()) + " && %s <= " + kind.filterLiteral(p.high());
          case IN -> "%s in [" + kind.filterLiteral(p.inA()) + ", " + kind.filterLiteral(p.inB()) + "]";
          case NIN ->
              "%s nin [" + kind.filterLiteral(p.inA()) + ", " + kind.filterLiteral(p.inB()) + "]";
          case IS_NULL -> "!%s";
          case REGEX -> "%s =~ /^" + p.prefix() + "/";
          case REGEXI -> "%s =~ /^" + ComboKind.upper(p.prefix()) + "/i";
          default -> null;
        });
  }

  /** Builds the predicate: object steps extend the path, array steps correlate into elements. */
  private static String nest(List<Step> steps, int from, String prefix, String leafKey, String leaf) {
    if (from == steps.size()) {
      return leaf.replace("%s", prefix + leafKey);
    }
    Step step = steps.get(from);
    if (!step.array()) {
      return nest(steps, from + 1, prefix + step.name() + ".", leafKey, leaf);
    }
    return prefix + step.name() + "[?(" + nest(steps, from + 1, "@.", leafKey, leaf) + ")]";
  }

  /**
   * The rows the {@code filter=} selects by its documented meaning: a {@code [?(...)]} step matches
   * when SOME element satisfies the inner predicate (Mongo's elemMatch, the JPA join), and the
   * comparison at the leaf is Jayway's, the toolkit's own JsonPath library. (Jayway alone treats
   * a correlated path inside a filter as true whenever the array exists, whatever the inner
   * predicate says; it is used here only at the leaf.) Empty when Jayway cannot run the leaf.
   */
  private Optional<Set<String>> filterOracle(
      boolean split, List<Step> steps, String leafKey, String leaf) {
    try {
      return Optional.of(ids(split, doc -> filterMatches(doc, steps, 0, List.of(), leafKey, leaf)));
    } catch (RuntimeException jaywayCannot) {
      return Optional.empty();
    }
  }

  private static boolean filterMatches(
      JsonNode node, List<Step> steps, int from, List<String> fields, String leafKey, String leaf) {
    if (from == steps.size()) {
      String path = "@." + String.join("", fields.stream().map(f -> f + ".").toList()) + leafKey;
      String expression = "$[?(" + leaf.replace("%s", path) + ")]";
      return !JsonPath.parse("[" + node + "]").read(expression, List.class).isEmpty();
    }
    Step step = steps.get(from);
    List<String> along = new ArrayList<>(fields);
    along.add(step.name());
    if (!step.array()) {
      return filterMatches(node, steps, from + 1, along, leafKey, leaf);
    }
    JsonNode array = node;
    for (String field : along) {
      array = array == null ? null : array.get(field);
    }
    if (array == null || !array.isArray()) {
      return false;
    }
    return array.valueStream()
        .anyMatch(element -> filterMatches(element, steps, from + 1, List.of(), leafKey, leaf));
  }

  // ---- one cell ---------------------------------------------------------------------------

  private void evaluate(
      boolean split,
      ComboPosition position,
      ComboKind kind,
      String entry,
      String operator,
      Map<String, String[]> params,
      Optional<Set<String>> expected) {
    cells++;
    String request = request(params);
    Optional<String> referenceRefusal = referenceRefusal(split, params);
    JsonbClause where;
    try {
      where = clauseBuilder.build(split ? ComboSplitDomain.class : ComboDomain.class, params);
    } catch (TmfFilteringException refused) {
      if (referenceRefusal.isEmpty()) {
        add("ACCEPTANCE", split, position, kind, entry, operator, request, "",
            "JSONB 400 (" + refused.getMessage() + "); the QueryDSL terminal accepts");
      }
      return;
    } catch (RuntimeException crashed) {
      add("ERROR", split, position, kind, entry, operator, request, "", "clause builder: " + crashed);
      return;
    }
    String table = split ? ComboSplitRow.TABLE : ComboRow.TABLE;
    String sql = "SELECT id FROM " + table + " WHERE " + where.sql();
    List<String> found;
    long count;
    try {
      found = jdbc.sql(sql).params(where.params()).query(String.class).list();
      count =
          jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + where.sql())
              .params(where.params())
              .query(Long.class)
              .single();
    } catch (DataAccessException driver) {
      add("ERROR", split, position, kind, entry, operator, request, sql + params(where),
          rootMessage(driver));
      return;
    }
    if (referenceRefusal.isPresent()) {
      add("ACCEPTANCE", split, position, kind, entry, operator, request, sql + params(where),
          "JSONB answers " + found.size() + " rows; the QueryDSL terminal refuses ("
              + referenceRefusal.get() + ")");
      return;
    }
    if (count != found.size()) {
      add("COUNT", split, position, kind, entry, operator, request, sql + params(where),
          "COUNT " + count + " against " + found.size() + " rows");
    }
    if (expected.isEmpty()) {
      skip("ORACLE_CANNOT_EVALUATE_" + entry);
      return;
    }
    Set<String> actual = new TreeSet<>(found);
    if (!actual.equals(expected.get())) {
      add("ROWS", split, position, kind, entry, operator, request, sql + params(where),
          diff(expected.get(), actual));
    }
  }

  /** The QueryDSL terminal's refusal of the same request, or empty when it builds a predicate. */
  private Optional<String> referenceRefusal(boolean split, Map<String, String[]> params) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/combo");
    params.forEach(request::addParameter);
    try {
      reference.resolveArgument(rootParameter(split), null, new ServletWebRequest(request), null);
      return Optional.empty();
    } catch (TmfFilteringException refused) {
      return Optional.of(refused.getMessage());
    } catch (RuntimeException crashed) {
      return Optional.of("QueryDSL terminal crashed: " + crashed);
    }
  }

  // ---- sort -------------------------------------------------------------------------------

  private void everySort(ComboPosition position, ComboKind kind) {
    if (position == ComboPosition.ARRAY_IN_ARRAY) {
      skip("SORT_THROUGH_AN_ARRAY_OF_ARRAYS");
      return;
    }
    for (Sort.Direction direction : Sort.Direction.values()) {
      cells++;
      String path = position.urlPrefix() + kind.leafPath();
      String request = "sort=" + (direction == Sort.Direction.DESC ? "-" : "") + path;
      JsonbClause order;
      try {
        Class<?> type = fieldPathResolver.resolve(ComboDomain.class, path, true).javaType();
        TmfSort sort = new TmfSort(List.of(new TmfSortTerm(direction, TmfSortTerm.Kind.PLAIN, path)));
        order = sortBuilder.buildOrderByClause(sort, field -> type);
      } catch (TmfFilteringException refused) {
        continue;
      } catch (RuntimeException crashed) {
        add("ERROR", false, position, kind, "SORT", direction.name(), request, "", crashed.toString());
        continue;
      }
      String sql = "SELECT id FROM " + ComboRow.TABLE + " ORDER BY " + order.sql();
      List<String> ordered;
      try {
        ordered = jdbc.sql(sql).params(order.params()).query(String.class).list();
      } catch (DataAccessException driver) {
        add("ERROR", false, position, kind, "SORT", direction.name(), request, sql,
            rootMessage(driver));
        continue;
      }
      if (position == ComboPosition.ARRAY || !isSortable(kind)) {
        skip("SORT_ORDER_UNDEFINED_FOR_" + (position == ComboPosition.ARRAY ? "ARRAY" : "KIND"));
        continue;
      }
      checkOrder(position, kind, direction, request, sql, ordered);
    }
  }

  private static boolean isSortable(ComboKind kind) {
    return switch (kind) {
      case OBJECT, MAP, STRING_LIST, OBJECT_LIST -> false;
      default -> true;
    };
  }

  /**
   * Values of the declared type must come in order; rows without a value (absent or JSON null)
   * after them ascending and before them descending (PostgreSQL's default null placement, the
   * toolkit's default {@code nulls-last=false}). Rows holding another type are not judged.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void checkOrder(
      ComboPosition position,
      ComboKind kind,
      Sort.Direction direction,
      String request,
      String sql,
      List<String> ordered) {
    Comparable previous = null;
    boolean seenNull = false;
    boolean seenTyped = false;
    for (String id : ordered) {
      Found found = ComboOracle.find(rows.get(id), position, kind);
      Optional<JsonNode> value = found.values().stream().findFirst();
      if (value.isEmpty() || value.get().isNull()) {
        if (direction == Sort.Direction.ASC || !seenTyped) {
          seenNull = true;
        } else {
          add("SORT_ORDER", false, position, kind, "SORT", direction.name(), request, sql,
              "a row without a value (" + id + ") after a typed one, descending");
          return;
        }
        continue;
      }
      Optional<Comparable<?>> typed = ComboOracle.typed(kind, value.get());
      if (typed.isEmpty()) {
        continue;
      }
      if (seenNull && direction == Sort.Direction.ASC) {
        add("SORT_ORDER", false, position, kind, "SORT", direction.name(), request, sql,
            "a typed row (" + id + ") after a row without a value, ascending");
        return;
      }
      Comparable current = typed.get();
      if (previous != null) {
        int c = previous.compareTo(current);
        if (direction == Sort.Direction.ASC ? c > 0 : c < 0) {
          add("SORT_ORDER", false, position, kind, "SORT", direction.name(), request, sql,
              "out of order at " + id + ": " + previous + " then " + current);
          return;
        }
      }
      previous = current;
      seenTyped = true;
    }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private Set<String> ids(boolean split, Function<JsonNode, Boolean> match) {
    return (split ? splitRows : rows)
        .entrySet().stream()
            .filter(e -> match.apply(e.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(TreeSet::new));
  }

  private void add(
      String verdict,
      boolean split,
      ComboPosition position,
      ComboKind kind,
      String entry,
      String operator,
      String request,
      String sql,
      String detail) {
    findings.add(
        new Finding(verdict, split ? "split" : "not split", position, kind, entry, operator,
            request, sql, detail));
  }

  private void skip(String rule) {
    skipped.merge(rule, 1, Integer::sum);
  }

  private static String diff(Set<String> expected, Set<String> actual) {
    Set<String> missing = new TreeSet<>(expected);
    missing.removeAll(actual);
    Set<String> extra = new TreeSet<>(actual);
    extra.removeAll(expected);
    return "missing " + missing + " extra " + extra;
  }

  private static String request(Map<String, String[]> params) {
    return params.entrySet().stream()
        .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
        .collect(Collectors.joining("&"));
  }

  private static String params(JsonbClause clause) {
    return clause.params().isEmpty() ? "" : "  -- params " + clause.params();
  }

  private static String rootMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    String message = String.valueOf(root.getMessage());
    int newline = message.indexOf('\n');
    return root.getClass().getSimpleName() + ": "
        + (newline < 0 ? message : message.substring(0, newline));
  }

  private static MethodParameter rootParameter(boolean split) {
    try {
      Method method =
          Tmf630JsonbCombinationIT.class.getDeclaredMethod(
              split ? "splitRoot" : "comboRoot", Predicate.class);
      return new MethodParameter(method, 0);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The QueryDSL terminal's handler shape for the non-split root; never called. */
  static void comboRoot(@QuerydslPredicate(root = ComboDomain.class) Predicate predicate) {
    throw new UnsupportedOperationException("signature only");
  }

  /** The QueryDSL terminal's handler shape for the split root; never called. */
  static void splitRoot(@QuerydslPredicate(root = ComboSplitDomain.class) Predicate predicate) {
    throw new UnsupportedOperationException("signature only");
  }

  // ---- report -----------------------------------------------------------------------------

  private String writeReport(
      double seconds,
      List<Finding> unexpected,
      Map<String, Integer> expectedByReason,
      Set<String> gone)
      throws IOException {
    Files.createDirectories(reportDir());
    StringBuilder tsv =
        new StringBuilder("verdict\tshape\tposition\tkind\tentry\toperator\trequest\tsql\tdetail\tgroup\n");
    for (Finding f : findings) {
      tsv.append(
              String.join("\t", f.verdict(), f.shape(), f.position().name(), f.kind().name(),
                      f.entry(), f.operator(), f.request(), f.sql(), f.detail(), signature(f))
                  .replace('\n', ' '))
          .append('\n');
    }
    Files.writeString(reportDir().resolve("findings.tsv"), tsv);
    Map<String, List<Finding>> groups =
        unexpected.stream().collect(Collectors.groupingBy(Tmf630JsonbCombinationIT::signature,
            TreeMap::new, Collectors.toList()));
    StringBuilder md = new StringBuilder("# JSONB combination suite\n\n");
    String summary =
        String.format(
            "%d cells in %.1f s; %d unexpected findings in %d groups; %d expected; %d expected but"
                + " no longer failing as listed; skipped by rule %s",
            cells, seconds, unexpected.size(), groups.size(),
            expectedByReason.values().stream().mapToInt(Integer::intValue).sum(), gone.size(),
            skipped);
    md.append(summary).append("\n\n## EXPECTED, by reason\n\n");
    expectedByReason.forEach((reason, n) -> md.append("- ").append(n).append(" · ").append(reason).append('\n'));
    md.append("\n## EXPECTED but no longer failing as listed\n\n");
    gone.forEach(g -> md.append("- `").append(g).append("`\n"));
    md.append("\n## UNEXPECTED\n\n");
    groups.forEach((signature, members) -> appendGroup(md, signature, members));
    Files.writeString(reportDir().resolve("findings.md"), md);
    return summary;
  }

  private static void appendGroup(StringBuilder md, String signature, List<Finding> members) {
    md.append("## ").append(signature).append(" (").append(members.size()).append(")\n\n");
    Function<Finding, String> coordinate =
        f -> f.shape() + "/" + f.position() + "/" + f.kind() + "/" + f.operator();
    md.append(members.stream().map(coordinate).collect(Collectors.joining(", "))).append("\n\n");
    Finding first = members.get(0);
    md.append("- request: `").append(first.request()).append("`\n");
    md.append("- sql: `").append(first.sql()).append("`\n");
    md.append("- answer: ").append(first.detail()).append("\n\n");
  }

  /**
   * Groups findings by what they share: verdict, entry and the shape of their detail. A ROWS
   * finding whose differing rows are all ones an oracle RULING decides is tagged {@code RULING}:
   * NE/NEI/NIN differing only on rows of another JSON type or unreadable text (the type-exact
   * "unequal" rule), IS_NULL/IS_NOT_NULL differing only on JSON-null rows (the key-absent rule).
   */
  private static String signature(Finding f) {
    String tag = dependsOnRuling(f) ? "RULING · " : "";
    return tag + plainSignature(f);
  }

  /**
   * Whether every differing row is one an oracle RULING decides: for NE/NEI/NIN a row holding
   * another JSON type or unreadable text, or a row without the field (Jayway counts it unequal);
   * for IS_NULL/IS_NOT_NULL a JSON-null row; for a {@code filter=} comparison a row holding another
   * JSON type (Jayway coerces {@code '5' == 5}; the toolkit is type-exact by ruling).
   */
  private static boolean dependsOnRuling(Finding f) {
    if (!"ROWS".equals(f.verdict())) {
      return false;
    }
    List<String> differing = differingRelations(f);
    if (differing.isEmpty()) {
      return false;
    }
    return switch (f.operator()) {
      case "NE", "NEI", "NIN" ->
          differing.stream()
              .allMatch(v -> v.startsWith("o") || v.startsWith("x") || v.equals(WITHOUT_FIELD));
      case "IS_NULL", "IS_NOT_NULL" -> differing.stream().allMatch("nul"::equals);
      default -> "FILTER".equals(f.entry()) && differing.stream().allMatch(v -> v.startsWith("o"));
    };
  }

  private static final String WITHOUT_FIELD = "row-without-the-field";

  /**
   * For each row a ROWS detail lists: its variant label ({@code d0}, {@code o1}, {@code nul}, …)
   * when the row holds this cell's field at this position, else {@link #WITHOUT_FIELD}.
   */
  private static List<String> differingRelations(Finding f) {
    String own = (f.shape().equals("split") ? "split" : f.position().name().toLowerCase())
        + "." + f.kind().key() + ".";
    List<String> relations = new ArrayList<>();
    Matcher m = Pattern.compile("([a-z_]+\\.[a-z0-9]+\\.)([a-z]+[0-9]*)").matcher(f.detail());
    while (m.find()) {
      relations.add(m.group(1).equals(own) ? m.group(2) : WITHOUT_FIELD);
    }
    return relations;
  }

  private static String plainSignature(Finding f) {
    String detail = f.detail();
    if ("ROWS".equals(f.verdict())) {
      detail = rowsSignature(f);
    } else {
      detail = detail.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("'[^']*'", "'…'")
          .replaceAll("\\d+", "N");
    }
    return f.verdict() + " · " + f.entry() + " · " + detail;
  }

  /** "missing [d0, row-without-the-field] extra [o0]", the differing rows by relation. */
  private static String rowsSignature(Finding f) {
    int extraAt = f.detail().indexOf(" extra ");
    Finding missingOnly =
        new Finding(f.verdict(), f.shape(), f.position(), f.kind(), f.entry(), f.operator(),
            f.request(), f.sql(), f.detail().substring(0, extraAt));
    Finding extraOnly =
        new Finding(f.verdict(), f.shape(), f.position(), f.kind(), f.entry(), f.operator(),
            f.request(), f.sql(), f.detail().substring(extraAt));
    return (f.position().isArray() ? "array position · " : "")
        + "missing " + new TreeSet<>(differingRelations(missingOnly))
        + " extra " + new TreeSet<>(differingRelations(extraOnly));
  }

  @SpringBootApplication(scanBasePackageClasses = ComboRow.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
