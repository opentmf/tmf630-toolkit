package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JsonbEntityRegistryTest {

  @Test
  @DisplayName("register and lookup by row and domain types")
  void registerAndLookup() {
    JsonbEntityRegistry registry = new JsonbEntityRegistry();
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(TestRow.class);
    registry.register(metadata);

    assertThat(registry.forRowType(TestRow.class)).contains(metadata);
    assertThat(registry.forDomainType(TestDomain.class)).contains(metadata);
    assertThat(registry.isJsonbBacked(TestRow.class)).isTrue();
    assertThat(registry.isJsonbBacked(TestDomain.class)).isTrue();
    assertThat(registry.isJsonbBacked(String.class)).isFalse();
    assertThat(registry.all()).containsExactly(metadata);
  }

  @Test
  @DisplayName("empty registry returns Optional.empty for lookups")
  void emptyRegistry() {
    JsonbEntityRegistry registry = new JsonbEntityRegistry();
    assertThat(registry.forRowType(TestRow.class)).isEmpty();
    assertThat(registry.forDomainType(TestDomain.class)).isEmpty();
    assertThat(registry.isJsonbBacked(TestRow.class)).isFalse();
    assertThat(registry.all()).isEmpty();
  }

  static class TestDomain {}

  @Tmf630JsonbBacked(domainType = TestDomain.class)
  static class TestRow {
    @Id private String id;
    JsonNode payload;
  }
}
