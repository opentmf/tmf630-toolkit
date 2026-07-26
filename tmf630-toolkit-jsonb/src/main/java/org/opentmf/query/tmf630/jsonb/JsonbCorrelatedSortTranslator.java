package org.opentmf.query.tmf630.jsonb;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.exception.TmfPagingException;

/**
 * Phase (b.6) — translates a correlated sort term (either {@code SIMPLE_RICH} or
 * {@code JSONPATH} kind) into a Postgres SQL/JSON path expression suitable for
 * {@code jsonb_path_query_first(payload, '&lt;path&gt;')}. Both source grammars converge
 * to the same target syntax:
 *
 * <ul>
 *   <li>Simple-rich {@code hop[key=value].leaf} → {@code $.hop[*] ? (@.key == "value").leaf}
 *   <li>JsonPath {@code $.hop[?(@.key == 'value')].leaf} → {@code $.hop[*] ? (@.key == "value").leaf}
 * </ul>
 *
 * <p>The {@link JsonbSortBuilder} wraps the returned path in the full ORDER BY
 * fragment: {@code ((jsonb_path_query_first(payload, ?::jsonpath)) #>> '{}')::&lt;cast&gt;
 * ASC|DESC [NULLS LAST]}. The path itself is passed as a JDBC parameter, not
 * inlined, so no SQL injection risk from user input flows through the sort side.
 *
 * <p>{@code #>> '{}'} extracts the scalar from the {@code jsonb} result as raw text
 * (stripping JSON quoting for strings), suitable for direct cast to the target type.
 * A NULL result (no match in the path) becomes SQL NULL and sorts per {@code NULLS
 * LAST}.
 *
 * <p>First-cut scope — single-hop only: {@code hop[key=value].leaf}. Multi-hop
 * chains ({@code a[k=v].b[k=v].c}), positional {@code [N]}, wildcards {@code [*]},
 * aggregators ({@code min()}/{@code max()}), and coercions ({@code num()}/
 * {@code str()}/{@code date()}) are rejected with clear messages. Aggregators and
 * coercions land in Phase b.7; the others remain out of scope for JSONB (or the
 * caller should use plain dotted sort with positional segments inline, which the
 * plain-sort path handles via {@code #>>'{a,N,c}'}).
 */
public class JsonbCorrelatedSortTranslator {

  /** {@code hop[key=value].leaf} — group 1=hop, 2=key, 3=value, 4=leaf. */
  private static final Pattern SIMPLE_RICH =
      Pattern.compile(
          "^([A-Za-z_][A-Za-z0-9_]*)\\[([A-Za-z_][A-Za-z0-9_]*)=([^\\]\\[]+)\\]\\.([A-Za-z_][A-Za-z0-9_.]*)$");

  /**
   * {@code $.hop[?(@.key == 'value')].leaf} — group 1=hop, 2=key, 3=value (with
   * quotes), 4=leaf. Deliberately narrow — supports only a single top-level equality
   * predicate. Compound predicates land later.
   */
  private static final Pattern JSONPATH_TERM =
      Pattern.compile(
          "^\\$\\.?([A-Za-z_][A-Za-z0-9_]*)\\[\\s*\\?\\s*\\(\\s*@\\.([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*(['\"])([^'\"]*)\\3\\s*\\)\\s*]\\.([A-Za-z_][A-Za-z0-9_.]*)$");

  /**
   * {@code hop[*].leaf} wildcard-projection form — group 1=hop, 2=leaf. Only meaningful
   * inside an aggregator wrapper (b.7); a bare wildcard sort without aggregator would
   * pick an arbitrary row's value, which is rarely what the caller wants — such
   * expressions are still translated (Postgres accepts them), but the aggregator
   * wrapping is the common use case.
   */
  private static final Pattern WILDCARD_TERM =
      Pattern.compile(
          "^([A-Za-z_][A-Za-z0-9_]*)\\[\\*]\\.([A-Za-z_][A-Za-z0-9_.]*)$");

  /**
   * Translates a correlated sort term to a {@link JsonbSortExpression}, peeling off
   * outer wrappers ({@code num()} / {@code str()} / {@code date()} coercion and
   * {@code min()} / {@code max()} aggregator) before parsing the inner path.
   * Wrappers apply in this exact order from outside in: coercion first, then
   * aggregator, then the correlated / wildcard / plain path — so
   * {@code num(min(prices[*].value))} decomposes as coercion={@code NUMERIC},
   * aggregator={@code MIN}, jsonPath={@code $.prices[*].value}.
   */
  public JsonbSortExpression translate(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new TmfPagingException("Correlated sort term must not be blank.");
    }
    String trimmed = expression.trim();
    rejectPositional(trimmed);

    Optional<JsonbCast> coercion = Optional.empty();
    Optional<JsonbSortExpression.Aggregator> aggregator = Optional.empty();
    String remainder = trimmed;

    Optional<JsonbCast> outerCoercion = detectCoercion(remainder);
    if (outerCoercion.isPresent()) {
      coercion = outerCoercion;
      remainder = stripWrapper(remainder);
    }

    Optional<JsonbSortExpression.Aggregator> outerAggregator = detectAggregator(remainder);
    if (outerAggregator.isPresent()) {
      aggregator = outerAggregator;
      remainder = stripWrapper(remainder);
    }

    // Reject a second, illegal wrapper (e.g. num(num(...)) or min(max(...))).
    if (detectCoercion(remainder).isPresent() || detectAggregator(remainder).isPresent()) {
      throw new TmfPagingException(
          "Only a single coercion (num/str/date) wrapping a single aggregator (min/max)"
              + " is supported. Nested duplicates rejected: "
              + expression);
    }

    String jsonPath = translatePath(remainder);
    return new JsonbSortExpression(aggregator, coercion, jsonPath);
  }

  private static String translatePath(String path) {
    Matcher simple = SIMPLE_RICH.matcher(path);
    if (simple.matches()) {
      return "$." + simple.group(1) + "[*] ? (@." + simple.group(2) + " == \""
          + escapeForDoubleQuoted(stripQuotes(simple.group(3).trim())) + "\")."
          + simple.group(4);
    }
    Matcher jsonPath = JSONPATH_TERM.matcher(path);
    if (jsonPath.matches()) {
      return "$." + jsonPath.group(1) + "[*] ? (@." + jsonPath.group(2) + " == \""
          + escapeForDoubleQuoted(jsonPath.group(4)) + "\")." + jsonPath.group(5);
    }
    Matcher wildcard = WILDCARD_TERM.matcher(path);
    if (wildcard.matches()) {
      return "$." + wildcard.group(1) + "[*]." + wildcard.group(2);
    }
    throw new TmfPagingException(
        "Correlated sort path does not match a supported shape ('hop[key=value].leaf',"
            + " '$.hop[?(@.key == \"value\")].leaf', or 'hop[*].leaf'): "
            + path);
  }

  private static Optional<JsonbCast> detectCoercion(String expression) {
    if (expression.startsWith("num(") && expression.endsWith(")")) {
      return Optional.of(JsonbCast.NUMERIC);
    }
    if (expression.startsWith("str(") && expression.endsWith(")")) {
      return Optional.of(JsonbCast.TEXT);
    }
    if (expression.startsWith("date(") && expression.endsWith(")")) {
      return Optional.of(JsonbCast.TIMESTAMPTZ);
    }
    return Optional.empty();
  }

  private static Optional<JsonbSortExpression.Aggregator> detectAggregator(String expression) {
    if (expression.startsWith("min(") && expression.endsWith(")")) {
      return Optional.of(JsonbSortExpression.Aggregator.MIN);
    }
    if (expression.startsWith("max(") && expression.endsWith(")")) {
      return Optional.of(JsonbSortExpression.Aggregator.MAX);
    }
    return Optional.empty();
  }

  /** Strips one wrapper — assumes caller has already confirmed the shape via detect*. */
  private static String stripWrapper(String expression) {
    int openParen = expression.indexOf('(');
    return expression.substring(openParen + 1, expression.length() - 1).trim();
  }

  private static void rejectPositional(String trimmed) {
    if (trimmed.matches(".*\\[\\d+].*")) {
      throw new TmfPagingException(
          "Positional [N] in JSONB correlated sort is not supported (use plain dotted"
              + " sort with numeric segments: e.g. sort=arr.0.leaf). Term: "
              + trimmed);
    }
  }

  private static String stripQuotes(String s) {
    if (s.length() >= 2) {
      char first = s.charAt(0);
      char last = s.charAt(s.length() - 1);
      if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
        return s.substring(1, s.length() - 1);
      }
    }
    return s;
  }

  private static String escapeForDoubleQuoted(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') {
        out.append('\\').append('"');
      } else if (c == '\\') {
        out.append('\\').append('\\');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
