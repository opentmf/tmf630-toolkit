package org.opentmf.query.tmf630.filtering;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a JsonPath {@code filter=} expression that mixes parent-only clauses with
 * split-collection references into a structured decomposition, so backend translators
 * can compose the two halves against their native storage without duplicating the AST
 * walk.
 *
 * <p>Shared between the JSONB and Mongo split-collection modules: the classification of
 * "parent-side" vs "split-side" is purely a function of the JsonPath text and the set
 * of split field names — no backend-specific concerns.
 *
 * <p><strong>Supported input shapes for this MVP first cut:</strong>
 *
 * <ul>
 *   <li>Parent-only: {@code $[?(@.status == 'X')]} — one parent clause, zero splits.
 *   <li>Single split correlation: {@code $[?(@.items[?(@.state == 'Y')])]} — zero
 *       parent clauses, one split.
 *   <li>Top-level {@code &&} conjunction mixing any number of parent clauses and any
 *       number of split correlations: {@code $[?(@.status == 'X' && @.priority > 5
 *       && @.items[?(@.state == 'Y')] && @.characteristic[?(@.name == 'color')])]}
 *       — the entire expression must be a top-level conjunction of leaf-level
 *       parent comparisons and top-level split correlations.
 * </ul>
 *
 * <p>Top-level {@code ||} disjunctions are supported symmetrically with {@code &&}:
 * the returned {@link Combinator} distinguishes the two so backend translators pick
 * the right composition (SQL {@code AND}/{@code OR} for JSONB, parent-first
 * {@code $lookup}-chain vs. {@code $unionWith} for Mongo).
 *
 * <p><strong>Rejected (deferred to later cuts under the same 3.0.0-SNAPSHOT):</strong>
 *
 * <ul>
 *   <li>Mixed {@code &&} and {@code ||} at the top level, e.g.
 *       {@code a && b || c}. Handling operator precedence needs a proper AST parser
 *       rather than the top-level split this decomposer performs; group with
 *       parentheses inside a single top-level conjunct if needed.
 *   <li>Nested boolean expressions where a split reference appears inside a
 *       parenthesised subgroup. Same reason.
 * </ul>
 *
 * <p>The decomposer never enforces "same split field only once" — two references to
 * the same field with different inner predicates are legal and mean "at least one
 * child satisfies predicate A AND at least one child (possibly a different one)
 * satisfies predicate B" (different-item semantics).
 */
public final class TmfSplitFilterDecomposer {

  private TmfSplitFilterDecomposer() {}

  /**
   * Splits a {@code filter=} JsonPath expression into its parent-side and split-side
   * halves.
   *
   * @param filterExpression the full expression, e.g. {@code $[?(@.status == 'X' &&
   *     @.items[?(@.state == 'Y')])]}. Bare wrapper form {@code [?(...)]} is also
   *     accepted.
   * @param splitFieldNames the set of field names that are declared as split
   *     collections on the parent domain — anything referenced via
   *     {@code @.<name>[?(...)]} is treated as a split correlation.
   * @return the decomposition; empty {@link Decomposition#parentOnlyFilter()} means
   *     the expression touches only splits, empty {@link Decomposition#splitClauses()}
   *     means it touches only the parent.
   * @throws TmfFilteringException if the expression is malformed, uses a rejected
   *     shape (top-level {@code ||}, nested split reference), or the decomposer
   *     cannot classify a clause.
   */
  public static Decomposition decompose(String filterExpression, Set<String> splitFieldNames) {
    if (filterExpression == null || filterExpression.isBlank()) {
      return new Decomposition(Optional.empty(), List.of(), Combinator.AND);
    }
    String body = unwrap(filterExpression.trim());
    boolean hasTopLevelAnd = containsTopLevelOperator(body, '&');
    boolean hasTopLevelOr = containsTopLevelOperator(body, '|');
    if (hasTopLevelAnd && hasTopLevelOr) {
      throw new TmfFilteringException(
          "Filter '"
              + filterExpression
              + "' mixes top-level '&&' and '||'. Operator-precedence parsing is not "
              + "supported here — group the intended operand with parentheses inside a "
              + "single top-level conjunct, or split the request into multiple calls.");
    }
    Combinator combinator = hasTopLevelOr ? Combinator.OR : Combinator.AND;
    List<String> topLevelClauses = splitTopLevel(body, combinator);

    List<String> parentClauses = new ArrayList<>();
    List<SplitClauseRef> splitClauses = new ArrayList<>();
    for (String clause : topLevelClauses) {
      String trimmed = clause.trim();
      Optional<SplitClauseRef> asSplit = tryParseAsSplitCorrelation(trimmed, splitFieldNames);
      if (asSplit.isPresent()) {
        splitClauses.add(asSplit.get());
        continue;
      }
      if (referencesAnySplitField(trimmed, splitFieldNames)) {
        throw new TmfFilteringException(
            "Filter clause '"
                + trimmed
                + "' references a split field but is not a bare top-level array "
                + "correlation. Move it outside the parent-only clauses, e.g. "
                + "@.<splitField>[?(<inner>)].");
      }
      parentClauses.add(trimmed);
    }

    Optional<String> parentOnlyFilter =
        parentClauses.isEmpty() ? Optional.empty() : Optional.of(rewrap(parentClauses, combinator));
    return new Decomposition(parentOnlyFilter, List.copyOf(splitClauses), combinator);
  }

  /**
   * Structured result of {@link #decompose(String, Set)}. Either half may be empty;
   * both being empty means the input filter was blank. {@link #combinator()} tells
   * the backend translator whether the parent-only piece and the split clauses
   * should be composed with logical AND or logical OR.
   */
  public record Decomposition(
      Optional<String> parentOnlyFilter,
      List<SplitClauseRef> splitClauses,
      Combinator combinator) {
    public boolean isEmpty() {
      return parentOnlyFilter.isEmpty() && splitClauses.isEmpty();
    }
  }

  /** Top-level boolean combinator that binds the decomposition's pieces together. */
  public enum Combinator {
    AND,
    OR
  }

  /** One split-collection reference resolved out of a compound filter. */
  public record SplitClauseRef(String splitFieldName, String innerPredicate) {}

  // --- internals ---

  private static String unwrap(String expr) {
    // Handle both $[?( ... )] and [?( ... )] wrappers; return the inner body.
    if (expr.startsWith("$[?(") && expr.endsWith(")]")) {
      return expr.substring(4, expr.length() - 2).trim();
    }
    if (expr.startsWith("[?(") && expr.endsWith(")]")) {
      return expr.substring(3, expr.length() - 2).trim();
    }
    throw new TmfFilteringException(
        "Filter expression must be wrapped in '$[?(...)]' or '[?(...)]': " + expr);
  }

  private static String rewrap(List<String> parentClauses, Combinator combinator) {
    String joiner = combinator == Combinator.OR ? " || " : " && ";
    return "$[?(" + String.join(joiner, parentClauses) + ")]";
  }

  private static boolean containsTopLevelOperator(String body, char opChar) {
    int depth = 0;
    for (int i = 0; i < body.length() - 1; i++) {
      char c = body.charAt(i);
      if (c == '(' || c == '[') depth++;
      else if (c == ')' || c == ']') depth--;
      else if (depth == 0 && c == opChar && body.charAt(i + 1) == opChar) return true;
    }
    return false;
  }

  private static List<String> splitTopLevel(String body, Combinator combinator) {
    char opChar = combinator == Combinator.OR ? '|' : '&';
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    int i = 0;
    while (i < body.length() - 1) {
      char c = body.charAt(i);
      if (c == '(' || c == '[') {
        depth++;
        i++;
      } else if (c == ')' || c == ']') {
        depth--;
        i++;
      } else if (depth == 0 && c == opChar && body.charAt(i + 1) == opChar) {
        parts.add(body.substring(start, i));
        start = i + 2;
        i += 2;
      } else {
        i++;
      }
    }
    parts.add(body.substring(start));
    return parts;
  }

  private static Optional<SplitClauseRef> tryParseAsSplitCorrelation(
      String clause, Set<String> splitFieldNames) {
    // Shape: @.<field>[?(<inner>)]
    if (!clause.startsWith("@.")) return Optional.empty();
    int bracketOpen = clause.indexOf('[');
    if (bracketOpen < 0) return Optional.empty();
    String fieldName = clause.substring(2, bracketOpen);
    if (!splitFieldNames.contains(fieldName)) return Optional.empty();
    // The rest must be exactly [?(<inner>)]
    String tail = clause.substring(bracketOpen);
    if (!tail.startsWith("[?(") || !tail.endsWith(")]")) return Optional.empty();
    String inner = tail.substring(3, tail.length() - 2);
    return Optional.of(new SplitClauseRef(fieldName, inner));
  }

  private static boolean referencesAnySplitField(String clause, Set<String> splitFieldNames) {
    for (String name : splitFieldNames) {
      if (clause.contains("@." + name + "[")) return true;
    }
    return false;
  }
}
