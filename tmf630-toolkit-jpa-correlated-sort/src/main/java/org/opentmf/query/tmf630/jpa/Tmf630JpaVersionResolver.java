package org.opentmf.query.tmf630.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.versioning.Tmf630VersionResolver;
import org.opentmf.query.tmf630.versioning.Tmf630VersionResolverSupport;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.VersionComparators;
import org.springframework.util.ClassUtils;

/**
 * JPA implementation of {@link Tmf630VersionResolver}. Selects the rows of the entity whose
 * {@code idField} equals the logical id, fetches all of them, and picks the max version in-JVM
 * via {@link VersionComparators}. Works with any Hibernate-compatible dialect.
 *
 * <p>The query is built with the Criteria API from the JPA metamodel — never from a query
 * string. The entity is the managed type itself; {@code idField} and {@code versionField}
 * are resolved as attributes of it (a name that is not an attribute fails there); the logical
 * id and version are bound as named parameters. Each parameter is typed as its attribute's
 * Java type, so the caller's String value binds exactly as the equivalent JPQL
 * {@code e.<field> = :param} binds it.
 *
 * <p>Uniform "fetch-all, sort in JVM" strategy across all three
 * {@link org.opentmf.query.tmf630.versioning.VersionOrder} values. See its javadoc
 * for the perf note (works well for typical PLM cardinality of a few versions per
 * logical id).
 */
public class Tmf630JpaVersionResolver implements Tmf630VersionResolver {

  private static final String ID_PARAMETER = "id";
  private static final String VERSION_PARAMETER = "version";

  private final EntityManager entityManager;

  public Tmf630JpaVersionResolver(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public <T> Optional<T> resolveLatest(Class<T> type, String logicalId) {
    Tmf630Versioned versioning = Tmf630VersionResolverSupport.requireVersioning(type);
    List<T> matches = fetchAllForLogicalId(type, versioning, logicalId);
    if (matches.isEmpty()) return Optional.empty();
    Comparator<String> comparator = VersionComparators.forOrder(versioning.versionOrder());
    return matches.stream()
        .max(
            (a, b) ->
                comparator.compare(
                    Tmf630VersionResolverSupport.readVersion(a, versioning),
                    Tmf630VersionResolverSupport.readVersion(b, versioning)));
  }

  @Override
  public <T> Optional<T> resolveSpecific(Class<T> type, String logicalId, String version) {
    if (version == null) return Optional.empty();
    Tmf630Versioned versioning = Tmf630VersionResolverSupport.requireVersioning(type);
    requireManagedEntity(type);
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
    CriteriaQuery<T> query = builder.createQuery(type);
    Root<T> root = query.from(type);
    query
        .select(root)
        .where(
            equalsParameter(builder, root, versioning.idField(), ID_PARAMETER),
            equalsParameter(builder, root, versioning.versionField(), VERSION_PARAMETER));
    List<T> results =
        entityManager
            .createQuery(query)
            .setParameter(ID_PARAMETER, logicalId)
            .setParameter(VERSION_PARAMETER, version)
            .setMaxResults(1)
            .getResultList();
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  private <T> List<T> fetchAllForLogicalId(
      Class<T> type, Tmf630Versioned versioning, String logicalId) {
    requireManagedEntity(type);
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
    CriteriaQuery<T> query = builder.createQuery(type);
    Root<T> root = query.from(type);
    query.select(root).where(equalsParameter(builder, root, versioning.idField(), ID_PARAMETER));
    return entityManager.createQuery(query).setParameter(ID_PARAMETER, logicalId).getResultList();
  }

  /**
   * {@code root.<attribute> = :parameterName}, the parameter typed as the attribute's (boxed)
   * Java type — the type the JPQL form infers for its named parameter.
   */
  private static Predicate equalsParameter(
      CriteriaBuilder builder, Root<?> root, String attribute, String parameterName) {
    Path<Object> path = root.get(attribute);
    Class<?> parameterType = ClassUtils.resolvePrimitiveIfNecessary(path.getJavaType());
    return builder.equal(path, builder.parameter(parameterType, parameterName));
  }

  private void requireManagedEntity(Class<?> type) {
    for (EntityType<?> entity : entityManager.getMetamodel().getEntities()) {
      if (entity.getJavaType().equals(type)) {
        return;
      }
    }
    throw new IllegalStateException(
        "Type " + type.getName() + " is not a JPA-managed entity; version resolution needs one.");
  }
}
