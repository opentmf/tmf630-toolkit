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
 * Structural unit tests for {@link ParentFirstLookupPipelineBuilder}. Asserts on the
 * serialised BSON pipeline — the exact document shape sent to Mongo — so any drift in
 * the emitted stages is caught here rather than only at IT time.
 */
class ParentFirstLookupPipelineBuilderTest {

  private final MongoInnerPredicateTranslator inner = new SimpleMongoInnerPredicateTranslator();
  private final ParentFirstLookupPipelineBuilder builder =
      new ParentFirstLookupPipelineBuilder(inner);

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
  @DisplayName("pipeline emits exactly three stages in order: $lookup, $match, $project")
  void threeStagesInOrder() {
    Aggregation agg = builder.build(split, "@.state == 'PENDING'");
    List<Document> stages = serialize(agg);
    assertThat(stages).hasSize(3);
    assertThat(stages.get(0)).containsKey("$lookup");
    assertThat(stages.get(1)).containsKey("$match");
    assertThat(stages.get(2)).containsKey("$project");
  }

  @Test
  @DisplayName("$lookup targets the split's childCollection and uses let with $$pId")
  void lookupTargetsChildCollectionWithLet() {
    Document lookup =
        (Document) serialize(builder.build(split, "@.state == 'PENDING'")).get(0).get("$lookup");
    assertThat(lookup.getString("from")).isEqualTo("orderItems");
    Document let = (Document) lookup.get("let");
    assertThat(let.getString("pId")).isEqualTo("$_id");
    assertThat(lookup.getString("as")).isEqualTo("__tmf630Match__");
  }

  @Test
  @DisplayName("$lookup inner pipeline enforces $expr $eq on parentId + inner criteria + $limit 1")
  void innerPipelineShape() {
    Document lookup =
        (Document) serialize(builder.build(split, "@.state == 'PENDING'")).get(0).get("$lookup");
    @SuppressWarnings("unchecked")
    List<Document> innerStages = (List<Document>) lookup.get("pipeline");
    assertThat(innerStages).hasSize(3);
    // stage 0: $match { $expr: { $eq: ["$parentId", "$$pId"] } }
    Document parentJoin = (Document) innerStages.get(0).get("$match");
    Document expr = (Document) parentJoin.get("$expr");
    @SuppressWarnings("unchecked")
    List<Object> eqOperands = (List<Object>) expr.get("$eq");
    assertThat(eqOperands).containsExactly("$parentId", "$$pId");
    // stage 1: $match { <inner-criteria on payload.field> }
    Document innerMatch = (Document) innerStages.get(1).get("$match");
    assertThat(innerMatch).isEqualTo(new Document("payload.state", "PENDING"));
    // stage 2: $limit 1
    assertThat(innerStages.get(2).get("$limit")).isEqualTo(1);
  }

  @Test
  @DisplayName("retention $match keeps only parents whose helper array is non-empty")
  void retainNonEmptyMatchArray() {
    Document retain =
        (Document) serialize(builder.build(split, "@.state == 'X'")).get(1).get("$match");
    Document arr = (Document) retain.get("__tmf630Match__");
    assertThat(arr).isEqualTo(new Document("$ne", List.of()));
  }

  @Test
  @DisplayName("$project strips the helper array from the output")
  void stripHelperArray()  {
    Document project =
        (Document) serialize(builder.build(split, "@.state == 'X'")).get(2).get("$project");
    assertThat(project.get("__tmf630Match__")).isEqualTo(0);
  }

  @Test
  @DisplayName("compound && inner predicate flows through as a single child-side criteria")
  void compoundInnerPredicate() {
    Document lookup =
        (Document)
            serialize(builder.build(split, "@.state == 'PENDING' && @.priority > 5"))
                .get(0)
                .get("$lookup");
    @SuppressWarnings("unchecked")
    List<Document> innerStages = (List<Document>) lookup.get("pipeline");
    Document innerMatch = (Document) innerStages.get(1).get("$match");
    // Contains $and with both leaf conditions expressed as payload.<field>
    assertThat(innerMatch).containsKey("$and");
  }

  private static List<Document> serialize(Aggregation agg) {
    AggregationOperationContext ctx = TypedAggregation.DEFAULT_CONTEXT;
    return agg.toPipeline(ctx);
  }
}
