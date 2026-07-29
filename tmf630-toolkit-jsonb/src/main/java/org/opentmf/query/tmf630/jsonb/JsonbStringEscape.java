package org.opentmf.query.tmf630.jsonb;

/**
 * Shared string escape helpers for JsonPath → SQL/JSON translation. Both
 * {@link JsonbJsonPathTranslator} (filter-side) and {@link JsonbCorrelatedSortTranslator}
 * (sort-side) rewrap single-quoted JsonPath string literals into double-quoted
 * SQL/JSON literals and need identical backslash/quote escaping — extracted here so
 * the two translators do not carry duplicated copies.
 */
final class JsonbStringEscape {

  private JsonbStringEscape() {}

  /**
   * Escapes the interior of a JSON double-quoted string: doubles backslashes and
   * escapes embedded {@code "}. Everything else passes through unchanged — the caller
   * is expected to have already stripped the outer quotes and to add the wrapping
   * {@code "..."} itself.
   */
  static String escapeForDoubleQuoted(String s) {
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
