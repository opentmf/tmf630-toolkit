package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class NoRefDocumentSerializerTest {

  private final NoRefDocumentSerializer serializer = new NoRefDocumentSerializer();

  @Test
  void asReferenceAlwaysThrows() {
    assertThrows(
        UnsupportedOperationException.class, () -> serializer.asReference("anything"));
  }

  @Test
  void isReferenceAlwaysReturnsFalse() {
    PathBuilder<Object> path = new PathBuilder<>(Object.class, "anyVar");
    assertFalse(serializer.isReference(path));
  }

  // ---------- Field-name resolution via MongoMappingContext ----------

  @Test
  void promotesUnannotatedIdToUnderscoreId() {
    NoRefDocumentSerializer s = new NoRefDocumentSerializer(new MongoMappingContext());
    PathBuilder<DefaultId> root = new PathBuilder<>(DefaultId.class, "defaultId");
    Predicate predicate = root.getString("id").eq("X");

    Document doc = (Document) s.handle(predicate);

    assertEquals("X", doc.get("_id"));
    assertFalse(doc.containsKey("id"));
  }

  @Test
  void respectsFieldIdOverrideOnNestedIdProperty() {
    NoRefDocumentSerializer s = new NoRefDocumentSerializer(new MongoMappingContext());
    PathBuilder<KeepsIdLiteral> root = new PathBuilder<>(KeepsIdLiteral.class, "keepsId");
    Predicate predicate = root.getString("id").eq("X");

    Document doc = (Document) s.handle(predicate);

    assertEquals("X", doc.get("id"));
    assertFalse(doc.containsKey("_id"));
  }

  @Test
  void honorsCustomFieldNameOnArbitraryProperty() {
    NoRefDocumentSerializer s = new NoRefDocumentSerializer(new MongoMappingContext());
    PathBuilder<CustomFieldName> root = new PathBuilder<>(CustomFieldName.class, "custom");
    Predicate predicate = root.getString("displayName").eq("Y");

    Document doc = (Document) s.handle(predicate);

    assertEquals("Y", doc.get("display_name"));
    assertFalse(doc.containsKey("displayName"));
  }

  static class DefaultId {
    String id;
  }

  static class KeepsIdLiteral {
    @Field("id")
    String id;
  }

  static class CustomFieldName {
    @Field("display_name")
    String displayName;
  }
}
