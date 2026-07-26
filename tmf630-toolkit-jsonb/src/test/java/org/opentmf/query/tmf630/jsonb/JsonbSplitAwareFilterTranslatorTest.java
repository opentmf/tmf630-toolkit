package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

class JsonbSplitAwareFilterTranslatorTest {

  private final JsonbPathExtractor extractor = new JsonbPathExtractor("payload");
  private final JsonbJsonPathTranslator delegate =
      new JsonbJsonPathTranslator(extractor, "payload");
  private JsonbEntityRegistry registry;
  private JsonbSplitAwareFilterTranslator translator;

  @BeforeEach
  void setUp() {
    registry = new StubRegistry();
    translator = new JsonbSplitAwareFilterTranslator(delegate, registry);
  }

  @Test
  @DisplayName("parent-only filter delegates unchanged when domain has no splits")
  void parentOnlyNoSplits() {
    JsonbClause c = translator.translate(NoSplitsDomain.class, "$[?(@.status == 'X')]");
    assertThat(c.sql()).isEqualTo("jsonb_path_exists(payload, ?::jsonpath)");
    assertThat(c.params()).containsExactly("$ ? (@.status == \"X\")");
  }

  @Test
  @DisplayName("parent-only filter delegates unchanged when domain HAS splits (no split ref)")
  void parentOnlyWithSplitsPresent() {
    JsonbClause c = translator.translate(OrderDomain.class, "$[?(@.status == 'X')]");
    assertThat(c.sql()).isEqualTo("jsonb_path_exists(payload, ?::jsonpath)");
  }

  @Test
  @DisplayName(
      "top-level array correlation into a split field emits EXISTS subquery on the child table")
  void topLevelSplitCorrelation() {
    JsonbClause c =
        translator.translate(OrderDomain.class, "$[?(@.items[?(@.state == 'PENDING')])]");
    assertThat(c.sql())
        .isEqualTo(
            "EXISTS (SELECT 1 FROM order_item WHERE parent_id = order_row.id AND"
                + " jsonb_path_exists(payload, ?::jsonpath))");
    assertThat(c.params()).containsExactly("$ ? (@.state == \"PENDING\")");
  }

  @Test
  @DisplayName("split correlation with compound child predicate flows through the base translator")
  void topLevelSplitCorrelationCompoundInner() {
    JsonbClause c =
        translator.translate(
            OrderDomain.class,
            "$[?(@.items[?(@.state == 'PENDING' && @.priority > 5)])]");
    assertThat(c.params())
        .containsExactly("$ ? (@.state == \"PENDING\" && @.priority > 5)");
    assertThat(c.sql())
        .contains("EXISTS (SELECT 1 FROM order_item WHERE parent_id = order_row.id");
  }

  @Test
  @DisplayName(
      "compound filter mixing parent + split at top level is decomposed and AND'd via SQL")
  void mixedParentAndSplitDecomposedToAnd() {
    JsonbClause c =
        translator.translate(
            OrderDomain.class, "$[?(@.status == 'X' && @.items[?(@.state == 'Y')])]");
    // Combined shape: (parentJsonpath AND EXISTS(...))
    assertThat(c.sql())
        .contains("jsonb_path_exists(payload, ?::jsonpath)")
        .contains("EXISTS (SELECT 1 FROM order_item");
    assertThat(c.sql()).contains(" AND ");
    // Both params flow through: parent jsonpath first, then child jsonpath.
    assertThat(c.params()).hasSize(2);
    assertThat(c.params().get(0)).asString().contains("@.status == \"X\"");
    assertThat(c.params().get(1)).asString().contains("@.state == \"Y\"");
  }

  @Test
  @DisplayName("top-level || mixing parent + split composes with SQL OR")
  void topLevelOrComposesWithSqlOr() {
    JsonbClause c =
        translator.translate(
            OrderDomain.class, "$[?(@.status == 'X' || @.items[?(@.state == 'Y')])]");
    assertThat(c.sql())
        .contains("jsonb_path_exists(payload, ?::jsonpath)")
        .contains("EXISTS (SELECT 1 FROM order_item")
        .contains(" OR ");
    assertThat(c.params()).hasSize(2);
    assertThat(c.params().get(0)).asString().contains("@.status == \"X\"");
    assertThat(c.params().get(1)).asString().contains("@.state == \"Y\"");
  }

  @Test
  @DisplayName("mixed top-level && and || is rejected (operator precedence not parsed)")
  void mixedTopLevelAndOrRejected() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    OrderDomain.class,
                    "$[?(@.status == 'X' && @.priority > 5 || @.items[?(@.state == 'Y')])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("mixes top-level '&&' and '||'");
  }

  @Test
  @DisplayName("non-default child payload column rejected with clear message")
  void nonDefaultChildPayloadColumnRejected() {
    registry = new StubRegistry(splitWithCustomPayloadColumn());
    translator = new JsonbSplitAwareFilterTranslator(delegate, registry);
    assertThatThrownBy(
            () ->
                translator.translate(
                    OrderDomain.class, "$[?(@.items[?(@.state == 'X')])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("default child payload column name");
  }

  @Test
  @DisplayName("unknown domain type — no @Tmf630JsonbBacked mapping — rejected")
  void unknownDomainType() {
    assertThatThrownBy(
            () -> translator.translate(String.class, "$[?(@.x == 'y')]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("No @Tmf630JsonbBacked");
  }

  @Test
  @DisplayName("bare wrapper [?(...)] form on split correlation still matches")
  void bareWrapperFormMatchesSplitCorrelation() {
    JsonbClause c = translator.translate(OrderDomain.class, "[?(@.items[?(@.state == 'X')])]");
    assertThat(c.sql())
        .contains("EXISTS (SELECT 1 FROM order_item");
  }

  // --- fixtures ---

  static class NoSplitsDomain {}

  static class OrderDomain {}

  static class OrderItem {}

  static class StubRegistry extends JsonbEntityRegistry {
    private final JsonbSplitCollectionMetadata split;

    StubRegistry() {
      this(defaultSplit());
    }

    StubRegistry(JsonbSplitCollectionMetadata split) {
      this.split = split;
    }

    @Override
    public Optional<JsonbEntityMetadata> forDomainType(Class<?> type) {
      if (OrderDomain.class.equals(type)) {
        return Optional.of(
            new JsonbEntityMetadata(
                Object.class,
                OrderDomain.class,
                "payload",
                "order_row",
                new JsonbAuditColumns(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()),
                List.of(split)));
      }
      if (NoSplitsDomain.class.equals(type)) {
        return Optional.of(
            new JsonbEntityMetadata(
                Object.class,
                NoSplitsDomain.class,
                "payload",
                "plain_row",
                new JsonbAuditColumns(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()),
                List.of()));
      }
      return Optional.empty();
    }
  }

  private static JsonbSplitCollectionMetadata defaultSplit() {
    return new JsonbSplitCollectionMetadata(
        "items",
        OrderItem.class,
        "order_item",
        100,
        "parent_id",
        "item_id",
        "item_order",
        "payload");
  }

  private static JsonbSplitCollectionMetadata splitWithCustomPayloadColumn() {
    return new JsonbSplitCollectionMetadata(
        "items",
        OrderItem.class,
        "order_item",
        100,
        "parent_id",
        "item_id",
        "item_order",
        "body"); // <— custom payload column
  }
}
