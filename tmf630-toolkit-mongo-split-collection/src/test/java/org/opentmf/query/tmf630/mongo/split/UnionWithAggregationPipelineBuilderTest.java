package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.mongo.split.UnionWithAggregationPipelineBuilder.SplitPiece;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperationContext;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;

/**
 * Structural unit tests for {@link UnionWithAggregationPipelineBuilder}. Asserts on
 * the serialised BSON pipeline shape so any drift in the emitted stages surfaces
 * here rather than only at IT time.
 */
class UnionWithAggregationPipelineBuilderTest {

  private final UnionWithAggregationPipelineBuilder builder =
      new UnionWithAggregationPipelineBuilder(
          new SimpleMongoInnerPredicateTranslator(),
          new SimpleMongoInnerPredicateTranslator(
              SimpleMongoInnerPredicateTranslator.PARENT_TOP_LEVEL_PREFIX));

  private final MongoSplitCollectionMetadata split1 =
      new MongoSplitCollectionMetadata(
          "items", Object.class, "orderItems", 100, "parentId", "itemId", "itemOrder", "payload");
  private final MongoSplitCollectionMetadata split2 =
      new MongoSplitCollectionMetadata(
          "characteristic",
          Object.class,
          "orderCharacteristics",
          100,
          "parentId",
          "itemId",
          "itemOrder",
          "payload");

  @Test
  @DisplayName("no-split input throws IllegalArgumentException")
  void requiresAtLeastOneSplit() {
    assertThatThrownBy(() -> builder.build("orders", Optional.empty(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "parent-only + one split → starts on parent collection with $match, $unionWith the split, dedup")
  void parentPlusOneSplitStartsOnParentCollection() {
    SplitAwareAggregation packaged =
        builder.build(
            "orders",
            Optional.of("@.status == 'CANCELLED'"),
            List.of(new SplitPiece(split1, "@.state == 'PENDING'")));
    assertThat(packaged.targetCollection()).isEqualTo("orders");
    List<Document> stages = serialize(packaged.pipeline());
    // [ $match(parent), $unionWith(items), $group(dedup), $replaceRoot ]
    assertThat(stages).hasSize(4);
    assertThat(stages.get(0)).containsKey("$match");
    assertThat((Document) stages.get(0).get("$match")).containsEntry("status", "CANCELLED");
    assertThat(stages.get(1)).containsKey("$unionWith");
    Document union = (Document) stages.get(1).get("$unionWith");
    assertThat(union.getString("coll")).isEqualTo("orderItems");
    assertThat(stages.get(2)).containsKey("$group");
    assertThat(stages.get(3)).containsKey("$replaceRoot");
  }

  @Test
  @DisplayName(
      "no parent + two splits → starts on first split's child collection, $unionWith the second, dedup")
  void twoSplitsNoParentStartsOnFirstChildCollection() {
    SplitAwareAggregation packaged =
        builder.build(
            "orders",
            Optional.empty(),
            List.of(
                new SplitPiece(split1, "@.state == 'A'"),
                new SplitPiece(split2, "@.name == 'color'")));
    assertThat(packaged.targetCollection()).isEqualTo("orderItems");
    List<Document> stages = serialize(packaged.pipeline());
    // [ $match(child1), $group, $lookup, $unwind, $replaceRoot,
    //   $unionWith(child2), $group(dedup), $replaceRoot ]
    assertThat(stages).hasSize(8);
    assertThat(stages.get(0)).containsKey("$match");
    assertThat((Document) stages.get(0).get("$match")).containsEntry("payload.state", "A");
    assertThat(stages.get(1)).containsKey("$group");
    assertThat(stages.get(2)).containsKey("$lookup");
    assertThat((Document) stages.get(2).get("$lookup")).containsEntry("from", "orders");
    assertThat(stages.get(3)).containsKey("$unwind");
    assertThat(stages.get(4)).containsKey("$replaceRoot");
    assertThat(stages.get(5)).containsKey("$unionWith");
    Document union = (Document) stages.get(5).get("$unionWith");
    assertThat(union.getString("coll")).isEqualTo("orderCharacteristics");
  }

  @Test
  @DisplayName("$unionWith inner pipeline mirrors the item-first shape for its split")
  void unionWithInnerPipelineIsItemFirstShape() {
    SplitAwareAggregation packaged =
        builder.build(
            "orders",
            Optional.of("@.status == 'X'"),
            List.of(new SplitPiece(split1, "@.state == 'PENDING'")));
    Document union = (Document) serialize(packaged.pipeline()).get(1).get("$unionWith");
    @SuppressWarnings("unchecked")
    List<Document> innerStages = (List<Document>) union.get("pipeline");
    assertThat(innerStages).hasSize(5);
    assertThat(innerStages.get(0)).containsKey("$match");
    assertThat(innerStages.get(1)).containsKey("$group");
    assertThat(innerStages.get(2)).containsKey("$lookup");
    assertThat(innerStages.get(3)).containsKey("$unwind");
    assertThat(innerStages.get(4)).containsKey("$replaceRoot");
  }

  @Test
  @DisplayName("dedup stage keys on _id and keeps the first observed root")
  void dedupStageShape() {
    SplitAwareAggregation packaged =
        builder.build(
            "orders",
            Optional.empty(),
            List.of(new SplitPiece(split1, "@.state == 'X'")));
    List<Document> stages = serialize(packaged.pipeline());
    Document dedupGroup = (Document) stages.get(stages.size() - 2).get("$group");
    assertThat(dedupGroup.getString("_id")).isEqualTo("$_id");
    assertThat(dedupGroup).containsKey("__tmf630Root__");
    Document replaceRoot = (Document) stages.get(stages.size() - 1).get("$replaceRoot");
    assertThat(replaceRoot.getString("newRoot")).isEqualTo("$__tmf630Root__");
  }

  private static List<Document> serialize(Aggregation agg) {
    AggregationOperationContext ctx = TypedAggregation.DEFAULT_CONTEXT;
    return agg.toPipeline(ctx);
  }
}
