package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Id;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JsonbSplitCollectionMetadataTest {

  @Test
  @DisplayName("scans domain type for @Tmf630JsonbSplitCollection fields and populates metadata")
  void scansSingleSplitField() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(OrderRow.class);
    assertThat(metadata.splitCollections()).hasSize(1);
    JsonbSplitCollectionMetadata split = metadata.splitCollections().get(0);
    assertThat(split.fieldName()).isEqualTo("items");
    assertThat(split.childType()).isEqualTo(OrderItem.class);
    assertThat(split.childTable()).isEqualTo("order_item");
    assertThat(split.maxInlineItems()).isEqualTo(50);
    assertThat(split.parentIdColumn()).isEqualTo("parent_id");
    assertThat(split.itemIdColumn()).isEqualTo("item_id");
    assertThat(split.itemOrderColumn()).isEqualTo("item_order");
    assertThat(split.payloadColumn()).isEqualTo("payload");
  }

  @Test
  @DisplayName("empty split-collection list when the domain type has no @Tmf630JsonbSplitCollection")
  void noSplitFieldsPresent() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(PlainRow.class);
    assertThat(metadata.splitCollections()).isEmpty();
  }

  @Test
  @DisplayName("honors custom column names on the annotation")
  void customColumnNames() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(CustomColumnsRow.class);
    JsonbSplitCollectionMetadata split = metadata.splitCollections().get(0);
    assertThat(split.parentIdColumn()).isEqualTo("po_id");
    assertThat(split.itemIdColumn()).isEqualTo("line_id");
    assertThat(split.itemOrderColumn()).isEqualTo("line_no");
    assertThat(split.payloadColumn()).isEqualTo("body");
  }

  @Test
  @DisplayName("multiple split-collection fields on one domain type each produce a metadata entry")
  void multipleSplitFields() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(MultiSplitRow.class);
    assertThat(metadata.splitCollections()).hasSize(2);
    assertThat(metadata.splitCollections())
        .extracting(JsonbSplitCollectionMetadata::fieldName)
        .containsExactlyInAnyOrder("items", "notes");
  }

  @Test
  @DisplayName("rejects maxInlineItems <= 0 at construction time")
  void rejectsNonPositiveCap() {
    assertThatThrownBy(
            () ->
                new JsonbSplitCollectionMetadata(
                    "items", Object.class, "t", 0, "p", "i", "o", "d"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JsonbSplitCollectionMetadata(
                    "items", Object.class, "t", -1, "p", "i", "o", "d"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects blank field / table / column names")
  void rejectsBlanks() {
    assertThatThrownBy(
            () ->
                new JsonbSplitCollectionMetadata(
                    "", Object.class, "t", 1, "p", "i", "o", "d"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JsonbSplitCollectionMetadata(
                    "items", Object.class, "", 1, "p", "i", "o", "d"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- fixtures ---

  static class OrderItem {}

  static class Note {}

  static class OrderDomain {
    @Tmf630JsonbSplitCollection(
        childTable = "order_item",
        childType = OrderItem.class,
        maxInlineItems = 50)
    private List<OrderItem> items;
  }

  @Tmf630JsonbBacked(domainType = OrderDomain.class)
  static class OrderRow {
    @Id private String id;
    JsonNode payload;
  }

  static class PlainDomain {}

  @Tmf630JsonbBacked(domainType = PlainDomain.class)
  static class PlainRow {
    @Id private String id;
    JsonNode payload;
  }

  static class CustomColumnsDomain {
    @Tmf630JsonbSplitCollection(
        childTable = "custom_child",
        childType = OrderItem.class,
        parentIdColumn = "po_id",
        itemIdColumn = "line_id",
        itemOrderColumn = "line_no",
        payloadColumn = "body")
    private List<OrderItem> lines;
  }

  @Tmf630JsonbBacked(domainType = CustomColumnsDomain.class)
  static class CustomColumnsRow {
    @Id private String id;
    JsonNode payload;
  }

  static class MultiSplitDomain {
    @Tmf630JsonbSplitCollection(childTable = "multi_item", childType = OrderItem.class)
    private List<OrderItem> items;

    @Tmf630JsonbSplitCollection(childTable = "multi_note", childType = Note.class)
    private List<Note> notes;
  }

  @Tmf630JsonbBacked(domainType = MultiSplitDomain.class)
  static class MultiSplitRow {
    @Id private String id;
    JsonNode payload;
  }
}
