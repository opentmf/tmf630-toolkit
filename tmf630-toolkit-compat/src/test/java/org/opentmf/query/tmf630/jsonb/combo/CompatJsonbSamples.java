package org.opentmf.query.tmf630.jsonb.combo;

import java.util.List;

/**
 * Requests on the combination suite's JSONB rows that its generated cells do not ask: the samples
 * of the 3.6.0 CHANGELOG entries (so every named fix is exercised) and the documented
 * PostgreSQL extensions of {@code filter=} (so their unchanged answers are proven). Each is a
 * label and the parameter's name and value.
 */
final class CompatJsonbSamples {

  private static final String LONG_PATTERN = "a".repeat(257);

  static final List<String[]> REQUESTS =
      List.of(
          // the forms PostgreSQL's jsonpath cannot parse
          new String[] {"BARE_EXISTS", "filter", "$[?(@.s)]"},
          new String[] {"BARE_EXISTS_IN_ARRAY", "filter", "$[?(@.arr[?(@.s)])]"},
          new String[] {"RECURSIVE_DESCENT", "filter", "$[?(@..s == 'alpha')]"},
          new String[] {"FUNCTION_COUNT", "filter", "$[?(@.ls.count() > 1)]"},
          new String[] {"FUNCTION_MIN", "filter", "$[?(@.ls.min() > 1)]"},
          new String[] {"FUNCTION_MAX", "filter", "$[?(@.ls.max() > 1)]"},
          new String[] {"LENGTH_GT", "filter", "$[?(@.ls.length() > 1)]"},
          new String[] {"LENGTH_EQ", "filter", "$[?(@.ls.length() == 2)]"},
          // the regex length cap (core default 256)
          new String[] {"REGEX_CAP_URL", "s.regex", "^" + LONG_PATTERN},
          new String[] {"REGEX_CAP_FILTER", "filter", "$[?(@.s =~ /^" + LONG_PATTERN + "/)]"},
          new String[] {"REGEX_WITHIN_CAP_URL", "s.regex", "^al"},
          // a body that closes the wrapper early
          new String[] {"WRAPPER_CLOSED", "filter", "$[?(@.s == 'alpha') || (@.s == 'bravo')]"},
          // the null literal
          new String[] {"EQ_NULL", "filter", "$[?(@.s == null)]"},
          new String[] {"NE_NULL", "filter", "$[?(@.s != null)]"},
          new String[] {"GT_NULL", "filter", "$[?(@.i > null)]"},
          // PostgreSQL's own jsonpath, accepted on JSONB
          new String[] {"PG_EXISTS", "filter", "$[?(exists(@.s))]"},
          new String[] {"PG_TYPE", "filter", "$[?(@.s.type() == 'string')]"},
          new String[] {"PG_SIZE", "filter", "$[?(@.ls.size() > 1)]"},
          new String[] {"PG_LIKE_REGEX", "filter", "$[?(@.s like_regex \"^al\")]"},
          new String[] {"PG_STARTS_WITH", "filter", "$[?(@.s starts with \"al\")]"},
          new String[] {"PG_ARITHMETIC", "filter", "$[?(@.i + 1 > 5)]"},
          new String[] {"NEGATED_GROUP", "filter", "$[?(!(@.s == 'alpha'))]"},
          // what the QueryDSL terminal refuses for the declared type (no type check on JSONB)
          new String[] {"LITERAL_UNREADABLE", "filter", "$[?(@.i == 'abc')]"},
          new String[] {"STRING_OPERATOR_ON_NUMBER", "i.contains", "5"},
          new String[] {"ORDER_ON_OBJECT", "o.gt", "5"},
          new String[] {"BETWEEN_THREE_VALUES", "i.between", "1,2,3"});

  private CompatJsonbSamples() {}
}
