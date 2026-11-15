package org.opentmf.query.tmf630.filtering.predicate;

import java.util.Map;
import java.util.Set;

/**
 * Fields of an open JSON type: a {@link Map}, or a Jackson {@code JsonNode} (Jackson 3's
 * {@code tools.jackson.databind.JsonNode} or Jackson 2's {@code com.fasterxml.jackson.databind.JsonNode},
 * recognised by name so the core depends on neither). A TMF characteristic's value is one. A URL or
 * {@code filter=} value for such a field is read as {@link JsonTypedValue} (since 3.6.0, ledger
 * #4246); {@code Object} is not one of them and keeps its released behaviour.
 */
public final class JsonTypedFields {

  private static final Set<String> JSON_NODE_TYPES =
      Set.of("tools.jackson.databind.JsonNode", "com.fasterxml.jackson.databind.JsonNode");

  private JsonTypedFields() {}

  /** Whether a field declared as {@code type} is of an open JSON type. */
  // sonar java:S1872: compared by name so the core compiles against no Jackson version
  @SuppressWarnings("java:S1872")
  public static boolean isJsonTyped(Class<?> type) {
    if (type == null) {
      return false;
    }
    if (Map.class.isAssignableFrom(type)) {
      return true;
    }
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      if (JSON_NODE_TYPES.contains(c.getName())) {
        return true;
      }
    }
    return false;
  }
}
