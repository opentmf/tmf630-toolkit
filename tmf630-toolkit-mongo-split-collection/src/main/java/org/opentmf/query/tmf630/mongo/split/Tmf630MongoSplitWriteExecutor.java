package org.opentmf.query.tmf630.mongo.split;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
  public void appendChild(Class<?> parentType, Object parentId, Object child) {
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
}
