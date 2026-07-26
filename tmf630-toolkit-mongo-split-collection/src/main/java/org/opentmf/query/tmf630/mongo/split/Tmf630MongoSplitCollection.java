package org.opentmf.query.tmf630.mongo.split;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a collection-typed field on a Mongo domain document as a <em>split</em> child
 * collection — the runtime stores it in a separate Mongo collection with a
 * {@code parentId} back-reference and an {@code itemOrder} index, rather than embedding
 * it inline in the parent document.
 *
 * <p>Read side: on a parent GET the first {@link #maxInlineItems()} children are merged
 * back into the parent's returned payload in item-order. Clients that need the tail
 * page through it via a {@link Tmf630MongoSubResourceController}.
 *
 * <p>Write side: {@link Tmf630MongoSplitWriteExecutor#saveWithSplits} atomically
 * upserts the parent document (with the split field stripped) and replaces the child
 * documents in the child collection.
 *
 * <p>This is the Mongo mirror of {@code @Tmf630JsonbSplitCollection} — the URL grammar,
 * response contract, and developer-facing shape are identical. The two backends can be
 * used interchangeably against the same domain model as long as the parent document
 * declares {@link Tmf630MongoSplitBacked} at the type level.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Tmf630MongoSplitCollection {

  /**
   * Name of the Mongo collection holding the split children. Required — there is no
   * sensible default (child collections are per-parent-type).
   */
  String childCollection();

  /**
   * Java type of a single child item — required because the field's generic parameter
   * is often erased at runtime for reflection-driven metadata scans.
   */
  Class<?> childType();

  /**
   * Maximum number of children merged back into the parent's inline response.
   * Requests that need more use the sub-endpoint. Default matches the JSONB module.
   */
  int maxInlineItems() default 100;

  /**
   * Field on the child document that points back at the parent's {@code _id}.
   * Defaults to {@code parentId}.
   */
  String parentIdField() default "parentId";

  /**
   * Field on the child document that identifies the child within the parent's scope.
   * Defaults to {@code itemId}.
   */
  String itemIdField() default "itemId";

  /**
   * Field on the child document that preserves per-parent ordering. The write hook
   * assigns this from the list position at save time; the read merge sorts by it.
   * Defaults to {@code itemOrder}.
   */
  String itemOrderField() default "itemOrder";

  /**
   * Field on the child document that holds the opaque child payload — a Mongo
   * sub-document mirroring the child POJO exactly. This wrapper shape (rather than
   * spreading the child's fields into the top-level document) mirrors the JSONB
   * module's row-with-payload design and, crucially, sidesteps Spring Data Mongo's
   * mandatory {@code id ↔ _id} field mapping: the child's own {@code id} field is
   * preserved verbatim inside {@code payload}, while the wrapper's {@code _id} is a
   * fresh ObjectId managed by Mongo. Defaults to {@code payload}.
   */
  String payloadField() default "payload";
}
