package org.opentmf.query.tmf630.jsonb.combo;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.jsonb.combo.ComboPosition.Step;
import tools.jackson.databind.JsonNode;

/**
 * The documented meaning of each URL attribute operator, computed in Java from a row's stored
 * JSON. The rules, stated once for the report:
 *
 * <ul>
 *   <li><b>Type-exact</b>, as the Mongo backend and the JSONB containment equality: a stored value
 *       compares only when it is of the field's declared JSON type (and, for a date, readable as
 *       one); otherwise it matches no value operator.
 *   <li><b>Existential on arrays</b>, as the JPA join and the Mongo array path: a field inside an
 *       array matches when ANY element's value does.
 *   <li><b>NE, NEI, NIN</b> match a present, non-null value that is not equal (so a value stored in
 *       another JSON type counts as not equal); JSON null and an absent key match neither them nor
 *       the positive operators (SQL three-valued logic, as JPA).
 *   <li><b>A value no cast reads</b> (a date string that is no date) matches NO operator, the
 *       negative ones included (the unreadable-value row's ruling (a), 2026-11-12).
 *   <li><b>IS_NULL / IS_NOT_NULL</b> under {@code MISSING_ONLY}: the key absent at the path, or
 *       present (a JSON null is present).
 *   <li><b>LIKE</b> is SQL LIKE ({@code %} any run, {@code _} one character, whole value);
 *       <b>CONTAINS / STARTS_WITH / ENDS_WITH</b> are literal, as QueryDSL's escaping on JPA and
 *       the quoted regex on Mongo; <b>REGEX</b> is an unanchored search; the {@code I} variants
 *       ignore case.
 * </ul>
 */
final class ComboOracle {

  private ComboOracle() {}

  /** The rows of {@code docs} (id to payload) that a URL cell selects by its documented meaning. */
  static Set<String> urlExpected(
      Map<String, JsonNode> docs, ComboPosition position, ComboKind kind, TmfOperator op) {
    List<String> query = ComboRequests.urlQuery(kind, op);
    return ids(docs, doc -> matches(op, kind, find(doc, position, kind), query));
  }

  /**
   * The rows of {@code docs} that a {@code filter=} cell selects by its documented meaning: a
   * {@code [?(...)]} step matches when SOME element satisfies the inner predicate (Mongo's
   * elemMatch, the JPA join), and the comparison at the leaf is Jayway's, the toolkit's own
   * JsonPath library. (Jayway alone treats a correlated path inside a filter as true whenever the
   * array exists, whatever the inner predicate says; it is used here only at the leaf.) A regex
   * is judged as the URL's, by search ({@code =~} searches on the toolkit, as {@code .regex} and
   * Mongo's {@code $regex}; Jayway full-matches). Empty when the operator has no {@code filter=}
   * form or Jayway cannot run the leaf.
   */
  static Optional<Set<String>> filterExpected(
      Map<String, JsonNode> docs, ComboPosition position, ComboKind kind, TmfOperator op) {
    Optional<String> leaf = ComboRequests.filterLeaf(kind, op);
    if (leaf.isEmpty()) {
      return Optional.empty();
    }
    if (op == TmfOperator.REGEX || op == TmfOperator.REGEXI) {
      return Optional.of(urlExpected(docs, position, kind, op));
    }
    List<Step> steps = ComboRequests.filterSteps(position, kind);
    String leafKey = ComboRequests.filterLeafKey(kind);
    try {
      return Optional.of(
          ids(docs, doc -> filterMatches(doc, steps, 0, List.of(), leafKey, leaf.get())));
    } catch (RuntimeException jaywayCannot) {
      return Optional.empty();
    }
  }

  private static boolean filterMatches(
      JsonNode node, List<Step> steps, int from, List<String> fields, String leafKey, String leaf) {
    if (from == steps.size()) {
      String path = "@." + String.join("", fields.stream().map(f -> f + ".").toList()) + leafKey;
      String expression = "$[?(" + leaf.replace("%s", path) + ")]";
      return !JsonPath.parse("[" + node + "]").read(expression, List.class).isEmpty();
    }
    Step step = steps.get(from);
    List<String> along = new ArrayList<>(fields);
    along.add(step.name());
    if (!step.array()) {
      return filterMatches(node, steps, from + 1, along, leafKey, leaf);
    }
    JsonNode array = node;
    for (String field : along) {
      array = array == null ? null : array.get(field);
    }
    if (array == null || !array.isArray()) {
      return false;
    }
    return array.valueStream()
        .anyMatch(element -> filterMatches(element, steps, from + 1, List.of(), leafKey, leaf));
  }

  private static Set<String> ids(Map<String, JsonNode> docs, Predicate<JsonNode> match) {
    Set<String> ids = new TreeSet<>();
    docs.forEach(
        (id, doc) -> {
          if (match.test(doc)) {
            ids.add(id);
          }
        });
    return ids;
  }

  /** What a row holds at the position: whether the path is present, and the values found. */
  record Found(boolean present, List<JsonNode> values) {}

  static Found find(JsonNode doc, ComboPosition position, ComboKind kind) {
    boolean present = false;
    List<JsonNode> values = new ArrayList<>();
    for (JsonNode container : position.containers(doc)) {
      JsonNode value = container.get(kind.key());
      if (value == null) {
        continue;
      }
      if (kind == ComboKind.OBJECT_LIST) {
        present |= addCodes(value, values);
      } else if (kind == ComboKind.STRING_LIST) {
        present = true;
        if (value.isArray()) {
          value.valueStream().forEach(values::add);
        }
      } else {
        present = true;
        values.add(value);
      }
    }
    return new Found(present, values);
  }

  private static boolean addCodes(JsonNode list, List<JsonNode> values) {
    boolean any = false;
    if (list.isArray()) {
      for (JsonNode element : list.valueStream().toList()) {
        JsonNode code = element.isObject() ? element.get("code") : null;
        if (code != null) {
          any = true;
          values.add(code);
        }
      }
    }
    return any;
  }

  /** Whether a row with {@code found} matches {@code operator} with the query values. */
  static boolean matches(TmfOperator operator, ComboKind kind, Found found, List<String> query) {
    return switch (operator) {
      case IS_NULL -> !found.present();
      case IS_NOT_NULL -> found.present();
      case NE -> anyValue(found, v -> readable(kind, v) && !positive(TmfOperator.EQ, kind, v, query));
      case NEI ->
          anyValue(found, v -> readable(kind, v) && !positive(TmfOperator.EQI, kind, v, query));
      case NIN -> anyValue(found, v -> readable(kind, v) && !positive(TmfOperator.IN, kind, v, query));
      default -> anyValue(found, v -> positive(operator, kind, v, query));
    };
  }

  /** False for a value of the declared JSON type that no cast reads: a date string no date parser reads. */
  private static boolean readable(ComboKind kind, JsonNode value) {
    boolean dated = kind.shape() == ComboKind.Shape.DATE || kind.shape() == ComboKind.Shape.INSTANT;
    return !(dated && value.isString() && typed(kind, value).isEmpty());
  }

  private static boolean anyValue(Found found, Predicate<JsonNode> test) {
    return found.values().stream().filter(v -> !v.isNull()).anyMatch(test);
  }

  private static boolean positive(
      TmfOperator operator, ComboKind kind, JsonNode value, List<String> query) {
    String text = value.isString() ? value.asString() : null;
    String q = query.get(0);
    return switch (operator) {
      case EQ -> is(compare(kind, value, q), c -> c == 0);
      case GT -> is(compare(kind, value, q), c -> c > 0);
      case GTE -> is(compare(kind, value, q), c -> c >= 0);
      case LT -> is(compare(kind, value, q), c -> c < 0);
      case LTE -> is(compare(kind, value, q), c -> c <= 0);
      case BETWEEN ->
          is(compare(kind, value, q), c -> c >= 0)
              && is(compare(kind, value, query.get(1)), c -> c <= 0);
      case IN -> query.stream().anyMatch(each -> is(compare(kind, value, each), c -> c == 0));
      case EQI -> kind.isText() && text != null && text.equalsIgnoreCase(q);
      case LIKE -> kind.isText() && text != null && like(q, false).matcher(text).matches();
      case LIKEI -> kind.isText() && text != null && like(q, true).matcher(text).matches();
      case CONTAINS -> kind.isText() && text != null && text.contains(q);
      case CONTAINSI -> kind.isText() && text != null && lower(text).contains(lower(q));
      case STARTS_WITH -> kind.isText() && text != null && text.startsWith(q);
      case STARTS_WITHI -> kind.isText() && text != null && lower(text).startsWith(lower(q));
      case ENDS_WITH -> kind.isText() && text != null && text.endsWith(q);
      case ENDS_WITHI -> kind.isText() && text != null && lower(text).endsWith(lower(q));
      case REGEX -> kind.isText() && text != null && Pattern.compile(q).matcher(text).find();
      case REGEXI -> kind.isText()
          && text != null
          && Pattern.compile(q, Pattern.CASE_INSENSITIVE).matcher(text).find();
      default -> throw new IllegalArgumentException(operator.name());
    };
  }

  private static boolean is(OptionalInt comparison, IntPredicate test) {
    return comparison.isPresent() && test.test(comparison.getAsInt());
  }

  /**
   * Compares the stored value with the query value when the stored value is of the declared JSON
   * type; empty when the two do not compare.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static OptionalInt compare(ComboKind kind, JsonNode value, String queryText) {
    Optional<Comparable<?>> stored = typed(kind, value);
    Optional<Comparable<?>> query = kind.parseQuery(queryText);
    if (stored.isEmpty() || query.isEmpty()) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(Integer.signum(((Comparable) stored.get()).compareTo(query.get())));
  }

  /** The stored value as a comparable of the declared type, or empty when it is not one. */
  static Optional<Comparable<?>> typed(ComboKind kind, JsonNode value) {
    return switch (kind.shape()) {
      case TEXT -> value.isString() ? Optional.of(value.asString()) : Optional.empty();
      case NUMBER -> value.isNumber() ? Optional.of(value.decimalValue()) : Optional.empty();
      case BOOLEAN -> value.isBoolean() ? Optional.of(value.booleanValue()) : Optional.empty();
      case DATE -> value.isString() ? ComboKind.date(value.asString()).map(d -> d) : Optional.empty();
      case INSTANT ->
          value.isString() ? ComboKind.instant(value.asString()).map(i -> i) : Optional.empty();
    };
  }

  private static Pattern like(String pattern, boolean ignoreCase) {
    StringBuilder regex = new StringBuilder();
    for (char c : pattern.toCharArray()) {
      switch (c) {
        case '%' -> regex.append(".*");
        case '_' -> regex.append('.');
        default -> regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(regex.toString(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
  }

  private static String lower(String text) {
    return text.toLowerCase(Locale.ROOT);
  }
}
