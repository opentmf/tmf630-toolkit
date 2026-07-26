package org.opentmf.query.tmf630.jpa;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.exception.TmfPagingException;

/**
 * Parses a single simple-rich sort term expression of the shape
 * {@code hopField[matchKey=matchValue].leafField}, e.g.
 * {@code orderCharacteristic[name=price].value}. Anything more complex — multi-hop chains
 * ({@code a[x=y].b[z=w].c}), positional indices ({@code arr[0].leaf}), wildcards
 * ({@code arr[*].leaf}), aggregators ({@code min(...)} / {@code max(...)}), coercions
 * ({@code num(...)} / {@code str(...)} / {@code date(...)}), or the JsonPath grammar —
 * is rejected with an actionable message. Phase (a.4) of the v3.0.0 roadmap deliberately
 * ships this narrow subset for JPA; broader grammars remain the JSONB backend's territory
 * (see {@code docs/JPA_BACKEND_GAP_ANALYSIS.md} §3.1 and {@code docs/V3_ROADMAP.md} §2).
 */
final class SimpleRichSortTermParser {

  /**
   * {@code hopField[matchKey=matchValue].leafField}. Captured groups: 1=hop, 2=key,
   * 3=value, 4=leaf. Whitespace is not permitted inside brackets to keep the grammar
   * unambiguous; leading/trailing whitespace on the whole term is stripped by the caller.
   */
  private static final Pattern SIMPLE_RICH =
      Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\[([A-Za-z_][A-Za-z0-9_]*)=([^\\]\\[]+)\\]\\.([A-Za-z_][A-Za-z0-9_]*)$");

  private SimpleRichSortTermParser() {}

  static ParsedTerm parse(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new TmfPagingException("Correlated sort term must not be blank.");
    }
    rejectDisallowedGrammar(expression);
    Matcher matcher = SIMPLE_RICH.matcher(expression.trim());
    if (!matcher.matches()) {
      throw new TmfPagingException(
          "Correlated sort term does not match the supported simple-rich shape"
              + " 'hopField[key=value].leafField': "
              + expression);
    }
    String hop = matcher.group(1);
    String key = matcher.group(2);
    String value = stripOuterQuotes(matcher.group(3).trim());
    String leaf = matcher.group(4);
    if (value.isEmpty()) {
      throw new TmfPagingException(
          "Correlated sort term match value must not be empty: " + expression);
    }
    return new ParsedTerm(hop, key, value, leaf);
  }

  private static void rejectDisallowedGrammar(String expression) {
    String trimmed = expression.trim();
    if (trimmed.startsWith("$.") || trimmed.contains("[?(") || trimmed.contains("[*]")) {
      throw new TmfPagingException(
          "JsonPath / wildcard sort grammar is not yet supported on JPA correlated sort"
              + " (Phase a.4 first cut). Use the simple-rich form 'field[key=value].leaf'"
              + " or run against a JSONB-backed entity for the full JsonPath sort grammar."
              + " Term: "
              + expression);
    }
    if (trimmed.matches(".*\\[\\d+].*")) {
      throw new TmfPagingException(
          "Positional index [N] in sort is not portable on JPA and not supported (Phase a.4"
              + " scope decision). Use a @OrderColumn-based association plus repository-level"
              + " Criteria for element-N sort, or run against a JSONB-backed entity. Term: "
              + expression);
    }
    if (trimmed.startsWith("min(") || trimmed.startsWith("max(")) {
      throw new TmfPagingException(
          "Sort aggregators min() / max() are not supported in Phase a.4 (out of scope).");
    }
    if (trimmed.startsWith("num(") || trimmed.startsWith("str(") || trimmed.startsWith("date(")) {
      throw new TmfPagingException(
          "Sort coercions num() / str() / date() are not portable across JPA dialects and"
              + " are not supported on JPA correlated sort. Store the field with its"
              + " natural type on the child entity.");
    }
  }

  private static String stripOuterQuotes(String s) {
    if (s.length() >= 2) {
      char first = s.charAt(0);
      char last = s.charAt(s.length() - 1);
      if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
        return s.substring(1, s.length() - 1);
      }
    }
    return s;
  }

  record ParsedTerm(String hopField, String matchKey, String matchValue, String leafField) {}
}
