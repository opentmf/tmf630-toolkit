package org.opentmf.query.tmf630.mongo.split;

/**
 * Immutable snapshot of one {@link Tmf630MongoSplitCollection} annotation, resolved
 * against its declaring field. Built once by {@link MongoSplitEntityRegistry} at
 * bootstrap and read many times per request thereafter.
 *
 * @param fieldName the Java field name on the parent POJO (e.g. {@code items})
 * @param childType the runtime class of a single child
 * @param childCollection the Mongo collection holding the children
 * @param maxInlineItems max items merged back into the parent GET response
 * @param parentIdField field on the child doc referencing the parent's {@code _id}
 * @param itemIdField field on the child doc identifying the item within the parent
 * @param itemOrderField field on the child doc preserving list order
 */
public record MongoSplitCollectionMetadata(
    String fieldName,
    Class<?> childType,
    String childCollection,
    int maxInlineItems,
    String parentIdField,
    String itemIdField,
    String itemOrderField,
    String payloadField) {}
