package org.opentmf.query.tmf630.mongo.split;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Default {@link MongoInnerPredicateTranslator} covering the JsonPath grammar subset
 * most split-child queries need: dotted field paths, string and numeric literals, the
 * comparison operators {@code == != < <= > >=}, and boolean composition via
 * {@code &&} / {@code ||}. Parentheses group. Anything else is rejected with a
 * {@link TmfFilteringException} so the failure is visible at the URL boundary rather
 * than a silently-wrong result set.
 *
 * <p>Deliberate non-goals for this MVP first cut (follow-up cuts under the same
 * v3.0.0 SNAPSHOT):
 * <ul>
 *   <li>Regex operators ({@code =~}) — needs Mongo {@code $regex} plumbing plus the
 *       feature-flag gate the parent JSONB module uses.
 *   <li>Existence / null checks ({@code @.foo}).
 *   <li>Nested {@code [?(...)]} on collection-typed child fields inside the child
 *       itself.
 *   <li>Function-style JsonPath (e.g. {@code @.length()}).
 * </ul>
 *
 * <p>Consumers needing richer grammar can implement {@link MongoInnerPredicateTranslator}
 * and register their instance as the {@code mongoInnerPredicateTranslator} bean.
 */
public class SimpleMongoInnerPredicateTranslator implements MongoInnerPredicateTranslator {

  /** Default prefix for child-side use — child docs wrap the payload in this field. */
  public static final String CHILD_PAYLOAD_PREFIX = "payload.";
  /** Empty prefix for parent-side use — parent docs have fields at the top level. */
  public static final String PARENT_TOP_LEVEL_PREFIX = "";

  private static final Pattern LEAF_PATTERN =
      Pattern.compile(
          "\\s*@\\.([A-Za-z_][\\w.]*)\\s*([<>]=?|[!=]=)\\s*(?:'([^']*)'|(-?\\d+(?:\\.\\d+)?))\\s*");

  private final String fieldPrefix;

  /** Constructs a translator for child-side use ({@code payload.} prefix). */
  public SimpleMongoInnerPredicateTranslator() {
    this(CHILD_PAYLOAD_PREFIX);
  }

  /**
   * Constructs a translator with a caller-supplied field prefix. Pass
   * {@link #CHILD_PAYLOAD_PREFIX} for child-side criteria (child wrapper docs store
   * the child payload under a nested {@code payload} field) or
   * {@link #PARENT_TOP_LEVEL_PREFIX} for parent-side criteria (parent docs have their
   * fields at the top level).
   */
  public SimpleMongoInnerPredicateTranslator(String fieldPrefix) {
    this.fieldPrefix = fieldPrefix == null ? "" : fieldPrefix;
  }

  @Override
  public Criteria translate(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new TmfFilteringException(
          "Inner predicate expression is empty; provide a comparison like '@.state == \"X\"'");
    }
    return parseOrExpression(new Cursor(expression.trim()));
  }

  private Criteria parseOrExpression(Cursor c) {
    List<Criteria> ors = new ArrayList<>();
    ors.add(parseAndExpression(c));
    while (c.skipIf("||")) {
      ors.add(parseAndExpression(c));
    }
    if (ors.size() == 1) return ors.get(0);
    return new Criteria().orOperator(ors.toArray(Criteria[]::new));
  }

  private Criteria parseAndExpression(Cursor c) {
    List<Criteria> ands = new ArrayList<>();
    ands.add(parseAtom(c));
    while (c.skipIf("&&")) {
      ands.add(parseAtom(c));
    }
    if (ands.size() == 1) return ands.get(0);
    return new Criteria().andOperator(ands.toArray(Criteria[]::new));
  }

  private Criteria parseAtom(Cursor c) {
    c.skipWhitespace();
    if (c.peek() == '(') {
      c.advance(1);
      Criteria inner = parseOrExpression(c);
      c.skipWhitespace();
      if (c.peek() != ')') {
        throw new TmfFilteringException(
            "Expected ')' to close a grouped predicate at position " + c.position);
      }
      c.advance(1);
      return inner;
    }
    return parseLeaf(c);
  }

  private Criteria parseLeaf(Cursor c) {
    c.skipWhitespace();
    // Find the extent of this leaf — until we hit an unbalanced ) or a boolean joiner.
    int start = c.position;
    int end = c.position;
    while (end < c.source.length() && !isLeafBoundary(c.source, end)) {
      end++;
    }
    String leaf = c.source.substring(start, end);
    Matcher m = LEAF_PATTERN.matcher(leaf);
    if (!m.matches()) {
      throw new TmfFilteringException(
          "Inner predicate leaf could not be parsed: '"
              + leaf.trim()
              + "'. Supported shape: @.<field> <op> <literal>, "
              + "op ∈ {== != < <= > >=}, literal string or number.");
    }
    c.position = end;
    String field = m.group(1);
    String op = m.group(2);
    Object value = m.group(3) != null ? m.group(3) : parseNumber(m.group(4));
    return buildCriteria(field, op, value);
  }

  private static boolean isLeafBoundary(String source, int pos) {
    char ch = source.charAt(pos);
    if (ch == ')') return true;
    if (pos + 1 >= source.length()) return false;
    char next = source.charAt(pos + 1);
    return (ch == '&' && next == '&') || (ch == '|' && next == '|');
  }

  private Criteria buildCriteria(String field, String op, Object value) {
    Criteria base = Criteria.where(fieldPrefix + field);
    return switch (op) {
      case "==" -> base.is(value);
      case "!=" -> base.ne(value);
      case "<" -> base.lt(value);
      case "<=" -> base.lte(value);
      case ">" -> base.gt(value);
      case ">=" -> base.gte(value);
      default ->
          throw new TmfFilteringException(
              "Unsupported operator '" + op + "' — expected one of == != < <= > >=");
    };
  }

  private static Object parseNumber(String literal) {
    if (literal.contains(".")) return Double.parseDouble(literal);
    long l = Long.parseLong(literal);
    if (l == (int) l) return (int) l;
    return l;
  }

  private static final class Cursor {
    private final String source;
    private int position;

    Cursor(String source) {
      this.source = source;
    }

    char peek() {
      skipWhitespace();
      return position < source.length() ? source.charAt(position) : '\0';
    }

    boolean skipIf(String token) {
      skipWhitespace();
      if (source.regionMatches(position, token, 0, token.length())) {
        position += token.length();
        return true;
      }
      return false;
    }

    void advance(int n) {
      position += n;
    }

    void skipWhitespace() {
      while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
        position++;
      }
    }
  }
}
