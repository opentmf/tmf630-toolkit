package org.opentmf.query.tmf630.jsonb.combo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One NAMED FIX of the compatibility report: a reviewed pattern over requests (each field a
 * regular expression that must match the whole field), the answer transitions it covers
 * ({@code 500>400}: a 500 that now answers 400; {@code 200>200}: the same status, other rows) and
 * the evidence its candidate rows must carry: {@code -} none, {@code name-twin} the release's rows
 * for the same request with each wire value written as its constant's name ({@link CompatNames}),
 * {@code oracle} the oracle's rows. A difference between the release and the candidate passes the
 * gate only when one rule matches it and its evidence holds; a rule no request matches fails the
 * gate too, and so does a rule with a transition from a 2xx to a 4xx or a 5xx.
 */
record CompatRule(
    String id,
    String changelog,
    Pattern backend,
    Pattern shape,
    Pattern position,
    Pattern kind,
    Pattern entry,
    Pattern operator,
    Pattern request,
    Set<String> transitions,
    String evidence) {

  /** No evidence beyond the transition. */
  static final String NO_EVIDENCE = "-";

  /** The release's rows for the request by the constant's name. */
  static final String NAME_TWIN = "name-twin";

  /** The oracle's rows. */
  static final String ORACLE = "oracle";

  /** The rules of {@code /compat/named-differences.tsv}. */
  static List<CompatRule> load() throws IOException {
    List<CompatRule> rules = new ArrayList<>();
    try (InputStream in = CompatRule.class.getResourceAsStream("/compat/named-differences.tsv")) {
      if (in == null) {
        return rules;
      }
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] c = line.split("\t", -1);
        rules.add(
            new CompatRule(
                c[0],
                c[1],
                Pattern.compile(c[2]),
                Pattern.compile(c[3]),
                Pattern.compile(c[4]),
                Pattern.compile(c[5]),
                Pattern.compile(c[6]),
                Pattern.compile(c[7]),
                Pattern.compile(c[8]),
                Set.of(c[9].split(",")),
                c.length > 10 && !c[10].isBlank() ? c[10].trim() : NO_EVIDENCE));
      }
    }
    return rules;
  }

  /** The transitions from a 2xx to a 4xx or a 5xx: a request that answered must keep answering. */
  List<String> answeredToError() {
    return transitions.stream()
        .filter(t -> t.startsWith("2") && (t.contains(">4") || t.contains(">5")))
        .sorted()
        .toList();
  }

  /** Whether this rule names the difference {@code from} → {@code to} of the request {@code key}. */
  boolean names(String[] key, CompatAnswer from, CompatAnswer to) {
    return backend.matcher(key[0]).matches()
        && shape.matcher(key[1]).matches()
        && position.matcher(key[2]).matches()
        && kind.matcher(key[3]).matches()
        && entry.matcher(key[4]).matches()
        && operator.matcher(key[5]).matches()
        && request.matcher(key[6]).matches()
        && transitions.contains(from.status() + ">" + to.status());
  }
}
