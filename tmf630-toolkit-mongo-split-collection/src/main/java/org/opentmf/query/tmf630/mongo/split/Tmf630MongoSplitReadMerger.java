package org.opentmf.query.tmf630.mongo.split;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Read-side counterpart to {@link Tmf630MongoSplitWriteExecutor}. Given a parent
 * document already loaded from Mongo (with its split fields empty because the writer
 * stripped them), fetches up to {@code maxInlineItems} children per split collection
 * and populates the parent's fields with the results, sorted by {@code itemOrder}.
 *
 * <p>The reader queries child wrapper docs as raw {@link Document}s, extracts the
 * {@code payload} sub-document, and asks the Mongo converter to deserialize
 * <em>that</em> into the child POJO. This route preserves the child's own {@code id}
 * field verbatim (the wrapper's {@code _id} — a Mongo-managed ObjectId — never enters
 * the POJO path).
 *
 * <p>Kept as a distinct component (rather than baked into a repository proxy) so that:
 * <ul>
 *   <li>the read merge stays independent of query mechanism — {@code MongoTemplate},
 *       Spring Data repositories, or a custom aggregation can all invoke it uniformly,
 *   <li>test code can exercise the merge in isolation without stubbing repositories,
 *   <li>callers that don't want the merge (e.g. bulk export paths) can bypass it.
 * </ul>
 */
public class Tmf630MongoSplitReadMerger {

  private final MongoOperations mongoOperations;
  private final MongoSplitEntityRegistry registry;

  public Tmf630MongoSplitReadMerger(
      MongoOperations mongoOperations, MongoSplitEntityRegistry registry) {
    this.mongoOperations = mongoOperations;
    this.registry = registry;
  }

  /**
   * Populates every split field on {@code parent} with up to
   * {@link MongoSplitCollectionMetadata#maxInlineItems()} children fetched from the
   * child collection, ordered by {@code itemOrder}. Returns the same instance for
   * fluent use.
   *
   * <p>No-op if the parent type is not registered as split-backed.
   */
  public <T> T merge(T parent) {
    if (parent == null) return null;
    MongoSplitEntityMetadata metadata =
        registry.forParentType(parent.getClass()).orElse(null);
    if (metadata == null) return parent;

    Object parentId = metadata.idOf(parent);
    if (parentId == null) return parent;

    MongoConverter converter = mongoOperations.getConverter();
    for (MongoSplitCollectionMetadata split : metadata.splits()) {
      Query q =
          new Query(Criteria.where(split.parentIdField()).is(parentId))
              .with(Sort.by(Sort.Order.asc(split.itemOrderField())))
              .limit(split.maxInlineItems());
      List<Document> wrappers =
          mongoOperations.find(q, Document.class, split.childCollection());
      List<Object> children = new ArrayList<>(wrappers.size());
      for (Document wrapper : wrappers) {
        children.add(deserializeChild(converter, wrapper, split));
      }
      setField(parent, metadata.parentType(), split.fieldName(), children);
    }
    return parent;
  }

  /**
   * Convenience: apply {@link #merge(Object)} to each element of a list, in place.
   * Returns the same list for fluent use.
   */
  public <T> List<T> mergeAll(List<T> parents) {
    if (parents == null) return null;
    for (T parent : parents) {
      merge(parent);
    }
    return parents;
  }

  /**
   * Deserializes the {@code payload} sub-document of a wrapper into the child POJO,
   * falling back to synthesising an id from {@code itemId} if the payload has none.
   * Kept package-private so {@link Tmf630MongoSubResourceController} can reuse it.
   */
  static Object deserializeChild(
      MongoConverter converter, Document wrapper, MongoSplitCollectionMetadata split) {
    Document payload = wrapper.get(split.payloadField(), Document.class);
    if (payload == null) payload = new Document();
    Object child = converter.read(split.childType(), payload);
    Object payloadId = payload.get("_id");
    if (payloadId == null) {
      // Payload had no id → set the POJO's id field from wrapper.itemId, so callers
      // observe the same identifier the parent hierarchy references.
      Object itemId = wrapper.get(split.itemIdField());
      if (itemId != null) {
        setChildIdIfPresent(child, itemId);
      }
    }
    return child;
  }

  private static void setChildIdIfPresent(Object child, Object idValue) {
    Class<?> cursor = child.getClass();
    while (cursor != null && cursor != Object.class) {
      try {
        Field f = cursor.getDeclaredField("id");
        f.setAccessible(true);
        f.set(child, idValue);
        return;
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("Unable to set id on " + child.getClass(), e);
      }
    }
  }

  private static void setField(Object target, Class<?> targetType, String fieldName, Object value) {
    Class<?> cursor = targetType;
    while (cursor != null && cursor != Object.class) {
      try {
        Field f = cursor.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
        return;
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(
            "Unable to set field '" + fieldName + "' on " + targetType.getName(), e);
      }
    }
    throw new IllegalStateException(
        "No field '" + fieldName + "' found on " + targetType.getName());
  }
}
