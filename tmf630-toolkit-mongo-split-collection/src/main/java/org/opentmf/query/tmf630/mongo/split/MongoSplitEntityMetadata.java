package org.opentmf.query.tmf630.mongo.split;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable metadata for a {@link Tmf630MongoSplitBacked} parent document type.
 *
 * @param parentType the parent domain class
 * @param parentCollection the Mongo collection storing parent documents
 * @param idField reflection handle for the parent's {@code _id} field (populated at
 *     scan time so the write executor doesn't reflect on every request)
 * @param splits the split-collection specs declared on this parent, in declaration order
 */
public record MongoSplitEntityMetadata(
    Class<?> parentType,
    String parentCollection,
    Field idField,
    List<MongoSplitCollectionMetadata> splits) {

  public MongoSplitEntityMetadata {
    splits = List.copyOf(splits);
  }

  /**
   * Returns the split-collection metadata for the given field name, or {@code null} if
   * this parent has no split with that name.
   */
  public MongoSplitCollectionMetadata splitByFieldName(String fieldName) {
    for (MongoSplitCollectionMetadata split : splits) {
      if (split.fieldName().equals(fieldName)) {
        return split;
      }
    }
    return null;
  }

  /**
   * Returns the split-collection metadata whose child type matches the given class, or
   * {@code null} if none does.
   */
  public MongoSplitCollectionMetadata splitByChildType(Class<?> childType) {
    for (MongoSplitCollectionMetadata split : splits) {
      if (split.childType().equals(childType)) {
        return split;
      }
    }
    return null;
  }

  /** Convenience: returns the {@code _id} value of the given parent instance. */
  @SuppressWarnings("java:S3011") // toolkit must read user-declared entity field regardless of visibility
  public Object idOf(Object parent) {
    try {
      idField.setAccessible(true);
      return idField.get(parent);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(
          "Unable to read id field '" + idField.getName() + "' on " + parentType.getName(), e);
    }
  }

  /**
   * Convenience: sets the split field on the parent to the given value (used to strip
   * split content from the parent before persisting).
   */
  @SuppressWarnings("java:S3011") // toolkit must write user-declared entity field regardless of visibility
  public void setSplitField(Object parent, String fieldName, Object value) {
    for (MongoSplitCollectionMetadata split : splits) {
      if (split.fieldName().equals(fieldName)) {
        try {
          Field f = findField(parentType, fieldName);
          f.setAccessible(true);
          f.set(parent, value);
          return;
        } catch (IllegalAccessException | NoSuchFieldException e) {
          throw new IllegalStateException(
              "Unable to write split field '" + fieldName + "' on " + parentType.getName(), e);
        }
      }
    }
  }

  /**
   * Convenience: returns the current value of the split field on the given parent.
   * Returns {@code null} if the field is absent or unreadable — caller decides how to
   * interpret {@code null} (usually as an empty child list).
   */
  @SuppressWarnings({"unchecked", "java:S3011", "java:S1168"}) // null intentionally distinguishes "field unset" from empty list per javadoc
  public List<Object> readSplitField(Object parent, String fieldName) {
    try {
      Field f = findField(parentType, fieldName);
      f.setAccessible(true);
      Object v = f.get(parent);
      if (v == null) return null;
      if (v instanceof List<?> list) return new ArrayList<>((List<Object>) list);
      throw new IllegalStateException(
          "Split field '" + fieldName + "' must be a List; got " + v.getClass().getName());
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalStateException(
          "Unable to read split field '" + fieldName + "' on " + parentType.getName(), e);
    }
  }

  private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return cursor.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }
}
