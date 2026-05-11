package org.opentmf.query.tmf630.mongo;

import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;

public final class MongoFieldResolver {

  private final MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> ctx;

  public MongoFieldResolver(
      MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> ctx) {
    this.ctx = ctx;
  }

  public static MongoFieldResolver passthrough() {
    return new MongoFieldResolver(null);
  }

  public String resolveBsonPath(Class<?> rootEntity, String dottedJavaPath) {
    if (ctx == null
        || rootEntity == null
        || dottedJavaPath == null
        || dottedJavaPath.isEmpty()) {
      return dottedJavaPath;
    }
    String[] segments = dottedJavaPath.split("\\.", -1);
    StringBuilder result = new StringBuilder();
    MongoPersistentEntity<?> currentEntity = ctx.getPersistentEntity(rootEntity);

    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i];
      if (currentEntity == null) {
        appendRemaining(result, segments, i);
        return result.toString();
      }
      MongoPersistentProperty property = currentEntity.getPersistentProperty(segment);
      if (property == null) {
        appendRemaining(result, segments, i);
        return result.toString();
      }
      if (!result.isEmpty()) {
        result.append('.');
      }
      result.append(property.getFieldName());
      currentEntity = ctx.getPersistentEntity(property.getActualType());
    }
    return result.toString();
  }

  public Class<?> getElementTypeAtPath(Class<?> rootEntity, String dottedJavaPath) {
    if (ctx == null
        || rootEntity == null
        || dottedJavaPath == null
        || dottedJavaPath.isEmpty()) {
      return rootEntity;
    }
    String[] segments = dottedJavaPath.split("\\.", -1);
    Class<?> currentType = rootEntity;
    MongoPersistentEntity<?> currentEntity = ctx.getPersistentEntity(currentType);

    for (String segment : segments) {
      if (currentEntity == null) {
        return null;
      }
      MongoPersistentProperty property = currentEntity.getPersistentProperty(segment);
      if (property == null) {
        return null;
      }
      currentType = property.getActualType();
      currentEntity = ctx.getPersistentEntity(currentType);
    }
    return currentType;
  }

  /**
   * Returns true if any non-final segment of {@code dottedJavaPath} resolves to a
   * collection-typed property on the given root entity. Used by the aggregation
   * translator to decide whether a {@code FieldRef} inside a {@code Coercion}
   * needs an {@code $arrayElemAt} projection — Mongo's path expressions
   * auto-project across arrays, returning an array of values that then breaks
   * {@code $convert}.
   *
   * <p>Returns false when there is no mapping context, the root entity is null,
   * the path is null/empty, has no intermediate segments, or any segment can't
   * be resolved against the mapping. Falsey-on-unknown is intentional: we'd
   * rather emit a slightly suboptimal expression than block a query.
   */
  public boolean hasArrayIntermediate(Class<?> rootEntity, String dottedJavaPath) {
    if (ctx == null
        || rootEntity == null
        || dottedJavaPath == null
        || dottedJavaPath.isEmpty()) {
      return false;
    }
    String[] segments = dottedJavaPath.split("\\.", -1);
    if (segments.length < 2) {
      return false;
    }
    MongoPersistentEntity<?> currentEntity = ctx.getPersistentEntity(rootEntity);
    for (int i = 0; i < segments.length - 1; i++) {
      if (currentEntity == null) {
        return false;
      }
      MongoPersistentProperty property = currentEntity.getPersistentProperty(segments[i]);
      if (property == null) {
        return false;
      }
      if (property.isCollectionLike()) {
        return true;
      }
      currentEntity = ctx.getPersistentEntity(property.getActualType());
    }
    return false;
  }

  private static void appendRemaining(StringBuilder result, String[] segments, int fromIndex) {
    for (int j = fromIndex; j < segments.length; j++) {
      if (!result.isEmpty()) {
        result.append('.');
      }
      result.append(segments[j]);
    }
  }
}
