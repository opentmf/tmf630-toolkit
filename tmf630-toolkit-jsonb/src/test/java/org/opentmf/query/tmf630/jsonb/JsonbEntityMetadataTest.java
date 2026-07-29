package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

class JsonbEntityMetadataTest {

  @Test
  @DisplayName("reads @Tmf630JsonbBacked with default payload field name and discovers audit fields")
  void readsAnnotationWithAllAuditFields() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(FullRow.class);
    assertThat(metadata.rowType()).isEqualTo(FullRow.class);
    assertThat(metadata.domainType()).isEqualTo(FullDomain.class);
    assertThat(metadata.payloadField()).isEqualTo("payload");
    assertThat(metadata.auditColumns().createdDateField()).contains("createdAt");
    assertThat(metadata.auditColumns().lastModifiedDateField()).contains("updatedAt");
    assertThat(metadata.auditColumns().createdByField()).contains("createdBy");
    assertThat(metadata.auditColumns().lastModifiedByField()).contains("updatedBy");
    assertThat(metadata.auditColumns().versionField()).contains("optLock");
  }

  @Test
  @DisplayName("audit columns absent when none of the annotations are present")
  void auditColumnsAllEmptyWhenNoAuditAnnotations() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(MinimalRow.class);
    assertThat(metadata.auditColumns().createdDateField()).isEmpty();
    assertThat(metadata.auditColumns().lastModifiedDateField()).isEmpty();
    assertThat(metadata.auditColumns().createdByField()).isEmpty();
    assertThat(metadata.auditColumns().lastModifiedByField()).isEmpty();
    assertThat(metadata.auditColumns().versionField()).isEmpty();
  }

  @Test
  @DisplayName("honors custom payload field name")
  void honorsCustomPayloadFieldName() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(CustomPayloadRow.class);
    assertThat(metadata.payloadField()).isEqualTo("body");
  }

  @Test
  @DisplayName("rejects rowType without @Tmf630JsonbBacked")
  void rejectsUnannotatedClass() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(UnannotatedRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("not @Tmf630JsonbBacked");
  }

  @Test
  @DisplayName("rejects rowType whose payload field is missing")
  void rejectsMissingPayloadField() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(MissingPayloadRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("missing the payload field");
  }

  @Test
  @DisplayName("rejects null rowType")
  void rejectsNullRowType() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- fixtures ---

  static class FullDomain {}

  static class MinimalDomain {}

  @Tmf630JsonbBacked(domainType = FullDomain.class)
  static class FullRow {
    @Id private String id;
    JsonNode payload;

    @CreatedDate private OffsetDateTime createdAt;
    @LastModifiedDate private OffsetDateTime updatedAt;
    @CreatedBy private String createdBy;
    @LastModifiedBy private String updatedBy;
    @Version private Integer optLock;
  }

  @Tmf630JsonbBacked(domainType = MinimalDomain.class)
  static class MinimalRow {
    @Id private String id;
    JsonNode payload;
  }

  @Tmf630JsonbBacked(domainType = MinimalDomain.class, payloadField = "body")
  static class CustomPayloadRow {
    @Id private String id;
    JsonNode body;
  }

  static class UnannotatedRow {
    @Id private String id;
  }

  @Tmf630JsonbBacked(domainType = MinimalDomain.class)
  static class MissingPayloadRow {
    @Id private String id;
    // No `payload` field.
  }
}
