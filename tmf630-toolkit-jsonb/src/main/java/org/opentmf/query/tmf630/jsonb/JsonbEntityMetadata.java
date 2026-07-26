package org.opentmf.query.tmf630.jsonb;

import java.lang.reflect.Field;
import java.util.Optional;
import org.springframework.util.Assert;

/**
 * Runtime descriptor for a row entity annotated with {@link Tmf630JsonbBacked}. Captured
 * once per row entity via reflection at bean-registration time; consumed by the
 * predicate/sort translators in later Phase (b) sub-milestones.
 *
 * <p>Immutable value object; created via {@link #of(Class)} which performs the reflection
 * scan and validation. A {@link Tmf630JsonbConfigurationException} is raised if the row
 * entity fails validation (annotation missing, payload field absent, etc.).
 */
public record JsonbEntityMetadata(
    Class<?> rowType,
    Class<?> domainType,
    String payloadField,
    JsonbAuditColumns auditColumns) {

  public JsonbEntityMetadata {
    Assert.notNull(rowType, "rowType must not be null");
    Assert.notNull(domainType, "domainType must not be null");
    Assert.hasText(payloadField, "payloadField must not be blank");
    Assert.notNull(auditColumns, "auditColumns must not be null");
  }

  /**
   * Introspects a candidate row entity and produces its metadata, or throws
   * {@link Tmf630JsonbConfigurationException} if it is not a valid JSONB-backed entity.
   *
   * <p>Validations performed:
   *
   * <ul>
   *   <li>The class carries {@code @Tmf630JsonbBacked}.
   *   <li>A field named per {@code @Tmf630JsonbBacked#payloadField} exists (walks
   *       superclasses).
   *   <li>The domain type from the annotation is a concrete class (not an interface,
   *       not annotation-only).
   * </ul>
   */
  public static JsonbEntityMetadata of(Class<?> rowType) {
    Assert.notNull(rowType, "rowType must not be null");
    Tmf630JsonbBacked annotation = rowType.getAnnotation(Tmf630JsonbBacked.class);
    if (annotation == null) {
      throw new Tmf630JsonbConfigurationException(
          "Row entity is not @Tmf630JsonbBacked: " + rowType.getName());
    }
    Class<?> domainType = annotation.domainType();
    if (domainType == null || domainType == Void.class) {
      throw new Tmf630JsonbConfigurationException(
          "@Tmf630JsonbBacked.domainType() must be a concrete class on " + rowType.getName());
    }
    String payloadField = annotation.payloadField();
    if (findField(rowType, payloadField).isEmpty()) {
      throw new Tmf630JsonbConfigurationException(
          "Row entity "
              + rowType.getName()
              + " is missing the payload field '"
              + payloadField
              + "' declared by @Tmf630JsonbBacked.payloadField().");
    }
    return new JsonbEntityMetadata(
        rowType, domainType, payloadField, JsonbAuditColumns.discover(rowType));
  }

  static Optional<Field> findField(Class<?> type, String name) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return Optional.of(cursor.getDeclaredField(name));
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return Optional.empty();
  }
}
