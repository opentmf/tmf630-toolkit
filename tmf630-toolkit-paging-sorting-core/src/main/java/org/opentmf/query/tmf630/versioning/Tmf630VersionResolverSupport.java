package org.opentmf.query.tmf630.versioning;

import java.lang.reflect.Field;

/**
 * Package-shared helpers for {@link Tmf630VersionResolver} implementations across the
 * three backends (JPA, Mongo, JSONB). Extracted here so each backend's resolver stays
 * focused on its persistence-layer specifics (JPQL, {@code MongoOperations},
 * {@code JdbcClient}) without repeating the annotation lookup and reflective version
 * read that every implementation needs.
 */
public final class Tmf630VersionResolverSupport {

  private Tmf630VersionResolverSupport() {}

  /**
   * Reads {@link Tmf630Versioned} from {@code type} and throws
   * {@link IllegalStateException} with a diagnostic message if it is absent — every
   * backend resolver requires it before it can address versioned rows.
   */
  public static Tmf630Versioned requireVersioning(Class<?> type) {
    Tmf630Versioned versioning = type.getAnnotation(Tmf630Versioned.class);
    if (versioning == null) {
      throw new IllegalStateException(
          "Type " + type.getName() + " is not @Tmf630Versioned — cannot resolve versions.");
    }
    return versioning;
  }

  /**
   * Reads the value of {@link Tmf630Versioned#versionField()} from the given entity via
   * reflection, walking up the superclass chain until the field is found. Returns
   * {@code null} when the value is {@code null}; throws {@link IllegalStateException}
   * with a diagnostic message when the field is absent or unreadable.
   */
  @SuppressWarnings("java:S3011") // toolkit must read user-declared entity field regardless of visibility
  public static String readVersion(Object entity, Tmf630Versioned versioning) {
    try {
      Field field = findField(entity.getClass(), versioning.versionField());
      field.setAccessible(true);
      Object value = field.get(entity);
      return value == null ? null : value.toString();
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalStateException(
          "Unable to read version field '"
              + versioning.versionField()
              + "' on "
              + entity.getClass().getName(),
          e);
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
