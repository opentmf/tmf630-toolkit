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
    boolean leafIsCollection = false;
    StringBuilder resolvedPath = new StringBuilder();
    String[] segments = fieldPath.split("\\.");
    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i];
      int bracket = segment.indexOf('[');
      String name = bracket < 0 ? segment : segment.substring(0, bracket);
      String indexDigits = bracket < 0 ? null : extractIndexDigits(segment, bracket, fieldPath);

      Field field = findField(current, name);
      if (field == null) {
        throw new TmfFilteringException("Unknown field path: " + fieldPath);
      }

      if (resolvedPath.length() > 0) {
        resolvedPath.append('.');
      }
      resolvedPath.append(name);

      boolean fieldIsCollection = Collection.class.isAssignableFrom(field.getType());
      if (indexDigits != null) {
        if (!fieldIsCollection) {
          throw new TmfFilteringException(
              "Positional index [N] requires a collection field: " + fieldPath);
        }
        resolvedPath.append('.').append(indexDigits);
      }
      boolean isLastSegment = i == segments.length - 1;
      leafIsCollection = isLastSegment && fieldIsCollection && indexDigits == null;
      current = resolveFieldType(field);
    }

    return new ResolvedField(resolvedPath.toString(), current, leafIsCollection);
  }

  private String extractIndexDigits(String segment, int bracket, String fullPath) {
    if (!segment.endsWith("]")) {
      throw new TmfFilteringException("Malformed positional index in field path: " + fullPath);
    }
    String digits = segment.substring(bracket + 1, segment.length() - 1);
    if (digits.isEmpty()) {
      throw new TmfFilteringException("Positional index [N] requires digits: " + fullPath);
    }
    for (int i = 0; i < digits.length(); i++) {
      if (!Character.isDigit(digits.charAt(i))) {
        throw new TmfFilteringException("Positional index [N] must be numeric: " + fullPath);
      }
    }
    return digits;
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
