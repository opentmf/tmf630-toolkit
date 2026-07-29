package org.opentmf.query.tmf630.jsonb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.versioning.Tmf630VersionResolver;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.VersionComparators;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PostgreSQL-with-JSONB implementation of {@link Tmf630VersionResolver}. Fetches every
 * row for the logical id from the parent table (matching {@code payload->>'idField' = ?}
 * via a placeholder-bound JDBC parameter), deserialises the payloads to the domain
 * type, and picks the max version using the configured
 * {@link org.opentmf.query.tmf630.versioning.VersionOrder}.
 *
 * <p>Requires the domain type to carry both {@link Tmf630Versioned} (declares the
 * id/version fields + comparator) and to be registered as {@code @Tmf630JsonbBacked}
 * (so the resolver knows the parent table and payload column names).
 */
public class Tmf630JsonbVersionResolver implements Tmf630VersionResolver {

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final JsonbEntityRegistry registry;

  public Tmf630JsonbVersionResolver(
      JdbcClient jdbcClient, ObjectMapper objectMapper, JsonbEntityRegistry registry) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.registry = registry;
  }

  @Override
  public <T> Optional<T> resolveLatest(Class<T> type, String logicalId) {
    Context context = context(type);
    List<T> matches = fetchAllForLogicalId(type, context, logicalId);
    if (matches.isEmpty()) return Optional.empty();
    Comparator<String> comparator = VersionComparators.forOrder(context.versioning.versionOrder());
    return matches.stream()
        .max((a, b) -> comparator.compare(readVersion(a, context), readVersion(b, context)));
  }

  @Override
  public <T> Optional<T> resolveSpecific(Class<T> type, String logicalId, String version) {
    if (version == null) return Optional.empty();
    Context context = context(type);
    return fetchAllForLogicalId(type, context, logicalId).stream()
        .filter(entity -> version.equals(readVersion(entity, context)))
        .findFirst();
  }

  private <T> List<T> fetchAllForLogicalId(Class<T> type, Context context, String logicalId) {
    String sql =
        "SELECT "
            + context.metadata.payloadField()
            + "::text FROM "
            + context.metadata.tableName()
            + " WHERE "
            + context.metadata.payloadField()
            + "->>'"
            + context.versioning.idField()
            + "' = ?";
    return jdbcClient
        .sql(sql)
        .param(1, logicalId)
        .query(String.class)
        .list()
        .stream()
        .map(json -> deserialise(json, type))
        .toList();
  }

  private <T> T deserialise(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(
          "Failed to deserialise payload for " + type.getName() + " version resolution", e);
    }
  }

  private Context context(Class<?> type) {
    Tmf630Versioned versioning = type.getAnnotation(Tmf630Versioned.class);
    if (versioning == null) {
      throw new TmfFilteringException(
          "Type " + type.getName() + " is not @Tmf630Versioned — cannot resolve versions.");
    }
    JsonbEntityMetadata metadata =
        registry
            .forDomainType(type)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for domain type: "
                            + type.getName()));
    return new Context(versioning, metadata);
  }

  @SuppressWarnings("java:S3011") // toolkit must read user-declared entity field regardless of visibility
  private static String readVersion(Object entity, Context context) {
    try {
      Field field = findField(entity.getClass(), context.versioning.versionField());
      field.setAccessible(true);
      Object value = field.get(entity);
      return value == null ? null : value.toString();
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalStateException(
          "Unable to read version field '"
              + context.versioning.versionField()
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

  private record Context(Tmf630Versioned versioning, JsonbEntityMetadata metadata) {}
}
