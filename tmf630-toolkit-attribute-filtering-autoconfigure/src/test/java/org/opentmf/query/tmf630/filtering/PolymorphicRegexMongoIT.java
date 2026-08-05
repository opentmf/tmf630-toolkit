package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.it.mongo.PolymorphicMongoEntity;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.SpringDataMongodbQuery;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Regression pin for the colleague's 2026-08-05 report: {@code .regex} on a
 * {@code characteristic.value} field declared as {@code Object} (TMF-620 catalog's polymorphic
 * scalar) previously failed at {@link PredicateFactory#build}'s static-type gate with HTTP 400.
 * After widening {@code validateRegex} to accept {@code Object}/{@code Serializable}, the same
 * predicate must reach Mongo and let {@code $regex} do its native mixed-type matching: string
 * values matching the pattern are returned; non-string values (Integer, Boolean, nested docs)
 * simply don't match — no server error.
 *
 * <p>Covers both grammars implicitly: the attribute-shorthand path and the JsonPath path both
 * route through the same {@link PredicateFactory#build} call, so exercising the predicate
 * factory directly against a real Mongo query pins the behavior for both.
 */
@SpringBootTest(classes = PolymorphicRegexMongoIT.TestApp.class)
@Testcontainers
class PolymorphicRegexMongoIT {

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MongoTemplate mongoTemplate;

  private static final String COLLECTION = "polymorphic_regex_entity";

  @BeforeEach
  void seed() {
    mongoTemplate.getDb().getCollection(COLLECTION).drop();
    mongoTemplate
        .getDb()
        .getCollection(COLLECTION)
        .insertMany(
            List.of(
                doc("p1", charDoc("plan", "Infinity_Giga")),
                doc("p2", charDoc("plan", "Infinity_Mega")),
                doc("p3", charDoc("plan", "Standard_Kilo")),
                doc("p4", charDoc("quota", 1024)),
                doc("p5", charDoc("active", true)),
                doc("p6", charDoc("nested", new Document("kind", "Infinity_Weird")))));
  }

  @Test
  void regexOnObjectValueMatchesOnlyStringDocuments() {
    Predicate predicate = regexPredicate(TmfOperator.REGEX, "^Infinity_.*");

    Set<String> hits = ids(execute(predicate));

    // p1 and p2 match by native Mongo $regex on the string values; p3's string doesn't match;
    // p4/p5/p6 carry non-string values, so Mongo's $regex silently skips them (the behavior we
    // are pinning — pre-fix this whole call failed at the static-type gate before any Mongo op
    // was issued).
    assertEquals(Set.of("p1", "p2"), hits);
  }

  @Test
  void regexIgnoreCaseOnObjectValueRespectsFlag() {
    Predicate predicate = regexPredicate(TmfOperator.REGEXI, "^infinity_.*");

    Set<String> hits = ids(execute(predicate));

    assertEquals(Set.of("p1", "p2"), hits);
  }

  private Predicate regexPredicate(TmfOperator op, String pattern) {
    PredicateFactory factory = new PredicateFactory(true, 256);
    PathBuilder<PolymorphicMongoEntity> root =
        new PathBuilder<>(PolymorphicMongoEntity.class, "polymorphicMongoEntity");
    return factory.build(
        root, new ResolvedField("characteristic.value", Object.class), op, pattern);
  }

  private List<PolymorphicMongoEntity> execute(Predicate predicate) {
    return new SpringDataMongodbQuery<>(mongoTemplate, PolymorphicMongoEntity.class)
        .where(predicate)
        .fetch();
  }

  private static Set<String> ids(List<PolymorphicMongoEntity> entities) {
    return entities.stream().map(e -> e.id).collect(Collectors.toSet());
  }

  private static Document doc(String id, Document characteristic) {
    return new Document("_id", id).append("characteristic", List.of(characteristic));
  }

  private static Document charDoc(String name, Object value) {
    return new Document("name", name).append("value", value);
  }

  @SpringBootApplication
  static class TestApp {}
}
