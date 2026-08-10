package org.opentmf.query.tmf630.jsonb;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.versioning.Tmf630VersionResolver;
import org.opentmf.query.tmf630.versioning.Tmf630VersionResolverSupport;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.VersionComparators;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
    Tmf630Versioned versioning = context.versioning;
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
    Context context = context(type);
    Tmf630Versioned versioning = context.versioning;
    return fetchAllForLogicalId(type, context, logicalId).stream()
        .filter(entity -> version.equals(Tmf630VersionResolverSupport.readVersion(entity, versioning)))
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
    } catch (JacksonException e) {
      throw new Tmf630JsonbSerializationException(
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

  private record Context(Tmf630Versioned versioning, JsonbEntityMetadata metadata) {}
}
