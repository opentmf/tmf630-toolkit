package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Unit tests that exercise {@link MongoSplitEntityRegistry} scan/build logic and
 * {@link MongoSplitEntityMetadata} helper methods without spinning up a Mongo
 * container. Covers the branches the IT alone doesn't reach: missing-annotation
 * candidates, missing-id error path, missing-splits error path, field lookup with
 * inheritance, and the two split lookup helpers.
 */
class MongoSplitEntityRegistryTest {

  private final MongoSplitEntityRegistry registry = new MongoSplitEntityRegistry();

  @Test
  @DisplayName("registerIfBacked skips a plain class with no annotation")
  void skipsUnannotated() {
    registry.registerIfBacked(String.class);
    assertThat(registry.all()).isEmpty();
    assertThat(registry.forParentType(String.class)).isEmpty();
  }

  @Test
  @DisplayName("registerIfBacked is idempotent for the same type")
  void idempotent() {
    registry.registerIfBacked(GoodParent.class);
    registry.registerIfBacked(GoodParent.class);
    assertThat(registry.all()).hasSize(1);
  }

  @Test
  @DisplayName("registerAll walks the collection and picks only annotated types")
  void bulkRegister() {
    registry.registerAll(List.of(String.class, GoodParent.class, InheritingChild.class));
    assertThat(registry.all()).extracting(MongoSplitEntityMetadata::parentType)
        .containsExactlyInAnyOrder(GoodParent.class, InheritingChild.class);
  }

  @Test
  @DisplayName("@Tmf630MongoSplitBacked with no @Tmf630MongoSplitCollection fields fails fast")
  void rejectsAnnotatedButEmpty() {
    assertThatThrownBy(() -> registry.registerIfBacked(EmptyAnnotated.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("declares no @Tmf630MongoSplitCollection fields");
  }

  @Test
  @DisplayName("parent without @Id and without a field named 'id' is rejected")
  void rejectsMissingId() {
    assertThatThrownBy(() -> registry.registerIfBacked(MissingIdParent.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no @Id field and no field named 'id'");
  }

  @Test
  @DisplayName("@Document with an explicit collection value overrides the default naming")
  void documentCollectionOverride() {
    registry.registerIfBacked(GoodParent.class);
    MongoSplitEntityMetadata metadata = registry.forParentType(GoodParent.class).orElseThrow();
    assertThat(metadata.parentCollection()).isEqualTo("custom_parent");
  }

  @Test
  @DisplayName("@Document with no explicit name falls back to uncapitalised simple name")
  void documentDefaultCollectionName() {
    registry.registerIfBacked(InheritingChild.class);
    MongoSplitEntityMetadata metadata =
        registry.forParentType(InheritingChild.class).orElseThrow();
    // InheritingChild → inheritingChild
    assertThat(metadata.parentCollection()).isEqualTo("inheritingChild");
  }

  @Test
  @DisplayName("child type lookup returns the parent metadata")
  void childTypeLookup() {
    registry.registerIfBacked(GoodParent.class);
    MongoSplitEntityMetadata metadata =
        registry.forChildType(GoodChild.class).orElseThrow();
    assertThat(metadata.parentType()).isEqualTo(GoodParent.class);
    assertThat(registry.forChildType(String.class)).isEmpty();
  }

  @Test
  @DisplayName("splitByFieldName / splitByChildType return the expected split")
  void metadataHelpers() {
    registry.registerIfBacked(GoodParent.class);
    MongoSplitEntityMetadata metadata = registry.forParentType(GoodParent.class).orElseThrow();
    assertThat(metadata.splitByFieldName("items")).isNotNull();
    assertThat(metadata.splitByFieldName("nope")).isNull();
    assertThat(metadata.splitByChildType(GoodChild.class)).isNotNull();
    assertThat(metadata.splitByChildType(String.class)).isNull();
  }

  @Test
  @DisplayName("idOf returns the id value, setSplitField clears it, readSplitField returns copy")
  void metadataReflectionHelpers() {
    registry.registerIfBacked(GoodParent.class);
    MongoSplitEntityMetadata metadata = registry.forParentType(GoodParent.class).orElseThrow();

    GoodParent p = new GoodParent();
    p.id = "P1";
    p.items = List.of(new GoodChild("a"), new GoodChild("b"));
    assertThat(metadata.idOf(p)).isEqualTo("P1");

    List<Object> read = metadata.readSplitField(p, "items");
    assertThat(read).hasSize(2);

    metadata.setSplitField(p, "items", null);
    assertThat(p.items).isNull();
    assertThat(metadata.readSplitField(p, "items")).isNull();
  }

  @Test
  @DisplayName("readSplitField refuses a non-List value")
  void readSplitFieldRejectsNonList() {
    registry.registerIfBacked(BadTypeParent.class);
    MongoSplitEntityMetadata metadata =
        registry.forParentType(BadTypeParent.class).orElseThrow();

    BadTypeParent p = new BadTypeParent();
    p.id = "P1";
    p.items = "not-a-list";
    assertThatThrownBy(() -> metadata.readSplitField(p, "items"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be a List");
  }

  // --- fixtures ---

  @Document(collection = "custom_parent")
  @Tmf630MongoSplitBacked
  static class GoodParent {
    @Id String id;

    @Tmf630MongoSplitCollection(childCollection = "good_child", childType = GoodChild.class)
    List<GoodChild> items;
  }

  static class GoodChild {
    String id;

    GoodChild() {}

    GoodChild(String id) {
      this.id = id;
    }
  }

  @Document
  @Tmf630MongoSplitBacked
  static class InheritingChild extends GoodParent {}

  @Tmf630MongoSplitBacked
  static class EmptyAnnotated {
    @Id String id;
  }

  @Tmf630MongoSplitBacked
  static class MissingIdParent {
    @Tmf630MongoSplitCollection(childCollection = "x", childType = GoodChild.class)
    List<GoodChild> items;
  }

  @Tmf630MongoSplitBacked
  static class BadTypeParent {
    String id;

    // Not actually a List — used to prove readSplitField's runtime type check.
    @Tmf630MongoSplitCollection(childCollection = "x", childType = GoodChild.class)
    Object items;
  }
}
