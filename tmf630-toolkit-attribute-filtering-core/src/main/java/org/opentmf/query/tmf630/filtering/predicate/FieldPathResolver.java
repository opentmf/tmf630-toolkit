package org.opentmf.query.tmf630.filtering.predicate;

import com.querydsl.core.types.dsl.PathBuilder;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

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
      current = resolveFieldType(field);
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

  private Class<?> resolveFieldType(Field field) {
    Class<?> fieldType = field.getType();
    if (!Collection.class.isAssignableFrom(fieldType)) {
      return fieldType;
    }
    Type genericType = field.getGenericType();
    if (genericType instanceof ParameterizedType parameterizedType) {
      Type[] args = parameterizedType.getActualTypeArguments();
      if (args.length == 1 && args[0] instanceof Class<?> elementType) {
        return elementType;
      }
    }
    return Object.class;
  }
}
