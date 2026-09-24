package org.opentmf.query.tmf630.filtering.it;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * One seed and one expectation table shared by the three-backend regex parity ITs:
 * {@code Tmf630PredicateSqlJpaRegexParityIT} (JPA/Postgres) and
 * {@code Tmf630PredicateMongoRegexParityIT} (MongoDB) in this module, and
 * {@code Tmf630JsonbUrlBindingParityIT#regexParityCases} in {@code tmf630-toolkit-jsonb}, which
 * cannot see this class and carries a verbatim copy — keep the three in step.
 *
 * <p>The seed is designed to expose the defects the 3.4.0 translator replaced, not to pass by
 * accident: values carry SQL wildcards ({@code %}, {@code _}) that must match literally, a
 * regex-dot pair ({@code a.b} vs {@code axb}), mixed case for the {@code i} flag, a bare
 * {@code p} so that an unanchored literal is provably CONTAINS and not exact-match, and
 * prefix/suffix pairs ({@code Resolved} / {@code Resolution} / {@code unresolved}) for the
 * anchors and the spec's own {@code Resol.*?} example.
 */
public final class RegexParityFixture {

  /** id → searchable value. */
  public static final Map<String, String> VALUES = new LinkedHashMap<>();

  static {
    VALUES.put("R1", "Pass-through");
    VALUES.put("R2", "PASSWORD_1");
    VALUES.put("R3", "50% off");
    VALUES.put("R4", "a.b");
    VALUES.put("R5", "axb");
    VALUES.put("R6", "p");
    VALUES.put("R7", "Resolved");
    VALUES.put("R8", "Resolution");
    VALUES.put("R9", "unresolved");
    VALUES.put("R10", "100_percent");
  }

  public static final Set<String> ALL = Set.copyOf(VALUES.keySet());

  private RegexParityFixture() {}

  /** Second, non-searchable-by-value column used by the OR-across-fields case. */
  public static String status(String id) {
    return "R9".equals(id) ? "TEST" : "NEW";
  }

  /**
   * Patterns inside the LIKE-expressible subset: every backend must return exactly these ids.
   * Columns: label, operator suffix ({@code regex} / {@code regexi}), pattern, expected ids.
   */
  public static Stream<Arguments> subsetCases() {
    return Stream.of(
        Arguments.of("bare literal is CONTAINS", "regex", "p", Set.of("R6", "R10")),
        Arguments.of("bare literal, i flag", "regexi", "p", Set.of("R1", "R2", "R6", "R10")),
        Arguments.of("both anchors is exact", "regexi", "^p$", Set.of("R6")),
        Arguments.of("leading anchor is STARTS WITH", "regexi", "^p", Set.of("R1", "R2", "R6")),
        Arguments.of("trailing anchor is ENDS WITH", "regexi", "d$", Set.of("R7", "R9")),
        Arguments.of("spec example Resol.*?", "regex", "Resol.*?", Set.of("R7", "R8")),
        Arguments.of("spec example, i flag", "regexi", "resol.*?", Set.of("R7", "R8", "R9")),
        Arguments.of("explicit .*p.*", "regex", ".*p.*", Set.of("R6", "R10")),
        Arguments.of("percent is a literal", "regex", "%", Set.of("R3")),
        Arguments.of("underscore is a literal", "regex", "_", Set.of("R2", "R10")),
        Arguments.of("dot is any one char", "regex", "a.b", Set.of("R4", "R5")),
        Arguments.of("escaped dot is a literal dot", "regex", "a\\.b", Set.of("R4")),
        Arguments.of("anchored with percent", "regex", "^50% off$", Set.of("R3")),
        Arguments.of("anchored with underscore", "regex", "^100_percent$", Set.of("R10")),
        Arguments.of("hyphen is a literal", "regex", "Pass-through", Set.of("R1")),
        Arguments.of("hyphen, i flag", "regexi", "PASS-THROUGH", Set.of("R1")),
        Arguments.of("anchor after wildcard", "regexi", "^.*ED$", Set.of("R7", "R9")),
        Arguments.of("wildcard then anchor", "regex", "ol.*n$", Set.of("R8")),
        Arguments.of("match all", "regex", ".*", ALL),
        Arguments.of("empty string only", "regex", "^$", Set.of()));
  }

  /**
   * Patterns outside the subset: JPA answers 400 naming the subset; the real-regex backends
   * (Mongo, JSONB) answer 200 with these ids. This is the one remaining, documented divergence.
   * Columns: pattern, ids a real regex engine returns.
   */
  public static Stream<Arguments> outsideSubsetCases() {
    return Stream.of(
        Arguments.of("p+", Set.of("R6", "R10")),
        Arguments.of("[pq]", Set.of("R6", "R10")),
        Arguments.of("(p|q)", Set.of("R6", "R10")),
        Arguments.of("\\d", Set.of("R2", "R3", "R10")));
  }

  /** Expected ids for "value contains t or T (case-insensitive) OR status is exactly TEST". */
  public static final Set<String> OR_ACROSS_FIELDS = Set.of("R1", "R8", "R9", "R10");
}
