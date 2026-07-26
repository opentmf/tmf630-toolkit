package org.opentmf.query.tmf630.jsonb;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Reflection-discovered audit / optimistic-lock column descriptor for a JSONB-backed
 * row entity. The toolkit does not prescribe column names — it reads the standard Spring
 * Data JPA auditing annotations ({@code @CreatedDate}, {@code @LastModifiedDate},
 * {@code @CreatedBy}, {@code @LastModifiedBy}) and JPA {@code @Version} directly and
 * stores the field name that each one applies to. See
 * {@code docs/JSONB_BACKEND_DESIGN.md} §1.3.
 *
 * <p>All fields are optional — a row entity may declare none, some, or all of these
 * annotated fields; whichever are absent are represented by {@link Optional#empty()}.
 * The Phase (b.1) autoconfigure uses this descriptor to log a startup summary of what
 * each JSONB-backed entity has configured, and later sub-milestones (write-hook wiring)
 * consult it directly.
 */
public record JsonbAuditColumns(
    Optional<String> createdDateField,
    Optional<String> lastModifiedDateField,
    Optional<String> createdByField,
    Optional<String> lastModifiedByField,
    Optional<String> versionField) {

  private static final String CREATED_DATE = "org.springframework.data.annotation.CreatedDate";
  private static final String LAST_MODIFIED_DATE =
      "org.springframework.data.annotation.LastModifiedDate";
  private static final String CREATED_BY = "org.springframework.data.annotation.CreatedBy";
  private static final String LAST_MODIFIED_BY =
      "org.springframework.data.annotation.LastModifiedBy";
  private static final String VERSION_JAKARTA = "jakarta.persistence.Version";
  private static final String VERSION_JAVAX = "javax.persistence.Version";

  static JsonbAuditColumns discover(Class<?> rowType) {
    Optional<String> createdDate = Optional.empty();
    Optional<String> lastModifiedDate = Optional.empty();
    Optional<String> createdBy = Optional.empty();
    Optional<String> lastModifiedBy = Optional.empty();
    Optional<String> version = Optional.empty();
    Class<?> cursor = rowType;
    while (cursor != null && cursor != Object.class) {
      for (Field field : cursor.getDeclaredFields()) {
        for (Annotation annotation : field.getAnnotations()) {
          String name = annotation.annotationType().getName();
          if (CREATED_DATE.equals(name) && createdDate.isEmpty()) {
            createdDate = Optional.of(field.getName());
          } else if (LAST_MODIFIED_DATE.equals(name) && lastModifiedDate.isEmpty()) {
            lastModifiedDate = Optional.of(field.getName());
          } else if (CREATED_BY.equals(name) && createdBy.isEmpty()) {
            createdBy = Optional.of(field.getName());
          } else if (LAST_MODIFIED_BY.equals(name) && lastModifiedBy.isEmpty()) {
            lastModifiedBy = Optional.of(field.getName());
          } else if ((VERSION_JAKARTA.equals(name) || VERSION_JAVAX.equals(name))
              && version.isEmpty()) {
            version = Optional.of(field.getName());
          }
        }
      }
      cursor = cursor.getSuperclass();
    }
    return new JsonbAuditColumns(
        createdDate, lastModifiedDate, createdBy, lastModifiedBy, version);
  }
}
