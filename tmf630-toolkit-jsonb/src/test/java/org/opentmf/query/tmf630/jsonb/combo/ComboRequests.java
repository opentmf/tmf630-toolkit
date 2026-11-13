package org.opentmf.query.tmf630.jsonb.combo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.jsonb.combo.ComboPosition.Step;

/**
 * The requests of the combination suite, one per cell: the URL attribute operators, the
 * {@code filter=} expressions, the Jayway words and the sort paths. Shared by the suite (which
 * judges each answer against its oracle) and by the compatibility report (which records each
 * answer, per version), so both ask exactly the same requests.
 */
final class ComboRequests {

  /** The positions of the not-split entity. */
  static final List<ComboPosition> NOT_SPLIT =
      List.of(
          ComboPosition.TOP,
          ComboPosition.NESTED,
          ComboPosition.ARRAY,
          ComboPosition.ARRAY_IN_ARRAY);

  /**
   * Jayway's filter operators the toolkit's {@code filter=} grammar does not have (README: "What
   * filter= does NOT support (rejected with 400)"), each with a value of its kind. Both the
   * QueryDSL terminal and JSONB must answer the toolkit's 400.
   */
  static final String[][] JAYWAY_OPERATORS_NOT_IN_THE_GRAMMAR = {
    {"in", "['x', 'y']"}, {"nin", "['x']"}, {"size", "2"}, {"empty", "true"},
    {"contains", "'x'"}, {"anyof", "['x']"}, {"noneof", "['x']"}, {"subsetof", "['x']"},
    {"all", "['x']"}, {"exists", "true"}, {"type", "'string'"}, {"matches", "'x'"},
    {"IN", "['x']"}, {"===", "'x'"}, {"!==", "'x'"}
  };

  private ComboRequests() {}

  /** The URL attribute operator's parameters at {@code position} on {@code kind}'s field. */
  static Map<String, String[]> urlParams(ComboPosition position, ComboKind kind, TmfOperator op) {
    List<String> query = urlQuery(kind, op);
    String value = op.isNoValueOperator() ? "true" : String.join(",", query);
    String name = position.urlPrefix() + kind.leafPath() + "." + op.suffix();
    return Map.of(name, new String[] {value});
  }

  /** The query values of a URL operator, from the kind's probe. */
  static List<String> urlQuery(ComboKind kind, TmfOperator op) {
    ComboKind.Probe p = kind.probe();
    return switch (op) {
      case EQ, NE -> List.of(p.eq());
      case EQI, NEI -> List.of(ComboKind.upper(p.eq()));
      case GT, GTE, LT, LTE -> List.of(p.above());
      case BETWEEN -> List.of(p.low(), p.high());
      case IN, NIN -> List.of(p.inA(), p.inB());
      case IS_NULL, IS_NOT_NULL -> List.of();
      case LIKE -> List.of(p.prefix() + "%");
      case LIKEI -> List.of(ComboKind.upper(p.prefix()) + "%");
      case CONTAINS -> List.of(p.infix());
      case CONTAINSI -> List.of(ComboKind.upper(p.infix()));
      case STARTS_WITH -> List.of(p.prefix());
      case STARTS_WITHI -> List.of(ComboKind.upper(p.prefix()));
      case ENDS_WITH -> List.of(p.suffix());
      case ENDS_WITHI -> List.of(ComboKind.upper(p.suffix()));
      case REGEX -> List.of("^" + p.prefix());
      case REGEXI -> List.of("^" + ComboKind.upper(p.prefix()));
    };
  }

  /** The predicate template of a {@code filter=} operator, {@code %s} standing for the path. */
  static Optional<String> filterLeaf(ComboKind kind, TmfOperator op) {
    ComboKind.Probe p = kind.probe();
    return Optional.ofNullable(
        switch (op) {
          case EQ -> "%s == " + kind.filterLiteral(p.eq());
          case NE -> "%s != " + kind.filterLiteral(p.eq());
          case GT -> "%s > " + kind.filterLiteral(p.above());
          case GTE -> "%s >= " + kind.filterLiteral(p.above());
          case LT -> "%s < " + kind.filterLiteral(p.above());
          case LTE -> "%s <= " + kind.filterLiteral(p.above());
          case BETWEEN ->
              "%s >= " + kind.filterLiteral(p.low()) + " && %s <= " + kind.filterLiteral(p.high());
          case IN -> "%s in [" + kind.filterLiteral(p.inA()) + ", " + kind.filterLiteral(p.inB()) + "]";
          case NIN ->
              "%s nin [" + kind.filterLiteral(p.inA()) + ", " + kind.filterLiteral(p.inB()) + "]";
          case IS_NULL -> "!%s";
          case REGEX -> "%s =~ /^" + p.prefix() + "/";
          case REGEXI -> "%s =~ /^" + ComboKind.upper(p.prefix()) + "/i";
          default -> null;
        });
  }

  /** The {@code filter=} path steps to {@code kind}'s field at {@code position}. */
  static List<Step> filterSteps(ComboPosition position, ComboKind kind) {
    List<Step> steps = new ArrayList<>(position.steps());
    if (kind == ComboKind.OBJECT_LIST) {
      steps.add(new Step("lo", true));
    }
    return steps;
  }

  /** The key the {@code filter=} leaf compares. */
  static String filterLeafKey(ComboKind kind) {
    return kind == ComboKind.OBJECT_LIST ? "code" : kind.key();
  }

  /** The whole {@code filter=} expression of {@code leaf} at {@code position} on {@code kind}. */
  static String filterExpression(ComboPosition position, ComboKind kind, String leaf) {
    return "$[?(" + nest(filterSteps(position, kind), 0, "@.", filterLeafKey(kind), leaf) + ")]";
  }

  /** Builds the predicate: object steps extend the path, array steps correlate into elements. */
  static String nest(List<Step> steps, int from, String prefix, String leafKey, String leaf) {
    if (from == steps.size()) {
      return leaf.replace("%s", prefix + leafKey);
    }
    Step step = steps.get(from);
    if (!step.array()) {
      return nest(steps, from + 1, prefix + step.name() + ".", leafKey, leaf);
    }
    return prefix + step.name() + "[?(" + nest(steps, from + 1, "@.", leafKey, leaf) + ")]";
  }

  /** The URL path of {@code kind}'s field at {@code position}, as a sort key. */
  static String sortPath(ComboPosition position, ComboKind kind) {
    return position.urlPrefix() + kind.leafPath();
  }

  /** The request as one string, {@code name=value&...}. */
  static String request(Map<String, String[]> params) {
    return params.entrySet().stream()
        .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
        .collect(Collectors.joining("&"));
  }
}
