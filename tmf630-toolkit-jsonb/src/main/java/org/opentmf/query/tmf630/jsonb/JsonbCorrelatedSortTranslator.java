package org.opentmf.query.tmf630.jsonb;

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
   * quotes), 4=leaf. Deliberately narrow — the b.6 first cut supports only a single
   * top-level equality predicate. Compound predicates land later.
   */
  private static final Pattern JSONPATH_TERM =
      Pattern.compile(
          "^\\$\\.?([A-Za-z_][A-Za-z0-9_]*)\\[\\s*\\?\\s*\\(\\s*@\\.([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*(['\"])([^'\"]*)\\3\\s*\\)\\s*]\\.([A-Za-z_][A-Za-z0-9_.]*)$");

  /**
   * Translates a correlated sort term to a Postgres SQL/JSON path expression.
   * Detects the grammar by shape (SIMPLE_RICH: contains {@code [k=v]};
   * JSONPATH: starts with {@code $} and contains {@code [?(}).
   */
  public String translate(String expression) {
    rejectDisallowedGrammar(expression);
    Matcher simple = SIMPLE_RICH.matcher(expression);
    if (simple.matches()) {
      return "$." + simple.group(1) + "[*] ? (@." + simple.group(2) + " == \""
          + escapeForDoubleQuoted(stripQuotes(simple.group(3).trim())) + "\")."
          + simple.group(4);
    }
    Matcher jsonPath = JSONPATH_TERM.matcher(expression);
    if (jsonPath.matches()) {
      return "$." + jsonPath.group(1) + "[*] ? (@." + jsonPath.group(2) + " == \""
          + escapeForDoubleQuoted(jsonPath.group(4)) + "\")." + jsonPath.group(5);
    }
    throw new TmfPagingException(
        "Correlated sort term does not match the supported single-hop shape"
            + " 'hop[key=value].leaf' (simple-rich) or"
            + " '$.hop[?(@.key == \"value\")].leaf' (JsonPath): "
            + expression);
  }

  private static void rejectDisallowedGrammar(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new TmfPagingException("Correlated sort term must not be blank.");
    }
    String trimmed = expression.trim();
    if (trimmed.contains("[*]")) {
      throw new TmfPagingException(
          "Wildcard [*] in JSONB correlated sort is not supported (out of scope for"
              + " Phase b.6). Term: "
              + expression);
    }
    if (trimmed.matches(".*\\[\\d+].*")) {
      throw new TmfPagingException(
          "Positional [N] in JSONB correlated sort is not supported (use plain dotted"
              + " sort with numeric segments: e.g. sort=arr.0.leaf). Term: "
              + expression);
    }
    if (trimmed.startsWith("min(")
        || trimmed.startsWith("max(")) {
      throw new TmfPagingException(
          "Sort aggregators min() / max() land in Phase b.7 (out of scope for b.6).");
    }
    if (trimmed.startsWith("num(")
        || trimmed.startsWith("str(")
        || trimmed.startsWith("date(")) {
      throw new TmfPagingException(
          "Sort coercions num() / str() / date() land in Phase b.7 (out of scope for b.6).");
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
