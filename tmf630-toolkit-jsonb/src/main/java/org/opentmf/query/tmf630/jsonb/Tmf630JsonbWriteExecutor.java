package org.opentmf.query.tmf630.jsonb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase (c.6) — auto-splitting write executor. Developer's POST/PATCH controllers call
 * {@link #saveWithSplits(Object)} with a full domain instance; the executor extracts
 * every {@link Tmf630JsonbSplitCollection}-annotated field, persists the parent's
 * payload (without those fields) into the parent table, and stores each split child
 * as its own row in the companion table — one atomic transaction.
 *
 * <p>Write semantics for this first cut:
 *
 * <ul>
 *   <li><b>Parent</b> — Postgres UPSERT: {@code INSERT ... ON CONFLICT (id) DO UPDATE
 *       SET payload = EXCLUDED.payload}. Works for both POST-new and PATCH-full-doc
 *       (the TMF pattern where the client sends the merged document).
 *   <li><b>Children</b> — full replace: {@code DELETE ... WHERE parent_id = ?} then
 *       {@code INSERT} each child. Simple and correct; surgical PATCH optimizations
 *       (append-only via JSON Patch add {@code /items/-}, per-item modify/remove)
 *       land in a later c.6.x sub-milestone.
 *   <li><b>Child id fallback</b> — if a child object has no {@code id} property, the
 *       toolkit fills in the item's positional index as the id (and injects it into
 *       the persisted JSON so subsequent reads round-trip). This matches the TMF
 *       convention where nested array items often have small ordinal ids.
 * </ul>
 *
 * <p>The whole operation runs under {@code @Transactional} — parent write + all child
 * writes commit or roll back together. Callers that already have an ambient
 * transaction join it (Spring's default propagation).
 *
 * <p>Convention assumed: the row entity's primary key column is named {@code id} and
 * matches the domain's {@code id} field value. This is the TMF-standard convention.
 * Configurable row-PK column names land in a follow-up if a downstream service needs
 * a different convention.
 */
public class Tmf630JsonbWriteExecutor {

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final JsonbEntityRegistry registry;

  public Tmf630JsonbWriteExecutor(
      JdbcClient jdbcClient, ObjectMapper objectMapper, JsonbEntityRegistry registry) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.registry = registry;
  }

  /**
   * Persists a domain instance and its split children atomically. Idempotent for the
   * parent (UPSERT); full-replace for each split collection.
   */
  @Transactional
  public <T> void saveWithSplits(T domain) {
    if (domain == null) {
      throw new IllegalArgumentException("domain must not be null");
    }
    JsonbEntityMetadata metadata =
        registry
            .forDomainType(domain.getClass())
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for domain type: "
                            + domain.getClass().getName()));
    try {
      // Serialize the domain to a mutable tree — split fields will be removed from it
      // before the parent payload is persisted. valueToTree round-trips through the
      // Jackson serializer so annotations like @JsonInclude and @JsonIgnore apply.
      ObjectNode parentTree = objectMapper.valueToTree(domain);
      String parentId = parentTree.path("id").asText(null);
      if (parentId == null || parentId.isEmpty()) {
        throw new TmfFilteringException(
            "Domain instance must have a non-empty 'id' field for split-write: "
                + domain.getClass().getSimpleName());
      }

      // Extract split fields from the tree first (mutates it), so the parent payload
      // we UPSERT below doesn't carry the children. But hold off on the child INSERTs
      // until AFTER the parent UPSERT — the FK on child.parent_id would otherwise
      // fail for a brand-new parent.
      java.util.Map<JsonbSplitCollectionMetadata, JsonNode> extracted = new java.util.LinkedHashMap<>();
      for (JsonbSplitCollectionMetadata split : metadata.splitCollections()) {
        extracted.put(split, parentTree.remove(split.fieldName()));
      }

      upsertParent(metadata, parentId, objectMapper.writeValueAsString(parentTree));

      for (java.util.Map.Entry<JsonbSplitCollectionMetadata, JsonNode> entry : extracted.entrySet()) {
        replaceSplitChildren(entry.getKey(), parentId, entry.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to serialize domain " + domain.getClass().getName() + " for split-write",
          e);
    }
  }

  private void upsertParent(JsonbEntityMetadata metadata, String parentId, String payloadJson) {
    jdbcClient
        .sql(
            "INSERT INTO "
                + metadata.tableName()
                + " (id, "
                + metadata.payloadField()
                + ") VALUES (?, ?::jsonb) "
                + "ON CONFLICT (id) DO UPDATE SET "
                + metadata.payloadField()
                + " = EXCLUDED."
                + metadata.payloadField())
        .param(1, parentId)
        .param(2, payloadJson)
        .update();
  }

  private void replaceSplitChildren(
      JsonbSplitCollectionMetadata split, String parentId, JsonNode childArray) {
    // Full-replace: delete existing children for this parent first.
    jdbcClient
        .sql(
            "DELETE FROM "
                + split.childTable()
                + " WHERE "
                + split.parentIdColumn()
                + " = ?")
        .param(1, parentId)
        .update();

    if (childArray == null || childArray.isNull() || !childArray.isArray() || childArray.isEmpty()) {
      return;
    }

    String insertSql =
        "INSERT INTO "
            + split.childTable()
            + " ("
            + split.parentIdColumn()
            + ", "
            + split.itemIdColumn()
            + ", "
            + split.itemOrderColumn()
            + ", "
            + split.payloadColumn()
            + ") VALUES (?, ?, ?, ?::jsonb)";
    for (int i = 0; i < childArray.size(); i++) {
      JsonNode child = childArray.get(i);
      String childId = resolveChildId(child, i);
      jdbcClient
          .sql(insertSql)
          .param(1, parentId)
          .param(2, childId)
          .param(3, i)
          .param(4, child.toString())
          .update();
    }
  }

  /**
   * Extracts the child's id from its {@code id} property, or falls back to the
   * positional index. If a fallback is used and the child is an object node, injects
   * the id back into the child JSON so subsequent reads see it too — round-trip
   * consistency for clients that use auto-generated ids.
   */
  private static String resolveChildId(JsonNode child, int index) {
    if (child != null && child.isObject()) {
      JsonNode idNode = child.get("id");
      if (idNode != null && !idNode.isNull() && !idNode.asText().isEmpty()) {
        return idNode.asText();
      }
      String fallback = String.valueOf(index);
      ((ObjectNode) child).put("id", fallback);
      return fallback;
    }
    return String.valueOf(index);
  }

  /**
   * Appends a single child to a split collection without touching the parent row or
   * other children — the JSON-Patch {@code add /items/-} optimization from JSONB doc
   * §3.4. Assigns the next item_order (max + 1) atomically via a subquery.
   */
  @Transactional
  public <T> void appendChild(Class<T> parentDomainType, String parentId, Object childInstance) {
    if (parentId == null || parentId.isEmpty()) {
      throw new IllegalArgumentException("parentId must not be blank");
    }
    if (childInstance == null) {
      throw new IllegalArgumentException("childInstance must not be null");
    }
    JsonbEntityMetadata metadata =
        registry
            .forDomainType(parentDomainType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for domain type: "
                            + parentDomainType.getName()));
    JsonbSplitCollectionMetadata split = findSplitForChildType(metadata, childInstance.getClass());
    try {
      JsonNode childNode = objectMapper.valueToTree(childInstance);
      String childPayload = childNode.toString();
      String childId = resolveChildId(childNode, -1); // -1 signals no known position yet
      if ("-1".equals(childId)) {
        throw new TmfFilteringException(
            "appendChild requires the child instance to carry an 'id' field.");
      }
      String insertSql =
          "INSERT INTO "
              + split.childTable()
              + " ("
              + split.parentIdColumn()
              + ", "
              + split.itemIdColumn()
              + ", "
              + split.itemOrderColumn()
              + ", "
              + split.payloadColumn()
              + ") VALUES (?, ?, "
              + " COALESCE((SELECT MAX("
              + split.itemOrderColumn()
              + ") + 1 FROM "
              + split.childTable()
              + " WHERE "
              + split.parentIdColumn()
              + " = ?), 0), "
              + " ?::jsonb)";
      jdbcClient
          .sql(insertSql)
          .param(1, parentId)
          .param(2, childId)
          .param(3, parentId)
          .param(4, childPayload)
          .update();
    } catch (Exception e) {
      if (e instanceof RuntimeException re) throw re;
      throw new UncheckedIOException(
          "Failed to serialize child " + childInstance.getClass().getName() + " for append",
          new IOException(e));
    }
  }

  private static JsonbSplitCollectionMetadata findSplitForChildType(
      JsonbEntityMetadata metadata, Class<?> childType) {
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
}
