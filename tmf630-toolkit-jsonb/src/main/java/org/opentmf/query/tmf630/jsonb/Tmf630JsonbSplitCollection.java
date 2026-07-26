package org.opentmf.query.tmf630.jsonb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a collection field on a {@link Tmf630JsonbBacked} domain model as "split-stored" —
 * the toolkit persists the parent payload with this field removed, and stores each item
 * as a row in a companion child table. At read time the executor fetches children and
 * merges them back into the parent payload; the client sees one merged JSON document,
 * unaware of the physical split.
 *
 * <p>Rationale and full design in {@code docs/JSONB_BACKEND_DESIGN.md} §3.4. In short:
 * some TMF resources have a child collection that grows arbitrarily large (canonically
 * {@code ProductOrder.productOrderItem}, where a single order can hold 500+ items). Inline
 * storage in the parent's {@code payload} JSONB hits three walls at scale — row size,
 * rewrite cost per update, and non-indexed item-side filters. Splitting to a companion
 * table addresses all three while preserving the transparent merged-payload API contract.
 *
 * <p>The split is architecturally an intra-entity storage detail, NOT a cross-entity JOIN
 * (§3.2 explicitly scopes those out) — the child rows only make sense in the context of
 * their parent. FK integrity via {@code ON DELETE CASCADE} on {@code parent_id} handles
 * parent-delete propagation; ordering is preserved via an {@code item_order} column on
 * the child table (no parent-side id list needed — cleaner than the Mongo pattern).
 *
 * <p>Table shape (per §3.4):
 *
 * <pre>{@code
 * CREATE TABLE product_order_item (
 *   parent_id   VARCHAR(50)  NOT NULL REFERENCES product_order(id) ON DELETE CASCADE,
 *   item_id     VARCHAR(50)  NOT NULL,
 *   item_order  INTEGER      NOT NULL,
 *   payload     JSONB        NOT NULL,
 *   created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
 *   updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
 *   opt_lock    INTEGER      NOT NULL DEFAULT 0,
 *   PRIMARY KEY (parent_id, item_id)
 * );
 * CREATE INDEX product_order_item_order_idx ON product_order_item (parent_id, item_order);
 * }</pre>
 *
 * <p>Phase (c.1) — first cut — ships the annotation and the metadata / registry plumbing
 * that later sub-milestones (c.4 read merge, c.5 sub-endpoint, c.6 PATCH optimization,
 * c.7 parity ITs) hang off. No runtime behavior yet; a domain model that declares this
 * annotation today still reads and writes exactly as it would without it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Tmf630JsonbSplitCollection {

  /**
   * The physical table name for the child rows. The toolkit assumes the columns
   * {@code parent_id}, {@code item_id}, {@code item_order}, {@code payload} plus the
   * standard audit columns per §1.3. Services that need a different column shape can
   * override individual names via the other attributes below.
   */
  String childTable();

  /**
   * Element (child) type of the collection. The parent's collection field itself is
   * declared as {@code List<Child>} — this attribute names {@code Child} explicitly to
   * avoid the reflection cost of resolving the generic type parameter at every payload
   * merge.
   */
  Class<?> childType();

  /**
   * Maximum number of child items to inline into the parent payload on a
   * {@code GET /{parent}/{id}} response. When the actual count exceeds this cap the
   * response body carries the first {@code maxInlineItems} items and an
   * {@code X-Total-Count-<ChildType>} header conveys the true total; the client
   * uses the {@code GET /{parent}/{id}/{childRoute}} sub-endpoint (c.5) for the tail.
   */
  int maxInlineItems() default 100;

  /**
   * Column on the child table that references the parent's PK. Defaults to
   * {@code "parent_id"}.
   */
  String parentIdColumn() default "parent_id";

  /**
   * Column on the child table that holds the TMF-visible child id. Defaults to
   * {@code "item_id"}.
   */
  String itemIdColumn() default "item_id";

  /**
   * Column on the child table that preserves the item's position within the collection.
   * Defaults to {@code "item_order"}.
   */
  String itemOrderColumn() default "item_order";

  /**
   * Column on the child table that holds the child's JSONB payload. Defaults to
   * {@code "payload"}.
   */
  String payloadColumn() default "payload";
}
