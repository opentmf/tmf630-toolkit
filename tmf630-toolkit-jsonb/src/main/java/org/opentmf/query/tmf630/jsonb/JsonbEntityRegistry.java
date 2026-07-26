package org.opentmf.query.tmf630.jsonb;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of {@link JsonbEntityMetadata} keyed by both row and domain types.
 * Populated at autoconfigure time from all {@link Tmf630JsonbBacked} annotations found
 * on the application classpath; consumed by predicate/sort translators in subsequent
 * Phase (b) sub-milestones (b.2 - b.7). Read-mostly and thread-safe.
 */
public class JsonbEntityRegistry {

  private final Map<Class<?>, JsonbEntityMetadata> byRow = new ConcurrentHashMap<>();
  private final Map<Class<?>, JsonbEntityMetadata> byDomain = new ConcurrentHashMap<>();

  public void register(JsonbEntityMetadata metadata) {
    byRow.put(metadata.rowType(), metadata);
    byDomain.put(metadata.domainType(), metadata);
  }

  public Optional<JsonbEntityMetadata> forRowType(Class<?> rowType) {
    return Optional.ofNullable(byRow.get(rowType));
  }

  public Optional<JsonbEntityMetadata> forDomainType(Class<?> domainType) {
    return Optional.ofNullable(byDomain.get(domainType));
  }

  public Collection<JsonbEntityMetadata> all() {
    return byRow.values();
  }

  public boolean isJsonbBacked(Class<?> type) {
    return byRow.containsKey(type) || byDomain.containsKey(type);
  }
}
