package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.Predicate;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.filtering.it.mongo.MongoJsonTypedDoc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.repository.support.SpringDataMongodbQuery;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.ServletWebRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * URL operators on a field of an open JSON type on Mongo (ledger #4246, (a′), since 3.6.0): the
 * value is read as JSON, as on JSONB ({@code Tmf630JsonbJsonTypedFieldIT}, the same requests and
 * stored values, the same rows). Before 3.6.0 every such request was the core's 400. The predicate
 * is resolved by the toolkit's resolver and sent as a repository's {@code findAll(predicate)} sends
 * it (Spring Data's serializer, then its query mapper); only ids are read back.
 */
@SpringBootTest(
    classes = Tmf630MongoJsonTypedFieldIT.TestApp.class,
    properties = {
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.allow-nested-paths-docdb=true"
    })
@Testcontainers
class Tmf630MongoJsonTypedFieldIT {

  @Container static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  /** The stored values: id suffix → JSON; {@code abs} has no key. */
  static final Map<String, String> STORED = new LinkedHashMap<>();

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

  private static final AtomicBoolean SEEDED = new AtomicBoolean();

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private Tmf630PredicateArgumentResolver resolver;

  /**
   * Seeds once per class run. Not {@code @BeforeAll} with a per-class instance: that resolves the
   * dynamic properties before the container has started.
   */
  @BeforeEach
  void seedOnce() {
    if (!SEEDED.compareAndSet(false, true)) {
      return;
    }
    var collection = mongoTemplate.getDb().getCollection(MongoJsonTypedDoc.COLLECTION);
    collection.drop();
    for (String field : List.of("o", "m", "j")) {
      STORED.forEach(
          (suffix, json) ->
              collection.insertOne(
                  Document.parse(json == null ? "{}" : "{\"" + field + "\":" + json + "}")
                      .append("_id", field + "." + suffix)));
    }
    STORED.forEach(
        (suffix, json) ->
            collection.insertOne(
                Document.parse(
                        "{\"characteristic\":[{\"name\":\"x\""
                            + (json == null ? "" : ",\"value\":" + json)
                            + "},{\"name\":\"y\",\"value\":\"other\"}]}")
                    .append("_id", "c." + suffix)));
  }

  static Stream<Arguments> answers() {
    return Stream.of(
        Arguments.of("j.eq", "42", List.of("j.n42", "j.n42.0", "j.s42")),
        Arguments.of("j.eq", "true", List.of("j.btrue", "j.strue")),
        Arguments.of("j.eq", "alpha", List.of("j.salpha")),
        Arguments.of("j.in", "42,alpha", List.of("j.n42", "j.n42.0", "j.s42", "j.salpha")),
        Arguments.of("j.gt", "10", List.of("j.n42", "j.n42.0")),
        Arguments.of("j.lt", "10", List.of("j.n7")),
        Arguments.of("j.between", "5,42", List.of("j.n42", "j.n42.0", "j.n7")),
        Arguments.of("j.contains", "lph", List.of("j.salpha")),
        Arguments.of("j.startswith", "4", List.of("j.s42")),
        Arguments.of("j.regex", "^4", List.of("j.s42")),
        Arguments.of("j.eqi", "ALPHA", List.of("j.salpha")),
        Arguments.of("m.eq", "42", List.of("m.n42", "m.n42.0", "m.s42")),
        Arguments.of("m.gt", "10", List.of("m.n42", "m.n42.0")),
        Arguments.of("characteristic.value.eq", "42", List.of("c.n42", "c.n42.0", "c.s42")),
        Arguments.of("characteristic.value.gt", "10", List.of("c.n42", "c.n42.0")),
        Arguments.of("o.eq", "42", List.of("o.s42")));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("answers")
  void aJsonTypedFieldReadsTheValueAsJson(String param, String value, List<String> rows) {
    assertThat(ids(param, value)).containsExactlyInAnyOrderElementsOf(rows);
  }

  /**
   * {@code ne} keeps Mongo's released reading (ledger #4247): every document none of whose values
   * is one of the readings, a document without the field included.
   */
  @ParameterizedTest(name = "j.ne=42")
  @MethodSource("notEqual")
  void notEqualIsMongosReading(String param, String value) {
    List<String> ids = ids(param, value);

    assertThat(ids).doesNotContain("j.n42", "j.n42.0", "j.s42").contains("j.salpha", "j.abs", "o.n42");
  }

  static Stream<Arguments> notEqual() {
    return Stream.of(Arguments.of("j.ne", "42"), Arguments.of("j.nin", "42,7"));
  }

  @ParameterizedTest(name = "{0}=alpha")
  @MethodSource("orderings")
  void anOrderingOnANonNumberIsRefused(String param) {
    assertThatThrownBy(() -> ids(param, "alpha"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageStartingWith("Operator requires a number for the JSON-typed field: ");
  }

  static Stream<String> orderings() {
    return Stream.of("j.gt", "m.lte", "characteristic.value.gte");
  }

  private List<String> ids(String param, String value) {
    Predicate predicate = predicate(Map.of(param, new String[] {value}));
    Document filter =
        new SpringDataMongodbQuery<>(mongoTemplate, MongoJsonTypedDoc.class)
            .where(predicate)
            .asDocument();
    Document mapped =
        new QueryMapper(mongoTemplate.getConverter())
            .getMappedObject(
                filter,
                mongoTemplate
                    .getConverter()
                    .getMappingContext()
                    .getPersistentEntity(MongoJsonTypedDoc.class));
    List<String> ids = new ArrayList<>();
    mongoTemplate
        .getCollection(MongoJsonTypedDoc.COLLECTION)
        .find(mapped)
        .projection(new Document("_id", 1))
        .forEach(d -> ids.add(String.valueOf(d.get("_id"))));
    return ids;
  }

  private Predicate predicate(Map<String, String[]> params) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/json-typed");
    params.forEach(request::addParameter);
    try {
      return (Predicate)
          resolver.resolveArgument(parameter(), null, new ServletWebRequest(request), null);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static MethodParameter parameter() throws NoSuchMethodException {
    Method method =
        Tmf630MongoJsonTypedFieldIT.class.getDeclaredMethod("handler", Predicate.class);
    return new MethodParameter(method, 0);
  }

  static void handler(@QuerydslPredicate(root = MongoJsonTypedDoc.class) Predicate predicate) {
    throw new UnsupportedOperationException("signature only");
  }

  @SpringBootApplication(
      scanBasePackageClasses = MongoJsonTypedDoc.class,
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
      })
  static class TestApp {}
}
