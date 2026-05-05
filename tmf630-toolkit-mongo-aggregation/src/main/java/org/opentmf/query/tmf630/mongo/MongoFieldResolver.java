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

  private static void appendRemaining(StringBuilder result, String[] segments, int fromIndex) {
    for (int j = fromIndex; j < segments.length; j++) {
      if (!result.isEmpty()) {
        result.append('.');
      }
      result.append(segments[j]);
    }
  }
}
