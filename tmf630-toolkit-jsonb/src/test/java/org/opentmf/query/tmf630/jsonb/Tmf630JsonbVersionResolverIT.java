package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.jsonb.it.VersionedOffering;
import org.opentmf.query.tmf630.jsonb.it.VersionedOfferingRow;
import org.opentmf.query.tmf630.versioning.TmfVersionedId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-Postgres IT for {@link Tmf630JsonbVersionResolver}. Uses SEMVER ordering so
 * the "1.9" vs "1.10" trap is exercised via the in-JVM comparator (SQL {@code MAX}
 * would get it wrong).
 */
@SpringBootTest(
    classes = Tmf630JsonbVersionResolverIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    })
@ActiveProfiles("test")
class Tmf630JsonbVersionResolverIT {

  @Autowired private Tmf630JsonbVersionResolver resolver;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void wipe() {
    jdbcClient.sql("DELETE FROM versioned_offering_row").update();
  }

  @Test
  @DisplayName("resolveLatest picks max semver-ordered version — '1.10' > '1.9'")
  void resolveLatestSemver() {
    seed("row1", "VirtualStorage", "1.0", "Virtual Storage v1");
    seed("row2", "VirtualStorage", "1.9", "Virtual Storage v1.9");
    seed("row3", "VirtualStorage", "1.10", "Virtual Storage v1.10");
    seed("row4", "VirtualStorage", "2.0", "Virtual Storage v2");

    Optional<VersionedOffering> latest =
        resolver.resolveLatest(VersionedOffering.class, "VirtualStorage");
    assertThat(latest).isPresent();
    assertThat(latest.get().getVersion()).isEqualTo("2.0");
    assertThat(latest.get().getName()).isEqualTo("Virtual Storage v2");
  }

  @Test
  @DisplayName("resolveLatest returns empty when no rows match the logical id")
  void resolveLatestEmpty() {
    Optional<VersionedOffering> latest =
        resolver.resolveLatest(VersionedOffering.class, "Nonexistent");
    assertThat(latest).isEmpty();
  }

  @Test
  @DisplayName("resolveSpecific finds the exact (logicalId, version) pair")
  void resolveSpecificMatch() {
    seed("row1", "VirtualStorage", "1.0", "v1");
    seed("row2", "VirtualStorage", "2.0", "v2");

    Optional<VersionedOffering> found =
        resolver.resolveSpecific(VersionedOffering.class, "VirtualStorage", "1.0");
    assertThat(found).isPresent();
    assertThat(found.get().getName()).isEqualTo("v1");
  }

  @Test
  @DisplayName("resolveSpecific returns empty when version doesn't exist for that logical id")
  void resolveSpecificMiss() {
    seed("row1", "VirtualStorage", "1.0", "v1");
    Optional<VersionedOffering> found =
        resolver.resolveSpecific(VersionedOffering.class, "VirtualStorage", "9.9");
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("resolveOrLatest with empty version dispatches to resolveLatest")
  void resolveOrLatestDispatchesToLatest() {
    seed("row1", "VirtualStorage", "1.0", "v1");
    seed("row2", "VirtualStorage", "2.0", "v2");

    Optional<VersionedOffering> found =
        resolver.resolveOrLatest(
            VersionedOffering.class, TmfVersionedId.parse("VirtualStorage"));
    assertThat(found).isPresent();
    assertThat(found.get().getVersion()).isEqualTo("2.0");
  }

  @Test
  @DisplayName("resolveOrLatest with version dispatches to resolveSpecific")
  void resolveOrLatestDispatchesToSpecific() {
    seed("row1", "VirtualStorage", "1.0", "v1");
    seed("row2", "VirtualStorage", "2.0", "v2");

    Optional<VersionedOffering> found =
        resolver.resolveOrLatest(
            VersionedOffering.class, TmfVersionedId.parse("VirtualStorage:(version=1.0)"));
    assertThat(found).isPresent();
    assertThat(found.get().getVersion()).isEqualTo("1.0");
  }

  private void seed(String rowId, String logicalId, String version, String name) {
    VersionedOffering domain = new VersionedOffering();
    domain.setId(logicalId);
    domain.setVersion(version);
    domain.setName(name);
    try {
      String payload = objectMapper.writeValueAsString(domain);
      jdbcClient
          .sql("INSERT INTO versioned_offering_row (row_id, payload) VALUES (?, ?::jsonb)")
          .param(1, rowId)
          .param(2, payload)
          .update();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SpringBootApplication(scanBasePackageClasses = VersionedOfferingRow.class)
  static class TestApp {
    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
