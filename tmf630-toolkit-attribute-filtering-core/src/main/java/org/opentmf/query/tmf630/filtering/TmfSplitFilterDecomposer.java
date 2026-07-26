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
 * <p><strong>Rejected (deferred to later cuts under the same 3.0.0-SNAPSHOT):</strong>
 *
 * <ul>
 *   <li>Top-level disjunctions ({@code || }). Combining a parent-side clause with a
 *       split-side clause via OR needs backend-specific composition logic (SQL
 *       trilean gotchas for JSONB, aggregation-shape choices for Mongo). Deferred.
 *   <li>Nested boolean expressions where a split reference appears inside a
 *       parenthesised subgroup. Requires a proper AST parser rather than the
 *       top-level split this MVP performs.
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
      return new Decomposition(Optional.empty(), List.of());
    }
    String body = unwrap(filterExpression.trim());
    if (containsTopLevelOr(body)) {
      throw new TmfFilteringException(
          "Filter '"
              + filterExpression
              + "' uses a top-level '||' that mixes clauses; disjunctions of "
              + "parent-side and split-side predicates are not yet supported. "
              + "Split the request into two calls or restructure the URL.");
    }
    List<String> topLevelConjuncts = splitTopLevelAnds(body);

    List<String> parentClauses = new ArrayList<>();
    List<SplitClauseRef> splitClauses = new ArrayList<>();
    for (String conjunct : topLevelConjuncts) {
      String trimmed = conjunct.trim();
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
                + "correlation. Move it outside the parent-only conjuncts, e.g. "
                + "@.<splitField>[?(<inner>)].");
      }
      parentClauses.add(trimmed);
    }

    Optional<String> parentOnlyFilter =
        parentClauses.isEmpty() ? Optional.empty() : Optional.of(rewrap(parentClauses));
    return new Decomposition(parentOnlyFilter, List.copyOf(splitClauses));
  }

  /**
   * Structured result of {@link #decompose(String, Set)}. Either half may be empty;
   * both being empty means the input filter was blank.
   */
  public record Decomposition(
      Optional<String> parentOnlyFilter, List<SplitClauseRef> splitClauses) {
    public boolean isEmpty() {
      return parentOnlyFilter.isEmpty() && splitClauses.isEmpty();
    }
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

  private static String rewrap(List<String> parentClauses) {
    return "$[?(" + String.join(" && ", parentClauses) + ")]";
  }

  private static boolean containsTopLevelOr(String body) {
    int depth = 0;
    for (int i = 0; i < body.length() - 1; i++) {
      char c = body.charAt(i);
      if (c == '(' || c == '[') depth++;
      else if (c == ')' || c == ']') depth--;
      else if (depth == 0 && c == '|' && body.charAt(i + 1) == '|') return true;
    }
    return false;
  }

  private static List<String> splitTopLevelAnds(String body) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < body.length() - 1; i++) {
      char c = body.charAt(i);
      if (c == '(' || c == '[') depth++;
      else if (c == ')' || c == ']') depth--;
      else if (depth == 0 && c == '&' && body.charAt(i + 1) == '&') {
        parts.add(body.substring(start, i));
        start = i + 2;
        i++; // skip the second '&'
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
