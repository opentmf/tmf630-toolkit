package org.opentmf.query.tmf630.filtering.predicate;

import com.querydsl.core.types.dsl.PathBuilder;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

import java.beans.Introspector;
import java.lang.reflect.Field;

public class FieldPathResolver {

  public PathBuilder<?> createRootPath(Class<?> rootEntity) {
    return new PathBuilder<>(rootEntity, Introspector.decapitalize(rootEntity.getSimpleName()));
  }

  public ResolvedField resolve(Class<?> rootEntity, String fieldPath, boolean allowNestedPaths) {
    if (fieldPath == null || fieldPath.isBlank()) {
      throw new TmfFilteringException("Field path must not be blank.");
    }
    if (!allowNestedPaths && fieldPath.contains(".")) {
      throw new TmfFilteringException("Nested field paths are disabled: " + fieldPath);
    }

    Class<?> current = rootEntity;
    for (String segment : fieldPath.split("\\.")) {
      Field field = findField(current, segment);
      if (field == null) {
        throw new TmfFilteringException("Unknown field path: " + fieldPath);
      }
      current = field.getType();
    }

    return new ResolvedField(fieldPath, current);
  }

  private Field findField(Class<?> type, String name) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return cursor.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return null;
  }
}
