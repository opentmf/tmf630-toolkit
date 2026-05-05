package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class MongoFieldResolverTest {

  private final MongoMappingContext ctx = new MongoMappingContext();
  private final MongoFieldResolver resolver = new MongoFieldResolver(ctx);

  @Test
  void passthroughResolverReturnsInputUnchanged() {
    MongoFieldResolver pt = MongoFieldResolver.passthrough();
    assertEquals("a.b.c", pt.resolveBsonPath(Root.class, "a.b.c"));
    assertEquals("id", pt.resolveBsonPath(Inner.class, "id"));
  }

  @Test
  void rootIdAnnotatedPropertyMapsToUnderscoreId() {
    assertEquals("_id", resolver.resolveBsonPath(Root.class, "id"));
  }

  @Test
  void nestedNonAnnotatedIdGetsAutoPromotedToUnderscoreId() {
    assertEquals("inner._id", resolver.resolveBsonPath(Root.class, "inner.id"));
  }

  @Test
  void fieldAnnotationOverridesAutoPromotion() {
    assertEquals(
        "innerKept.id", resolver.resolveBsonPath(Root.class, "innerKept.id"));
  }

  @Test
  void traversesIntoListElementType() {
    // 'items' is a List<Inner>; Inner.id has no @Field, so it auto-promotes to _id.
    assertEquals("items._id", resolver.resolveBsonPath(Root.class, "items.id"));
  }

  @Test
  void traversesIntoListElementTypeWithFieldOverride() {
    assertEquals(
        "itemsKept.id", resolver.resolveBsonPath(Root.class, "itemsKept.id"));
  }

  @Test
  void unknownPropertyIsPassedThroughUnchanged() {
    assertEquals(
        "inner.notAField", resolver.resolveBsonPath(Root.class, "inner.notAField"));
  }

  @Test
  void respectsCustomFieldNameOverride() {
    // Root.renamed is annotated @Field("renamed_target"), so the BSON name is
    // "renamed_target". The Java path "renamed.target" resolves to
    // "renamed_target.target" — the @Field is honored at the matching segment, and
    // the leaf "target" passes through unchanged.
    assertEquals(
        "renamed_target.target", resolver.resolveBsonPath(Root.class, "renamed.target"));
  }

  @Test
  void nullOrEmptyInputsAreReturnedAsIs() {
    assertNull(resolver.resolveBsonPath(Root.class, null));
    assertEquals("", resolver.resolveBsonPath(Root.class, ""));
    assertEquals("a.b", resolver.resolveBsonPath(null, "a.b"));
  }

  @Test
  void getElementTypeAtPathReturnsRootForEmptyPath() {
    assertEquals(Root.class, resolver.getElementTypeAtPath(Root.class, ""));
  }

  @Test
  void getElementTypeAtPathTraversesIntoListElementType() {
    assertEquals(Inner.class, resolver.getElementTypeAtPath(Root.class, "items"));
  }

  @Test
  void getElementTypeAtPathReturnsScalarTypeForLeaf() {
    assertEquals(String.class, resolver.getElementTypeAtPath(Root.class, "items.id"));
  }

  @Test
  void getElementTypeAtPathReturnsNullForUnknownProperty() {
    assertNull(resolver.getElementTypeAtPath(Root.class, "notAField"));
  }

  @Test
  void getElementTypeAtPathReturnsNullWhenNoMappingContext() {
    MongoFieldResolver pt = MongoFieldResolver.passthrough();
    assertEquals(Root.class, pt.getElementTypeAtPath(Root.class, "items"));
  }

  @Document
  static class Root {
    @Id String id;
    Inner inner;
    InnerKept innerKept;
    List<Inner> items;
    List<InnerKept> itemsKept;
    @Field("renamed_target") Renamed renamed;
  }

  static class Inner {
    String id;
    String name;
  }

  static class InnerKept {
    @Field("id") String id;
    String name;
  }

  static class Renamed {
    String target;
  }
}
