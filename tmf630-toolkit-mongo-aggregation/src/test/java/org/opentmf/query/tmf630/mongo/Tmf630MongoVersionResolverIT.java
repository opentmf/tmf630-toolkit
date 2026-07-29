package org.opentmf.query.tmf630.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.TmfVersionedId;
import org.opentmf.query.tmf630.versioning.VersionOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Real-Mongo IT for {@link Tmf630MongoVersionResolver}. Uses NUMERIC_STRING
 * ordering to exercise the DNext-convention numeric-string version case — plain
 * BSON lex sort would produce "1", "10", "2" (wrong); NUMERIC_STRING sorts to
 * "1", "2", "10" (right).
 */
@SpringBootTest(classes = Tmf630MongoVersionResolverIT.TestApp.class)
@Testcontainers
class Tmf630MongoVersionResolverIT {

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private Tmf630MongoVersionResolver resolver;

  @BeforeEach
  void wipe() {
    mongoTemplate.dropCollection(VersionedOfferingMongo.class);
    // Note: same logical id present across multiple docs is legal here because the
    // Mongo _id is the auto-generated per-doc key, not the logical id.
    mongoTemplate.insertAll(
        List.of(
            new VersionedOfferingMongo(null, "VirtualStorage", "0", "v0"),
            new VersionedOfferingMongo(null, "VirtualStorage", "1", "v1"),
            new VersionedOfferingMongo(null, "VirtualStorage", "10", "v10"),
            new VersionedOfferingMongo(null, "VirtualStorage", "13", "v13"),
            new VersionedOfferingMongo(null, "VirtualStorage", "2", "v2"),
            new VersionedOfferingMongo(null, "OtherThing", "1", "other-v1")));
  }

  @Test
  @DisplayName(
      "resolveLatest with NUMERIC_STRING picks max by numeric value — '13' > '10' > '2'")
  void resolveLatestNumeric() {
    Optional<VersionedOfferingMongo> latest =
        resolver.resolveLatest(VersionedOfferingMongo.class, "VirtualStorage");
    assertThat(latest).isPresent();
    assertThat(latest.get().version).isEqualTo("13");
    assertThat(latest.get().name).isEqualTo("v13");
  }

  @Test
  @DisplayName("resolveLatest returns empty for unknown logical id")
  void resolveLatestEmpty() {
    assertThat(resolver.resolveLatest(VersionedOfferingMongo.class, "Nonexistent")).isEmpty();
  }

  @Test
  @DisplayName("resolveSpecific returns the exact version for the given logical id")
  void resolveSpecificMatch() {
    Optional<VersionedOfferingMongo> found =
        resolver.resolveSpecific(VersionedOfferingMongo.class, "VirtualStorage", "2");
    assertThat(found).isPresent();
    assertThat(found.get().name).isEqualTo("v2");
  }

  @Test
  @DisplayName("resolveSpecific returns empty when version doesn't exist for that logical id")
  void resolveSpecificMiss() {
    assertThat(resolver.resolveSpecific(VersionedOfferingMongo.class, "VirtualStorage", "99"))
        .isEmpty();
  }

  @Test
  @DisplayName("resolveOrLatest dispatches to specific when version present, latest otherwise")
  void resolveOrLatestDispatches() {
    Optional<VersionedOfferingMongo> withVersion =
        resolver.resolveOrLatest(
            VersionedOfferingMongo.class, TmfVersionedId.parse("VirtualStorage:(version=1)"));
    assertThat(withVersion).isPresent();
    assertThat(withVersion.get().name).isEqualTo("v1");

    Optional<VersionedOfferingMongo> withoutVersion =
        resolver.resolveOrLatest(
            VersionedOfferingMongo.class, TmfVersionedId.parse("VirtualStorage"));
    assertThat(withoutVersion).isPresent();
    assertThat(withoutVersion.get().version).isEqualTo("13");
  }

  @Test
  @DisplayName("scoping is per-logical-id — 'OtherThing' doesn't leak into VirtualStorage results")
  void resolveLatestScoping() {
    Optional<VersionedOfferingMongo> other =
        resolver.resolveLatest(VersionedOfferingMongo.class, "OtherThing");
    assertThat(other).isPresent();
    assertThat(other.get().version).isEqualTo("1");
  }

  @Document("versionedOffering")
  @Tmf630Versioned(versionOrder = VersionOrder.NUMERIC_STRING)
  @SuppressWarnings({"java:S116", "java:S117"}) // `_id` deliberately mirrors the Mongo document field name
  static class VersionedOfferingMongo {
    @Id String _id;
    String id;
    String version;
    String name;

    VersionedOfferingMongo() {}

    VersionedOfferingMongo(String _id, String id, String version, String name) {
      this._id = _id;
      this.id = id;
      this.version = version;
      this.name = name;
    }
  }

  @SpringBootApplication
  static class TestApp {}
}
