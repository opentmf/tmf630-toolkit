package org.opentmf.query.tmf630.mongo;

import java.math.BigDecimal;
import java.util.List;

public final class JsonPathSortAst {

  public record SortPath(List<ArrayHop> hops, LeafExpression leaf) {

    public SortPath {
      hops = List.copyOf(hops);
    }
  }

  /**
   * One array traversal step. {@code index} carries a positional {@code [N]} selection
   * (TMF630 Part 6 JSONPath 0-based index access): the hop picks the literal element at
   * that position of the (predicate-filtered) array instead of the first match. A
   * {@code null} index keeps the pre-existing first-match semantics.
   */
  public record ArrayHop(String arrayPath, Predicate predicate, Integer index) {

    public ArrayHop(String arrayPath, Predicate predicate) {
      this(arrayPath, predicate, null);
    }
  }

  public sealed interface Predicate
      permits AndPredicate, OrPredicate, ComparisonPredicate, AlwaysTruePredicate {}

  public record AndPredicate(Predicate left, Predicate right) implements Predicate {}

  public record OrPredicate(Predicate left, Predicate right) implements Predicate {}

  public record ComparisonPredicate(
      String fieldPath, ComparisonOperator op, Literal literal) implements Predicate {}

  public record AlwaysTruePredicate() implements Predicate {

    public static final AlwaysTruePredicate INSTANCE = new AlwaysTruePredicate();
  }

  public enum ComparisonOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE
  }

  public sealed interface Literal
      permits StringLiteral, NumberLiteral, BooleanLiteral, NullLiteral {}

  public record StringLiteral(String value) implements Literal {}

  public record NumberLiteral(BigDecimal value) implements Literal {}

  public record BooleanLiteral(boolean value) implements Literal {}

  public record NullLiteral() implements Literal {}

  public sealed interface LeafExpression permits FieldRef, Aggregator, Coercion {}

  public record FieldRef(String fieldPath) implements LeafExpression {}

  public record Aggregator(AggregatorOp op, LeafExpression inner) implements LeafExpression {}

  public record Coercion(CoercionType type, LeafExpression inner) implements LeafExpression {}

  public enum AggregatorOp {
    MIN,
    MAX
  }

  public enum CoercionType {
    STR("string"),
    NUM("double"),
    DATE("date");

    private final String mongoTypeName;

    CoercionType(String mongoTypeName) {
      this.mongoTypeName = mongoTypeName;
    }

    public String mongoTypeName() {
      return mongoTypeName;
    }
  }

  private JsonPathSortAst() {}
}
