package org.opentmf.query.tmf630.jsonb;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * True-count helper for split-collection children. When a parent GET returns a
 * merged view capped at {@code maxInlineItems}, the client can't tell whether it
 * received all of the parent's children or only a truncated prefix. Callers use
 * this counter to fetch the true count per parent, then emit the
 * {@code X-Total-Count-<ChildName>} header (or an equivalent signal) so the
 * client knows to page through the tail via the sub-endpoint.
 *
 * <p>Ships two shapes:
 *
 * <ul>
 *   <li>{@link #count(Class, String, Class)} — single parent id, returns
 *       {@link ChildCountInfo} with the true count plus the cap the executor is
 *       using so the caller can decide whether the response was truncated.
 *   <li>{@link #countAll(Class, Collection, Class)} — batch variant for list
 *       responses (one row per parent). Returns a map keyed by parent id.
 * </ul>
 *
 * <p>Not wired into the executor or advice on purpose: header emission is a
 * controller-layer concern. The counter is a data helper only; the developer
 * emits the header in whatever framework flavor they prefer
 * ({@code HttpServletResponse.setHeader}, {@code ResponseEntity.header}, etc.).
 */
public class Tmf630JsonbSplitChildCounter {

  private final JdbcClient jdbcClient;
  private final JsonbEntityRegistry registry;

  public Tmf630JsonbSplitChildCounter(JdbcClient jdbcClient, JsonbEntityRegistry registry) {
    this.jdbcClient = jdbcClient;
    this.registry = registry;
  }

  /**
   * Returns the true child count for a single parent, plus the executor's inline
   * cap for that split (so the caller can compute truncated = count > cap).
   *
   * @throws TmfFilteringException if the parent type is not registered or has no
   *     split of the given child type.
   */
  public ChildCountInfo count(Class<?> parentType, String parentId, Class<?> childType) {
    if (parentId == null || parentId.isEmpty()) {
      throw new IllegalArgumentException("parentId must not be blank");
    }
    JsonbSplitCollectionMetadata split = resolveSplit(parentType, childType);
    Long count =
        jdbcClient
            .sql(
                "SELECT COUNT(*) FROM "
                    + split.childTable()
                    + " WHERE "
                    + split.parentIdColumn()
                    + " = ?")
            .param(1, parentId)
            .query(Long.class)
            .single();
    return new ChildCountInfo(count == null ? 0L : count, split.maxInlineItems());
  }

  /**
   * Batch variant: returns the true count for each parent id in one round trip.
   * Missing parents (no matching rows in the child table) map to {@code 0L}
   * explicitly, so the caller can iterate the input set uniformly.
   */
  public Map<String, Long> countAll(
      Class<?> parentType, Collection<String> parentIds, Class<?> childType) {
    if (parentIds == null || parentIds.isEmpty()) return Map.of();
    JsonbSplitCollectionMetadata split = resolveSplit(parentType, childType);
    String placeholders = "?" + ",?".repeat(parentIds.size() - 1);
    List<String> idList = List.copyOf(parentIds);
    var jdbc =
        jdbcClient.sql(
            "SELECT "
                + split.parentIdColumn()
                + ", COUNT(*) FROM "
                + split.childTable()
                + " WHERE "
                + split.parentIdColumn()
                + " IN ("
                + placeholders
                + ") GROUP BY "
                + split.parentIdColumn());
    for (int i = 0; i < idList.size(); i++) {
      jdbc = jdbc.param(i + 1, idList.get(i));
    }
    Map<String, Long> countsBySeen = new HashMap<>();
    jdbc.query(
            (rs, rowNum) -> {
              countsBySeen.put(rs.getString(1), rs.getLong(2));
              return null;
            })
        .list();
    Map<String, Long> out = new HashMap<>();
    for (String id : parentIds) {
      out.put(id, countsBySeen.getOrDefault(id, 0L));
    }
    return out;
  }

  private JsonbSplitCollectionMetadata resolveSplit(Class<?> parentType, Class<?> childType) {
    JsonbEntityMetadata metadata =
        registry
            .forDomainType(parentType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for domain type: "
                            + parentType.getName()));
    return metadata.splitCollections().stream()
        .filter(s -> s.childType().equals(childType))
        .findFirst()
        .orElseThrow(
            () ->
                new TmfFilteringException(
                    "No @Tmf630JsonbSplitCollection on "
                        + metadata.domainType().getSimpleName()
                        + " for child type "
                        + childType.getName()));
  }

  /**
   * Small carrier for a single-parent count query: the true count of children in
   * the DB, plus the executor's {@code maxInlineItems} cap so the caller can
   * decide whether an inline response was truncated.
   */
  public record ChildCountInfo(long trueCount, int maxInlineItems) {
    /** {@code true} when {@link #trueCount()} exceeds {@link #maxInlineItems()}. */
    public boolean truncated() {
      return trueCount > maxInlineItems;
    }
  }
}
