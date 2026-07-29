package org.opentmf.query.tmf630.jpa;

import java.util.ArrayList;
import java.util.List;
import org.opentmf.query.tmf630.exception.TmfPagingException;

/**
 * Parses a rich sort-term expression into a hop chain, leaf field, and optional aggregator.
 *
 * <p>Accepted grammar:
 *
 * <pre>{@code
 * <term>     ::= <aggregator>? <hopChain>
 * <aggregator> ::= 'min(' <hopChain> ')' | 'max(' <hopChain> ')'
 * <hopChain> ::= <hop> ('.' <hop>)* '.' <leaf>
 * <hop>      ::= <ident> '[' <ident> '=' <value> ']'
 * <leaf>     ::= <ident>
 * <ident>    ::= [A-Za-z_][A-Za-z0-9_]*
 * <value>    ::= <bareToken> | "'" ... "'" | '"' ... '"'
 * }</pre>
 *
 * <p>Examples that parse:
 *
 * <ul>
 *   <li>{@code characteristics[name=price].value} — single hop
 *   <li>{@code items[sku=X].variants[color=red].price} — two hops
 *   <li>{@code min(items[sku=X].variants[color=red].price)} — explicit aggregator
 * </ul>
 *
 * <p>Explicitly rejected with actionable messages: JsonPath grammar ({@code $.}, {@code [?(...)]}),
 * wildcards ({@code [*]}), positional indices ({@code [N]}), and coercions
 * ({@code num()}/{@code str()}/{@code date()}). Those either require dialect-specific SQL that
 * the toolkit cross-dialect promise forbids, or a JsonPath-predicate → SQL-WHERE translator
 * that belongs on the JSONB backend.
 */
final class SimpleRichSortTermParser {

  private SimpleRichSortTermParser() {}

  static ParsedTerm parse(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new TmfPagingException("Correlated sort term must not be blank.");
    }
    String trimmed = expression.trim();
    rejectDisallowedGrammar(trimmed);

    AggregatorPart aggPart = extractAggregatorPart(trimmed, expression);
    HopChain hopChain = parseHopChain(aggPart.inner(), expression);
    return new ParsedTerm(hopChain.hops(), hopChain.leaf(), aggPart.aggregator());
  }

  private static AggregatorPart extractAggregatorPart(String trimmed, String original) {
    if (trimmed.startsWith("min(") && trimmed.endsWith(")")) {
      return new AggregatorPart(Aggregator.MIN, stripAggregatorCall(trimmed, "min", original));
    }
    if (trimmed.startsWith("max(") && trimmed.endsWith(")")) {
      return new AggregatorPart(Aggregator.MAX, stripAggregatorCall(trimmed, "max", original));
    }
    return new AggregatorPart(Aggregator.NONE, trimmed);
  }

  private static HopChain parseHopChain(String inner, String expression) {
    List<Hop> hops = new ArrayList<>();
    int pos = 0;
    String leaf = null;
    while (pos < inner.length()) {
      IdentRead read = readIdent(inner, pos, expression);
      pos = read.newPos();
      if (pos < inner.length() && inner.charAt(pos) == '[') {
        HopRead hop = readHopBracket(inner, pos, read.ident(), expression);
        hops.add(hop.hop());
        pos = hop.newPos();
      } else {
        leaf = read.ident();
        requireEndOfInput(inner, pos, expression);
      }
    }
    requireNonEmptyChain(hops, leaf, expression);
    return new HopChain(List.copyOf(hops), leaf);
  }

  private static IdentRead readIdent(String inner, int start, String expression) {
    int pos = start;
    while (pos < inner.length() && isIdentChar(inner.charAt(pos))) {
      pos++;
    }
    if (pos == start) {
      throw new TmfPagingException(
          "Expected identifier at position " + pos + " in: " + expression);
    }
    return new IdentRead(inner.substring(start, pos), pos);
  }

  private static HopRead readHopBracket(String inner, int startBracket, String ident, String expression) {
    int pos = startBracket + 1;
    int eqPos = inner.indexOf('=', pos);
    int closePos = inner.indexOf(']', pos);
    if (eqPos < 0 || closePos < 0 || eqPos > closePos) {
      throw new TmfPagingException(
          "Malformed hop; expected '[key=value]' in: " + expression);
    }
    String key = validateBareIdent(inner.substring(pos, eqPos).trim(), expression);
    String value = stripOuterQuotes(inner.substring(eqPos + 1, closePos).trim());
    if (value.isEmpty()) {
      throw new TmfPagingException(
          "Correlated sort hop match value must not be empty: " + expression);
    }
    int afterBracket = closePos + 1;
    if (afterBracket >= inner.length() || inner.charAt(afterBracket) != '.') {
      throw new TmfPagingException(
          "Hop '[key=value]' must be followed by '.<field>' in: " + expression);
    }
    return new HopRead(new Hop(ident, key, value), afterBracket + 1);
  }

  private static void requireEndOfInput(String inner, int pos, String expression) {
    if (pos != inner.length()) {
      throw new TmfPagingException(
          "Unexpected trailing input '" + inner.substring(pos) + "' in: " + expression);
    }
  }

  private static void requireNonEmptyChain(List<Hop> hops, String leaf, String expression) {
    if (hops.isEmpty() || leaf == null) {
      throw new TmfPagingException(
          "Correlated sort term must be 'hop[key=value].leaf' or a chain 'a[k=v].b[k=v].leaf'"
              + " (optionally wrapped in min()/max()): "
              + expression);
    }
  }

  private record AggregatorPart(Aggregator aggregator, String inner) {}

  private record HopChain(List<Hop> hops, String leaf) {}

  private record IdentRead(String ident, int newPos) {}

  private record HopRead(Hop hop, int newPos) {}

  private static String stripAggregatorCall(String expression, String name, String original) {
    String inner = expression.substring(name.length() + 1, expression.length() - 1).trim();
    if (inner.indexOf('(') >= 0 || inner.indexOf(')') >= 0) {
      throw new TmfPagingException(
          "Nested calls are not supported inside " + name + "(): " + original);
    }
    if (inner.isEmpty()) {
      throw new TmfPagingException(name + "() must wrap a hop chain, not be empty: " + original);
    }
    return inner;
  }

  private static void rejectDisallowedGrammar(String expression) {
    if (expression.startsWith("$.") || expression.contains("[?(")) {
      throw new TmfPagingException(
          "JsonPath sort grammar is not supported on JPA correlated sort — use the rich form"
              + " 'field[key=value].leaf' (optionally with min()/max() and multi-hop chains) or"
              + " run against a JSONB-backed entity for the full JsonPath sort grammar. Term: "
              + expression);
    }
    if (expression.contains("[*]")) {
      throw new TmfPagingException(
          "Wildcard '[*]' in sort is not supported on JPA — use min(...) / max(...) around the"
              + " intended reduction, or a JSONB-backed entity. Term: "
              + expression);
    }
    if (expression.matches(".*\\[\\d+].*")) {
      throw new TmfPagingException(
          "Positional index '[N]' in sort is not portable across JPA dialects and is not"
              + " supported. Use a @OrderColumn-based association plus repository-level Criteria"
              + " for element-N sort, or run against a JSONB-backed entity. Term: "
              + expression);
    }
    if (expression.startsWith("num(")
        || expression.startsWith("str(")
        || expression.startsWith("date(")) {
      throw new TmfPagingException(
          "Sort coercions num() / str() / date() are not portable across JPA dialects and are"
              + " not supported on JPA correlated sort. Store the field with its natural type on"
              + " the child entity, or run against a JSONB-backed entity. Term: "
              + expression);
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

  private static String validateBareIdent(String token, String expression) {
    if (token.isEmpty()) {
      throw new TmfPagingException(
          "Correlated sort hop match key must not be empty: " + expression);
    }
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      boolean valid = Character.isLetterOrDigit(c) || c == '_';
      if (i == 0 && !(Character.isLetter(c) || c == '_')) {
        valid = false;
      }
      if (!valid) {
        throw new TmfPagingException(
            "Correlated sort hop match key must be a bare identifier: " + expression);
      }
    }
    return token;
  }

  private static boolean isIdentChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  enum Aggregator {
    NONE,
    MIN,
    MAX
  }

  record Hop(String hopField, String matchKey, String matchValue) {}

  record ParsedTerm(List<Hop> hops, String leafField, Aggregator aggregator) {}
}
