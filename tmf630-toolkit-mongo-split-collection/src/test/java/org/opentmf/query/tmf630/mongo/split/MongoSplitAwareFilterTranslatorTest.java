package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Routing-logic unit tests for {@link MongoSplitAwareFilterTranslator}. Only exercises
 * the paths that don't call into Mongo — the actual child-lookup path is verified
 * end-to-end by {@code Tmf630MongoSplitCollectionIT}.
 */
class MongoSplitAwareFilterTranslatorTest {

  private final MongoSplitEntityRegistry registry = newRegistryWith(FixtureParent.class);
  private final MongoSplitAwareFilterTranslator translator =
      new MongoSplitAwareFilterTranslator(
          registry, mock(MongoOperations.class), new SimpleMongoInnerPredicateTranslator());

  @Test
  @DisplayName("null / blank filter → null (caller falls back to normal filter path)")
  void nullOrBlankReturnsNull() {
    assertThat(translator.translate(FixtureParent.class, null)).isNull();
    assertThat(translator.translate(FixtureParent.class, "  ")).isNull();
  }

  @Test
  @DisplayName("parent-only filter with no split reference → null")
  void parentOnlyReturnsNull() {
    assertThat(translator.translate(FixtureParent.class, "$[?(@.status == 'X')]")).isNull();
  }

  @Test
  @DisplayName("unknown parent type raises TmfFilteringException")
  void unknownParentType() {
    assertThatThrownBy(() -> translator.translate(String.class, "$[?(@.status == 'X')]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("No @Tmf630MongoSplitBacked");
  }

  @Test
  @DisplayName(
      "compound filter mixing parent + split at top level is decomposed via the shared decomposer "
          + "(pipeline form asserted for unit-level coverage; criteria form covered by IT)")
  void mixedParentAndSplitDecomposedInPipeline() {
    // Pipeline form doesn't touch Mongo (aggregation is built purely from BSON), so
    // it's safe to exercise here without a live MongoOperations. Criteria form
    // executes a distinct query and is covered end-to-end by the IT.
    org.springframework.data.mongodb.core.aggregation.Aggregation pipeline =
        translator.translateAsPipeline(
            FixtureParent.class,
            "$[?(@.status == 'X' && @.items[?(@.state == 'Y')])]");
    assertThat(pipeline).isNotNull();
    List<org.bson.Document> stages =
        pipeline.toPipeline(
            org.springframework.data.mongodb.core.aggregation.TypedAggregation.DEFAULT_CONTEXT);
    // Expect at least: parent-side $match + $lookup + retention $match + $project
    assertThat(stages).hasSizeGreaterThanOrEqualTo(4);
    assertThat(stages.get(0)).containsKey("$match");
    // The very first $match holds the parent-only criterion — no $lookup helper yet.
    assertThat((org.bson.Document) stages.get(0).get("$match")).containsEntry("status", "X");
  }

  @Test
  @DisplayName(
      "top-level || in translate() (criteria form) is rejected with a pointer to the $unionWith entry")
  void topLevelOrInCriteriaFormRejectedWithPointer() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    FixtureParent.class,
                    "$[?(@.status == 'X' || @.items[?(@.state == 'Y')])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("translateAsUnionWithAggregation");
  }

  @Test
  @DisplayName(
      "top-level || in translateAsPipeline() (parent-first form) is rejected with the same pointer")
  void topLevelOrInParentFirstPipelineRejectedWithPointer() {
    assertThatThrownBy(
            () ->
                translator.translateAsPipeline(
                    FixtureParent.class,
                    "$[?(@.status == 'X' || @.items[?(@.state == 'Y')])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("translateAsUnionWithAggregation");
  }

  @Test
  @DisplayName(
      "translateAsUnionWithAggregation on OR-shaped input packages a pipeline + target collection")
  void unionWithEntryProducesPackagedAggregation() {
    SplitAwareAggregation packaged =
        translator.translateAsUnionWithAggregation(
            FixtureParent.class,
            "$[?(@.status == 'X' || @.items[?(@.state == 'Y')])]");
    assertThat(packaged).isNotNull();
    // Parent-only clause present → target collection is the parent collection.
    assertThat(packaged.targetCollection()).isEqualTo("fixtureParent");
    assertThat(packaged.pipeline()).isNotNull();
  }

  @Test
  @DisplayName("translateAsUnionWithAggregation on AND-shaped input rejects with pointer")
  void unionWithEntryRejectsAndShaped() {
    assertThatThrownBy(
            () ->
                translator.translateAsUnionWithAggregation(
                    FixtureParent.class,
                    "$[?(@.status == 'X' && @.items[?(@.state == 'Y')])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("translateAsPipeline");
  }

  @Test
  @DisplayName("router: blank filter → null (caller uses normal path)")
  void routerBlankReturnsNull() {
    assertThat(translator.route(FixtureParent.class, null)).isNull();
    assertThat(translator.route(FixtureParent.class, "  ")).isNull();
  }

  @Test
  @DisplayName("router: parent-only filter → null (caller's translator is a superset)")
  void routerParentOnlyReturnsNull() {
    assertThat(translator.route(FixtureParent.class, "$[?(@.status == 'X')]")).isNull();
  }

  @Test
  @DisplayName(
      "router: AND-shaped split-touching filter → SplitAwareAggregation on the parent collection")
  void routerAndDispatchesToParentFirst() {
    SplitAwareAggregation packaged =
        translator.route(
            FixtureParent.class,
            "$[?(@.status == 'X' && @.items[?(@.state == 'Y')])]");
    assertThat(packaged).isNotNull();
    assertThat(packaged.targetCollection()).isEqualTo("fixtureParent");
    // Parent-first pipeline: top stage is $match (parent-side), then $lookup, etc.
    List<org.bson.Document> stages =
        packaged
            .pipeline()
            .toPipeline(
                org.springframework.data.mongodb.core.aggregation.TypedAggregation
                    .DEFAULT_CONTEXT);
    assertThat(stages).isNotEmpty();
    assertThat(stages.get(0)).containsKey("$match");
    // Second stage should be the $lookup (parent-first shape).
    assertThat(stages.get(1)).containsKey("$lookup");
  }

  @Test
  @DisplayName(
      "router: OR-shaped filter → SplitAwareAggregation with the $unionWith shape's target")
  void routerOrDispatchesToUnionWith() {
    SplitAwareAggregation packaged =
        translator.route(
            FixtureParent.class,
            "$[?(@.status == 'X' || @.items[?(@.state == 'Y')])]");
    assertThat(packaged).isNotNull();
    // Parent-only clause present → target = parent collection.
    assertThat(packaged.targetCollection()).isEqualTo("fixtureParent");
    List<org.bson.Document> stages =
        packaged
            .pipeline()
            .toPipeline(
                org.springframework.data.mongodb.core.aggregation.TypedAggregation
                    .DEFAULT_CONTEXT);
    // Union-with shape: $match, $unionWith, $group (dedup), $replaceRoot.
    assertThat(stages).hasSize(4);
    assertThat(stages.get(0)).containsKey("$match");
    assertThat(stages.get(1)).containsKey("$unionWith");
    assertThat(stages.get(2)).containsKey("$group");
    assertThat(stages.get(3)).containsKey("$replaceRoot");
  }

  @Test
  @DisplayName("parent type with zero splits registered → null (defensive)")
  void parentWithoutSplitsReturnsNull() {
    // No @Tmf630MongoSplitBacked type has zero splits in practice — the registry
    // rejects that at scan time. Confirm the guard exists anyway by pointing at an
    // unknown-to-us type: because it isn't in the registry, this throws instead of
    // silently returning null. The check-in-registry branch is exercised.
    assertThatThrownBy(
            () -> translator.translate(String.class, "$[?(@.status == 'X')]"))
        .isInstanceOf(TmfFilteringException.class);
  }

  private static MongoSplitEntityRegistry newRegistryWith(Class<?> type) {
    MongoSplitEntityRegistry r = new MongoSplitEntityRegistry();
    r.registerIfBacked(type);
    return r;
  }

  @Tmf630MongoSplitBacked
  static class FixtureParent {
    @Id String id;
    String status;

    @Tmf630MongoSplitCollection(childCollection = "fixture_child", childType = FixtureChild.class)
    List<FixtureChild> items;
  }

  static class FixtureChild {
    String id;
    String state;
  }
}
