package org.opentmf.query.tmf630.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.jpa.it.VersionedRevisionSpec;
import org.opentmf.query.tmf630.jpa.it.VersionedSpec;
import org.opentmf.query.tmf630.versioning.TmfVersionedId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres IT for {@link Tmf630JpaVersionResolver} against a plain
 * {@code @Entity}. LEX ordering — SQL {@code ORDER BY version DESC} produces the
 * same result in this fixture's data set, but the resolver goes through the
 * uniform in-JVM sort path anyway.
 */
@SpringBootTest(
    classes = Tmf630JpaVersionResolverIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
@Transactional
class Tmf630JpaVersionResolverIT {

  @Autowired private Tmf630JpaVersionResolver resolver;
  @Autowired private EntityManager entityManager;

  @BeforeEach
  void seed() {
    // Persist a few versions for two logical ids.
    entityManager.persist(row("row1", "VirtualStorage", "1.0", "v1"));
    entityManager.persist(row("row2", "VirtualStorage", "2.0", "v2"));
    entityManager.persist(row("row3", "VirtualStorage", "3.0", "v3"));
    entityManager.persist(row("row4", "OtherThing", "1.0", "other-v1"));
    entityManager.persist(revision("rev1", "K1", 1, "r1"));
    entityManager.persist(revision("rev2", "K1", 2, "r2"));
    entityManager.persist(revision("rev3", "K1", 3, "r3"));
    entityManager.persist(revision("rev4", "K2", 1, "k2-r1"));
    entityManager.flush();
  }

  @Test
  @DisplayName("custom field names + Integer version: String version binds to the typed field")
  void customFieldNamesAndIntegerVersion() {
    assertThat(resolver.resolveSpecific(VersionedRevisionSpec.class, "K1", "2"))
        .map(VersionedRevisionSpec::getName)
        .contains("r2");
    assertThat(resolver.resolveSpecific(VersionedRevisionSpec.class, "K1", "9")).isEmpty();
    assertThat(resolver.resolveLatest(VersionedRevisionSpec.class, "K1"))
        .map(VersionedRevisionSpec::getName)
        .contains("r3");
    assertThat(resolver.resolveLatest(VersionedRevisionSpec.class, "K2"))
        .map(VersionedRevisionSpec::getName)
        .contains("k2-r1");
  }

  @Test
  @DisplayName("resolveLatest picks the max LEX-ordered version for the logical id")
  void resolveLatest() {
    Optional<VersionedSpec> latest =
        resolver.resolveLatest(VersionedSpec.class, "VirtualStorage");
    assertThat(latest).isPresent();
    assertThat(latest.get().getVersion()).isEqualTo("3.0");
    assertThat(latest.get().getName()).isEqualTo("v3");
  }

  @Test
  @DisplayName("resolveLatest returns empty for unknown logical id")
  void resolveLatestEmpty() {
    assertThat(resolver.resolveLatest(VersionedSpec.class, "Nonexistent")).isEmpty();
  }

  @Test
  @DisplayName("resolveSpecific returns the exact (logicalId, version) pair")
  void resolveSpecificMatch() {
    Optional<VersionedSpec> found =
        resolver.resolveSpecific(VersionedSpec.class, "VirtualStorage", "2.0");
    assertThat(found).isPresent();
    assertThat(found.get().getName()).isEqualTo("v2");
  }

  @Test
  @DisplayName("resolveSpecific returns empty when the version isn't there")
  void resolveSpecificMiss() {
    assertThat(resolver.resolveSpecific(VersionedSpec.class, "VirtualStorage", "9.9")).isEmpty();
  }

  @Test
  @DisplayName("resolveOrLatest dispatches: version-present → specific, absent → latest")
  void resolveOrLatestDispatches() {
    Optional<VersionedSpec> withVersion =
        resolver.resolveOrLatest(
            VersionedSpec.class, TmfVersionedId.parse("VirtualStorage:(version=1.0)"));
    assertThat(withVersion).isPresent();
    assertThat(withVersion.get().getName()).isEqualTo("v1");

    Optional<VersionedSpec> withoutVersion =
        resolver.resolveOrLatest(
            VersionedSpec.class, TmfVersionedId.parse("VirtualStorage"));
    assertThat(withoutVersion).isPresent();
    assertThat(withoutVersion.get().getVersion()).isEqualTo("3.0");
  }

  @Test
  @DisplayName("scoping is per-logical-id — OtherThing doesn't leak into VirtualStorage results")
  void scoping() {
    Optional<VersionedSpec> other = resolver.resolveLatest(VersionedSpec.class, "OtherThing");
    assertThat(other).isPresent();
    assertThat(other.get().getVersion()).isEqualTo("1.0");
  }

  private static VersionedSpec row(String rowId, String id, String version, String name) {
    VersionedSpec spec = new VersionedSpec();
    spec.setRowId(rowId);
    spec.setId(id);
    spec.setVersion(version);
    spec.setName(name);
    return spec;
  }

  private static VersionedRevisionSpec revision(
      String rowId, String logicalKey, int revision, String name) {
    VersionedRevisionSpec spec = new VersionedRevisionSpec();
    spec.setRowId(rowId);
    spec.setLogicalKey(logicalKey);
    spec.setRevision(revision);
    spec.setName(name);
    return spec;
  }

  @SpringBootApplication(scanBasePackageClasses = VersionedSpec.class)
  static class TestApp {}
}
