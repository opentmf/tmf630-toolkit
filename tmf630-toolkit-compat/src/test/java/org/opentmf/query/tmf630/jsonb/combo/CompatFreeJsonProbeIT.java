package org.opentmf.query.tmf630.jsonb.combo;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.Tmf630PredicateArgumentResolver;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbClauseBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
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

/**
 * PROBE for the design note docs/URL_FILTER_ON_FREE_JSON_VALUE.md (TOOLKIT-URL-FILTER-ON-FREE-JSON-
 * VALUE-1): today's answer of every URL operator on fields of an open JSON type (Object, Map,
 * JsonNode, and a characteristic's JsonNode value inside an array) on JSONB, Mongo and JPA; the
 * candidate forms of option (b) asked directly (SQL and Mongo documents); and their plans on a
 * larger JSONB table. Runs only with {@code -Dcompat.run=FREEJSON}; writes
 * {@code <compat.out>/FREEJSON/probe.tsv}.
 */
@SpringBootTest(classes = CompatRecorderIT.TestApp.class)
@Testcontainers
@EnabledIfSystemProperty(named = "compat.run", matches = "FREEJSON")
class CompatFreeJsonProbeIT {

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.5");

  /** The stored values: id suffix → JSON. JSON null is "nul"; "abs" has no key. */
  private static final Map<String, String> STORED = new LinkedHashMap<>();

  static {
    STORED.put("s42", "\"42\"");
    STORED.put("n42", "42");
    STORED.put("n42.0", "42.0");
    STORED.put("btrue", "true");
    STORED.put("strue", "\"true\"");
    STORED.put("salpha", "\"alpha\"");
    STORED.put("n7", "7");
    STORED.put("obj", "{\"amount\":5}");
    STORED.put("nul", "null");
    STORED.put("abs", null);
  }

  private static final List<String> FIELDS = List.of("o", "m", "j", "characteristic.value");

  /** Operator → the values asked. */
  private static final Map<String, List<String>> OPERATORS = new LinkedHashMap<>();

  static {
    OPERATORS.put("eq", List.of("42", "true", "alpha"));
    OPERATORS.put("ne", List.of("42"));
    OPERATORS.put("in", List.of("42,alpha"));
    OPERATORS.put("gt", List.of("10"));
    OPERATORS.put("lt", List.of("10"));
    OPERATORS.put("contains", List.of("lph"));
    OPERATORS.put("startswith", List.of("4"));
    OPERATORS.put("regex", List.of("^4"));
    OPERATORS.put("isnull", List.of("true"));
    OPERATORS.put("isnotnull", List.of("true"));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
  }

  @Autowired private JdbcClient jdbc;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;
  @Autowired private Tmf630PredicateArgumentResolver resolver;
  @Autowired private FieldPathResolver fieldPathResolver;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private EntityManager em;
  @Autowired private TransactionTemplate tx;

  private final List<String> lines = new ArrayList<>();

  @Test
  void probe() throws IOException {
    seed();
    for (String field : FIELDS) {
      for (Map.Entry<String, List<String>> op : OPERATORS.entrySet()) {
        for (String value : op.getValue()) {
          Map<String, String[]> params = Map.of(field + "." + op.getKey(), new String[] {value});
          String request = field + "." + op.getKey() + "=" + value;
          line("TODAY", "JSONB", request, jsonb(params));
          line("TODAY", "MONGO", request, mongo(params));
          if ("m".equals(field)) {
            line("TODAY", "JPA", request, jpa(params));
          }
        }
      }
    }
    optionB();
    jpaEmulation();
    plans();
    Path dir = Path.of(System.getProperty("compat.out", "target/compat"), "FREEJSON");
    Files.createDirectories(dir);
    Files.write(dir.resolve("probe.tsv"), lines);
  }

  // ---- seed --------------------------------------------------------------------------------

  private void seed() {
    jdbc.sql("TRUNCATE " + FreeJsonRow.TABLE).update();
    jdbc.sql("TRUNCATE " + FreeJsonJpa.TABLE).update();
    var collection = mongoTemplate.getDb().getCollection(FreeJsonDoc.COLLECTION);
    collection.drop();
    for (String field : List.of("o", "m", "j")) {
      STORED.forEach((suffix, json) -> {
        String doc = json == null ? "{}" : "{\"" + field + "\":" + json + "}";
        insert(field + "." + suffix, doc);
        if ("m".equals(field) && json != null) {
          jdbc.sql("INSERT INTO " + FreeJsonJpa.TABLE + " (id, m) VALUES (?, ?::jsonb)")
              .params("m." + suffix, json)
              .update();
        }
      });
    }
    STORED.forEach((suffix, json) -> {
      String value = json == null ? "" : ",\"value\":" + json;
      insert(
          "c." + suffix,
          "{\"characteristic\":[{\"name\":\"x\"" + value + "},{\"name\":\"y\",\"value\":\"other\"}]}");
    });
  }

  private void insert(String id, String doc) {
    jdbc.sql("INSERT INTO " + FreeJsonRow.TABLE + " (id, payload) VALUES (?, ?::jsonb)")
        .params(id, doc)
        .update();
    mongoTemplate
        .getDb()
        .getCollection(FreeJsonDoc.COLLECTION)
        .insertOne(Document.parse(doc).append("_id", id));
  }

  // ---- today ------------------------------------------------------------------------------

  private CompatAnswer jsonb(Map<String, String[]> params) {
    JsonbClause where;
    try {
      where = clauseBuilder.build(FreeJsonDomain.class, params);
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
    return sql("SELECT id FROM " + FreeJsonRow.TABLE + " WHERE " + where.sql(), where.params());
  }

  private CompatAnswer sql(String select, List<Object> params) {
    try {
      return CompatAnswer.rows(jdbc.sql(select).params(params).query(String.class).list());
    } catch (RuntimeException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  private CompatAnswer mongo(Map<String, String[]> params) {
    Predicate predicate;
    try {
      predicate = predicate(FreeJsonDoc.class, params);
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
    try {
      Document filter =
          new SpringDataMongodbQuery<>(mongoTemplate, FreeJsonDoc.class)
              .where(predicate)
              .asDocument();
      Document mapped =
          new QueryMapper(mongoTemplate.getConverter())
              .getMappedObject(
                  filter,
                  mongoTemplate.getConverter().getMappingContext().getPersistentEntity(FreeJsonDoc.class));
      return mongoFind(mapped);
    } catch (RuntimeException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  private CompatAnswer mongoFind(Document filter) {
    try {
      List<String> ids = new ArrayList<>();
      mongoTemplate.getCollection(FreeJsonDoc.COLLECTION)
          .find(filter)
          .projection(new Document("_id", 1))
          .forEach(d -> ids.add(String.valueOf(d.get("_id"))));
      return CompatAnswer.rows(ids);
    } catch (RuntimeException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  private CompatAnswer jpa(Map<String, String[]> params) {
    Predicate predicate;
    try {
      predicate = predicate(FreeJsonJpa.class, params);
    } catch (TmfFilteringException refused) {
      return CompatAnswer.refused(refused.getMessage());
    } catch (RuntimeException crashed) {
      return CompatAnswer.failed(crashed);
    }
    return jpaFetch(predicate);
  }

  private CompatAnswer jpaFetch(Predicate predicate) {
    PathBuilder<?> path = fieldPathResolver.createRootPath(FreeJsonJpa.class);
    try {
      return CompatAnswer.rows(
          tx.execute(
              status ->
                  new JPAQuery<Object>(em)
                      .select(path.getString("id"))
                      .from(path)
                      .where(predicate)
                      .fetch()));
    } catch (RuntimeException driver) {
      return CompatAnswer.failed(driver);
    }
  }

  // ---- option (b), asked directly ------------------------------------------------------------

  private void optionB() {
    for (String[] c :
        new String[][] {
          {"j=42", "payload->'j' IN ('42'::jsonb, '\"42\"'::jsonb)"},
          {"j=true", "payload->'j' IN ('true'::jsonb, '\"true\"'::jsonb)"},
          {"j=alpha", "payload->'j' = '\"alpha\"'::jsonb"},
          {"j=42 (containment)", "(payload @> '{\"j\":42}' OR payload @> '{\"j\":\"42\"}')"},
          {"j.gt=10 (numbers only)", "payload @? '$ ? (@.j > 10)'"},
          {"j.lt=10 (numbers only)", "payload @? '$ ? (@.j < 10)'"},
          {"characteristic.value=42", "payload @? '$.characteristic[*] ? (@.value == 42 || @.value == \"42\")'"},
          {"characteristic.value=42 (containment)",
            "(payload @> '{\"characteristic\":[{\"value\":42}]}' OR payload @> '{\"characteristic\":[{\"value\":\"42\"}]}')"},
          {"characteristic.value.gt=10", "payload @? '$.characteristic[*] ? (@.value > 10)'"},
          {"correlated name=x value=42", "payload @? '$.characteristic[*] ? (@.name == \"x\" && (@.value == 42 || @.value == \"42\"))'"}
        }) {
      line("OPTION_B", "JSONB", c[0], sql("SELECT id FROM " + FreeJsonRow.TABLE + " WHERE " + c[1].replace("@?", "@??"), List.of()));
    }
    for (String[] c :
        new String[][] {
          {"j=42", "{\"j\": {\"$in\": [\"42\", 42]}}"},
          {"j=true", "{\"j\": {\"$in\": [\"true\", true]}}"},
          {"j=alpha", "{\"j\": \"alpha\"}"},
          {"j.gt=10 (numbers only)", "{\"j\": {\"$gt\": 10}}"},
          {"j.lt=10 (numbers only)", "{\"j\": {\"$lt\": 10}}"},
          {"characteristic.value=42", "{\"characteristic.value\": {\"$in\": [\"42\", 42]}}"},
          {"characteristic.value.gt=10", "{\"characteristic.value\": {\"$gt\": 10}}"},
          {"correlated name=x value=42", "{\"characteristic\": {\"$elemMatch\": {\"name\": \"x\", \"value\": {\"$in\": [\"42\", 42]}}}}"}
        }) {
      line("OPTION_B", "MONGO", c[0], mongoFind(Document.parse(c[1])));
    }
  }

  /** Option (a) on JPA: the URL string bound against the JSON column, as Object is bound. */
  private void jpaEmulation() {
    PathBuilder<?> path = fieldPathResolver.createRootPath(FreeJsonJpa.class);
    line("OPTION_A", "JPA", "m=42 (string bound to the JSON column)",
        jpaFetch(Expressions.path(Object.class, path, "m").eq((Object) "42")));
  }

  // ---- plans on a larger table ------------------------------------------------------------------

  private void plans() {
    String t = "free_json_big";
    jdbc.sql("DROP TABLE IF EXISTS " + t).update();
    jdbc.sql("CREATE TABLE " + t + " (id text primary key, payload jsonb not null)").update();
    jdbc.sql(
            "INSERT INTO " + t + " SELECT 'r' || g, jsonb_build_object('j', CASE WHEN g % 3 = 0 THEN to_jsonb(g % 1000) ELSE to_jsonb((g % 1000)::text) END,"
                + " 'characteristic', jsonb_build_array(jsonb_build_object('name', 'k' || (g % 7), 'value', CASE WHEN g % 2 = 0 THEN to_jsonb(g % 1000) ELSE to_jsonb((g % 1000)::text) END),"
                + " jsonb_build_object('name', 'z', 'value', 'w')))"
                + " FROM generate_series(1, 20000) g")
        .update();
    jdbc.sql("CREATE INDEX free_json_big_gin ON " + t + " USING gin (payload jsonb_path_ops)").update();
    jdbc.sql("CREATE INDEX free_json_big_j ON " + t + " ((payload->>'j'))").update();
    jdbc.sql("ANALYZE " + t).update();
    for (String[] c :
        new String[][] {
          {"today-like j=42 (text, ->>)", "payload->>'j' = '42'"},
          {"b j=42 (IN of jsonb)", "payload->'j' IN ('42'::jsonb, '\"42\"'::jsonb)"},
          {"b j=42 (containment)", "(payload @> '{\"j\":42}' OR payload @> '{\"j\":\"42\"}')"},
          {"b characteristic.value=42 (jsonpath)", "payload @? '$.characteristic[*] ? (@.value == 42 || @.value == \"42\")'"},
          {"b characteristic.value=42 (containment)",
            "(payload @> '{\"characteristic\":[{\"value\":42}]}' OR payload @> '{\"characteristic\":[{\"value\":\"42\"}]}')"},
          {"b correlated name=k3 value=42", "payload @? '$.characteristic[*] ? (@.name == \"k3\" && (@.value == 42 || @.value == \"42\"))'"},
          {"b characteristic.value.gt=990", "payload @? '$.characteristic[*] ? (@.value > 990)'"}
        }) {
      String where = c[1].replace("@?", "@??");
      long count = jdbc.sql("SELECT count(*) FROM " + t + " WHERE " + where).query(Long.class).single();
      String plan =
          String.join(" / ",
              jdbc.sql("EXPLAIN (COSTS OFF) SELECT id FROM " + t + " WHERE " + where).query(String.class).list())
              .replaceAll("\\s+", " ");
      lines.add("PLAN\tJSONB\t" + c[0] + "\t" + count + "\t" + plan);
      System.out.println("FREEJSON PLAN " + c[0] + " :: rows=" + count + " :: " + plan);
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private void line(String part, String backend, String request, CompatAnswer answer) {
    lines.add(part + "\t" + backend + "\t" + request + "\t" + answer.status() + "\t" + answer.answer());
    System.out.println("FREEJSON " + part + " " + backend + " " + request + " :: " + answer.status() + " " + answer.answer());
  }

  private Predicate predicate(Class<?> root, Map<String, String[]> params) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
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
    String name = root == FreeJsonDoc.class ? "mongoRoot" : "jpaRoot";
    for (Method method : CompatFreeJsonProbeIT.class.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return new MethodParameter(method, 0);
      }
    }
    throw new IllegalStateException(name);
  }

  static void mongoRoot(@QuerydslPredicate(root = FreeJsonDoc.class) Predicate p) {
    throw new UnsupportedOperationException("signature only");
  }

  static void jpaRoot(@QuerydslPredicate(root = FreeJsonJpa.class) Predicate p) {
    throw new UnsupportedOperationException("signature only");
  }
}
