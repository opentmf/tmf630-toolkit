package org.opentmf.query.tmf630.mongo.split;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Bootstrap-time cache of {@link MongoSplitEntityMetadata} keyed by parent domain type.
 *
 * <p>Populated by {@link Tmf630MongoSplitCollectionAutoConfiguration} on startup: it
 * scans candidate types (either supplied explicitly or discovered via the
 * {@code MongoMappingContext}) and registers any that carry
 * {@link Tmf630MongoSplitBacked}. Discovery is a one-time cost; lookups thereafter are
 * lock-free {@code ConcurrentHashMap} reads.
 *
 * <p>The registry is deliberately Spring-Data-Mongo-agnostic in its public API — it
 * accepts arbitrary {@code Class<?>} inputs. That keeps the metadata construction
 * testable without spinning up a Mongo context.
 */
public class MongoSplitEntityRegistry {

  private static final Logger log = LoggerFactory.getLogger(MongoSplitEntityRegistry.class);

  private final Map<Class<?>, MongoSplitEntityMetadata> byParentType = new ConcurrentHashMap<>();
  private final Map<Class<?>, MongoSplitEntityMetadata> byChildType = new ConcurrentHashMap<>();

  /**
   * Registers metadata for the given parent type if it carries
   * {@link Tmf630MongoSplitBacked}. No-op otherwise. Idempotent.
   */
  public void registerIfBacked(Class<?> candidate) {
    if (!candidate.isAnnotationPresent(Tmf630MongoSplitBacked.class)) {
      return;
    }
    if (byParentType.containsKey(candidate)) {
      return;
    }
    MongoSplitEntityMetadata metadata = build(candidate);
    byParentType.put(candidate, metadata);
    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      byChildType.put(split.childType(), metadata);
    }
    log.info(
        "tmf630-mongo-split: registered {} (collection={}, splits={})",
        candidate.getSimpleName(),
        metadata.parentCollection(),
        metadata.splits().stream().map(MongoSplitCollectionMetadata::fieldName).toList());
  }

  /** Bulk-register variant for boot-time scans. */
  public void registerAll(Collection<Class<?>> candidates) {
    for (Class<?> candidate : candidates) {
      registerIfBacked(candidate);
    }
  }

  public Optional<MongoSplitEntityMetadata> forParentType(Class<?> parentType) {
    return Optional.ofNullable(byParentType.get(parentType));
  }

  public Optional<MongoSplitEntityMetadata> forChildType(Class<?> childType) {
    return Optional.ofNullable(byChildType.get(childType));
  }

  public Collection<MongoSplitEntityMetadata> all() {
    return byParentType.values();
  }

  static MongoSplitEntityMetadata build(Class<?> parentType) {
    String parentCollection = resolveCollectionName(parentType);
    Field idField = findIdField(parentType);
    List<MongoSplitCollectionMetadata> splits = new ArrayList<>();
    for (Field field : allDeclaredFields(parentType)) {
      Tmf630MongoSplitCollection anno = field.getAnnotation(Tmf630MongoSplitCollection.class);
      if (anno == null) continue;
      splits.add(
          new MongoSplitCollectionMetadata(
              field.getName(),
              anno.childType(),
              anno.childCollection(),
              anno.maxInlineItems(),
              anno.parentIdField(),
              anno.itemIdField(),
              anno.itemOrderField(),
              anno.payloadField()));
    }
    if (splits.isEmpty()) {
      throw new IllegalStateException(
          "@Tmf630MongoSplitBacked type "
              + parentType.getName()
              + " declares no @Tmf630MongoSplitCollection fields — "
              + "add at least one, or drop the type-level annotation.");
    }
    return new MongoSplitEntityMetadata(parentType, parentCollection, idField, splits);
  }

  private static String resolveCollectionName(Class<?> parentType) {
    Document doc = parentType.getAnnotation(Document.class);
    if (doc != null) {
      if (!doc.collection().isBlank()) return doc.collection();
      if (!doc.value().isBlank()) return doc.value();
    }
    // Mongo's default: uncapitalized simple name.
    String simple = parentType.getSimpleName();
    return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
  }

  private static Field findIdField(Class<?> parentType) {
    for (Field field : allDeclaredFields(parentType)) {
      if (field.isAnnotationPresent(Id.class) || "id".equals(field.getName())) {
        return field;
      }
    }
    throw new IllegalStateException(
        "@Tmf630MongoSplitBacked type "
            + parentType.getName()
            + " has no @Id field and no field named 'id' — cannot resolve parent identifier.");
  }

  private static List<Field> allDeclaredFields(Class<?> type) {
    List<Field> out = new ArrayList<>();
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      for (Field f : cursor.getDeclaredFields()) {
        out.add(f);
      }
      cursor = cursor.getSuperclass();
    }
    return out;
  }
}
