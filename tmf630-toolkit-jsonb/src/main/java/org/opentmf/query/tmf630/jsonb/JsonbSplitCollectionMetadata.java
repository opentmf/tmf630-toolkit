package org.opentmf.query.tmf630.jsonb;

import org.springframework.util.Assert;

/**
 * Runtime descriptor for a {@link Tmf630JsonbSplitCollection}-annotated field. Captured
 * once per split-collection at bean-registration time by
 * {@link JsonbEntityMetadata#of(Class)}; consumed by the read-merge (c.4), sub-endpoint
 * (c.5), PATCH-optimization (c.6), and predicate-splitter (c.2) machinery in later
 * sub-milestones.
 *
 * <p>Immutable value object. See {@link Tmf630JsonbSplitCollection} Javadoc for what
 * each field means and the required child-table column shape.
 */
public record JsonbSplitCollectionMetadata(
    String fieldName,
    Class<?> childType,
    String childTable,
    int maxInlineItems,
    String parentIdColumn,
    String itemIdColumn,
    String itemOrderColumn,
    String payloadColumn) {

  public JsonbSplitCollectionMetadata {
    Assert.hasText(fieldName, "fieldName must not be blank");
    Assert.notNull(childType, "childType must not be null");
    Assert.hasText(childTable, "childTable must not be blank");
    Assert.isTrue(
        maxInlineItems > 0, "maxInlineItems must be positive; got " + maxInlineItems);
    Assert.hasText(parentIdColumn, "parentIdColumn must not be blank");
    Assert.hasText(itemIdColumn, "itemIdColumn must not be blank");
    Assert.hasText(itemOrderColumn, "itemOrderColumn must not be blank");
    Assert.hasText(payloadColumn, "payloadColumn must not be blank");
  }

  static JsonbSplitCollectionMetadata from(
      String fieldName, Tmf630JsonbSplitCollection annotation) {
    if (annotation.childType() == null || annotation.childType() == Void.class) {
      throw new Tmf630JsonbConfigurationException(
          "@Tmf630JsonbSplitCollection on field '"
              + fieldName
              + "' must declare a concrete childType().");
    }
    return new JsonbSplitCollectionMetadata(
        fieldName,
        annotation.childType(),
        annotation.childTable(),
        annotation.maxInlineItems(),
        annotation.parentIdColumn(),
        annotation.itemIdColumn(),
        annotation.itemOrderColumn(),
        annotation.payloadColumn());
  }
}
