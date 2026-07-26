package org.opentmf.query.tmf630.mongo;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.versioning.Tmf630VersionResolver;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.VersionComparators;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * MongoDB implementation of {@link Tmf630VersionResolver}. Fetches every document
 * matching the logical id from the entity's collection and picks the max version
 * using the configured
 * {@link org.opentmf.query.tmf630.versioning.VersionOrder}.
 *
 * <p>Requires the domain type to carry {@link Tmf630Versioned} and be a
 * spring-data-mongodb {@code @Document} (or otherwise mapped so
 * {@code MongoOperations.find(query, type)} returns instances). The collection is
 * resolved by Spring Data's mapping context.
 *
 * <p>Uniform "fetch-all, sort in JVM" strategy across all three
 * {@link org.opentmf.query.tmf630.versioning.VersionOrder} values. See
 * {@link org.opentmf.query.tmf630.versioning.VersionOrder} javadoc for the perf
 * note (works well for typical PLM cardinalities of a few versions per logical id).
 */
public class Tmf630MongoVersionResolver implements Tmf630VersionResolver {

  private final MongoOperations mongoOperations;

  public Tmf630MongoVersionResolver(MongoOperations mongoOperations) {
    this.mongoOperations = mongoOperations;
  }

  @Override
  public <T> Optional<T> resolveLatest(Class<T> type, String logicalId) {
    Tmf630Versioned versioning = requireVersioning(type);
    List<T> matches =
        mongoOperations.find(
            new Query(Criteria.where(versioning.idField()).is(logicalId)), type);
    if (matches.isEmpty()) return Optional.empty();
    Comparator<String> comparator = VersionComparators.forOrder(versioning.versionOrder());
    return matches.stream()
        .max((a, b) -> comparator.compare(readVersion(a, versioning), readVersion(b, versioning)));
  }

  @Override
  public <T> Optional<T> resolveSpecific(Class<T> type, String logicalId, String version) {
    if (version == null) return Optional.empty();
    Tmf630Versioned versioning = requireVersioning(type);
    Query query =
        new Query(
            Criteria.where(versioning.idField())
                .is(logicalId)
                .and(versioning.versionField())
                .is(version));
    return Optional.ofNullable(mongoOperations.findOne(query, type));
  }

  private static Tmf630Versioned requireVersioning(Class<?> type) {
    Tmf630Versioned versioning = type.getAnnotation(Tmf630Versioned.class);
    if (versioning == null) {
      throw new IllegalStateException(
          "Type " + type.getName() + " is not @Tmf630Versioned — cannot resolve versions.");
    }
    return versioning;
  }

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
