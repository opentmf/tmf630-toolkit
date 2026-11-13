package org.opentmf.query.tmf630.jsonb.combo;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.Tmf630PredicateArgumentResolver;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.jsonb.JsonbSortBuilder;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbClauseBuilder;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.repository.support.SpringDataMongodbQuery;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The compatibility report's recorder (TOOLKIT-COMPATIBILITY-REPORT-AGAINST-RELEASE-1): asks one
 * toolkit version every request of the combination suite and of the ledger's samples, and writes
 * each answer (status and rows) to {@code <compat.out>/<compat.run>/answers.tsv}. It judges
 * nothing; {@link CompatComparatorTest} compares the runs. Run only by {@code run.sh}, which sets
 * {@code compat.run}; without it the class is disabled, so no container starts.
 *
 * <p>Backends: JSONB (every suite cell: URL operators, {@code filter=}, Jayway words, sorts, not
 * split and split, plus {@link CompatJsonbSamples}); Mongo (the suite's URL, {@code filter=} and
 * Jayway cells on {@link CompatMongoCombo}, not split, plus the ledger samples); JPA (the suite's
 * top-level cells a typed column can hold on {@link CompatJpaCombo}, plus the ledger samples); and
 * the QueryDSL terminal's acceptance on JSON-typed fields ({@link CompatLedgerJson}).
 *
 * <p>Each suite cell also carries the oracle's rows ({@link ComboOracle}, over the documents the
 * backend holds; {@code -} where it has none), and a request naming a wire-value enum is asked a
 * second time by the constant's name ({@link CompatNames}), the evidence for the wire value.
 * {@link CompatElementSamples} asks typed fields inside arrays, each with its stated rows.
 */
@SpringBootTest(classes = CompatRecorderIT.TestApp.class)
@Testcontainers
@EnabledIfSystemProperty(named = "compat.run", matches = "R[0-9]+")
class CompatRecorderIT {

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.5");

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  /** The kinds a typed JPA column can hold. */
  private static final List<ComboKind> TYPEABLE =
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

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
    String spring = System.getProperty("compat.springProperties", "");
    for (String pair : spring.split(",")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        String key = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        registry.add(key, () -> value);
      }
    }
  }

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private JsonbSortBuilder sortBuilder;
  @Autowired private Tmf630PredicateArgumentResolver resolver;
  @Autowired private FieldPathResolver fieldPathResolver;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private EntityManager em;
  @Autowired private TransactionTemplate tx;

  private final Map<String, CompatAnswer> answers = new TreeMap<>();
  private final Map<String, String> oracles = new TreeMap<>();
  private final Map<String, JsonNode> rows = new LinkedHashMap<>();
  private final Map<String, JsonNode> splitRows = new LinkedHashMap<>();
  /** What the JPA twin's typed columns hold, as documents: a NULL column is an absent key. */
  private final Map<String, JsonNode> jpaDocs = new LinkedHashMap<>();

  @Test
  void recordEveryAnswer() throws IOException {
    long start = System.currentTimeMillis();
    ComboSeed.documents(rows, splitRows);
    ComboSeed.write(jdbc, rows, splitRows);
    seedMongoTwin();
    tx.executeWithoutResult(status -> seedJpaTwin());
    CompatLedgerSamples.seedMongo(mongoTemplate);
    tx.executeWithoutResult(status -> CompatLedgerSamples.seedJpa(em));
    CompatElementSamples.seed(jdbc);
    long seeded = System.currentTimeMillis();

    recordJsonb();
    recordMongo();
    recordJpa();
    recordLedger();
    recordElements();
    write(start, seeded, System.currentTimeMillis());
  }

  // ---- JSONB ----------------------------------------------------------------------------

  private void recordJsonb() {
    for (ComboKind kind : ComboKind.values()) {
      for (ComboPosition position : ComboRequests.NOT_SPLIT) {
        everyOperator("JSONB", false, position, kind, rows, params -> jsonb(false, params));
        everySort(position, kind);
      }
      everyOperator(
          "JSONB", true, ComboPosition.SPLIT, kind, splitRows, params -> jsonb(true, params));
    }
    for (String[] sample : CompatJsonbSamples.REQUESTS) {
      Map<String, String[]> params = Map.of(sample[1], new String[] {sample[2]});
      put("JSONB", "sample", "-", "-", "SAMPLE", sample[0], params, jsonb(false, params));
    }
  }

  private CompatAnswer jsonb(boolean split, Map<String, String[]> params) {
    return split
        ? jsonb(ComboSplitDomain.class, ComboSplitRow.TABLE, params)
        : jsonb(ComboDomain.class, ComboRow.TABLE, params);
  }

  private CompatAnswer jsonb(Class<?> domain, String table, Map<String, String[]> params) {
    JsonbClause where;
    try {
      where = clauseBuilder.build(domain, params);
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
    try {
      return CompatAnswer.rows(
          jdbc.sql("SELECT id FROM " + table + " WHERE " + where.sql())
              .params(where.params())
              .query(String.class)
              .list());
    } catch (DataAccessException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  private void everySort(ComboPosition position, ComboKind kind) {
    if (position == ComboPosition.ARRAY_IN_ARRAY) {
      return;
    }
    for (Sort.Direction direction : Sort.Direction.values()) {
      String path = ComboRequests.sortPath(position, kind);
      String request = "sort=" + (direction == Sort.Direction.DESC ? "-" : "") + path;
      put("JSONB", "not split", position.name(), kind.name(), "SORT", direction.name(),
          request, sort(path, direction));
    }
  }

  private CompatAnswer sort(String path, Sort.Direction direction) {
    JsonbClause order;
    try {
      Class<?> type = fieldPathResolver.resolve(ComboDomain.class, path, true).javaType();
      TmfSort sort = new TmfSort(List.of(new TmfSortTerm(direction, TmfSortTerm.Kind.PLAIN, path)));
      order = sortBuilder.buildOrderByClause(sort, field -> type);
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
    try {
      // the id breaks ties, so equal keys come back in one order on every version
      return CompatAnswer.ordered(
          jdbc.sql("SELECT id FROM " + ComboRow.TABLE + " ORDER BY " + order.sql() + ", id")
              .params(order.params())
              .query(String.class)
              .list());
    } catch (DataAccessException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  // ---- Mongo ----------------------------------------------------------------------------

  private void seedMongoTwin() {
    var collection = mongoTemplate.getDb().getCollection(CompatMongoCombo.COLLECTION);
    collection.drop();
    rows.forEach((id, doc) -> collection.insertOne(Document.parse(doc.toString()).append("_id", id)));
  }

  private void recordMongo() {
    for (ComboKind kind : ComboKind.values()) {
      for (ComboPosition position : ComboRequests.NOT_SPLIT) {
        everyOperator(
            "MONGO", false, position, kind, rows, params -> mongo(CompatMongoCombo.class, params));
      }
    }
  }

  private CompatAnswer mongo(Class<?> root, Map<String, String[]> params) {
    Predicate predicate;
    try {
      predicate = predicate(root, params);
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
    try {
      // Spring Data's own serializer (as a repository's findAll(predicate) uses), then its
      // QueryMapper for the entity; only the ids are read, so no stored value is mapped
      Document filter =
          predicate == null
              ? new Document()
              : new SpringDataMongodbQuery<>(mongoTemplate, root).where(predicate).asDocument();
      Document mapped =
          new QueryMapper(mongoTemplate.getConverter())
              .getMappedObject(
                  filter, mongoTemplate.getConverter().getMappingContext().getPersistentEntity(root));
      List<String> ids = new ArrayList<>();
      mongoTemplate.getCollection(mongoTemplate.getCollectionName(root))
          .find(mapped)
          .projection(new Document("_id", 1))
          .forEach(d -> ids.add(String.valueOf(d.get("_id"))));
      return CompatAnswer.rows(ids);
    } catch (RuntimeException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  // ---- JPA ------------------------------------------------------------------------------

  private void seedJpaTwin() {
    for (ComboKind kind : TYPEABLE) {
      for (String[] stored : kind.storedValues()) {
        if (!stored[0].startsWith("d") && !"nul".equals(stored[0])) {
          continue;
        }
        CompatJpaCombo row = new CompatJpaCombo();
        row.id = "top." + kind.key() + "." + stored[0];
        ObjectNode doc = JSON.createObjectNode();
        if (!"nul".equals(stored[0])) {
          set(row, kind, JSON.readTree(stored[1]));
          doc.set(kind.key(), JSON.readTree(stored[1]));
        }
        em.persist(row);
        jpaDocs.put(row.id, doc);
      }
    }
  }

  /** The declared-type value of {@code value} in {@code kind}'s column. */
  private static void set(CompatJpaCombo row, ComboKind kind, JsonNode value) {
    switch (kind) {
      case STRING -> row.s = value.asString();
      case INTEGER -> row.i = value.intValue();
      case LONG -> row.l = value.longValue();
      case DECIMAL -> row.d = new BigDecimal(value.asString());
      case DOUBLE -> row.f = value.doubleValue();
      case BOOLEAN -> row.b = value.booleanValue();
      case DATE -> row.ld = LocalDate.parse(value.asString());
      case DATE_TIME -> row.t = OffsetDateTime.parse(value.asString());
      case PLAIN_ENUM -> row.e = ComboColor.valueOf(value.asString());
      case WIRE_ENUM -> row.w = wire(value.asString());
      default -> throw new IllegalArgumentException("not typeable: " + kind);
    }
  }

  private static ComboStage wire(String text) {
    for (ComboStage stage : ComboStage.values()) {
      if (stage.wire().equals(text)) {
        return stage;
      }
    }
    throw new IllegalArgumentException(text);
  }

  private void recordJpa() {
    for (ComboKind kind : TYPEABLE) {
      everyOperator(
          "JPA", false, ComboPosition.TOP, kind, jpaDocs, params -> jpa(CompatJpaCombo.class, params));
    }
  }

  private CompatAnswer jpa(Class<?> root, Map<String, String[]> params) {
    Predicate predicate;
    try {
      predicate = predicate(root, params);
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
    PathBuilder<?> path = fieldPathResolver.createRootPath(root);
    try {
      List<String> ids =
          tx.execute(
              status ->
                  new JPAQuery<Object>(em)
                      .select(path.getString("id"))
                      .from(path)
                      .where(predicate == null ? new BooleanBuilder() : predicate)
                      .fetch());
      return CompatAnswer.rows(ids);
    } catch (RuntimeException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  // ---- the ledger's samples ---------------------------------------------------------------

  private void recordLedger() {
    for (String[] sample : CompatLedgerSamples.REQUESTS) {
      Map<String, String[]> params = Map.of(sample[0], new String[] {sample[1]});
      String entry = "filter".equals(sample[0]) ? "FILTER" : "URL";
      put("MONGO", "ledger", "-", "-", entry, sample[0], params, mongo(CompatLedgerDoc.class, params));
      put("JPA", "ledger", "-", "-", entry, sample[0], params, jpa(CompatLedgerRow.class, params));
      put("CORE", "json-typed", "-", "-", entry, sample[0], params, acceptance(params));
    }
  }

  /** The QueryDSL terminal's acceptance only: 200 "accepted", or its 400. */
  private CompatAnswer acceptance(Map<String, String[]> params) {
    try {
      predicate(CompatLedgerJson.class, params);
      return new CompatAnswer(200, "accepted");
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
  }

  // ---- typed fields inside arrays ---------------------------------------------------------

  private void recordElements() {
    for (CompatElementSamples.Request r : CompatElementSamples.REQUESTS) {
      Map<String, String[]> params = Map.of(r.param(), new String[] {r.value()});
      put("JSONB", "elements", r.position(), r.kind(), "URL", r.operator(), params,
          jsonb(CompatElementDomain.class, CompatElementRow.TABLE, params), r.rows());
    }
  }

  // ---- the suite's cells, per backend --------------------------------------------------------

  @FunctionalInterface
  private interface Ask {
    CompatAnswer answer(Map<String, String[]> params);
  }

  /**
   * Every operator of the suite on one backend, with the oracle's rows over {@code docs}, the
   * documents that backend holds; a request on a wire-value enum is asked by name too.
   */
  private void everyOperator(
      String backend,
      boolean split,
      ComboPosition position,
      ComboKind kind,
      Map<String, JsonNode> docs,
      Ask ask) {
    String shape = split ? "split" : "not split";
    for (TmfOperator op : TmfOperator.values()) {
      Map<String, String[]> url = ComboRequests.urlParams(position, kind, op);
      String urlOracle = rowsOf(ComboOracle.urlExpected(docs, position, kind, op));
      put(backend, shape, position.name(), kind.name(), "URL", op.name(), url, ask.answer(url),
          urlOracle);
      askByName(backend, shape, position, kind, "URL", op, url, ask);
      Optional<String> leaf = ComboRequests.filterLeaf(kind, op);
      if (leaf.isPresent()) {
        Map<String, String[]> filter =
            Map.of("filter", new String[] {ComboRequests.filterExpression(position, kind, leaf.get())});
        String filterOracle =
            ComboOracle.filterExpected(docs, position, kind, op).map(this::rowsOf).orElse("-");
        put(backend, shape, position.name(), kind.name(), "FILTER", op.name(), filter,
            ask.answer(filter), filterOracle);
        askByName(backend, shape, position, kind, "FILTER", op, filter, ask);
      }
    }
    for (String[] word : ComboRequests.JAYWAY_OPERATORS_NOT_IN_THE_GRAMMAR) {
      Map<String, String[]> filter =
          Map.of(
              "filter",
              new String[] {
                ComboRequests.filterExpression(position, kind, "%s " + word[0] + " " + word[1])
              });
      put(backend, shape, position.name(), kind.name(), "FILTER", "JAYWAY_" + word[0], filter,
          ask.answer(filter));
    }
  }

  /** The request by the constant's name, when it names a wire value: the evidence for it. */
  private void askByName(
      String backend,
      String shape,
      ComboPosition position,
      ComboKind kind,
      String entry,
      TmfOperator op,
      Map<String, String[]> params,
      Ask ask) {
    if (kind != ComboKind.WIRE_ENUM || !CompatNames.namesAWireValue(ComboRequests.request(params))) {
      return;
    }
    Map<String, String[]> named = CompatNames.byName(params);
    put(backend, shape, position.name(), kind.name(), entry, op.name(), named, ask.answer(named));
  }

  private String rowsOf(Set<String> ids) {
    return String.join(",", new TreeSet<>(ids));
  }

  private Predicate predicate(Class<?> root, Map<String, String[]> params) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/compat");
    params.forEach(request::addParameter);
    try {
      return (Predicate)
          resolver.resolveArgument(parameter(root), null, new ServletWebRequest(request), null);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static MethodParameter parameter(Class<?> root) {
    String name =
        root == CompatMongoCombo.class
            ? "mongoComboRoot"
            : root == CompatJpaCombo.class
                ? "jpaComboRoot"
                : root == CompatLedgerDoc.class
                    ? "mongoLedgerRoot"
                    : root == CompatLedgerRow.class ? "jpaLedgerRoot" : "jsonTypedRoot";
    for (Method method : CompatRecorderIT.class.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return new MethodParameter(method, 0);
      }
    }
    throw new IllegalStateException(name);
  }

  static void mongoComboRoot(@QuerydslPredicate(root = CompatMongoCombo.class) Predicate p) {
    throw new UnsupportedOperationException("signature only");
  }

  static void jpaComboRoot(@QuerydslPredicate(root = CompatJpaCombo.class) Predicate p) {
    throw new UnsupportedOperationException("signature only");
  }

  static void mongoLedgerRoot(@QuerydslPredicate(root = CompatLedgerDoc.class) Predicate p) {
    throw new UnsupportedOperationException("signature only");
  }

  static void jpaLedgerRoot(@QuerydslPredicate(root = CompatLedgerRow.class) Predicate p) {
    throw new UnsupportedOperationException("signature only");
  }

  static void jsonTypedRoot(@QuerydslPredicate(root = CompatLedgerJson.class) Predicate p) {
    throw new UnsupportedOperationException("signature only");
  }

  // ---- output -----------------------------------------------------------------------------

  private void put(
      String backend,
      String shape,
      String position,
      String kind,
      String entry,
      String operator,
      Map<String, String[]> params,
      CompatAnswer answer) {
    put(backend, shape, position, kind, entry, operator, params, answer, "-");
  }

  private void put(
      String backend,
      String shape,
      String position,
      String kind,
      String entry,
      String operator,
      Map<String, String[]> params,
      CompatAnswer answer,
      String oracle) {
    String request = ComboRequests.request(params);
    put(backend, shape, position, kind, entry, operator, request, answer);
    oracles.put(String.join("\t", backend, shape, position, kind, entry, operator, request), oracle);
  }

  private void put(
      String backend,
      String shape,
      String position,
      String kind,
      String entry,
      String operator,
      String request,
      CompatAnswer answer) {
    String key = String.join("\t", backend, shape, position, kind, entry, operator, request);
    answers.put(key, answer);
  }

  private void write(long start, long seeded, long end) throws IOException {
    String run = System.getProperty("compat.run");
    Path dir = Path.of(System.getProperty("compat.out", "target/compat"), run);
    Files.createDirectories(dir);
    List<String> lines = new ArrayList<>();
    lines.add("backend\tshape\tposition\tkind\tentry\toperator\trequest\tstatus\tanswer\toracle");
    answers.forEach(
        (key, a) ->
            lines.add(
                key + "\t" + a.status() + "\t" + a.answer() + "\t"
                    + oracles.getOrDefault(key, "-")));
    Files.write(dir.resolve("answers.tsv"), lines);
    Files.write(
        dir.resolve("meta.tsv"),
        List.of(
            "run\t" + run,
            "toolkit\t" + System.getProperty("compat.toolkitVersion", "?"),
            "springProperties\t" + System.getProperty("compat.springProperties", ""),
            "requests\t" + answers.size(),
            "started\t" + Instant.ofEpochMilli(start),
            "seededMillis\t" + (seeded - start),
            "askedMillis\t" + (end - seeded)));
  }

  @SpringBootApplication(scanBasePackageClasses = CompatRecorderIT.class)
  static class TestApp {
    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }
}
