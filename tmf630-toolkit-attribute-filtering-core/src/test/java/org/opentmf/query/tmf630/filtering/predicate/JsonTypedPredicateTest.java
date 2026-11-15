package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.querydsl.core.types.Constant;
import com.querydsl.core.types.Operation;
import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import jakarta.persistence.Entity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.springframework.core.convert.support.DefaultConversionService;
import tools.jackson.databind.JsonNode;

/**
 * A field of an open JSON type ({@code Map}, {@code JsonNode}) on a document root: the value's JSON
 * reading (ledger #4246, (a′)). On a JPA root it keeps the converter's 400.
 */
@SuppressWarnings("java:S1068")
class JsonTypedPredicateTest {

  private final PredicateFactory factory = new PredicateFactory(true, 256);
  private final PathBuilder<Doc> doc = new PathBuilder<>(Doc.class, "doc");
  private final PathBuilder<Row> row = new PathBuilder<>(Row.class, "row");
  private final ResolvedField node = new ResolvedField("j", JsonNode.class);
  private final ResolvedField map = new ResolvedField("m", Map.class);

  @Test
  void equalityIsAnyReadingOfTheValue() {
    Operation<?> eq = operation(factory.build(doc, node, TmfOperator.EQ, JsonTypedValue.read("42")));

    assertEquals(Ops.IN, eq.getOperator());
    assertEquals(List.of("42", 42L), constant(eq, 1));
  }

  @Test
  void notEqualIsNoneOfTheReadings() {
    Operation<?> ne = operation(factory.build(doc, map, TmfOperator.NE, JsonTypedValue.read("true")));

    assertEquals(Ops.NOT_IN, ne.getOperator());
    assertEquals(List.of("true", true), constant(ne, 1));
  }

  @Test
  void inIsAnyReadingOfAnyValue() {
    List<Object> values = List.of(JsonTypedValue.read("42"), JsonTypedValue.read("alpha"));

    Operation<?> in = operation(factory.buildMulti(doc, node, TmfOperator.IN, values));

    assertEquals(Ops.IN, in.getOperator());
    assertEquals(List.of("42", 42L, "alpha"), constant(in, 1));
  }

  @Test
  void ninIsNoReadingOfAnyValue() {
    List<Object> values = List.of(JsonTypedValue.read("1"), JsonTypedValue.read("2.5"));

    Operation<?> nin = operation(factory.buildMulti(doc, node, TmfOperator.NIN, values));

    assertEquals(Ops.NOT_IN, nin.getOperator());
    assertEquals(List.of("1", 1L, "2.5", 2.5d), constant(nin, 1));
  }

  @Test
  void anOrderingComparesTheNumber() {
    Operation<?> gt = operation(factory.build(doc, node, TmfOperator.GT, JsonTypedValue.read("10")));

    assertEquals(Ops.GT, gt.getOperator());
    assertEquals(10L, constant(gt, 1));
  }

  @Test
  void betweenComparesTwoNumbers() {
    List<Object> values = List.of(JsonTypedValue.read("1"), JsonTypedValue.read("9.5"));

    Operation<?> between = operation(factory.buildMulti(doc, node, TmfOperator.BETWEEN, values));

    assertEquals(Ops.BETWEEN, between.getOperator());
    assertEquals(1L, constant(between, 1));
    assertEquals(9.5d, constant(between, 2));
  }

  @Test
  void anOrderingOnANonNumberIsRefused() {
    JsonTypedValue alpha = JsonTypedValue.read("alpha");

    TmfFilteringException refused =
        assertThrows(
            TmfFilteringException.class, () -> factory.build(doc, node, TmfOperator.GT, alpha));
    assertEquals(
        "Operator requires a number for the JSON-typed field: j", refused.getMessage());
  }

  @Test
  void aStringOperatorMatchesTheText() {
    Operation<?> contains =
        operation(factory.build(doc, node, TmfOperator.CONTAINS, JsonTypedValue.read("lph")));

    assertEquals(Ops.STRING_CONTAINS, contains.getOperator());
    assertEquals("lph", constant(contains, 1));
  }

  @Test
  void aJpaRootKeepsTheConvertersRefusal() {
    JsonTypedValue value = JsonTypedValue.read("42");

    TmfFilteringException refused =
        assertThrows(
            TmfFilteringException.class, () -> factory.build(row, map, TmfOperator.EQ, value));
    assertEquals(
        "Field \"m\" has type Map which cannot be converted from a String.", refused.getMessage());
  }

  @Test
  void aJpaRootRefusesAMultiValueOperatorTheSameWay() {
    List<Object> values = List.of(JsonTypedValue.read("42"));

    TmfFilteringException refused =
        assertThrows(
            TmfFilteringException.class,
            () -> factory.buildMulti(row, node, TmfOperator.IN, values));
    assertEquals(
        "Field \"j\" has type JsonNode which cannot be converted from a String.",
        refused.getMessage());
  }

  @Test
  void theConverterReadsAJsonTypedValueForAFilterOnly() {
    ValueConverter converter = new ValueConverter(new DefaultConversionService());

    assertInstanceOf(JsonTypedValue.class, converter.convertFilterValue("42", Map.class, "m"));
    assertInstanceOf(JsonTypedValue.class, converter.convertFilterValue("42", JsonNode.class, "j"));
    assertEquals(42, converter.convertFilterValue("42", Integer.class, "i"));
    TmfFilteringException refused =
        assertThrows(TmfFilteringException.class, () -> converter.convert("42", Map.class, "m"));
    assertEquals(
        "Field \"m\" has type Map which cannot be converted from a String.", refused.getMessage());
  }

  private static Operation<?> operation(Predicate predicate) {
    return assertInstanceOf(Operation.class, predicate);
  }

  private static Object constant(Operation<?> operation, int index) {
    return assertInstanceOf(Constant.class, operation.getArg(index)).getConstant();
  }

  static class Doc {
    private Map<String, Object> m;
    private JsonNode j;
  }

  @Entity
  static class Row {
    private Map<String, Object> m;
    private JsonNode j;
  }
}
