package org.opentmf.query.tmf630.mongo.split;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mongo mirror of {@code Tmf630JsonbWriteExecutor}. Given a parent domain instance
 * annotated with {@link Tmf630MongoSplitBacked}, atomically:
 *
 * <ol>
 *   <li>strips split-collection fields off the parent and upserts the resulting
 *       "slim" parent document into its collection,
 *   <li>deletes all existing children of that parent in each split collection,
 *   <li>inserts the new children as wrapper documents shaped
 *       {@code {_id: ObjectId, parentId, itemId, itemOrder, payload: <child BSON>}}.
 * </ol>
 *
 * <p>The wrapper-doc shape (with the child's own BSON inside a {@code payload}
 * sub-document rather than spread at the top level) mirrors the JSONB module's
 * row-with-payload design and — importantly — sidesteps Spring Data Mongo's mandatory
 * {@code id ↔ _id} field mapping. The child's own {@code id} field is preserved
 * verbatim inside {@code payload}, while the wrapper's {@code _id} is a fresh
 * ObjectId managed by Mongo. Cross-parent {@code itemId} collisions are therefore
 * impossible-by-construction, and no {@code @Field}/{@code @Id} annotation is
 * required on the child POJO.
 *
 * <p><strong>Atomicity note:</strong> the method is annotated {@link Transactional}. In
 * a Mongo replica set with a {@code MongoTransactionManager} bean present, the three
 * operations run in a multi-document transaction. On a standalone Mongo, transactions
 * are unavailable and the operations run non-atomically; a mid-sequence failure can
 * leave orphaned children. Callers that need strict atomicity must provision a
 * replica set — mirroring the JSONB module's requirement of a transactional
 * {@code DataSource}.
 *
 * <p>The parent instance passed in is mutated: split fields are set to {@code null}
 * before persist and restored to their original values afterward, so callers can keep
 * using the instance without surprise. This mirrors the JSONB executor's contract.
 */
public class Tmf630MongoSplitWriteExecutor {

  private static final Logger log = LoggerFactory.getLogger(Tmf630MongoSplitWriteExecutor.class);

  private final MongoOperations mongoOperations;
  private final MongoSplitEntityRegistry registry;

  public Tmf630MongoSplitWriteExecutor(
      MongoOperations mongoOperations, MongoSplitEntityRegistry registry) {
    this.mongoOperations = mongoOperations;
    this.registry = registry;
  }

  /**
   * POST / PATCH-full-document primitive: upsert the parent, replace all split children.
   *
   * @throws IllegalStateException if the parent type is not registered as split-backed
   */
  @Transactional
  public <T> T saveWithSplits(T parent) {
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parent.getClass())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Type "
                            + parent.getClass().getName()
                            + " is not @Tmf630MongoSplitBacked — cannot saveWithSplits"));

    Object parentId = metadata.idOf(parent);
    if (parentId == null) {
      throw new IllegalStateException(
          "Parent instance has null id; assign an id before saveWithSplits");
    }

    // Snapshot each split field, then clear it on the parent so the persist doesn't
    // duplicate children inline.
    List<SplitSnapshot> snapshots = new ArrayList<>();
    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      List<Object> children = metadata.readSplitField(parent, split.fieldName());
      snapshots.add(new SplitSnapshot(split, children));
      metadata.setSplitField(parent, split.fieldName(), null);
    }

    try {
      // 1) Upsert the slim parent doc.
      mongoOperations.save(parent, metadata.parentCollection());

      // 2 & 3) For each split, wipe then re-insert children wrapped with back-refs.
      MongoConverter converter = mongoOperations.getConverter();
      for (SplitSnapshot snap : snapshots) {
        MongoSplitCollectionMetadata split = snap.split;
        mongoOperations.remove(
            new Query(Criteria.where(split.parentIdField()).is(parentId)),
            split.childCollection());
        if (snap.children == null || snap.children.isEmpty()) continue;
        List<Document> docs = new ArrayList<>(snap.children.size());
        int order = 0;
        for (Object child : snap.children) {
          Document payload = new Document();
          converter.write(child, payload);
          Object childId = extractChildId(child, payload, split);
          if (childId == null) childId = "i-" + order;
          Document wrapper = new Document();
          wrapper.put(split.parentIdField(), parentId);
          wrapper.put(split.itemIdField(), childId);
          wrapper.put(split.itemOrderField(), order++);
          wrapper.put(split.payloadField(), payload);
          docs.add(wrapper);
        }
        mongoOperations.insert(docs, split.childCollection());
      }
      log.debug(
          "tmf630-mongo-split: saved parent {} with {} split collection(s)",
          parentId,
          snapshots.size());
      return parent;
    } finally {
      // Restore snapshotted split fields so the caller's instance still has its data.
      for (SplitSnapshot snap : snapshots) {
        metadata.setSplitField(parent, snap.split.fieldName(), snap.children);
      }
    }
  }

  /**
   * Appends a single child to the given parent's split collection. Optimised path for
   * the {@code JSON Patch add /items/-} case. Assigns {@code itemOrder} from the
   * current child count for this parent.
   */
  @Transactional
  public void appendChild(Class<?> parentType, String parentId, Object child) {
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parentType)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Type " + parentType.getName() + " is not @Tmf630MongoSplitBacked"));
    MongoSplitCollectionMetadata split = metadata.splitByChildType(child.getClass());
    if (split == null) {
      throw new IllegalStateException(
          "No split collection on " + parentType.getName() + " for child type " + child.getClass());
    }
    long currentCount =
        mongoOperations.count(
            new Query(Criteria.where(split.parentIdField()).is(parentId)),
            split.childCollection());
    Document payload = new Document();
    mongoOperations.getConverter().write(child, payload);
    Object childId = extractChildId(child, payload, split);
    if (childId == null) childId = "i-" + currentCount;
    Document wrapper = new Document();
    wrapper.put(split.parentIdField(), parentId);
    wrapper.put(split.itemIdField(), childId);
    wrapper.put(split.itemOrderField(), (int) currentCount);
    wrapper.put(split.payloadField(), payload);
    mongoOperations.insert(wrapper, split.childCollection());
  }

  /**
   * Best-effort child-id extraction: look for a field named {@code id} on the child,
   * or fall back to whatever value Spring Data mapped into {@code _id} on the payload
   * (which, for POJOs following the convention, is the same thing). Returns
   * {@code null} if neither has a value.
   */
  private static Object extractChildId(
      Object child, Document payload, MongoSplitCollectionMetadata split) {
    Object fromField = firstNonNullFieldValue(child, "id", split.itemIdField());
    if (fromField != null) return fromField;
    Object mappedId = payload.get("_id");
    return mappedId;
  }

  private static Object firstNonNullFieldValue(Object obj, String... fieldNames) {
    for (String fieldName : fieldNames) {
      Optional<Field> maybe = findField(obj.getClass(), fieldName);
      if (maybe.isEmpty()) continue;
      Field field = maybe.get();
      field.setAccessible(true);
      try {
        Object v = field.get(obj);
        if (v != null) return v;
      } catch (IllegalAccessException ignored) {
        // fall through to next candidate
      }
    }
    return null;
  }

  private static Optional<Field> findField(Class<?> type, String fieldName) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return Optional.of(cursor.getDeclaredField(fieldName));
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return Optional.empty();
  }

  private record SplitSnapshot(MongoSplitCollectionMetadata split, List<Object> children) {}

  /**
   * PATCH primitive — replaces the {@code payload} sub-document of one existing
   * child in-place. Serves JSON-Patch operations of the form
   * {@code replace /items/<itemId>/<subpath>} where the caller has already merged
   * the sub-path change into the full child value. Does not touch
   * {@code itemOrder} — position within the parent is preserved.
   *
   * <p>Returns the count of documents modified: {@code 1} on success, {@code 0} if
   * the (parent, item) tuple does not exist.
   */
  @Transactional
  public long updateChild(
      Class<?> parentType, String parentId, String itemId, Object updatedChild) {
    if (parentId == null) throw new IllegalArgumentException("parentId must not be null");
    if (itemId == null) throw new IllegalArgumentException("itemId must not be null");
    if (updatedChild == null) throw new IllegalArgumentException("updatedChild must not be null");
    MongoSplitCollectionMetadata split = resolveSplit(parentType, updatedChild.getClass());
    Document payload = new Document();
    mongoOperations.getConverter().write(updatedChild, payload);
    Query q =
        new Query(
            Criteria.where(split.parentIdField())
                .is(parentId)
                .and(split.itemIdField())
                .is(itemId));
    Update update = new Update().set(split.payloadField(), payload);
    return mongoOperations.updateFirst(q, update, split.childCollection()).getModifiedCount();
  }

  /**
   * PATCH primitive — deletes one child. Serves JSON-Patch operations of the form
   * {@code remove /items/<itemId>}. Does not renumber remaining children's
   * {@code itemOrder}; gaps are harmless (the read merge sorts by order, not
   * position). If dense ordering matters, follow with
   * {@link #reindexChildren(Class, Object, Class)}.
   *
   * <p>Returns {@code 1} on success, {@code 0} if the child does not exist.
   */
  @Transactional
  public long removeChild(
      Class<?> parentType, String parentId, String itemId, Class<?> childType) {
    if (parentId == null) throw new IllegalArgumentException("parentId must not be null");
    if (itemId == null) throw new IllegalArgumentException("itemId must not be null");
    MongoSplitCollectionMetadata split = resolveSplit(parentType, childType);
    Query q =
        new Query(
            Criteria.where(split.parentIdField())
                .is(parentId)
                .and(split.itemIdField())
                .is(itemId));
    return mongoOperations.remove(q, split.childCollection()).getDeletedCount();
  }

  /**
   * Reindexes {@code itemOrder} to {@code 0, 1, 2, ...} for the given parent's
   * children in the order returned by their current {@code itemOrder}. Optional
   * housekeeping after a series of {@link #removeChild} calls that left gaps.
   */
  @Transactional
  public void reindexChildren(Class<?> parentType, String parentId, Class<?> childType) {
    if (parentId == null) throw new IllegalArgumentException("parentId must not be null");
    MongoSplitCollectionMetadata split = resolveSplit(parentType, childType);
    Query q =
        new Query(Criteria.where(split.parentIdField()).is(parentId))
            .with(Sort.by(Sort.Order.asc(split.itemOrderField())));
    List<Document> wrappers = mongoOperations.find(q, Document.class, split.childCollection());
    int order = 0;
    for (Document wrapper : wrappers) {
      Object itemId = wrapper.get(split.itemIdField());
      Query one =
          new Query(
              Criteria.where(split.parentIdField())
                  .is(parentId)
                  .and(split.itemIdField())
                  .is(itemId));
      mongoOperations.updateFirst(
          one, new Update().set(split.itemOrderField(), order++), split.childCollection());
    }
  }

  /**
   * Reconciling save — same final state as {@link #saveWithSplits(Object)} but only
   * touches documents that actually changed. For each split collection:
   *
   * <ul>
   *   <li>children present in the inbound instance and absent in the DB → INSERT,
   *   <li>children present in both with a different payload → UPDATE (payload +
   *       itemOrder),
   *   <li>children absent in the inbound instance but present in the DB → DELETE,
   *   <li>children unchanged → left alone (no write, no change-stream event).
   * </ul>
   *
   * <p>Use this instead of {@link #saveWithSplits(Object)} when the parent has many
   * children and the client typically changes only a few per request — avoids the
   * remove-all + insert-all churn.
   */
  @Transactional
  public <T> T saveWithSplitsReconciled(T parent) {
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parent.getClass())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Type "
                            + parent.getClass().getName()
                            + " is not @Tmf630MongoSplitBacked"));
    Object parentId = metadata.idOf(parent);
    if (parentId == null) {
      throw new IllegalStateException("Parent instance has null id");
    }
    List<SplitSnapshot> snapshots = new ArrayList<>();
    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      List<Object> children = metadata.readSplitField(parent, split.fieldName());
      snapshots.add(new SplitSnapshot(split, children));
      metadata.setSplitField(parent, split.fieldName(), null);
    }
    try {
      mongoOperations.save(parent, metadata.parentCollection());
      for (SplitSnapshot snap : snapshots) {
        reconcileSplitChildren(snap.split, parentId, snap.children);
      }
      return parent;
    } finally {
      for (SplitSnapshot snap : snapshots) {
        metadata.setSplitField(parent, snap.split.fieldName(), snap.children);
      }
    }
  }

  private void reconcileSplitChildren(
      MongoSplitCollectionMetadata split, Object parentId, List<Object> inbound) {
    // Load existing (itemId → payload) for this parent.
    Query all = new Query(Criteria.where(split.parentIdField()).is(parentId));
    List<Document> existingDocs =
        mongoOperations.find(all, Document.class, split.childCollection());
    Map<Object, Document> existingByItemId = new HashMap<>();
    for (Document doc : existingDocs) {
      existingByItemId.put(doc.get(split.itemIdField()), doc);
    }
    MongoConverter converter = mongoOperations.getConverter();

    Set<Object> seen = new HashSet<>();
    int order = 0;
    if (inbound != null) {
      for (Object child : inbound) {
        Document payload = new Document();
        converter.write(child, payload);
        Object itemId = extractChildId(child, payload, split);
        if (itemId == null) itemId = "i-" + order;
        seen.add(itemId);
        Document existing = existingByItemId.get(itemId);
        if (existing == null) {
          insertChildWrapper(split, parentId, itemId, order, payload);
        } else {
          Document existingPayload = existing.get(split.payloadField(), Document.class);
          if (existingPayload == null || !existingPayload.equals(payload)) {
            Query one =
                new Query(
                    Criteria.where(split.parentIdField())
                        .is(parentId)
                        .and(split.itemIdField())
                        .is(itemId));
            mongoOperations.updateFirst(
                one,
                new Update().set(split.payloadField(), payload).set(split.itemOrderField(), order),
                split.childCollection());
          } else if (!Integer.valueOf(order).equals(existing.get(split.itemOrderField()))) {
            Query one =
                new Query(
                    Criteria.where(split.parentIdField())
                        .is(parentId)
                        .and(split.itemIdField())
                        .is(itemId));
            mongoOperations.updateFirst(
                one, new Update().set(split.itemOrderField(), order), split.childCollection());
          }
        }
        order++;
      }
    }
    // DELETE anything in DB but not in the inbound set.
    for (Object stale : existingByItemId.keySet()) {
      if (!seen.contains(stale)) {
        Query one =
            new Query(
                Criteria.where(split.parentIdField())
                    .is(parentId)
                    .and(split.itemIdField())
                    .is(stale));
        mongoOperations.remove(one, split.childCollection());
      }
    }
  }

  private void insertChildWrapper(
      MongoSplitCollectionMetadata split,
      Object parentId,
      Object itemId,
      int order,
      Document payload) {
    Document wrapper = new Document();
    wrapper.put(split.parentIdField(), parentId);
    wrapper.put(split.itemIdField(), itemId);
    wrapper.put(split.itemOrderField(), order);
    wrapper.put(split.payloadField(), payload);
    mongoOperations.insert(wrapper, split.childCollection());
  }

  private MongoSplitCollectionMetadata resolveSplit(Class<?> parentType, Class<?> childType) {
    MongoSplitEntityMetadata metadata =
        registry
            .forParentType(parentType)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Type " + parentType.getName() + " is not @Tmf630MongoSplitBacked"));
    MongoSplitCollectionMetadata split = metadata.splitByChildType(childType);
    if (split == null) {
      throw new IllegalStateException(
          "No @Tmf630MongoSplitCollection on "
              + parentType.getName()
              + " for child type "
              + childType.getName());
    }
    return split;
  }
}
