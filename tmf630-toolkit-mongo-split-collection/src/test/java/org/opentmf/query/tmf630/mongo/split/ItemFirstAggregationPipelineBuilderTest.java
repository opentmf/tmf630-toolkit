package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperationContext;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;

/**
 * Structural unit tests for {@link ItemFirstAggregationPipelineBuilder}. Asserts on
 * the serialised BSON pipeline shape so any drift in the emitted stages is caught
 * here rather than only at IT time.
 */
class ItemFirstAggregationPipelineBuilderTest {

  private final MongoInnerPredicateTranslator inner = new SimpleMongoInnerPredicateTranslator();
  private final ItemFirstAggregationPipelineBuilder builder =
      new ItemFirstAggregationPipelineBuilder(inner);

  private final MongoSplitCollectionMetadata split =
      new MongoSplitCollectionMetadata(
          "items",
          Object.class,
          "orderItems",
          100,
          "parentId",
          "itemId",
          "itemOrder",
          "payload");

  @Test
  @DisplayName("emits exactly five stages in the item-first order")
  void fiveStagesInOrder() {
    Aggregation agg = builder.build("orders", split, "@.state == 'PENDING'");
    List<Document> stages = serialize(agg);
    assertThat(stages).hasSize(5);
    assertThat(stages.get(0)).containsKey("$match");
    assertThat(stages.get(1)).containsKey("$group");
    assertThat(stages.get(2)).containsKey("$lookup");
    assertThat(stages.get(3)).containsKey("$unwind");
    assertThat(stages.get(4)).containsKey("$replaceRoot");
  }

  @Test
  @DisplayName("$match stage carries the child-side criteria on payload.<field>")
  void matchStageCarriesChildCriteria() {
    Document match =
        (Document) serialize(builder.build("orders", split, "@.state == 'PENDING'")).get(0).get("$match");
    assertThat(match).isEqualTo(new Document("payload.state", "PENDING"));
  }

  @Test
  @DisplayName("$group stage keys by the split's parentIdField")
  void groupStageKeysByParentIdField() {
    Document group =
        (Document) serialize(builder.build("orders", split, "@.state == 'X'")).get(1).get("$group");
    assertThat(group.getString("_id")).isEqualTo("$parentId");
  }

  @Test
  @DisplayName("$lookup joins to the parent collection by _id")
  void lookupJoinsToParentCollectionById() {
    Document lookup =
        (Document) serialize(builder.build("orders", split, "@.state == 'X'")).get(2).get("$lookup");
    assertThat(lookup.getString("from")).isEqualTo("orders");
    assertThat(lookup.getString("localField")).isEqualTo("_id");
    assertThat(lookup.getString("foreignField")).isEqualTo("_id");
    assertThat(lookup.getString("as")).isEqualTo("__tmf630Parent__");
  }

  @Test
  @DisplayName("$unwind unpacks the parent-join stage field")
  void unwindStage() {
    Object unwind = serialize(builder.build("orders", split, "@.state == 'X'")).get(3).get("$unwind");
    assertThat(unwind).isEqualTo("$__tmf630Parent__");
  }

  @Test
  @DisplayName("$replaceRoot promotes the joined parent doc to the top of the output")
  void replaceRootPromotesParent() {
    Document replaceRoot =
        (Document) serialize(builder.build("orders", split, "@.state == 'X'")).get(4).get("$replaceRoot");
    assertThat(replaceRoot.getString("newRoot")).isEqualTo("$__tmf630Parent__");
  }

  @Test
  @DisplayName("compound inner predicate (via && / ||) survives into the $match")
  void compoundInnerPredicate() {
    Document match =
        (Document)
            serialize(builder.build("orders", split, "@.state == 'A' && @.priority > 5"))
                .get(0)
                .get("$match");
    assertThat(match).containsKey("$and");
  }

  private static List<Document> serialize(Aggregation agg) {
    AggregationOperationContext ctx = TypedAggregation.DEFAULT_CONTEXT;
    return agg.toPipeline(ctx);
  }
}
