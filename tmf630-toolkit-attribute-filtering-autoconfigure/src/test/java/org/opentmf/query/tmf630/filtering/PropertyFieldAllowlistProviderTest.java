package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PropertyFieldAllowlistProviderTest {

  @Test
  void returnsConfiguredAllowlistForEntity() {
    PropertyFieldAllowlistProvider provider =
        new PropertyFieldAllowlistProvider(Map.of("SampleEntity", List.of("id", "name")));

    Set<String> fields = provider.allowedFields(SampleEntity.class);

    assertEquals(Set.of("id", "name"), fields);
  }

  @Test
  void returnsEmptySetForMissingEntityMapping() {
    PropertyFieldAllowlistProvider provider = new PropertyFieldAllowlistProvider(Map.of());
    assertEquals(Set.of(), provider.allowedFields(SampleEntity.class));
  }

  static class SampleEntity {}
}
