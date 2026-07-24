package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.repository.support.SpringDataMongodbQuery;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Cross-executor parity matrix: every filter (including paths whose BSON names differ from their
 * Java names via {@code @Field} renames) combined with every sort routing must return the same id
 * set and total from {@link Tmf630MongoCorrelatedSortExecutor#findAll} as the plain find path does
 * for the identical predicate. The reference is {@link SpringDataMongodbQuery} — the exact engine
 * behind {@code QuerydslPredicateExecutor#findAll(predicate, pageable)} on Mongo repositories —
 * used directly because this module generates no QueryDSL Q classes.
 */
@SpringBootTest(classes = Tmf630MongoFilterSortParityIT.TestApp.class)
@Testcontainers
class Tmf630MongoFilterSortParityIT {

  @Container
  static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.5");

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private Tmf630MongoCorrelatedSortExecutor executor;

  private static final PathBuilder<KeyedOrder> ROOT =
      new PathBuilder<>(KeyedOrder.class, "keyedOrder");

  // Correlated sort key per order: sku of the item with id L1; orders lacking an L1 item
  // resolve to null and land last (the executor's nulls-last policy), tie-broken by serial.
  private static final Map<String, String> L1_SKU = Map.of("S1", "d-sku", "S3", "c-sku");

  // Positional sort key per order: sku of the literal first item element.
  private static final Map<String, String> INDEX0_SKU =
      Map.of("S1", "d-sku", "S2", "a-sku", "S3", "c-sku", "S4", "b-sku");

  @BeforeEach
  void setUp() {
    mongoTemplate.dropCollection(KeyedOrder.class);
    mongoTemplate.insertAll(
        List.of(
            new KeyedOrder(new Key("S1", "acme"), List.of(new Item("L1", "d-sku")), "open"),
            new KeyedOrder(new Key("S2", "acme"), List.of(new Item("L2", "a-sku")), "open"),
            new KeyedOrder(
                new Key("S3", "beta"),
                List.of(new Item("L1", "c-sku"), new Item("L9", "z-sku")),
                "closed"),
            new KeyedOrder(new Key("S4", "acme"), List.of(new Item("L3", "b-sku")), "hold")));
  }

  // sonar java:S125 — inline enum-body comments describe dotted path fragments
  // (e.g. `item.0.id`, `[N]`) that Sonar mis-detects as commented-out code.
  @SuppressWarnings("java:S125")
  enum FilterCase {
    COMPOSITE_SERIAL_EQ(ROOT.getString("key.serial").eq("S1"), Set.of("S1")),
    COMPOSITE_TENANT_EQ(ROOT.getString("key.tenant").eq("acme"), Set.of("S1", "S2", "S4")),
    COMPOSITE_TENANT_IN(
        ROOT.getString("key.tenant").in("acme", "beta"), Set.of("S1", "S2", "S3", "S4")),
    ITEM_ID_EQ(ROOT.getString("item.id").eq("L1"), Set.of("S1", "S3")),
    // TMF630 Part 6 positional index [N] in filter path — dotted numeric hop
    // (`item.0.id`) resolves to the literal first element on Mongo. S1's [0] is L1;
    // S3's [0] is also L1 (its L9 sits at [1]); S2/S4 have no L1 at index 0.
    ITEM_INDEX0_ID_EQ(ROOT.getString("item.0.id").eq("L1"), Set.of("S1", "S3")),
    // TMF630 Part 6 length() on collection field — Ops.COL_SIZE equality; must
    // survive the aggregation executor's $match stage the same way it survives
    // the plain find path. S3 has 2 items; all others have 1.
    ITEM_LENGTH_EQ_2(
        Expressions.numberOperation(
                Integer.class, Ops.COL_SIZE, ROOT.getCollection("item", Item.class))
            .eq(2),
        Set.of("S3")),
    STATUS_EQ(ROOT.getString("status").eq("open"), Set.of("S1", "S2"));

    final Predicate predicate;
    final Set<String> expectedIds;

    FilterCase(Predicate predicate, Set<String> expectedIds) {
      this.predicate = predicate;
      this.expectedIds = expectedIds;
    }
  }

  enum SortCase {
    NONE(TmfSort.empty()),
    PLAIN(new TmfSort(List.of(term(TmfSortTerm.Kind.PLAIN, "key.serial")))),
    SIMPLE_RICH(
        new TmfSort(
            List.of(
                term(TmfSortTerm.Kind.SIMPLE_RICH, "item[id=L1].sku"),
                term(TmfSortTerm.Kind.PLAIN, "key.serial")))),
    JSONPATH(
        new TmfSort(
            List.of(
                term(TmfSortTerm.Kind.JSONPATH, "$.item[?(@.id == 'L1')].sku"),
                term(TmfSortTerm.Kind.PLAIN, "key.serial")))),
    JSONPATH_INDEX(
        new TmfSort(
            List.of(
                term(TmfSortTerm.Kind.JSONPATH, "$.item[0].sku"),
                term(TmfSortTerm.Kind.PLAIN, "key.serial"))));

    final TmfSort sort;

    SortCase(TmfSort sort) {
      this.sort = sort;
    }

    private static TmfSortTerm term(TmfSortTerm.Kind kind, String expression) {
      return new TmfSortTerm(Sort.Direction.ASC, kind, expression);
    }
  }

  static Stream<Arguments> matrix() {
    return Stream.of(FilterCase.values())
        .flatMap(f -> Stream.of(SortCase.values()).map(s -> Arguments.of(f, s)));
  }

  @ParameterizedTest
  @MethodSource("matrix")
  void executorReturnsSameRowsAndTotalAsRepositoryFind(FilterCase filter, SortCase sort) {
    List<String> expected =
        ids(
            new SpringDataMongodbQuery<>(mongoTemplate, KeyedOrder.class)
                .where(filter.predicate)
                .fetch());
    Page<KeyedOrder> actual =
        executor.findAll(KeyedOrder.class, filter.predicate, sort.sort, PageRequest.of(0, 10));

    // Fixture sanity: the reference path itself sees the seeded rows.
    assertEquals(filter.expectedIds, Set.copyOf(expected));

    assertEquals(Set.copyOf(expected), Set.copyOf(ids(actual.getContent())));
    assertEquals(expected.size(), actual.getTotalElements());
    if (sort != SortCase.NONE) {
      assertEquals(expectedOrder(filter, sort), ids(actual.getContent()));
    }
  }

  private static List<String> ids(List<KeyedOrder> orders) {
    return orders.stream().map(o -> o.key.serial).toList();
  }

  private static Comparator<String> sortKeyComparator(Map<String, String> sortKey) {
    return Comparator.comparing(sortKey::get, Comparator.nullsLast(Comparator.naturalOrder()));
  }

  private static List<String> expectedOrder(FilterCase filter, SortCase sort) {
    Comparator<String> bySerial = Comparator.naturalOrder();
    Map<String, String> sortKey = sort == SortCase.JSONPATH_INDEX ? INDEX0_SKU : L1_SKU;
    Comparator<String> cmp =
        sort == SortCase.PLAIN
            ? bySerial
            : sortKeyComparator(sortKey).thenComparing(bySerial);
    return filter.expectedIds.stream().sorted(cmp).toList();
  }

  // ---------- Fixture: all BSON renames the bug depends on, nothing else ----------

  /**
   * {@code @Id} composite key whose sub-fields carry {@code @Field} renames (one to {@code _id}),
   * an embedded list whose element {@code id} is stored as {@code _id}, and a plain unrenamed
   * scalar ({@code status}) as control.
   */
  @Document(collection = "keyed_orders")
  static class KeyedOrder {
    @Id Key key;
    List<Item> item;
    String status;

    KeyedOrder() {}

    KeyedOrder(Key key, List<Item> item, String status) {
      this.key = key;
      this.item = item;
      this.status = status;
    }
  }

  static class Key {
    @Field("_id") String serial;
    @Field("t") String tenant;

    Key() {}

    Key(String serial, String tenant) {
      this.serial = serial;
      this.tenant = tenant;
    }
  }

  static class Item {
    @Field("_id") String id;
    String sku;

    Item() {}

    Item(String id, String sku) {
      this.id = id;
      this.sku = sku;
    }
  }

  @SpringBootApplication
  static class TestApp {}
}
