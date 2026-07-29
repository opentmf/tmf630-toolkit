package org.opentmf.query.tmf630.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.versioning.Tmf630VersionResolver;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.VersionComparators;

/**
 * JPA implementation of {@link Tmf630VersionResolver}. Builds a JPQL query
 * {@code SELECT e FROM &lt;EntityName&gt; e WHERE e.&lt;idField&gt; = :id}, fetches
 * all matching rows, and picks the max version in-JVM via
 * {@link VersionComparators}. Works with any Hibernate-compatible dialect.
 *
 * <p>The entity's JPQL name is resolved from the JPA metamodel — either the explicit
 * {@code @Entity(name = "...")} value or the class's simple name.
 *
 * <p>Uniform "fetch-all, sort in JVM" strategy across all three
 * {@link org.opentmf.query.tmf630.versioning.VersionOrder} values. See its javadoc
 * for the perf note (works well for typical PLM cardinality of a few versions per
 * logical id).
 */
public class Tmf630JpaVersionResolver implements Tmf630VersionResolver {

  private final EntityManager entityManager;

  public Tmf630JpaVersionResolver(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public <T> Optional<T> resolveLatest(Class<T> type, String logicalId) {
    Tmf630Versioned versioning = requireVersioning(type);
    List<T> matches = fetchAllForLogicalId(type, versioning, logicalId);
    if (matches.isEmpty()) return Optional.empty();
    Comparator<String> comparator = VersionComparators.forOrder(versioning.versionOrder());
    return matches.stream()
        .max((a, b) -> comparator.compare(readVersion(a, versioning), readVersion(b, versioning)));
  }

  @Override
  public <T> Optional<T> resolveSpecific(Class<T> type, String logicalId, String version) {
    if (version == null) return Optional.empty();
    Tmf630Versioned versioning = requireVersioning(type);
    String jpql =
        "SELECT e FROM "
            + resolveEntityName(type)
            + " e WHERE e."
            + versioning.idField()
            + " = :id AND e."
            + versioning.versionField()
            + " = :version";
    List<T> results =
        entityManager
            .createQuery(jpql, type)
            .setParameter("id", logicalId)
            .setParameter("version", version)
            .setMaxResults(1)
            .getResultList();
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  private <T> List<T> fetchAllForLogicalId(
      Class<T> type, Tmf630Versioned versioning, String logicalId) {
    String jpql =
        "SELECT e FROM "
            + resolveEntityName(type)
            + " e WHERE e."
            + versioning.idField()
            + " = :id";
    return entityManager
        .createQuery(jpql, type)
        .setParameter("id", logicalId)
        .getResultList();
  }

  private String resolveEntityName(Class<?> type) {
    for (EntityType<?> entity : entityManager.getMetamodel().getEntities()) {
      if (entity.getJavaType().equals(type)) {
        return entity.getName();
      }
    }
    throw new IllegalStateException(
        "Type " + type.getName() + " is not a JPA-managed entity; version resolution needs one.");
  }

  private static Tmf630Versioned requireVersioning(Class<?> type) {
    Tmf630Versioned versioning = type.getAnnotation(Tmf630Versioned.class);
    if (versioning == null) {
      throw new IllegalStateException(
          "Type " + type.getName() + " is not @Tmf630Versioned — cannot resolve versions.");
    }
    return versioning;
  }

  @SuppressWarnings("java:S3011") // toolkit must read user-declared entity field regardless of visibility
  private static String readVersion(Object entity, Tmf630Versioned versioning) {
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
