package org.opentmf.query.tmf630.jsonb;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;

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

  private static final Set<String> VERSION_ANNOTATIONS = Set.of(VERSION_JAKARTA, VERSION_JAVAX);

  static JsonbAuditColumns discover(Class<?> rowType) {
    Accumulator acc = new Accumulator();
    for (Class<?> cursor = rowType; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
      for (Field field : cursor.getDeclaredFields()) {
        acc.absorb(field);
      }
    }
    return acc.toRecord();
  }

  private static final class Accumulator {
    Optional<String> createdDate = Optional.empty();
    Optional<String> lastModifiedDate = Optional.empty();
    Optional<String> createdBy = Optional.empty();
    Optional<String> lastModifiedBy = Optional.empty();
    Optional<String> version = Optional.empty();

    void absorb(Field field) {
      for (Annotation annotation : field.getAnnotations()) {
        assignForAnnotation(annotation.annotationType().getName(), field.getName());
      }
    }

    private void assignForAnnotation(String annotationName, String fieldName) {
      if (CREATED_DATE.equals(annotationName)) {
        createdDate = firstNonEmpty(createdDate, fieldName);
      } else if (LAST_MODIFIED_DATE.equals(annotationName)) {
        lastModifiedDate = firstNonEmpty(lastModifiedDate, fieldName);
      } else if (CREATED_BY.equals(annotationName)) {
        createdBy = firstNonEmpty(createdBy, fieldName);
      } else if (LAST_MODIFIED_BY.equals(annotationName)) {
        lastModifiedBy = firstNonEmpty(lastModifiedBy, fieldName);
      } else if (VERSION_ANNOTATIONS.contains(annotationName)) {
        version = firstNonEmpty(version, fieldName);
      }
    }

    JsonbAuditColumns toRecord() {
      return new JsonbAuditColumns(
          createdDate, lastModifiedDate, createdBy, lastModifiedBy, version);
    }
  }

  private static Optional<String> firstNonEmpty(Optional<String> current, String fieldName) {
    return current.isPresent() ? current : Optional.of(fieldName);
  }
}
